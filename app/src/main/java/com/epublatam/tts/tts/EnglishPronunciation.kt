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
        Regex("""\bA[iy]den\b""", RegexOption.IGNORE_CASE) to "Éiden",
        Regex("""\bAidan\b""", RegexOption.IGNORE_CASE) to "Éiden",
        Regex("""\bHayden\b""", RegexOption.IGNORE_CASE) to "Héiden",
        Regex("""\bJayden\b""", RegexOption.IGNORE_CASE) to "Yéiden",
        Regex("""\bBrayden\b""", RegexOption.IGNORE_CASE) to "Bréiden",
        Regex("""\bCayden\b""", RegexOption.IGNORE_CASE) to "Kéiden",
        Regex("""\bRaiden\b""", RegexOption.IGNORE_CASE) to "Réiden",
    )

    /** Una sola palabra → fonética española aproximada. */
    private val WORDS: Map<String, String> = mapOf(
        "ayden" to "Éiden",
        "aiden" to "Éiden",
        "aidan" to "Éiden",
        "aden" to "Éiden",
        "hayden" to "Héiden",
        "jayden" to "Yéiden",
        "brayden" to "Bréiden",
        "cayden" to "Kéiden",
        "raiden" to "Réiden",
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
        "james" to "Yeims",
        "john" to "Yon",
        "jack" to "Yak",
        "george" to "Yory",
        "william" to "Uíliam",
        "michael" to "Máikel",
        "david" to "Déivid",
        "richard" to "Ríchard",
        "robert" to "Róbert",
        "edward" to "Éduard",
        "henry" to "Hénri",
        "charles" to "Charls",
        "thomas" to "Tómas",
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
        if (lower in SPANISH_STOP) return false
        if (lower.any { it in "áéíóúüñ" }) return false
        val engHints = listOf(
            "th", "wh", "gh", "ck", "ph", "tion", "ough", "ight", "kn", "wr",
            "ee", "oo", "ayden", "aiden", "ayde",
        )
        if (engHints.any { lower.contains(it) }) return true
        // Nombres tipo Jayden / Hayden
        if (lower.endsWith("ayden") || lower.endsWith("aiden")) return true
        return false
    }

    /** Aprox. para nombres ingleses; no reescribir todo el español. */
    private fun approxEnglish(word: String): String {
        var s = word.lowercase()
        s = s.replace("ayden", "éiden")
            .replace("aiden", "éiden")
            .replace("ay", "ei")
            .replace("tion", "shon")
            .replace("ough", "af")
            .replace("ight", "ait")
            .replace("kn", "n")
            .replace("ph", "f")
            .replace("th", "t")
            .replace("wh", "gu")
            .replace("ck", "k")
            .replace("ee", "i")
            .replace("oo", "u")
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
        // Fonética en español: <lang en-GB> resetea el pitch y vuelve el cantito.
        val spoken = EnglishPronunciation.forOffline(raw)
        return insertHumanBreaks(xmlEscape(spoken))
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
                out.append(' ')
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
                // Punto / ? / !: el silencio va DESPUÉS del audio (delay), no acá.
            }
            i++
        }
        return out.toString()
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
