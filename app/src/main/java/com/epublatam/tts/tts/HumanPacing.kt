package com.epublatam.tts.tts

/**
 * Tiempos de un narrador humano de ficción en español (audiolibro de misterio).
 *
 * Fuentes de magnitud (lectura en voz alta, no conversación):
 * - Conversación en español ≈ 160–180 palabras/min.
 * - Audiolibro de ficción ≈ 145–155 pal/min.
 * - Misterio / suspenso ≈ 135–145 pal/min (acá: 140).
 * - Pausa de coma / cláusula: 250–400 ms (Goldman-Eisler; Campione & Véronis).
 * - Pausa de punto / fin de enunciado: 600–800 ms.
 * - Pausa de párrafo: 900–1200 ms.
 *
 * Las pausas van DENTRO del audio (SSML). Nunca se espera a sintetizar
 * cada coma: eso es lo que parecía “80 minutos”.
 */
object HumanPacing {
    const val COMMA_MS = 320
    const val COLON_MS = 400
    const val PERIOD_MS = 720
    const val QUESTION_MS = 760
    const val PARAGRAPH_MS = 1050

    /** Edge default ~175 pal/min → 140 pal/min = 0.80. */
    const val EDGE_RATE_FACTOR = 0.80f
    const val EDGE_PREPARE_RATE = "-20%"

    /** Piper: más lengthScale = más lento; speed < 1 también frena. */
    const val PIPER_LENGTH_SCALE = 1.28f
    const val PIPER_SILENCE_SCALE = 0.55f
    const val PIPER_BASE_SPEED = 0.78f

    fun ssmlBreak(ms: Int): String = """<break time="${ms}ms"/>"""

    fun pauseAfterEnding(text: String): Long {
        val end = text.trimEnd().lastOrNull() ?: return 0L
        return when (end) {
            '.', '…' -> PERIOD_MS.toLong()
            '?', '!' -> QUESTION_MS.toLong()
            else -> 120L
        }
    }
}
