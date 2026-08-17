package com.epublatam.tts.tts

/**
 * Nombres y palabras en inglés → cómo deben sonar en narración.
 * - [phonetic]: para Piper (español fonético)
 * - Para Edge se envuelven en &lt;lang xml:lang="en-GB"&gt;…
 */
object EnglishPronunciation {
    /** Frases largas primero (orden importa). */
    private val PHRASES: List<Pair<Regex, String>> = listOf(
        Regex("""\bAgatha\s+Christie\b""", RegexOption.IGNORE_CASE) to "Ágata Cristi",
        Regex("""\bSir\s+Arthur\s+Conan\s+Doyle\b""", RegexOption.IGNORE_CASE) to "Ser Árzur Cóuan Doil",
        Regex("""\bArthur\s+Conan\s+Doyle\b""", RegexOption.IGNORE_CASE) to "Árzur Cóuan Doil",
        Regex("""\bConan\s+Doyle\b""", RegexOption.IGNORE_CASE) to "Cóuan Doil",
        Regex("""\bSherlock\s+Holmes\b""", RegexOption.IGNORE_CASE) to "Shérloc Jolms",
        Regex("""\bDr\.?\s*Watson\b""", RegexOption.IGNORE_CASE) to "dóctor Guótson",
        Regex("""\bMiss\s+Marple\b""", RegexOption.IGNORE_CASE) to "Mis Márpel",
        Regex("""\bHercule\s+Poirot\b""", RegexOption.IGNORE_CASE) to "Erciúl Poaró",
        Regex("""\bPoirot\b""", RegexOption.IGNORE_CASE) to "Poaró",
        Regex("""\bNew\s+York\b""", RegexOption.IGNORE_CASE) to "Niu York",
        Regex("""\bScotland\s+Yard\b""", RegexOption.IGNORE_CASE) to "Escótland Yard",
    )

    /** Una sola palabra → fonética española aproximada. */
    private val WORDS: Map<String, String> = mapOf(
        "blackheart" to "Blákhart",
        "black" to "Blak",
        "heart" to "Hart",
        "christie" to "Cristi",
        "agatha" to "Ágata",
        "sherlock" to "Shérloc",
        "holmes" to "Jolms",
        "watson" to "Guótson",
        "marple" to "Márpel",
        "poirot" to "Poaró",
        "hercule" to "Erciúl",
        "doyle" to "Doil",
        "conan" to "Cóuan",
        "arthur" to "Árzur",
        "miss" to "Mis",
        "mister" to "Míster",
        "mr" to "Míster",
        "mrs" to "Mísis",
        "ms" to "Mis",
        "sir" to "Ser",
        "lord" to "Lord",
        "lady" to "Leidi",
        "scotland" to "Escótland",
        "yard" to "Yard",
        "london" to "Lóndon",
        "england" to "Íngland",
        "british" to "Britis",
        "english" to "Ínglish",
        "chapter" to "cháper",
        "murder" to "márder",
        "mystery" to "místery",
        "crime" to "craim",
        "killer" to "quíler",
        "death" to "dez",
        "dead" to "ded",
        "blood" to "blad",
        "night" to "nait",
        "light" to "lait",
        "house" to "jaus",
        "street" to "strit",
        "road" to "róud",
        "park" to "park",
        "hotel" to "jotél",
        "club" to "clab",
        "train" to "trein",
        "station" to "stéishon",
        "letter" to "léter",
        "note" to "nóut",
        "phone" to "fóun",
        "okay" to "oukéi",
        "hello" to "jelou",
        "bye" to "bai",
        "morning" to "mórning",
        "evening" to "ívening",
        "afternoon" to "áfter nun",
    )

    /** Para Piper / offline: reescribe a fonética española. */
    fun forOffline(raw: String): String {
        var t = raw
        for ((re, rep) in PHRASES) {
            t = re.replace(t, rep)
        }
        t = t.replace(Regex("""\b([A-Za-z][A-Za-z''-]{1,40})\b""")) { m ->
            val w = m.groupValues[1]
            val key = w.lowercase().trimEnd('.')
            WORDS[key] ?: if (looksEnglish(w)) approxEnglish(w) else w
        }
        return t
    }

    /**
     * Para Edge: inserta marcas [[en:...]] que luego se convierten a SSML lang.
     * Las frases conocidas se fonetizan en español (más natural con voz AR).
     */
    fun markForEdge(raw: String): String {
        var t = raw
        for ((re, rep) in PHRASES) {
            t = re.replace(t, rep)
        }
        t = t.replace(Regex("""\b([A-Za-z][A-Za-z''-]{1,40})\b""")) { m ->
            val w = m.groupValues[1]
            val key = w.lowercase().trimEnd('.')
            when {
                WORDS.containsKey(key) -> WORDS.getValue(key)
                looksEnglish(w) -> "[[en:$w]]"
                else -> w
            }
        }
        return t
    }

