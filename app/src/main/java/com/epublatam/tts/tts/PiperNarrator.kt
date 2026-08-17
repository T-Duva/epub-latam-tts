package com.epublatam.tts.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Narración lenta con pausas reales en comas/puntos.
 * Piper suele “correr” el texto si se manda en bloques largos: partimos por respiración.
 */
class PiperNarrator(private val context: Context) {
    companion object {
        private const val TAG = "PiperNarrator"
        /** Más alto = más lento (VITS). */
        private const val LENGTH_SCALE = 1.55f
        /** Silencio interno entre oraciones del motor. */
        private const val SILENCE_SCALE = 0.65f
        /** Velocidad base (1.0 del slider ≈ esta). */
        private const val BASE_SPEED = 0.68f
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
        message = "Argentino · ritmo pausado",
    )
        private set

    fun isModelReady(): Boolean = installer.isReady()

    suspend fun ensureModel(onProgress: (String) -> Unit) {
        installer.ensureReady(onProgress)
    }

    suspend fun prepare(label: String, mystery: Boolean = true, onProgress: (String) -> Unit = {}) {
        ensureModel(onProgress)
        withContext(Dispatchers.IO) {
            // Recrear siempre: el ritmo (lengthScale) importa
            tts?.let { runCatching { /* native GC via drop */ } }
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
                    maxNumSentences = 1,
                    silenceScale = SILENCE_SCALE,
                ),
            )
        }
        status = TtsStatus(
            engine = TtsEngineKind.PIPER,
            voiceLabel = label,
            message = "Argentino · lento, con pausas",
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
        val phrases = BreathChunks.split(text)
        if (phrases.isEmpty()) {
            withContext(Dispatchers.Main) { onDone() }
            return
        }
        // rate 1.0 del usuario = BASE_SPEED; no dejamos que vuelva a “Ferrari”
        val speed = (BASE_SPEED * rate.coerceIn(0.75f, 1.25f)).coerceIn(0.55f, 0.92f)

        withContext(Dispatchers.IO) {
            for ((index, phrase) in phrases.withIndex()) {
                if (session.get() != my) return@withContext
                withContext(Dispatchers.Main) {
                    onProgress("Leyendo ${index + 1}/${phrases.size}…")
                }
                val audio = try {
                    engine.generate(text = phrase.text, sid = 0, speed = speed)
                } catch (e: Exception) {
                    Log.e(TAG, "generate failed", e)
                    throw IllegalStateException("Error de voz: ${e.message}", e)
                }
                if (session.get() != my) return@withContext
                if (audio.samples.isEmpty()) continue

                val wav = File(cacheDir, "p_${UUID.randomUUID()}.wav")
                try {
                    val ok = audio.save(wav.absolutePath)
                    if (!ok || !wav.exists() || wav.length() < 44L) {
                        throw IllegalStateException("No se pudo guardar el audio")
                    }
                    player.playFile(wav) { session.get() == my }
                    if (session.get() == my && index < phrases.lastIndex) {
                        delay(phrase.pauseAfterMs)
                    }
                } finally {
                    wav.delete()
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

data class BreathPhrase(
    val text: String,
    /** Silencio después de decir esta frase (respiración). */
    val pauseAfterMs: Long,
)

/** Parte el texto en frases cortas y define cuánto callar después de cada una. */
object BreathChunks {
    fun split(raw: String): List<BreathPhrase> {
        var t = raw
            .replace('\u00A0', ' ')
            .replace(Regex("[\\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("([,;:.!?…])(?=\\S)"), "$1 ")
            .replace(Regex(" {2,}"), " ")
            .trim()
        if (t.isEmpty()) return emptyList()

        val out = mutableListOf<BreathPhrase>()
        for (paragraph in t.split(Regex("\\n{2,}"))) {
            val p = paragraph.trim()
            if (p.isEmpty()) continue
            val parts = p.split(Regex("(?<=[,;:.!?…])\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            for ((i, part) in parts.withIndex()) {
                val lastInPara = i == parts.lastIndex
                out += BreathPhrase(
                    text = part,
                    pauseAfterMs = pauseFor(part, endOfParagraph = lastInPara),
                )
            }
            // Extra aire entre párrafos
            if (out.isNotEmpty()) {
                val last = out.removeAt(out.lastIndex)
                out += last.copy(pauseAfterMs = maxOf(last.pauseAfterMs, 700L))
            }
        }
        // No hace falta pausa después del último
        if (out.isNotEmpty()) {
            val last = out.removeAt(out.lastIndex)
            out += last.copy(pauseAfterMs = 0L)
        }
        return out
    }

    private fun pauseFor(phrase: String, endOfParagraph: Boolean): Long {
        val end = phrase.trimEnd().lastOrNull() ?: return 220L
        val base = when (end) {
            ',', ';' -> 380L
            ':' -> 420L
            '.', '…' -> 620L
            '!', '?' -> 680L
            else -> 260L
        }
        return if (endOfParagraph) base + 180L else base
    }
}
