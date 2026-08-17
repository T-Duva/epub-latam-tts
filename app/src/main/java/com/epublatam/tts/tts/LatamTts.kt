package com.epublatam.tts.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import com.epublatam.tts.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Headers
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

enum class TtsEngineKind { ELEVEN, EDGE }

data class TtsStatus(
    val engine: TtsEngineKind,
    val voiceLabel: String,
    val message: String? = null,
)

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
            .ifBlank { BuildConfig.ELEVENLABS_API_KEY.trim() }
            .ifBlank {
                // respaldo pedido por el usuario
                "sk_2d18d9523094fd4a90ecd8d5a617b5c2a5b5fe18b896022a"
            }
        return try {
            eleven.prepare(key)
            // prueba corta real
            eleven.warmUp()
            mode = TtsEngineKind.ELEVEN
            lastStatus = eleven.status
            lastStatus
        } catch (e: Exception) {
            Log.e("PersonaVoice", "ElevenLabs falló, uso Dalia: ${e.message}")
            edge.prepare()
            mode = TtsEngineKind.EDGE
            lastStatus = edge.status.copy(
                message = "ElevenLabs falló (${e.message}). Usando Dalia.",
            )
            lastStatus
        }
    }

    suspend fun speak(text: String, rate: Float, onProgress: (String) -> Unit = {}, onDone: () -> Unit) {
        val cleaned = text.trim()
        if (cleaned.length < 3) {
            onDone()
            return
        }
        when (mode) {
            TtsEngineKind.ELEVEN -> {
                try {
                    eleven.speak(cleaned, rate, onProgress, onDone)
                } catch (e: Exception) {
                    Log.e("PersonaVoice", "Eleven speak fail: ${e.message}")
                    mode = TtsEngineKind.EDGE
                    lastStatus = edge.status.copy(message = "Cambié a Dalia: ${e.message}")
                    edge.prepare()
                    edge.speak(cleaned, rate, onProgress, onDone)
                }
            }
            TtsEngineKind.EDGE -> edge.speak(cleaned, rate, onProgress, onDone)
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

        val paragraphs = t.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (p in paragraphs) {
            if (p.length <= maxChars) out += p
            else out += splitSentences(p, maxChars)
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

private class AudioPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    suspend fun playFile(file: File, stillActive: () -> Boolean) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                if (!stillActive()) {
                    cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                stopInternal()
                requestFocus()
                val mp = MediaPlayer()
                player = mp
                try {
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    FileInputStream(file).use { fis ->
                        mp.setDataSource(fis.fd)
                    }
                    mp.setOnCompletionListener {
                        stopInternal()
                        if (cont.isActive) cont.resume(Unit)
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        stopInternal()
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("Error de audio ($what/$extra)"),
                            )
                        }
                        true
                    }
                    mp.prepare()
                    if (!stillActive()) {
                        stopInternal()
                        if (cont.isActive) cont.resume(Unit)
                        return@suspendCancellableCoroutine
                    }
                    mp.start()
                } catch (e: Exception) {
                    stopInternal()
                    if (cont.isActive) cont.resumeWithException(e)
                }
                cont.invokeOnCancellation { stopInternal() }
            }
        }
    }

    fun stop() = stopInternal()

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
    }

    private fun stopInternal() {
        player?.runCatching {
            stop()
            release()
        }
        player = null
        abandonFocus()
    }
}

