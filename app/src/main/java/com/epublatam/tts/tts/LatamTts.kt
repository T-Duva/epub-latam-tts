package com.epublatam.tts.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
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

enum class TtsEngineKind { ELEVEN, EDGE, PIPER }

data class TtsStatus(
    val engine: TtsEngineKind,
    val voiceLabel: String,
    val message: String? = null,
)

/** Mejor calidad posible: Edge Tomas AR (inglés bien) + Piper de respaldo. */
class PersonaVoice(private val context: Context) {
    private val edge = EdgeNarrator(context)
    private val piper = PiperNarrator(context)
    private var usePiper = false

    var lastStatus: TtsStatus = edge.status
        private set

    suspend fun prepare(onProgress: (String) -> Unit = {}): TtsStatus {
        return try {
            onProgress("Preparando voz argentina…")
            edge.prepare("es-AR-TomasNeural", "Tomas · Argentina", serio = true)
            usePiper = false
            lastStatus = edge.status.copy(message = "Argentino · grave, para en el punto")
            lastStatus
        } catch (e: Exception) {
            Log.w("PersonaVoice", "Edge no disponible, Piper: ${e.message}")
            onProgress("Usando voz offline…")
            piper.prepare("Daniela · Argentina", mystery = true, onProgress = onProgress)
            usePiper = true
            lastStatus = piper.status.copy(
                message = "Offline · ${e.message?.take(60) ?: "sin red"}",
            )
            lastStatus
        }
    }

    suspend fun speak(text: String, rate: Float, onProgress: (String) -> Unit = {}, onDone: () -> Unit) {
        val cleaned = MysteryText.prepare(text)
        if (cleaned.length < 3) {
            onDone()
            return
        }
        if (usePiper) {
            piper.speak(cleaned, rate, onProgress, onDone)
        } else {
            edge.speak(cleaned, rate, onProgress, onDone)
        }
    }

    fun stop() {
        edge.stop()
        piper.stop()
    }

    fun pause() {
        edge.pause()
        piper.pause()
    }

    fun release() {
        edge.release()
        piper.release()
    }
}

