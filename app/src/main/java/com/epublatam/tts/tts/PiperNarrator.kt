package com.epublatam.tts.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Piper Daniela AR — ritmo normal; fonética para inglés. */
class PiperNarrator(private val context: Context) {
    companion object {
        private const val TAG = "PiperNarrator"
        private const val LENGTH_SCALE = 1.12f
        private const val SILENCE_SCALE = 0.28f
        private const val BASE_SPEED = 0.92f
    }

    private val installer = PiperModelInstaller(context)
    private val player = AudioPlayer(context)
    private val session = AtomicInteger(0)
    private val cacheDir = File(context.cacheDir, "piper_voice").also { it.mkdirs() }

    @Volatile
    private var tts: OfflineTts? = null

    var status = TtsStatus(
        engine = TtsEngineKind.PIPER,
        voiceLabel = "Daniela · Argentina",
        message = "Argentino offline",
    )
        private set

    fun isModelReady(): Boolean = installer.isReady()

    suspend fun ensureModel(onProgress: (String) -> Unit) {
        installer.ensureReady(onProgress)
    }

    suspend fun prepare(label: String, mystery: Boolean = true, onProgress: (String) -> Unit = {}) {
        ensureModel(onProgress)
        withContext(Dispatchers.IO) {
            tts = null
            onProgress("Cargando motor de voz…")
            tts = OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = installer.modelPath,
                            tokens = installer.tokensPath,
                            dataDir = installer.dataDirPath,
                            noiseScale = 0.667f,
                            noiseScaleW = 0.8f,
                            lengthScale = LENGTH_SCALE,
                        ),
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                    maxNumSentences = 2,
                    silenceScale = SILENCE_SCALE,
                ),
            )
        }
        status = TtsStatus(
            engine = TtsEngineKind.PIPER,
            voiceLabel = label,
            message = "Argentino · ritmo normal",
        )
        withContext(Dispatchers.IO) {
            val audio = requireTts().generate(text = "Listo.", sid = 0, speed = BASE_SPEED)
            if (audio.samples.isEmpty()) error("Piper no generó audio de prueba")
        }
        onProgress(status.message ?: "Listo")
    }

    private fun requireTts(): OfflineTts =
        tts ?: error("Motor Piper no inicializado")

    suspend fun speak(
        text: String,
        rate: Float,
        onProgress: (String) -> Unit,
        onDone: () -> Unit,
    ) {
        val my = session.incrementAndGet()
        player.stop()
        val engine = requireTts()
        val prepared = EnglishPronunciation.forOffline(text)
        val chunks = StoryChunks.split(prepared, maxChars = 420)
        if (chunks.isEmpty()) {
            withContext(Dispatchers.Main) { onDone() }
            return
        }
        val speed = (BASE_SPEED * rate.coerceIn(0.8f, 1.3f)).coerceIn(0.75f, 1.15f)

        withContext(Dispatchers.IO) {
            coroutineScope {
                var nextJob = if (chunks.size > 1) {
                    async { runCatching { engine.generate(text = chunks[1], sid = 0, speed = speed) }.getOrNull() }
                } else {
                    null
                }
                for ((index, chunk) in chunks.withIndex()) {
                    if (session.get() != my) return@coroutineScope
                    withContext(Dispatchers.Main) {
                        onProgress("Leyendo ${index + 1}/${chunks.size}…")
                    }
                    val audio = if (index == 0) {
                        engine.generate(text = chunk, sid = 0, speed = speed)
                    } else {
                        nextJob?.await() ?: engine.generate(text = chunk, sid = 0, speed = speed)
                    }
                    if (session.get() != my) return@coroutineScope

                    nextJob = if (index + 2 < chunks.size) {
                        async {
                            runCatching {
                                engine.generate(text = chunks[index + 2], sid = 0, speed = speed)
                            }.getOrNull()
                        }
                    } else {
                        null
                    }

                    if (audio.samples.isEmpty()) continue
                    val wav = File(cacheDir, "p_${UUID.randomUUID()}.wav")
                    try {
                        val ok = audio.save(wav.absolutePath)
                        if (!ok || !wav.exists() || wav.length() < 44L) {
                            throw IllegalStateException("No se pudo guardar el audio")
                        }
                        player.playFile(wav) { session.get() == my }
                        if (session.get() == my && index < chunks.lastIndex) delay(70)
                    } finally {
                        wav.delete()
                    }
                }
            }
            if (session.get() == my) {
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    fun stop() {
        session.incrementAndGet()
        player.stop()
    }

    fun pause() = player.pause()

    fun release() {
        stop()
        tts = null
    }
}
