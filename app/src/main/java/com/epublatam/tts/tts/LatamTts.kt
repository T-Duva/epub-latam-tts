package com.epublatam.tts.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.epublatam.tts.BuildConfig
import kotlinx.coroutines.Dispatchers
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

enum class TtsEngineKind { AI_NEURAL, CLOUD, SYSTEM }

data class TtsStatus(
    val engine: TtsEngineKind,
    val voiceLabel: String,
    val message: String? = null,
)

interface LatamSpeaker {
    suspend fun prepare(): TtsStatus
    suspend fun speak(text: String, rate: Float, onDone: () -> Unit)
    fun pause()
    fun stop()
    fun release()
}

class TtsRouter(private val context: Context) {
    private val edge = EdgeLatamTts(context)
    private val cloud = CloudLatamTts(context)
    private val system = SystemLatamTts(context)
    private var active: LatamSpeaker = edge
    var lastStatus: TtsStatus? = null
        private set

    suspend fun preparePreferred(): TtsStatus {
        // 1) Voz neural IA (Edge es-MX) — natural, respeta puntuación, sin API key
        runCatching { edge.prepare() }.onSuccess {
            active = edge
            lastStatus = it
            return it
        }
        // 2) Google Cloud si hay key
        if (BuildConfig.GCS_TTS_API_KEY.isNotBlank()) {
            runCatching { cloud.prepare() }.onSuccess {
                active = cloud
                lastStatus = it
                return it
            }
        }
        // 3) Sistema
        active = system
        val status = system.prepare()
        lastStatus = status
        return status
    }

    suspend fun speak(text: String, rate: Float, onDone: () -> Unit) {
        val natural = NaturalText.prepareForSpeech(text)
        try {
            active.speak(natural, rate, onDone)
        } catch (e: Exception) {
            if (active !== system) {
                // Intentar otro motor neural antes del sistema
                if (active !== edge) {
                    runCatching {
                        active = edge
                        lastStatus = edge.prepare()
                        active.speak(natural, rate, onDone)
                        return
                    }
                }
                if (active !== cloud && BuildConfig.GCS_TTS_API_KEY.isNotBlank()) {
                    runCatching {
                        active = cloud
                        lastStatus = cloud.prepare()
                        active.speak(natural, rate, onDone)
                        return
                    }
                }
                active = system
                lastStatus = system.prepare()
                active.speak(natural, rate, onDone)
            } else {
                throw e
            }
        }
    }

    fun pause() = active.pause()
    fun stop() = active.stop()
    fun release() {
        edge.release()
        cloud.release()
        system.release()
    }
}