/** Texto para narración seria: cadencia de afirmación, no de pregunta. */
object MysteryText {
    fun prepare(raw: String): String {
        var t = raw
            .replace('\u00A0', ' ')
            .replace(Regex("[\\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace('–', ',')
            .replace('—', ',')
            .replace(Regex("([.!?;:,…])(?=\\S)"), "$1 ")
            .replace(Regex(" {2,}"), " ")
            .trim()
        t = dropFakeQuestions(t)
        val lines = t.split('\n').map { line ->
            val s = line.trimEnd()
            if (s.isEmpty()) s
            else if (s.last() in ".!?…,;:") s
            else "$s."
        }
        return lines.joinToString("\n")
    }

    /** El ? sin ¿ hace que Tomas/Elena “canten” la frase como pregunta. */
    private fun dropFakeQuestions(s: String): String {
        val out = StringBuilder(s.length)
        var open = false
        for (c in s) {
            when (c) {
                '¿' -> {
                    open = true
                    out.append(c)
                }
                '?' -> {
                    if (open) {
                        out.append(c)
                        open = false
                    } else {
                        out.append('.')
                    }
                }
                '.', '!', '\n' -> {
                    open = false
                    out.append(c)
                }
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}

object StoryChunks {
    /**
     * Párrafos / varias oraciones juntas para que el TTS respire solo
     * (timing natural). No una oración = un archivo = un silencio eterno.
     */
    fun splitPassages(raw: String, maxChars: Int = 560): List<String> {
        val t = raw.trim()
        if (t.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val paragraphs = t.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        for (p in paragraphs) {
            val flat = p.replace('\n', ' ').replace(Regex(" {2,}"), " ")
            if (flat.length <= maxChars) {
                out += flat
                continue
            }
            val sentences = splitSentences(flat, maxChars)
            val buf = StringBuilder()
            for (s in sentences) {
                if (buf.isNotEmpty() && buf.length + 1 + s.length > maxChars) {
                    out += buf.toString().trim()
                    buf.clear()
                }
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(s)
            }
            if (buf.isNotEmpty()) out += buf.toString().trim()
        }
        return out
    }

    fun splitUtterances(raw: String): List<Utterance> =
        splitPassages(raw).map { Utterance(it, 0L) }

    fun split(raw: String, maxChars: Int = 700): List<String> = splitPassages(raw, maxChars)

    private fun splitSentences(text: String, maxChars: Int): List<String> {
        val sentences = text.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (s in sentences) {
            if (s.length <= maxChars) {
                out += s
                continue
            }
            var rest = s
            while (rest.length > maxChars) {
                var cut = rest.lastIndexOf(',', maxChars)
                if (cut < maxChars / 3) cut = rest.lastIndexOf(' ', maxChars)
                if (cut < maxChars / 3) cut = maxChars
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim().trimStart(',', ' ')
            }
            if (rest.isNotEmpty()) out += rest
        }
        return out
    }
}

internal class AudioPlayer(private val context: Context) {
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
        // Brian: profundo, serio — mejor para misterio que voces “light”
        private const val VOICE_MYSTERY = "nPczCjzI2devNBz1zQrb"
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
    private var voiceId: String = VOICE_MYSTERY
    private var mystery = true

    var status = TtsStatus(
        engine = TtsEngineKind.ELEVEN,
        voiceLabel = "Brian · misterio",
        message = "Narración seria con alma",
    )
        private set

    fun prepare(key: String, mystery: Boolean = true) {
        apiKey = key
        this.mystery = mystery
        voiceId = VOICE_MYSTERY
        status = TtsStatus(
            engine = TtsEngineKind.ELEVEN,
            voiceLabel = "Brian · misterio",
            message = "Tono grave y serio · español forzado",
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
        val spanish = MysteryText.prepare(text)
            .replace('"', '«')
            .trim()

        // Más estabilidad = menos teatral alegre, más serio
        val body = JSONObject()
            .put("text", spanish)
            .put("model_id", MODEL)
            .put("language_code", LANGUAGE)
            .put(
                "voice_settings",
                JSONObject()
                    .put("stability", if (mystery) 0.62 else 0.40)
                    .put("similarity_boost", 0.80)
                    .put("use_speaker_boost", true),
            )
            .toString()

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128")
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
        // Español Argentina — se elige en prepare()
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
    private var voiceName: String = "es-AR-TomasNeural"
    private var serio: Boolean = true
    private var clockSkewMs: Long = 0L

    var status = TtsStatus(
        engine = TtsEngineKind.EDGE,
        voiceLabel = "Tomas · Argentina",
        message = "Argentino serio",
    )
        private set

    suspend fun prepare(voiceId: String, label: String, serio: Boolean) {
        this.voiceName = voiceId
        this.serio = serio
        syncClockSkew()
        val rate = HumanPacing.EDGE_PREPARE_RATE
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                synthesizePlain("Listo.", rate)
                status = TtsStatus(
                    engine = TtsEngineKind.EDGE,
                    voiceLabel = label,
                    message = "Argentino · pausa en puntos, como un narrador",
                )
                return
            } catch (e: Exception) {
                last = e
                Log.e(TAG, "prepare intento ${attempt + 1}: ${e.message}")
                delay(450L * (attempt + 1))
                syncClockSkew()
            }
        }
        throw IllegalStateException(
            "No se pudo conectar con $voiceId. ${last?.message ?: "error"}",
            last,
        )
    }

    /** Ajusta reloj contra Date del servidor (evita 403 por Sec-MS-GEC). */
    private fun syncClockSkew() {
        try {
            val req = Request.Builder()
                .url(
                    "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list" +
                        "?trustedclienttoken=$TRUSTED_TOKEN",
                )
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0",
                )
                .build()
            client.newCall(req).execute().use { resp ->
                val dateHdr = resp.header("Date") ?: return
                val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                val server = fmt.parse(dateHdr)?.time ?: return
                clockSkewMs = server - System.currentTimeMillis()
                Log.d(TAG, "clockSkewMs=$clockSkewMs")
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncClockSkew: ${e.message}")
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
        val chunks = StoryChunks.splitPassages(text)
        if (chunks.isEmpty()) {
            withContext(Dispatchers.Main) { onDone() }
            return
        }
        val edgeRate = toEdgeRate(rate)
        withContext(Dispatchers.IO) {
            coroutineScope {
                val jobs = MutableList(chunks.size) { null as kotlinx.coroutines.Deferred<ByteArray?>? }
                jobs[0] = async { runCatching { synthesizePlain(chunks[0], edgeRate) }.getOrNull() }
                if (chunks.size > 1) {
                    jobs[1] = async { runCatching { synthesizePlain(chunks[1], edgeRate) }.getOrNull() }
                }
                for (index in chunks.indices) {
                    if (session.get() != my) return@coroutineScope
                    withContext(Dispatchers.Main) {
                        onProgress("Leyendo ${index + 1}/${chunks.size}…")
                    }
                    val audio = jobs[index]?.await() ?: synthesizePlain(chunks[index], edgeRate)
                    jobs[index] = null
                    if (index + 2 < chunks.size && jobs[index + 2] == null) {
                        jobs[index + 2] = async {
                            runCatching { synthesizePlain(chunks[index + 2], edgeRate) }.getOrNull()
                        }
                    }
                    if (session.get() != my) return@coroutineScope
                    if (audio.isEmpty()) continue
                    val file = File(cacheDir, "d_${UUID.randomUUID()}.mp3")
                    file.writeBytes(audio)
                    try {
                        player.playFile(file) { session.get() == my }
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
        val factor = HumanPacing.EDGE_RATE_FACTOR * userRate.coerceIn(0.85f, 1.20f)
        val pct = ((factor - 1f) * 100f).roundToInt()
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    private fun generateSecMsGec(): String {
        var ticks = (System.currentTimeMillis() + clockSkewMs) / 1000.0 + WIN_EPOCH
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

    private fun buildSsml(text: String, rate: String): String {
        val body = EdgeSsmlText.body(text)
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='es-AR'>" +
            "<voice name='$voiceName'>" +
            "<prosody pitch='${HumanPacing.EDGE_PITCH}' rate='$rate' volume='+0%'>" +
            body +
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
                        val code = response?.code
                        val detail = t.message?.take(120) ?: t.javaClass.simpleName
                        val msg = when {
                            code == 403 -> "Voz online rechazada (403). Reloj del teléfono o token. $detail"
                            code != null -> "Voz online falló (HTTP $code): $detail"
                            else -> "Voz online falló: $detail"
                        }
                        Log.e(TAG, msg, t)
                        completeErr(IllegalStateException(msg, t))
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
