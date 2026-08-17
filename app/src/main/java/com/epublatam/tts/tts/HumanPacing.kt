package com.epublatam.tts.tts

/**
 * Pausa de narrador, sin duplicar.
 * v1.6.6 sumaba break SSML + delay de ~850–1200 ms → agujeros enormes.
 * Acá el punto es un respiro (~380 ms) después del audio; el SSML no vuelve a callar.
 */
object HumanPacing {
    const val COMMA_MS = 180
    const val COLON_MS = 220
    const val PERIOD_MS = 380
    const val QUESTION_MS = 420
    const val PARAGRAPH_MS = 550

    const val EDGE_RATE_FACTOR = 0.82f
    const val EDGE_PREPARE_RATE = "-18%"
    const val EDGE_PITCH = "-12Hz"
    const val EDGE_CONTOUR = "(0%,+0Hz) (55%,-6Hz) (100%,-18Hz)"

    const val PIPER_LENGTH_SCALE = 1.28f
    const val PIPER_SILENCE_SCALE = 0.22f
    const val PIPER_BASE_SPEED = 0.78f

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
