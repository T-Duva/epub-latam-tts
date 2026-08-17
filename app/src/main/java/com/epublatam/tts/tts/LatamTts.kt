package com.epublatam.tts.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

enum class TtsEngineKind { ELEVEN, EDGE }

data class TtsStatus(
    val engine: TtsEngineKind,
    val voiceLabel: String,
    val message: String? = null,
)

/**
 * Elige la mejor voz disponible.
 * ElevenLabs (clave) = voz con alma. Edge = respaldo sin clave, sin pausas eternas.
 */
class PersonaVoice(
    private val context: Context,
    private val apiKeyProvider: suspend () -> String?,
) {
    private val edge = EdgeNarrator(context)
    private val eleven = ElevenNarrator(context)
    private var mode: TtsEngineKind = TtsEngineKind.EDGE

    var lastStatus: TtsStatus = edge.status
        private set

    suspend fun prepare(): TtsStatus {
        val key = apiKeyProvider()?.trim().orEmpty()
            .ifBlank { com.epublatam.tts.BuildConfig.ELEVENLABS_API_KEY.trim() }
        if (key.isNotBlank()) {
            eleven.prepare(key)
            mode = TtsEngineKind.ELEVEN
            lastStatus = eleven.status
            return lastStatus
        }
        edge.prepare()
        mode = TtsEngineKind.EDGE
        lastStatus = edge.status.copy(
            message = "Voz básica (Dalia). Sin clave ElevenLabs.",
        )
        return lastStatus
    }

    suspend fun speak(text: String, rate: Float, onDone: () -> Unit) {
        when (mode) {
            TtsEngineKind.ELEVEN -> eleven.speak(text, rate, onDone)
            TtsEngineKind.EDGE -> edge.speak(text, rate, onDone)
        }
    }

    fun stop() {
        edge.stop()
        eleven.stop()
    }

    fun pause() {
        edge.pause()
        eleven.pause()
    }

    fun release() {
        edge.release()
        eleven.release()
    }
}

