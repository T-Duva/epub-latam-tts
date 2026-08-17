package com.epublatam.tts.tts

/**
 * Narrador humano de misterio (lectura en voz alta, no charla).
 *
 * Actual (v1.6.5) vs esto:
 * - Pitch -4Hz y sin contorno → cantito (el final sube). Acá: -12Hz y cadencia que cae.
 * - Pausas solo en SSML `<break>` que Edge ignora. Acá: silencio real después de cada oración.
 * - Chunks de 700 caracteres + 40 ms → no se oye el punto. Acá: una oración y ~850 ms de silencio.
 */
object HumanPacing {
    const val COMMA_MS = 420
    const val COLON_MS = 480
    const val PERIOD_MS = 850
    const val QUESTION_MS = 900
    const val PARAGRAPH_MS = 1200

    /** ~175 pal/min neural → ~135 pal/min narrador. */
    const val EDGE_RATE_FACTOR = 0.78f
    const val EDGE_PREPARE_RATE = "-22%"
    const val EDGE_PITCH = "-12Hz"
    const val EDGE_CONTOUR = "(0%,+0Hz) (55%,-6Hz) (100%,-18Hz)"

    const val PIPER_LENGTH_SCALE = 1.32f
    const val PIPER_SILENCE_SCALE = 0.62f
    const val PIPER_BASE_SPEED = 0.74f

    fun ssmlBreak(ms: Int): String = """<break time="${ms}ms"/>"""

    fun pauseAfter(text: String, endOfParagraph: Boolean): Long {
        if (endOfParagraph) return PARAGRAPH_MS.toLong()
        val end = text.trimEnd().lastOrNull() ?: return COMMA_MS.toLong()
        return when (end) {
            '.', '…' -> PERIOD_MS.toLong()
            '?', '!' -> QUESTION_MS.toLong()
            ',', ';' -> COMMA_MS.toLong()
            ':' -> COLON_MS.toLong()
            else -> COMMA_MS.toLong()
        }
    }
}

data class Utterance(
    val text: String,
    val pauseAfterMs: Long,
)