/** Prepara el texto para que la voz neural respete pausas naturales. */
object NaturalText {
    fun prepareForSpeech(raw: String): String {
        var t = raw
            .replace('\u00A0', ' ')
            .replace(Regex("[\\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        // Espacio después de puntuación si falta (evita "hola.Mundo")
        t = t.replace(Regex("([.!?;:,])(?=\\S)"), "$1 ")
        // No duplicar espacios
        t = t.replace(Regex(" {2,}"), " ")
        // Puntos suspensivos limpios
        t = t.replace(Regex("\\.{4,}"), "...")
        return t.trim()
    }

    /**
     * Parte en frases respetando . ! ? ; y saltos de párrafo.
     * Las comas quedan dentro de la frase (la voz neural hace la pausa corta).
     */
    fun splitPhrases(text: String, maxChars: Int = 420): List<String> {
        val cleaned = prepareForSpeech(text)
        if (cleaned.isEmpty()) return emptyList()

        val sentences = cleaned
            .split(Regex("(?<=[.!?…;])\\s+|\\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val out = mutableListOf<String>()
        val buf = StringBuilder()
        for (s in sentences) {
            if (s.length > maxChars) {
                if (buf.isNotEmpty()) {
                    out += buf.toString().trim()
                    buf.clear()
                }
                out += splitLongKeepingCommas(s, maxChars)
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

    private fun splitLongKeepingCommas(text: String, maxChars: Int): List<String> {
        val parts = mutableListOf<String>()
        var remaining = text
        while (remaining.length > maxChars) {
            var cut = remaining.lastIndexOf(',', maxChars)
            if (cut < maxChars / 3) cut = remaining.lastIndexOf(' ', maxChars)
            if (cut < maxChars / 3) cut = maxChars
            parts += remaining.substring(0, cut + 1).trim()
            remaining = remaining.substring(cut + 1).trim()
        }
        if (remaining.isNotBlank()) parts += remaining
        return parts
    }
}

/**
 * Voz neural IA vía Microsoft Edge Read Aloud.
 * Español México: Dalia (muy natural). Respeta puntos y comas.
 */
class EdgeLatamTts(private val context: Context) : LatamSpeaker {
    companion object {
        private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val CHROMIUM = "143.0.3650.75"
        private const val VOICE = "es-MX-DaliaNeural"
        private const val WIN_EPOCH = 11644473600L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var player: MediaPlayer? = null
    @Volatile private var cancelled = false
    private val cacheDir = File(context.cacheDir, "edge_tts").also { it.mkdirs() }

    override suspend fun prepare(): TtsStatus =
        TtsStatus(
            engine = TtsEngineKind.AI_NEURAL,
            voiceLabel = "Dalia (IA neural · México)",
            message = "Voz humana neural — respeta puntuación",
        )

    override suspend fun speak(text: String, rate: Float, onDone: () -> Unit) {
        stop()
        cancelled = false
        val phrases = NaturalText.splitPhrases(text)
        val edgeRate = toEdgeRate(rate)
        withContext(Dispatchers.IO) {
            for ((index, phrase) in phrases.withIndex()) {
                if (cancelled) break
                val audio = synthesize(phrase, edgeRate)
                if (cancelled || audio.isEmpty()) continue
                val file = File(cacheDir, "p_${UUID.randomUUID()}.mp3")
                file.writeBytes(audio)
                try {
                    playFile(file)
                    // Pausa breve entre frases (naturalidad)
                    if (!cancelled && index < phrases.lastIndex) {
                        val pauseMs = when {
                            phrase.endsWith('.') || phrase.endsWith('!') || phrase.endsWith('?') ||
                                phrase.endsWith('…') -> 280L
                            phrase.endsWith(';') -> 180L
                            else -> 90L
                        }
                        delay(pauseMs)
                    }
                } finally {
                    file.delete()
                }
            }
            if (!cancelled) {
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    private fun toEdgeRate(userRate: Float): String {
        val pct = ((userRate.coerceIn(0.6f, 1.8f) - 1f) * 100f).roundToInt()
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    private fun generateSecMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000.0 + WIN_EPOCH
        ticks -= ticks % 300
        val windowsTicks = (ticks * 10_000_000.0).toLong()
        val toHash = "$windowsTicks$TRUSTED_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(toHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun dateString(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun buildSsml(text: String, rate: String): String {
        // Breaks explícitos: coma corta, punto más largo
        val ssmlBody = buildString {
            val tokens = Regex("([^,.!?;:…]+[,.!?;:…]?)").findAll(text).map { it.value }.toList()
            if (tokens.isEmpty()) {
                append(xmlEscape(text))
            } else {
                tokens.forEachIndexed { i, tok ->
                    append(xmlEscape(tok.trim()))
                    val trim = tok.trimEnd()
                    val pause = when {
                        trim.endsWith('.') || trim.endsWith('!') || trim.endsWith('?') || trim.endsWith('…') -> "400ms"
                        trim.endsWith(';') -> "250ms"
                        trim.endsWith(':') -> "220ms"
                        trim.endsWith(',') -> "160ms"
                        else -> null
                    }
                    if (pause != null && i < tokens.lastIndex) {
                        append("<break time=\"$pause\"/>")
                    }
                    if (i < tokens.lastIndex) append(' ')
                }
            }
        }

        return """
            <speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="es-MX">
              <voice name="$VOICE">
                <prosody rate="$rate" pitch="+0Hz" volume="+0%">
                  $ssmlBody
                </prosody>
              </voice>
            </speak>
        """.trimIndent().replace(Regex(">\\s+<"), "><")
    }

    private fun buildSsmlPlain(text: String, rate: String): String {
        val escaped = xmlEscape(text)
        return """
            <speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="es-MX">
              <voice name="$VOICE">
                <prosody rate="$rate" pitch="+0Hz" volume="+0%">$escaped</prosody>
              </voice>
            </speak>
        """.trimIndent().replace(Regex(">\\s+<"), "><")
    }

    private suspend fun synthesize(text: String, rate: String): ByteArray {
        return try {
            synthesizeSsml(buildSsml(text, rate))
        } catch (_: Exception) {
            // Algunos endpoints rechazan <break>; la voz neural igual respeta puntuación
            synthesizeSsml(buildSsmlPlain(text, rate))
        }
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
                .add("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
                .add("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase()};")
                .build()

            val request = Request.Builder().url(url).headers(headers).build()
            val audio = ByteArrayOutputStream()
            val finished = AtomicBoolean(false)

            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val config =
                        "X-Timestamp:${dateString()}\r\n" +
                            "Content-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" +
                            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"true","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                    webSocket.send(config)

                    val ssmlMsg =
                        "X-RequestId:$reqId\r\n" +
                            "Content-Type:application/ssml+xml\r\n" +
                            "X-Timestamp:${dateString()}Z\r\n" +
                            "Path:ssml\r\n\r\n" +
                            ssml
                    webSocket.send(ssmlMsg)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        webSocket.close(1000, null)
                        if (finished.compareAndSet(false, true) && cont.isActive) {
                            val bytes = audio.toByteArray()
                            if (bytes.isEmpty()) {
                                cont.resumeWithException(IllegalStateException("Sin audio de la voz neural"))
                            } else {
                                cont.resume(bytes)
                            }
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    if (data.size < 2) return
                    val headerLen = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
                    if (headerLen + 2 > data.size) return
                    val header = String(data, 2, headerLen, Charsets.UTF_8)
                    if (!header.contains("Path:audio")) return
                    val payload = data.copyOfRange(2 + headerLen, data.size)
                    if (payload.isNotEmpty()) audio.write(payload)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (finished.compareAndSet(false, true) && cont.isActive) {
                        cont.resumeWithException(t)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (finished.compareAndSet(false, true) && cont.isActive) {
                        val bytes = audio.toByteArray()
                        if (bytes.isEmpty()) {
                            cont.resumeWithException(IllegalStateException("Conexión cerrada sin audio"))
                        } else {
                            cont.resume(bytes)
                        }
                    }
                }
            })

            cont.invokeOnCancellation {
                cancelled = true
                ws.cancel()
            }
        }

    private suspend fun playFile(file: File) = suspendCancellableCoroutine { cont ->
        if (cancelled) {
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
                if (player === it) player = null
                if (cont.isActive) cont.resume(Unit)
            }
            setOnErrorListener { p, _, _ ->
                p.release()
                if (player === p) player = null
                if (cont.isActive) cont.resume(Unit)
                true
            }
            prepare()
            start()
        }
        player = mp
        cont.invokeOnCancellation {
            runCatching {
                mp.stop()
                mp.release()
            }
            player = null
        }
    }

    override fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
    }

    override fun stop() {
        cancelled = true
        player?.runCatching {
            stop()
            release()
        }
        player = null
    }

    override fun release() = stop()
}

class CloudLatamTts(private val context: Context) : LatamSpeaker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var player: MediaPlayer? = null
    private val cacheDir = File(context.cacheDir, "tts_audio").also { it.mkdirs() }
    private val languageCode = "es-US"
    private val voiceName = "es-US-Neural2-B"

    override suspend fun prepare(): TtsStatus {
        val key = BuildConfig.GCS_TTS_API_KEY
        if (key.isBlank()) error("Sin API key")
        return TtsStatus(
            engine = TtsEngineKind.CLOUD,
            voiceLabel = "$voiceName",
            message = "Google Neural (latam)",
        )
    }

    override suspend fun speak(text: String, rate: Float, onDone: () -> Unit) {
        stop()
        val chunks = NaturalText.splitPhrases(text, maxChars = 700)
        withContext(Dispatchers.IO) {
            for ((index, chunk) in chunks.withIndex()) {
                val audio = synthesize(chunk, rate)
                val file = File(cacheDir, "chunk_${UUID.randomUUID()}.mp3")
                file.writeBytes(audio)
                playFile(file)
                file.delete()
                if (index < chunks.lastIndex) delay(200)
            }
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    private fun synthesize(text: String, rate: Float): ByteArray {
        val key = BuildConfig.GCS_TTS_API_KEY
        // SSML con pausas
        val ssml = buildString {
            append("<speak>")
            val parts = Regex("([^,.!?;:…]+[,.!?;:…]?)").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }
            parts.forEachIndexed { i, tok ->
                append(xmlEscape(tok))
                val pause = when {
                    tok.endsWith('.') || tok.endsWith('!') || tok.endsWith('?') -> "400ms"
                    tok.endsWith(',') -> "160ms"
                    tok.endsWith(';') || tok.endsWith(':') -> "250ms"
                    else -> null
                }
                if (pause != null) append("<break time=\"$pause\"/>")
                if (i >= 0) append(' ')
            }
            append("</speak>")
        }
        val bodyJson = org.json.JSONObject()
            .put("input", org.json.JSONObject().put("ssml", ssml))
            .put(
                "voice",
                org.json.JSONObject()
                    .put("languageCode", languageCode)
                    .put("name", voiceName),
            )
            .put(
                "audioConfig",
                org.json.JSONObject()
                    .put("audioEncoding", "MP3")
                    .put("speakingRate", rate.toDouble().coerceIn(0.5, 2.0)),
            )

        val request = Request.Builder()
            .url("https://texttospeech.googleapis.com/v1/text:synthesize?key=$key")
            .post(
                bodyJson.toString().toRequestBody(
                    "application/json; charset=utf-8".toMediaType(),
                ),
            )
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Google TTS ${response.code}: $raw")
            val audioB64 = org.json.JSONObject(raw).getString("audioContent")
            return android.util.Base64.decode(audioB64, android.util.Base64.DEFAULT)
        }
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private suspend fun playFile(file: File) = suspendCancellableCoroutine { cont ->
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
                if (player === it) player = null
                if (cont.isActive) cont.resume(Unit)
            }
            setOnErrorListener { p, _, _ ->
                p.release()
                if (player === p) player = null
                if (cont.isActive) cont.resume(Unit)
                true
            }
            prepare()
            start()
        }
        player = mp
        cont.invokeOnCancellation {
            mp.stop()
            mp.release()
            player = null
        }
    }

    override fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
    }

    override fun stop() {
        player?.runCatching {
            stop()
            release()
        }
        player = null
    }

    override fun release() = stop()
}

class SystemLatamTts(private val context: Context) : LatamSpeaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var onDoneCallback: (() -> Unit)? = null
    private var pendingUtterances = 0

    override suspend fun prepare(): TtsStatus = suspendCancellableCoroutine { cont ->
        val engine = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                if (cont.isActive) {
                    cont.resume(
                        TtsStatus(TtsEngineKind.SYSTEM, "ninguna", "No se pudo iniciar TTS del sistema"),
                    )
                }
                return@TextToSpeech
            }
            ready = true
            if (cont.isActive) cont.resume(pickLatamVoice(tts!!))
        }
        tts = engine
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                pendingUtterances--
                if (pendingUtterances <= 0) {
                    onDoneCallback?.invoke()
                    onDoneCallback = null
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                pendingUtterances--
                if (pendingUtterances <= 0) {
                    onDoneCallback?.invoke()
                    onDoneCallback = null
                }
            }
        })
    }

    private fun pickLatamVoice(engine: TextToSpeech): TtsStatus {
        val mx = Locale("es", "MX")
        if (engine.setLanguage(mx) >= TextToSpeech.LANG_AVAILABLE) {
            rejectSpain(engine)
            return TtsStatus(TtsEngineKind.SYSTEM, "es-MX", "Respaldo del sistema (México)")
        }
        val us = Locale("es", "US")
        if (engine.setLanguage(us) >= TextToSpeech.LANG_AVAILABLE) {
            rejectSpain(engine)
            return TtsStatus(TtsEngineKind.SYSTEM, "es-US", "Respaldo del sistema (latam)")
        }
        val latam = engine.voices?.firstOrNull {
            it.locale.language == "es" && !it.locale.country.equals("ES", true)
        }
        if (latam != null) {
            engine.voice = latam
            return TtsStatus(TtsEngineKind.SYSTEM, latam.name, "Respaldo del sistema")
        }
        return TtsStatus(
            TtsEngineKind.SYSTEM,
            "faltante",
            "Instalá Español (México) en Ajustes → Texto a voz",
        )
    }

    private fun rejectSpain(engine: TextToSpeech) {
        val voice = engine.voice
        if (voice != null && voice.locale.country.equals("ES", ignoreCase = true)) {
            engine.voices?.firstOrNull {
                it.locale.language == "es" && !it.locale.country.equals("ES", true)
            }?.let { engine.voice = it }
        }
    }

    override suspend fun speak(text: String, rate: Float, onDone: () -> Unit) {
        val engine = tts ?: error("TTS no iniciado")
        if (!ready) error("TTS no listo")
        val status = pickLatamVoice(engine)
        if (status.voiceLabel == "faltante") error(status.message ?: "Sin voz latam")
        // Un poco más lento = más natural
        engine.setSpeechRate((rate * 0.92f).coerceIn(0.5f, 1.6f))
        onDoneCallback = onDone
        val phrases = NaturalText.splitPhrases(text, maxChars = 800)
        pendingUtterances = phrases.size
        phrases.forEachIndexed { index, phrase ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            // Silencio entre frases vía QUEUE + utterance
            engine.speak(phrase, mode, null, "p-$index")
            if (index < phrases.lastIndex) {
                val silence = when {
                    phrase.endsWith('.') || phrase.endsWith('!') || phrase.endsWith('?') -> 400L
                    phrase.endsWith(',') -> 180L
                    else -> 120L
                }
                engine.playSilentUtterance(silence, TextToSpeech.QUEUE_ADD, "s-$index")
                pendingUtterances++
            }
        }
        if (phrases.isEmpty()) onDone()
    }

    override fun pause() {
        tts?.stop()
    }

    override fun stop() {
        tts?.stop()
        onDoneCallback = null
        pendingUtterances = 0
    }

    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