/** Trocea para narración: párrafos/frases largas (NO por cada coma). */
object StoryChunks {
    fun split(raw: String, maxChars: Int = 700): List<String> {
        var t = raw
            .replace('\u00A0', ' ')
            .replace(Regex("[\\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("([.!?;:,])(?=\\S)"), "$1 ")
            .replace(Regex(" {2,}"), " ")
            .trim()
        if (t.isEmpty()) return emptyList()

        // Primero por párrafos
        val paragraphs = t.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (p in paragraphs) {
            if (p.length <= maxChars) {
                out += p
            } else {
                out += splitSentences(p, maxChars)
            }
        }
        return out
    }

    private fun splitSentences(text: String, maxChars: Int): List<String> {
        val sentences = text.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        for (s in sentences) {
            if (s.length > maxChars) {
                if (buf.isNotEmpty()) {
                    out += buf.toString().trim()
                    buf.clear()
                }
                var rest = s
                while (rest.length > maxChars) {
                    var cut = rest.lastIndexOf(' ', maxChars)
                    if (cut < maxChars / 2) cut = maxChars
                    out += rest.substring(0, cut).trim()
                    rest = rest.substring(cut).trim()
                }
                if (rest.isNotEmpty()) out += rest
                continue
            }
            if (buf.isNotEmpty() && buf.length + 1 + s.length > maxChars) {
                out += buf.toString().trim()
                buf.clear()
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(s)
        }
        if (buf.isNotEmpty()) out += buf.toString().trim()
        return out
    }
}

private suspend fun playMp3(playerHolder: Array<MediaPlayer?>, file: File, cancelled: () -> Boolean) =
    suspendCancellableCoroutine { cont ->
        if (cancelled()) {
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                it.release()
                if (playerHolder[0] === it) playerHolder[0] = null
                if (cont.isActive) cont.resume(Unit)
            }
            setOnErrorListener { p, _, _ ->
                p.release()
                if (playerHolder[0] === p) playerHolder[0] = null
                if (cont.isActive) cont.resume(Unit)
                true
            }
            prepare()
            start()
        }
        playerHolder[0] = mp
        cont.invokeOnCancellation {
            runCatching {
                mp.stop()
                mp.release()
            }
            playerHolder[0] = null
        }
    }

/**
 * ElevenLabs: voz con interpretación / alma.
 * Voice: Lily (narración cálida) + multilingual v2 + stability baja = más expresiva.
 */
class ElevenNarrator(private val context: Context) {
    companion object {
        private const val TAG = "ElevenNarrator"
        // Lily — narración cálida (premade)
        private const val VOICE_ID = "pFZP5JQG7iQjIQuC4Bku"
        private const val MODEL = "eleven_multilingual_v2"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val playerHolder = arrayOfNulls<MediaPlayer>(1)
    @Volatile private var cancelled = false
    private val cacheDir = File(context.cacheDir, "eleven_voice").also { it.mkdirs() }
    private var apiKey: String = ""

    var status = TtsStatus(
        engine = TtsEngineKind.ELEVEN,
        voiceLabel = "Lily · ElevenLabs",
        message = "Voz con alma (interpretación)",
    )
        private set

    fun prepare(key: String) {
        apiKey = key
        status = TtsStatus(
            engine = TtsEngineKind.ELEVEN,
            voiceLabel = "Lily · ElevenLabs",
            message = "Voz con alma — interpreta el texto",
        )
    }

    suspend fun speak(text: String, rate: Float, onDone: () -> Unit) {
        stop()
        cancelled = false
        val chunks = StoryChunks.split(text, maxChars = 900)
        if (chunks.isEmpty()) {
            withContext(Dispatchers.Main) { onDone() }
            return
        }
        withContext(Dispatchers.IO) {
            coroutineScope {
                var nextJob = if (chunks.size > 1) {
                    async { runCatching { synthesize(chunks[1]) }.getOrNull() }
                } else {
                    null
                }
                for ((index, chunk) in chunks.withIndex()) {
                    if (cancelled) break
                    val audio = if (index == 0) {
                        synthesize(chunk)
                    } else {
                        nextJob?.await() ?: synthesize(chunk)
                    }
                    if (index + 2 < chunks.size) {
                        nextJob = async { runCatching { synthesize(chunks[index + 2]) }.getOrNull() }
                    } else {
                        nextJob = null
                    }
                    val file = File(cacheDir, "e_${UUID.randomUUID()}.mp3")
                    file.writeBytes(audio)
                    try {
                        playMp3(playerHolder, file) { cancelled }
                        if (!cancelled && index < chunks.lastIndex) delay(120)
                    } finally {
                        file.delete()
                    }
                }
            }
            if (!cancelled) withContext(Dispatchers.Main) { onDone() }
        }
    }

    private fun synthesize(text: String): ByteArray {
        val body = JSONObject()
            .put("text", text)
            .put("model_id", MODEL)
            .put(
                "voice_settings",
                JSONObject()
                    .put("stability", 0.28) // más bajo = más vida / variación
                    .put("similarity_boost", 0.82)
                    .put("style", 0.55)
                    .put("use_speaker_boost", true),
            )
            .toString()

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$VOICE_ID?output_format=mp3_44100_128")
            .addHeader("xi-api-key", apiKey)
            .addHeader("Accept", "audio/mpeg")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string().orEmpty()
                Log.e(TAG, "ElevenLabs ${response.code}: $err")
                error(
                    when (response.code) {
                        401, 403 -> "Clave ElevenLabs inválida. Revisala en la pantalla principal."
                        429 -> "Límite gratis de ElevenLabs agotado este mes."
                        else -> "ElevenLabs error ${response.code}"
                    },
                )
            }
            return response.body?.bytes() ?: error("Sin audio")
        }
    }

    fun stop() {
        cancelled = true
        playerHolder[0]?.runCatching {
            stop()
            release()
        }
        playerHolder[0] = null
    }

    fun pause() {
        playerHolder[0]?.takeIf { it.isPlaying }?.pause()
    }

    fun release() = stop()
}

/**
 * Edge neural (Dalia). Sin clave.
 * Importante: NO pedir un audio por cada coma (eso causaba ~8s de espera).
 */
class EdgeNarrator(private val context: Context) {
    companion object {
        private const val TAG = "EdgeNarrator"
        private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val CHROMIUM = "143.0.3650.75"
        private const val VOICE = "es-MX-DaliaNeural"
        private const val WIN_EPOCH = 11_644_473_600L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val playerHolder = arrayOfNulls<MediaPlayer>(1)
    @Volatile private var cancelled = false
    private val cacheDir = File(context.cacheDir, "edge_voice").also { it.mkdirs() }

    var status = TtsStatus(
        engine = TtsEngineKind.EDGE,
        voiceLabel = "Dalia · México",
        message = "Voz básica (sin clave)",
    )
        private set

    suspend fun prepare() {
        synthesizePlain("Listo.", "-5%")
        status = TtsStatus(
            engine = TtsEngineKind.EDGE,
            voiceLabel = "Dalia · México",
            message = "Voz básica. Para alma: clave ElevenLabs gratis abajo.",
        )
    }

    suspend fun speak(text: String, rate: Float, onDone: () -> Unit) {
        stop()
        cancelled = false
        val chunks = StoryChunks.split(text, maxChars = 900)
        if (chunks.isEmpty()) {
            withContext(Dispatchers.Main) { onDone() }
            return
        }
        val edgeRate = toEdgeRate(rate)
        withContext(Dispatchers.IO) {
            coroutineScope {
                var nextJob = if (chunks.size > 1) {
                    async { runCatching { synthesizePlain(chunks[1], edgeRate) }.getOrNull() }
                } else {
                    null
                }
                for ((index, chunk) in chunks.withIndex()) {
                    if (cancelled) break
                    val audio = if (index == 0) {
                        synthesizePlain(chunk, edgeRate)
                    } else {
                        nextJob?.await() ?: synthesizePlain(chunk, edgeRate)
                    }
                    nextJob = if (index + 2 < chunks.size) {
                        async { runCatching { synthesizePlain(chunks[index + 2], edgeRate) }.getOrNull() }
                    } else {
                        null
                    }
                    val file = File(cacheDir, "d_${UUID.randomUUID()}.mp3")
                    file.writeBytes(audio)
                    try {
                        playMp3(playerHolder, file) { cancelled }
                        if (!cancelled && index < chunks.lastIndex) delay(80)
                    } finally {
                        file.delete()
                    }
                }
            }
            if (!cancelled) withContext(Dispatchers.Main) { onDone() }
        }
    }

    private fun toEdgeRate(userRate: Float): String {
        val pct = ((userRate.coerceIn(0.7f, 1.4f) - 1f) * 100f).roundToInt()
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    private fun generateSecMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000.0 + WIN_EPOCH
        ticks -= ticks % 300.0
        ticks *= 10_000_000.0
        val str = "${ticks.toLong()}$TRUSTED_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(str.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun dateString(): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US,
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun buildSsml(text: String, rate: String): String {
        val escaped = xmlEscape(text)
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$VOICE'>" +
            "<prosody pitch='+0Hz' rate='$rate' volume='+0%'>" +
            escaped +
            "</prosody></voice></speak>"
    }

    private suspend fun synthesizePlain(text: String, rate: String): ByteArray {
        val clean = text.trim()
        if (clean.isEmpty()) return ByteArray(0)
        return synthesizeSsml(buildSsml(clean, rate))
    }

    private suspend fun synthesizeSsml(ssml: String): ByteArray =
        suspendCancellableCoroutine { cont ->
            val reqId = connectId()
            val url =
                "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                    "?TrustedClientToken=$TRUSTED_TOKEN" +
                    "&ConnectionId=$reqId" +
                    "&Sec-MS-GEC=${generateSecMsGec()}" +
                    "&Sec-MS-GEC-Version=1-$CHROMIUM"

            val major = CHROMIUM.substringBefore('.')
            val headers = Headers.Builder()
                .add("Pragma", "no-cache")
                .add("Cache-Control", "no-cache")
                .add("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .add(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36 Edg/$major.0.0.0",
                )
                .add("Accept-Language", "es-MX,es;q=0.9")
                .add("Cookie", "muid=${connectId().uppercase()};")
                .build()

            val request = Request.Builder().url(url).headers(headers).build()
            val audio = ByteArrayOutputStream()
            val finished = AtomicBoolean(false)

            fun completeOk() {
                if (finished.compareAndSet(false, true) && cont.isActive) {
                    val bytes = audio.toByteArray()
                    if (bytes.isEmpty()) {
                        cont.resumeWithException(IllegalStateException("Sin audio. Revisá internet."))
                    } else {
                        cont.resume(bytes)
                    }
                }
            }

            fun completeErr(t: Throwable) {
                if (finished.compareAndSet(false, true) && cont.isActive) {
                    cont.resumeWithException(t)
                }
            }

            val ws = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            "X-Timestamp:${dateString()}\r\n" +
                                "Content-Type:application/json; charset=utf-8\r\n" +
                                "Path:speech.config\r\n\r\n" +
                                """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"true","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}""",
                        )
                        webSocket.send(
                            "X-RequestId:$reqId\r\n" +
                                "Content-Type:application/ssml+xml\r\n" +
                                "X-Timestamp:${dateString()}Z\r\n" +
                                "Path:ssml\r\n\r\n" +
                                ssml,
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("Path:turn.end")) {
                            webSocket.close(1000, null)
                            completeOk()
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val data = bytes.toByteArray()
                        if (data.size < 2) return
                        var i = 2
                        while (i + 3 < data.size) {
                            if (data[i] == '\r'.code.toByte() && data[i + 1] == '\n'.code.toByte() &&
                                data[i + 2] == '\r'.code.toByte() && data[i + 3] == '\n'.code.toByte()
                            ) {
                                val payload = data.copyOfRange(i + 4, data.size)
                                if (payload.isNotEmpty()) audio.write(payload)
                                return
                            }
                            i++
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "ws fail ${response?.code}: ${t.message}")
                        completeErr(
                            IllegalStateException("Sin internet para la voz. Activá datos/Wi‑Fi.", t),
                        )
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        completeOk()
                    }
                },
            )

            cont.invokeOnCancellation {
                cancelled = true
                ws.cancel()
            }
        }

    fun stop() {
        cancelled = true
        playerHolder[0]?.runCatching {
            stop()
            release()
        }
        playerHolder[0] = null
    }

    fun pause() {
        playerHolder[0]?.takeIf { it.isPlaying }?.pause()
    }

    fun release() = stop()
}