class ElevenNarrator(private val context: Context) {
    companion object {
        private const val TAG = "ElevenNarrator"
        // Jessica: más neutra. Con language_code=es evita acento inglés en palabras latinas.
        private const val VOICE_ID = "cgSgspJ2msm6clMCkdW9"
        // flash_v2_5 soporta language_code (multilingual_v2 no lo respeta bien con voces EN)
        private const val MODEL = "eleven_flash_v2_5"
        private const val LANGUAGE = "es"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val player = AudioPlayer(context)
    private val session = AtomicInteger(0)
    private val cacheDir = File(context.cacheDir, "eleven_voice").also { it.mkdirs() }
    private var apiKey: String = ""

    var status = TtsStatus(
        engine = TtsEngineKind.ELEVEN,
        voiceLabel = "Jessica · español",
        message = "Voz con alma · pronunciación en español",
    )
        private set

    fun prepare(key: String) {
        apiKey = key
        status = TtsStatus(
            engine = TtsEngineKind.ELEVEN,
            voiceLabel = "Jessica · español",
            message = "Pronunciación forzada a español (no inglés)",
        )
    }

    suspend fun warmUp() {
        withContext(Dispatchers.IO) {
            val audio = synthesize("Hola.")
            if (audio.size < 100) error("Audio vacío de ElevenLabs")
        }
    }

    suspend fun speak(
        text: String,
        rate: Float,
        onProgress: (String) -> Unit,
        onDone: () -> Unit,
    ) {
        val my = session.incrementAndGet()
        player.stop()
        val chunks = StoryChunks.split(text, maxChars = 800)
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
                    if (session.get() != my) return@coroutineScope
                    withContext(Dispatchers.Main) {
                        onProgress("Leyendo ${index + 1}/${chunks.size}…")
                    }
                    val audio = if (index == 0) synthesize(chunk)
                    else nextJob?.await() ?: synthesize(chunk)
                    if (session.get() != my) return@coroutineScope
                    if (audio.isEmpty()) error("Sin audio en bloque ${index + 1}")

                    nextJob = if (index + 2 < chunks.size) {
                        async { runCatching { synthesize(chunks[index + 2]) }.getOrNull() }
                    } else {
                        null
                    }

                    val file = File(cacheDir, "e_${UUID.randomUUID()}.mp3")
                    file.writeBytes(audio)
                    try {
                        player.playFile(file) { session.get() == my }
                        if (session.get() == my && index < chunks.lastIndex) delay(100)
                    } finally {
                        file.delete()
                    }
                }
            }
            if (session.get() == my) {
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    private fun synthesize(text: String): ByteArray {
        // Normaliza tipografía para que el motor no “piense” en inglés
        val spanish = text
            .replace('…', '.')
            .replace('–', '-')
            .replace('—', ',')
            .replace('"', '«')
            .replace('"', '»')
            .trim()

        val body = JSONObject()
            .put("text", spanish)
            .put("model_id", MODEL)
            .put("language_code", LANGUAGE)
            .put(
                "voice_settings",
                JSONObject()
                    .put("stability", 0.40)
                    .put("similarity_boost", 0.78)
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
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                val err = bytes.toString(Charsets.UTF_8)
                Log.e(TAG, "ElevenLabs ${response.code}: $err")
                error(
                    when (response.code) {
                        401, 403 -> "Clave ElevenLabs inválida"
                        429 -> "Límite de ElevenLabs agotado"
                        else -> "ElevenLabs ${response.code}: ${err.take(120)}"
                    },
                )
            }
            if (bytes.size < 50) error("Respuesta de audio vacía")
            return bytes
        }
    }

    fun stop() {
        session.incrementAndGet()
        player.stop()
    }

    fun pause() = player.pause()
    fun release() = stop()
}

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

    private val player = AudioPlayer(context)
    private val session = AtomicInteger(0)
    private val cacheDir = File(context.cacheDir, "edge_voice").also { it.mkdirs() }

    var status = TtsStatus(
        engine = TtsEngineKind.EDGE,
        voiceLabel = "Dalia · México",
        message = "Voz básica",
    )
        private set

    suspend fun prepare() {
        synthesizePlain("Listo.", "-5%")
        status = TtsStatus(
            engine = TtsEngineKind.EDGE,
            voiceLabel = "Dalia · México",
            message = "Voz básica (Microsoft)",
        )
    }

    suspend fun speak(
        text: String,
        rate: Float,
        onProgress: (String) -> Unit,
        onDone: () -> Unit,
    ) {
        val my = session.incrementAndGet()
        player.stop()
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
                    if (session.get() != my) return@coroutineScope
                    withContext(Dispatchers.Main) {
                        onProgress("Leyendo ${index + 1}/${chunks.size}…")
                    }
                    val audio = if (index == 0) synthesizePlain(chunk, edgeRate)
                    else nextJob?.await() ?: synthesizePlain(chunk, edgeRate)
                    if (session.get() != my) return@coroutineScope

                    nextJob = if (index + 2 < chunks.size) {
                        async { runCatching { synthesizePlain(chunks[index + 2], edgeRate) }.getOrNull() }
                    } else {
                        null
                    }

                    val file = File(cacheDir, "d_${UUID.randomUUID()}.mp3")
                    file.writeBytes(audio)
                    try {
                        player.playFile(file) { session.get() == my }
                        if (session.get() == my && index < chunks.lastIndex) delay(80)
                    } finally {
                        file.delete()
                    }
                }
            }
            if (session.get() == my) {
                withContext(Dispatchers.Main) { onDone() }
            }
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
                        completeErr(IllegalStateException("Sin internet para la voz.", t))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        completeOk()
                    }
                },
            )

            cont.invokeOnCancellation { ws.cancel() }
        }

    fun stop() {
        session.incrementAndGet()
        player.stop()
    }

    fun pause() = player.pause()
    fun release() = stop()
}
