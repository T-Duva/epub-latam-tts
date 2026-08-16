package com.epublatam.tts.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.epublatam.tts.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

enum class TtsEngineKind { CLOUD, SYSTEM }

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
    private val cloud = CloudLatamTts(context)
    private val system = SystemLatamTts(context)
    private var active: LatamSpeaker = if (BuildConfig.GCS_TTS_API_KEY.isNotBlank()) cloud else system
    var lastStatus: TtsStatus? = null
        private set

    suspend fun preparePreferred(): TtsStatus {
        if (BuildConfig.GCS_TTS_API_KEY.isNotBlank()) {
            val cloudStatus = runCatching { cloud.prepare() }.getOrNull()
            if (cloudStatus != null) {
                active = cloud
                lastStatus = cloudStatus
                return cloudStatus
            }
        }
        active = system
        val status = system.prepare()
        lastStatus = status
        return status
    }

    suspend fun speak(text: String, rate: Float, onDone: () -> Unit) {
        try {
            active.speak(text, rate, onDone)
        } catch (e: Exception) {
            if (active !== system) {
                active = system
                lastStatus = system.prepare()
                active.speak(text, rate, onDone)
            } else {
                throw e
            }
        }
    }

    fun pause() = active.pause()
    fun stop() = active.stop()
    fun release() {
        cloud.release()
        system.release()
    }
}

class CloudLatamTts(private val context: Context) : LatamSpeaker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var player: MediaPlayer? = null
    private val cacheDir = File(context.cacheDir, "tts_audio").also { it.mkdirs() }

    // Español latino (Google usa es-US para LatAm). Nunca es-ES.
    private val languageCode = "es-US"
    private val voiceName = "es-US-Neural2-A"

    override suspend fun prepare(): TtsStatus {
        val key = BuildConfig.GCS_TTS_API_KEY
        if (key.isBlank()) error("Sin API key")
        return TtsStatus(
            engine = TtsEngineKind.CLOUD,
            voiceLabel = "$voiceName ($languageCode)",
            message = "Voz en la nube (español latino)",
        )
    }

    override suspend fun speak(text: String, rate: Float, onDone: () -> Unit) {
        stop()
        val chunks = chunkText(text, maxChars = 800)
        withContext(Dispatchers.IO) {
            for ((index, chunk) in chunks.withIndex()) {
                val audio = synthesize(chunk, rate)
                val file = File(cacheDir, "chunk_${UUID.randomUUID()}.mp3")
                file.writeBytes(audio)
                playFile(file)
                file.delete()
                if (index == chunks.lastIndex) {
                    withContext(Dispatchers.Main) { onDone() }
                }
            }
            if (chunks.isEmpty()) {
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    private fun synthesize(text: String, rate: Float): ByteArray {
        val key = BuildConfig.GCS_TTS_API_KEY
        val bodyJson = JSONObject()
            .put("input", JSONObject().put("text", text))
            .put(
                "voice",
                JSONObject()
                    .put("languageCode", languageCode)
                    .put("name", voiceName),
            )
            .put(
                "audioConfig",
                JSONObject()
                    .put("audioEncoding", "MP3")
                    .put("speakingRate", rate.toDouble().coerceIn(0.5, 2.0)),
            )

        val request = Request.Builder()
            .url("https://texttospeech.googleapis.com/v1/text:synthesize?key=$key")
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Google TTS ${response.code}: $raw")
            }
            val audioB64 = JSONObject(raw).getString("audioContent")
            return android.util.Base64.decode(audioB64, android.util.Base64.DEFAULT)
        }
    }

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

    override suspend fun prepare(): TtsStatus = suspendCancellableCoroutine { cont ->
        val engine = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                if (cont.isActive) cont.resume(
                    TtsStatus(TtsEngineKind.SYSTEM, "ninguna", "No se pudo iniciar TTS del sistema"),
                )
                return@TextToSpeech
            }
            ready = true
            val result = pickLatamVoice(tts!!)
            if (cont.isActive) cont.resume(result)
        }
        tts = engine
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                onDoneCallback?.invoke()
                onDoneCallback = null
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onDoneCallback?.invoke()
                onDoneCallback = null
            }
        })
    }

    private fun pickLatamVoice(engine: TextToSpeech): TtsStatus {
        val mx = Locale("es", "MX")
        val us = Locale("es", "US")
        val mxResult = engine.setLanguage(mx)
        if (mxResult >= TextToSpeech.LANG_AVAILABLE) {
            engine.voice?.let { v ->
                if (v.locale.country.equals("ES", true) && !v.locale.country.equals("MX", true)) {
                    // keep checking
                }
            }
            rejectSpain(engine)
            return TtsStatus(TtsEngineKind.SYSTEM, "es-MX", "TTS del sistema (México)")
        }
        val usResult = engine.setLanguage(us)
        if (usResult >= TextToSpeech.LANG_AVAILABLE) {
            rejectSpain(engine)
            return TtsStatus(TtsEngineKind.SYSTEM, "es-US", "TTS del sistema (español US / latam)")
        }
        // Try any non-Spain Spanish voice
        val latam = engine.voices?.firstOrNull { v ->
            v.locale.language == "es" && !v.locale.country.equals("ES", ignoreCase = true)
        }
        if (latam != null) {
            engine.voice = latam
            return TtsStatus(
                TtsEngineKind.SYSTEM,
                latam.name,
                "TTS del sistema (${latam.locale})",
            )
        }
        return TtsStatus(
            TtsEngineKind.SYSTEM,
            "faltante",
            "Instalá la voz Español (México) en Ajustes → Texto a voz. No se usará español de España.",
        )
    }

    private fun rejectSpain(engine: TextToSpeech) {
        val voice = engine.voice
        if (voice != null && voice.locale.country.equals("ES", ignoreCase = true)) {
            val alt = engine.voices?.firstOrNull {
                it.locale.language == "es" && !it.locale.country.equals("ES", true)
            }
            if (alt != null) engine.voice = alt
        }
    }

    override suspend fun speak(text: String, rate: Float, onDone: () -> Unit) {
        val engine = tts ?: error("TTS no iniciado")
        if (!ready) error("TTS no listo")
        val status = pickLatamVoice(engine)
        if (status.voiceLabel == "faltante") {
            error(status.message ?: "Sin voz latam")
        }
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        onDoneCallback = onDone
        val chunks = chunkText(text, maxChars = 3500)
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = if (index == chunks.lastIndex) "done" else "part-$index"
            engine.speak(chunk, mode, null, id)
        }
        if (chunks.isEmpty()) onDone()
    }

    override fun pause() {
        tts?.stop()
    }

    override fun stop() {
        tts?.stop()
        onDoneCallback = null
    }

    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}

fun chunkText(text: String, maxChars: Int): List<String> {
    val cleaned = text.trim()
    if (cleaned.isEmpty()) return emptyList()
    if (cleaned.length <= maxChars) return listOf(cleaned)
    val parts = mutableListOf<String>()
    var remaining = cleaned
    while (remaining.isNotEmpty()) {
        if (remaining.length <= maxChars) {
            parts += remaining
            break
        }
        var cut = remaining.lastIndexOf('.', maxChars)
        if (cut < maxChars / 2) cut = remaining.lastIndexOf(' ', maxChars)
        if (cut < maxChars / 2) cut = maxChars
        parts += remaining.substring(0, cut + 1).trim()
        remaining = remaining.substring(cut + 1).trim()
    }
    return parts.filter { it.isNotBlank() }
}
