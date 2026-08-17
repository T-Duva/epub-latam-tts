package com.epublatam.tts.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
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

enum class TtsEngineKind { PERSONA, SISTEMA }

data class TtsStatus(
    val engine: TtsEngineKind,
    val voiceLabel: String,
    val message: String? = null,
)

/**
 * Voz de persona (Microsoft Neural, español México).
 * Sin fallback a la voz robótica del teléfono.
 * Parte el texto en respiraciones (comas/puntos) con silencios reales.
 */
class PersonaVoice(private val context: Context) {
    companion object {
        private const val TAG = "PersonaVoice"
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

    private var player: MediaPlayer? = null
    @Volatile private var cancelled = false
    private val cacheDir = File(context.cacheDir, "persona_voice").also { it.mkdirs() }

    var lastStatus: TtsStatus = TtsStatus(
        engine = TtsEngineKind.PERSONA,
        voiceLabel = "Dalia · México",
        message = "Voz de persona (necesita internet)",
    )
        private set

    suspend fun prepare(): TtsStatus {
        // Prueba real: si esto falla, no fingimos que hay voz buena
        synthesizePlain("Listo.", "-15%")
        lastStatus = TtsStatus(
            engine = TtsEngineKind.PERSONA,
            voiceLabel = "Dalia · México",
            message = "Voz de persona · con pausas naturales",
        )
        return lastStatus
    }

    suspend fun speak(text: String, rate: Float, onDone: () -> Unit) {
        stop()
        cancelled = false
        val breaths = BreathSplitter.split(text)
        if (breaths.isEmpty()) {
            withContext(Dispatchers.Main) { onDone() }
            return
        }
        // Más lento = más humano
        val edgeRate = toEdgeRate(rate * 0.88f)
        withContext(Dispatchers.IO) {
            for ((index, breath) in breaths.withIndex()) {
                if (cancelled) break
                val audio = try {
                    synthesizePlain(breath.text, edgeRate)
                } catch (e: Exception) {
                    Log.e(TAG, "Fallo síntesis: ${e.message}", e)
                    // Un reintento con token nuevo
                    delay(400)
                    synthesizePlain(breath.text, edgeRate)
                }
                if (cancelled || audio.isEmpty()) continue
                val file = File(cacheDir, "b_${UUID.randomUUID()}.mp3")
                file.writeBytes(audio)
                try {
                    playFile(file)
                    if (!cancelled && index < breaths.lastIndex) {
                        delay(breath.pauseAfterMs)
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

    fun stop() {
        cancelled = true
        player?.runCatching {
            stop()
            release()
        }
        player = null
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
    }

    fun release() = stop()

    private fun toEdgeRate(userRate: Float): String {
        val pct = ((userRate.coerceIn(0.55f, 1.5f) - 1f) * 100f).roundToInt()
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
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /** SSML idéntico al de edge-tts (Python). Sin tags break (Microsoft los ignora/rechaza). */
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
                        "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36 " +
                        "Edg/$major.0.0.0",
                )
                .add("Accept-Encoding", "gzip, deflate, br")
                .add("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
                .add("Cookie", "muid=${connectId().uppercase()};")
                .build()

            val request = Request.Builder().url(url).headers(headers).build()
            val audio = ByteArrayOutputStream()
            val finished = AtomicBoolean(false)

            fun completeOk() {
                if (finished.compareAndSet(false, true) && cont.isActive) {
                    val bytes = audio.toByteArray()
                    if (bytes.isEmpty()) {
                        cont.resumeWithException(
                            IllegalStateException("No llegó audio. Revisá internet."),
                        )
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
                        when {
                            text.contains("Path:turn.end") -> {
                                webSocket.close(1000, null)
                                completeOk()
                            }
                            text.contains("Path:response") || text.contains("Path:turn.start") ||
                                text.contains("Path:audio.metadata") -> Unit
                            else -> Log.d(TAG, "msg: ${text.take(120)}")
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val data = bytes.toByteArray()
                        if (data.size < 2) return
                        val headerLen = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
                        if (headerLen + 2 > data.size) return
                        val header = String(data, 2, headerLen, Charsets.UTF_8)
                        if (!header.contains("Path:audio")) return
                        val payloadStart = 2 + headerLen
                        // Algunos mensajes traen \r\n extra tras el header; edge-tts usa headerLen+2
                        // desde el inicio del buffer de headers (después de los 2 bytes de longitud).
                        // get_headers_and_data: data[header_length + 2 :] relativo al buffer SIN los 2 bytes?
                        // En Python: parameters, data = get_headers_and_data(received.data, header_length)
                        // donde received.data INCLUYE los 2 bytes, y header_length es solo del header text.
                        // get_headers_and_data(data, header_length): headers from data[:header_length], 
                        // body from data[header_length + 2:]
                        // Wait - they pass received.data which is FULL binary including first 2 bytes!
                        // Looking again:
                        // header_length = int.from_bytes(received.data[:2], "big")
                        // parameters, data = get_headers_and_data(received.data, header_length)
                        // 
                        // get_headers_and_data(data, header_length):
                        //   for line in data[:header_length].split...
                        //   return headers, data[header_length + 2:]
                        //
                        // That would parse data[:header_length] starting from byte 0 which is the length bytes!
                        // So they're passing wrong? Let me look again...
                        //
                        // Actually: get_headers_and_data(received.data, header_length) 
                        // uses data[:header_length] for headers - that includes the 2 length bytes at start
                        // unless header_length counts from start...
                        // 
                        // From communicate.py:
                        // header_length = int.from_bytes(received.data[:2], "big")
                        // parameters, data = get_headers_and_data(received.data, header_length)
                        //
                        // And get_headers_and_data:
                        // headers from data[:header_length] 
                        // body = data[header_length + 2:]
                        //
                        // This seems like a bug unless... they meant get_headers_and_data(received.data[2:], header_length)?
                        // Looking at many Kotlin ports - they use:
                        // val header = String(data, 2, headerLen)
                        // val audioData = data.copyOfRange(2 + headerLen, data.size)
                        //
                        // Some use 2 + headerLen + 2 for the \r\n\r\n
                        // Python: data[header_length + 2:] on FULL message means skip first header_length bytes
                        // THEN skip 2 more. If header_length is only the header text length, and first 2 bytes
                        // are length prefix, the Python code would be wrong unless header_length includes something else.
                        //
                        // From edge-tts issues and working Android ports (capacitor-edge-tts):
                        // typically: offset = 2 + headerLength; if there's \r\n\r\n after headers it's included in headerLength
                        //
                        // I'll use: find \r\n\r\n after the 2-byte length

                        val sep = indexOfCrLfCrLf(data, 2)
                        val payload = if (sep >= 0) {
                            data.copyOfRange(sep + 4, data.size)
                        } else {
                            data.copyOfRange(2 + headerLen, data.size)
                        }
                        if (payload.isNotEmpty()) audio.write(payload)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "ws fail: ${response?.code} ${t.message}")
                        completeErr(
                            IllegalStateException(
                                "No se pudo usar la voz de persona. Activá internet e intentá de nuevo.",
                                t,
                            ),
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

    private fun indexOfCrLfCrLf(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 3 < data.size) {
            if (data[i] == '\r'.code.toByte() && data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() && data[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
            i++
        }
        return -1
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
}

data class BreathUnit(val text: String, val pauseAfterMs: Long)

/** Parte el texto como respira una persona: coma = pausa corta, punto = pausa larga. */
object BreathSplitter {
    fun split(raw: String): List<BreathUnit> {
        var t = raw
            .replace('\u00A0', ' ')
            .replace(Regex("[\\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{2,}"), ". ")
            .replace(Regex("\\n"), " ")
            .replace(Regex("([.!?;:,])(?=\\S)"), "$1 ")
            .replace(Regex(" {2,}"), " ")
            .trim()
        if (t.isEmpty()) return emptyList()

        // Cada trozo termina en puntuación o es un fragmento corto
        val tokens = Regex("([^,.!?;:…]+[,.!?;:…]?)").findAll(t)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .toList()

        val units = mutableListOf<BreathUnit>()
        val buf = StringBuilder()

        fun flush(pause: Long) {
            val piece = buf.toString().trim()
            if (piece.isNotEmpty()) {
                units += BreathUnit(piece, pause)
            }
            buf.clear()
        }

        for (tok in tokens) {
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(tok)
            val end = tok.trimEnd().lastOrNull()
            val pause = when (end) {
                '.', '!', '?', '…' -> 550L
                ';' -> 380L
                ':' -> 320L
                ',' -> 280L
                else -> 0L
            }
            // Respirar siempre en puntuación, o si el buffer ya es largo
            if (pause > 0 || buf.length >= 90) {
                flush(if (pause > 0) pause else 220L)
            }
        }
        flush(0L)
        return units
    }
}