    fun looksEnglish(word: String): Boolean {
        if (word.length < 3) return false
        val lower = word.lowercase()
        // Palabras españolas comunes: no tocar
        if (lower in SPANISH_STOP) return false
        if (lower.any { it in "áéíóúüñ" }) return false
        val engHints = listOf(
            "th", "wh", "gh", "ck", "ph", "tion", "ough", "ight", "kn", "wr",
            "ee", "oo", "sh", "ch", "ing", "ment", "ness", "ship", "ward",
        )
        if (engHints.any { lower.contains(it) }) return true
        // Mayúscula interna tipo BlackHeart / o Title Case inglés sin tilde
        if (word.first().isUpperCase() && word.drop(1).any { it.isLowerCase() }) {
            // Nombres propios: si no parece español típico
            if (!lower.endsWith("ción") && !lower.endsWith("dad") && !lower.endsWith("mente")) {
                val vowels = lower.count { it in "aeiou" }
                if (vowels > 0 && lower.length >= 4) return true
            }
        }
        return false
    }

    /** Aprox. grosera letra a letra-ish para Piper. */
    private fun approxEnglish(word: String): String {
        var s = word.lowercase()
        s = s.replace("tion", "shon")
            .replace("sion", "shon")
            .replace("ough", "af")
            .replace("ight", "ait")
            .replace("kn", "n")
            .replace("wr", "r")
            .replace("ph", "f")
            .replace("th", "t")
            .replace("wh", "gu")
            .replace("ck", "k")
            .replace("ch", "ch")
            .replace("sh", "sh")
            .replace("ee", "i")
            .replace("oo", "u")
            .replace("ea", "i")
            .replace("ay", "ei")
            .replace("oy", "oi")
            .replace("qu", "cu")
            .replace("j", "y")
            .replace("w", "gu")
            .replace("y", "i")
            .replace("c", "k")
        return s.replaceFirstChar { it.uppercase() }
    }

    private val SPANISH_STOP = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "al",
        "y", "o", "u", "e", "en", "con", "por", "para", "sin", "sobre", "entre",
        "que", "qué", "como", "cómo", "cuando", "dónde", "donde", "cual", "cuál",
        "este", "esta", "estos", "estas", "ese", "esa", "esos", "esas", "aquel",
        "muy", "más", "menos", "ya", "aún", "aun", "sí", "si", "no", "también",
        "pero", "porque", "aunque", "mientras", "después", "antes", "ahora",
        "hoy", "ayer", "mañana", "siempre", "nunca", "todo", "toda", "todos",
        "nada", "algo", "alguien", "nadie", "señor", "señora", "don", "doña",
        "capítulo", "parte", "página", "libro", "historia", "caso", "muerte",
        "casa", "calle", "ciudad", "país", "hombre", "mujer", "niño", "niña",
    )
}

object EdgeSsmlText {
    fun body(raw: String): String {
        val marked = EnglishPronunciation.markForEdge(raw)
        val sb = StringBuilder()
        var i = 0
        while (i < marked.length) {
            if (marked.startsWith("[[en:", i)) {
                val end = marked.indexOf("]]", i)
                if (end > i) {
                    val word = marked.substring(i + 5, end)
                    sb.append("<lang xml:lang=\"en-GB\">")
                        .append(xmlEscape(word))
                        .append("</lang>")
                    i = end + 2
                    continue
                }
            }
            val next = marked.indexOf("[[en:", i).let { if (it < 0) marked.length else it }
            sb.append(xmlEscape(marked.substring(i, next)))
            i = next
        }
        return insertHumanBreaks(sb.toString())
    }

    /**
     * Pausa real de narrador, dentro del audio.
     * No corta el texto: el motor respira y sigue.
     */
    private fun insertHumanBreaks(xml: String): String {
        val out = StringBuilder(xml.length + 64)
        var i = 0
        while (i < xml.length) {
            if (xml.startsWith("\n\n", i)) {
                out.append(HumanPacing.ssmlBreak(HumanPacing.PARAGRAPH_MS))
                while (i < xml.length && xml[i] == '\n') i++
                continue
            }
            val c = xml[i]
            out.append(c)
            val next = xml.getOrNull(i + 1)
            val afterSpaceOrEnd = next == null || next.isWhitespace() || next == '<'
            when (c) {
                ',' -> if (afterSpaceOrEnd) out.append(HumanPacing.ssmlBreak(HumanPacing.COMMA_MS))
                ';', ':' -> if (afterSpaceOrEnd) out.append(HumanPacing.ssmlBreak(HumanPacing.COLON_MS))
                '.', '…' -> if (afterSpaceOrEnd && !isAbbreviationPeriod(xml, i)) {
                    out.append(HumanPacing.ssmlBreak(HumanPacing.PERIOD_MS))
                }
                '?', '!' -> if (afterSpaceOrEnd) {
                    out.append(HumanPacing.ssmlBreak(HumanPacing.QUESTION_MS))
                }
            }
            i++
        }
        return out.toString()
    }

    private val ABBREV = setOf(
        "dr", "sr", "sra", "srta", "ud", "uds", "etc", "mr", "mrs", "ms", "st", "vs",
    )

    private fun isAbbreviationPeriod(s: String, periodIndex: Int): Boolean {
        var start = periodIndex - 1
        while (start >= 0 && s[start].isLetter()) start--
        val word = s.substring(start + 1, periodIndex).lowercase()
        return word in ABBREV
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
