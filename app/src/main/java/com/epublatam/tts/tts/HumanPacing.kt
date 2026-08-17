package com.epublatam.tts.tts

/**
 * Ritmo de narrador, no metrónomo.
 *
 * Mal: rate/lengthScale estira CADA fonema igual + un delay después de cada
 * punto + síntesis de la oración siguiente = pausas eternas y lectura robot.
 * Bien: el motor elige duraciones (vocal tónica más larga, “de/el” más cortos);
 * el siguiente audio se fabrica MIENTRAS suena este; no se espera red al punto.
 */
object HumanPacing {
    const val COMMA_MS = 160
    const val COLON_MS = 180

    /** Cerca del default neural: el modelo marca el timing, no un estirado uniforme. */
    const val EDGE_RATE_FACTOR = 0.94f
    const val EDGE_PREPARE_RATE = "-8%"
    const val EDGE_PITCH = "-8Hz"

    const val PIPER_LENGTH_SCALE = 1.04f
    const val PIPER_SILENCE_SCALE = 0.40f
    const val PIPER_BASE_SPEED = 0.94f

    fun ssmlBreak(ms: Int): String = """<break time="${ms}ms"/>"""
}

data class Utterance(
    val text: String,
    val pauseAfterMs: Long = 0L,
)
