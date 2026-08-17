package com.epublatam.tts.tts

/**
 * Lento como narrador (~130 pal/min), sin hueco de red al punto.
 * El prefetch sigue: el siguiente audio se arma mientras suena este.
 * Las pausas de coma/punto van DENTRO del audio, no esperando síntesis.
 */
object HumanPacing {
    const val COMMA_MS = 220
    const val COLON_MS = 260
    const val PERIOD_MS = 320
    const val QUESTION_MS = 360
    const val PARAGRAPH_MS = 420

    /** Neural default ~175 pal/min → ~130 pal/min. */
    const val EDGE_RATE_FACTOR = 0.74f
    const val EDGE_PREPARE_RATE = "-26%"
    const val EDGE_PITCH = "-8Hz"

    const val PIPER_LENGTH_SCALE = 1.16f
    const val PIPER_SILENCE_SCALE = 0.48f
    const val PIPER_BASE_SPEED = 0.76f

    fun ssmlBreak(ms: Int): String = """<break time="${ms}ms"/>"""
}

data class Utterance(
    val text: String,
    val pauseAfterMs: Long = 0L,
)
