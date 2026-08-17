package com.epublatam.tts.tts

/**
 * Palabras/nombres en inglés → fonética que un TTS español no confunde
 * con el léxico (Ayden ≠ alguien).
 *
 * 1) Diccionario de nombres frecuentes
 * 2) Si parece inglés (ortografía), transcripción automática
 * 3) Nunca tocar español (artículos, nombres rioplatenses)
 */
object EnglishPronunciation {
    private val PHRASES: List<Pair<Regex, String>> = listOf(
        Regex("""\bAgatha\s+Christie\b""", RegexOption.IGNORE_CASE) to "Ágata Cristi",
        Regex("""\bSir\s+Arthur\s+Conan\s+Doyle\b""", RegexOption.IGNORE_CASE) to "Ser Árzur Cóuan Doil",
        Regex("""\bArthur\s+Conan\s+Doyle\b""", RegexOption.IGNORE_CASE) to "Árzur Cóuan Doil",
        Regex("""\bConan\s+Doyle\b""", RegexOption.IGNORE_CASE) to "Cóuan Doil",
        Regex("""\bSherlock\s+Holmes\b""", RegexOption.IGNORE_CASE) to "Shérloc Jolms",
        Regex("""\bDr\.?\s*Watson\b""", RegexOption.IGNORE_CASE) to "dóctor Guótson",
        Regex("""\bMiss\s+Marple\b""", RegexOption.IGNORE_CASE) to "Mis Márpel",
        Regex("""\bHercule\s+Poirot\b""", RegexOption.IGNORE_CASE) to "Erciúl Poaró",
        Regex("""\bNew\s+York\b""", RegexOption.IGNORE_CASE) to "Niu York",
        Regex("""\bScotland\s+Yard\b""", RegexOption.IGNORE_CASE) to "Escótland Yard",
        Regex("""\bLos\s+Angeles\b""", RegexOption.IGNORE_CASE) to "Los Ángeles",
    )

    private val WORDS: Map<String, String> = mapOf(
        // nombres tipo Ayden / Aiden (el TTS los mapea a “alguien”)
        "ayden" to "Éiden", "aiden" to "Éiden", "aidan" to "Éiden", "aden" to "Éiden",
        "hayden" to "Héiden", "jayden" to "Yéiden", "brayden" to "Bréiden",
        "cayden" to "Kéiden", "raiden" to "Réiden", "kayden" to "Kéiden",
        "zayden" to "Zéiden", "jayda" to "Yéida", "kayla" to "Kéila",
        "layla" to "Léila", "taylor" to "Téilor", "tyler" to "Táiler",
        "kyle" to "Káil", "ryan" to "Ráian", "bryan" to "Bráian",
        "dylan" to "Dílan", "ethan" to "Ízan", "nathan" to "Néizan",
        "noah" to "Nóua", "liam" to "Líam", "owen" to "Óuen",
        "evan" to "Évan", "ian" to "Ían", "sean" to "Shón",
        "shaun" to "Shón", "shawn" to "Shón",
        "james" to "Yeims", "jamie" to "Yéimi", "jim" to "Yim", "jimmy" to "Yimi",
        "john" to "Yon", "johnny" to "Yoni", "jack" to "Yak", "jake" to "Yeik",
        "jacob" to "Yéicob", "jason" to "Yéison", "justin" to "Yástin",
        "joseph" to "Yósif", "joe" to "Yóu", "joel" to "Yóuel",
        "george" to "Yory", "geoffrey" to "Yéfri", "jeffrey" to "Yéfri",
        "william" to "Uíliam", "will" to "Uil", "bill" to "Bil", "billy" to "Bili",
        "wilson" to "Uílson", "williams" to "Uíliams",
        "michael" to "Máikel", "mike" to "Maik", "mitchell" to "Míchel",
        "richard" to "Ríchard", "dick" to "Dik", "ricky" to "Riki",
        "robert" to "Róbert", "bob" to "Bob", "bobby" to "Bobi", "robin" to "Róbin",
        "edward" to "Éduard", "ed" to "Ed", "eddie" to "Edi", "ted" to "Ted",
        "henry" to "Hénri", "harry" to "Hári", "harold" to "Hárold",
        "charles" to "Charls", "charlie" to "Chárli", "chuck" to "Chak",
        "thomas" to "Tómas", "tom" to "Tom", "tommy" to "Tomi",
        "christopher" to "Crístofer", "chris" to "Cris", "christian" to "Crístian",
        "steven" to "Stíven", "stephen" to "Stíven", "steve" to "Stiv",
        "peter" to "Píter", "pete" to "Pit", "patrick" to "Pátrick",
        "paul" to "Pol", "philip" to "Fílip", "phillip" to "Fílip",
        "andrew" to "Ándru", "andy" to "Andi", "anthony" to "Ánzoni",
        "matthew" to "Mázhiu", "matt" to "Mat", "mark" to "Mark",
        "scott" to "Escot", "stuart" to "Estíuart", "stewart" to "Estíuart",
        "brian" to "Bráian", "keith" to "Kiz", "kenneth" to "Kéneth",
        "kevin" to "Kévin", "eric" to "Éric", "derek" to "Dérek",
        "craig" to "Kreg", "greg" to "Greg", "gregory" to "Grégori",
        "wayne" to "Uéin", "warren" to "Uóren", "walter" to "Uólter",
        "bruce" to "Brus", "gordon" to "Górdon", "howard" to "Háauard",
        "lawrence" to "Lórens", "laurence" to "Lórens",
        "alfred" to "Álfred", "albert" to "Álbert", "arthur" to "Árzur",
        "oliver" to "Óliver", "oscar" to "Óscar", "felix" to "Félix",
        "max" to "Max", "maxwell" to "Máxuel", "alex" to "Álex",
        "alexander" to "Álexander", "nicholas" to "Nícolas", "nick" to "Nik",
        "benjamin" to "Bényamin", "ben" to "Ben",
        "samuel" to "Sámiuel", "sam" to "Sam", "seth" to "Set",
        "adam" to "Ádam", "aaron" to "Éron", "abraham" to "Éibraham",
        "isaac" to "Áisac", "isaiah" to "Aisáia", "elijah" to "Iláiya",
        "joshua" to "Yóshua", "caleb" to "Kéleb", "luke" to "Liuk",
        "lucas" to "Lúcas", "nathaniel" to "Nazániel",
        "mary" to "Méri", "marie" to "Marí",
        "jane" to "Yein", "jean" to "Yin", "joan" to "Yóun",
        "anne" to "An", "ann" to "An", "anna" to "Ána",
        "elizabeth" to "Elízabet", "liz" to "Lis", "betty" to "Beti",
        "helen" to "Hélen", "ellen" to "Élen", "eleanor" to "Élenor",
        "margaret" to "Márgaret", "maggie" to "Magui", "meg" to "Meg",
        "sarah" to "Séra", "sara" to "Sára", "susan" to "Súsan",
        "stephanie" to "Stéfani", "emily" to "Émili", "emma" to "Éma",
        "amy" to "Éimi", "katie" to "Kéiti", "kate" to "Keit", "kathy" to "Kázi",
        "catherine" to "Kázerin", "katherine" to "Kázerin",
        "caroline" to "Cárolain", "carol" to "Cárol", "cindy" to "Sindi",
        "jennifer" to "Yénifer", "jenny" to "Yeni", "jessica" to "Yésica",
        "julie" to "Yúli", "julia" to "Yúlia", "julian" to "Yúlian",
        "nancy" to "Nánsi", "patty" to "Pati",
        "barbara" to "Bárbara", "dorothy" to "Dórozi",
        "ruth" to "Ruz", "sharon" to "Shéron", "linda" to "Linda",
        "deborah" to "Débora", "donna" to "Dóna", "diane" to "Daián",
        "hannah" to "Hána", "chloe" to "Clóu", "zoey" to "Zóui",
        "madison" to "Médison", "brooklyn" to "Brúklin",
        "grace" to "Greis", "faith" to "Feiz", "hope" to "Joup",
        "rose" to "Rous", "lily" to "Lili", "lucy" to "Lúsi",
        "violet" to "Váiolet", "ivy" to "Áivi", "ruby" to "Rubi",
        "alice" to "Ális", "agnes" to "Ágnes", "edith" to "Ídit",
        "evelyn" to "Évelin", "florence" to "Flórens",
        "hastings" to "Héstings", "japp" to "Yap", "lemon" to "Lémon",
        "marple" to "Márpel", "poirot" to "Poaró", "hercule" to "Erciúl",
        "sherlock" to "Shérloc", "holmes" to "Jolms", "watson" to "Guótson",
        "moriarty" to "Moriárti", "lestrade" to "Lestréid",
        "christie" to "Cristi", "agatha" to "Ágata",
        "doyle" to "Doil", "conan" to "Cóuan",
        "blackheart" to "Blákhart", "blackwood" to "Blákuud",
        "black" to "Blak", "white" to "Uáit", "green" to "Grin",
        "brown" to "Bráun", "gray" to "Grei", "grey" to "Grei",
        "smith" to "Esmit", "jones" to "Yóns", "johnson" to "Yónson",
        "miller" to "Míler", "davis" to "Déivis",
        "moore" to "Mur", "anderson" to "Ánderson",
        "jackson" to "Yákson", "harris" to "Háris",
        "martin" to "Martín", "thompson" to "Tómpson", "garcia" to "García",
        "martinez" to "Martínez", "robinson" to "Róbinson", "clark" to "Clark",
        "rodriguez" to "Rodríguez", "lewis" to "Lúis", "lee" to "Li",
        "walker" to "Uóker", "hall" to "Jol", "allen" to "Álen",
        "young" to "Yang", "king" to "King", "wright" to "Rait",
        "hill" to "Jil", "adams" to "Ádams",
        "baker" to "Béiker", "nelson" to "Nélson", "carter" to "Cárter",
        "perez" to "Pérez", "roberts" to "Róberts",
        "turner" to "Térner", "phillips" to "Fílips", "campbell" to "Cámbel",
        "parker" to "Párker", "evans" to "Évans", "edwards" to "Éduards",
        "collins" to "Cólins", "morris" to "Móris",
        "murphy" to "Mérfi", "cook" to "Cuk", "rogers" to "Róyers",
        "morgan" to "Mórgan", "peterson" to "Píterson", "cooper" to "Cúper",
        "reed" to "Rid", "bailey" to "Béili", "bell" to "Bel",
        "gomez" to "Gómez", "kelly" to "Kéli",
        "ward" to "Uord", "cox" to "Cox", "richardson" to "Ríchardson",
        "wood" to "Uud", "brooks" to "Bruks",
        "bennett" to "Bénet",
        "hughes" to "Hius", "price" to "Prais", "sanders" to "Sánders",
        "patterson" to "Páterson", "powell" to "Páuel", "jenkins" to "Yénkins",
        "perry" to "Péri", "russell" to "Rásel", "sullivan" to "Sálivan",
        "miss" to "Mis", "mister" to "Míster", "mr" to "Míster",
        "mrs" to "Mísis", "ms" to "Mis", "sir" to "Ser",
        "lord" to "Lord", "lady" to "Leidi", "dame" to "Deim",
        "scotland" to "Escótland", "yard" to "Yard",
        "london" to "Lóndon", "england" to "Íngland",
        "britain" to "Bríten", "british" to "Britis", "english" to "Ínglish",
        "america" to "América", "american" to "Américan",
        "chapter" to "cháper", "murder" to "márder", "mystery" to "místery",
        "crime" to "craim", "killer" to "quíler", "death" to "dez",
        "dead" to "ded", "blood" to "blad", "night" to "nait",
        "light" to "lait", "house" to "jaus", "street" to "strit",
        "road" to "róud", "park" to "park", "hotel" to "jotél",
        "club" to "clab", "train" to "trein", "station" to "stéishon",
        "letter" to "léter", "note" to "nóut", "phone" to "fóun",
        "okay" to "oukéi", "hello" to "jelou", "bye" to "bai",
        "morning" to "mórning", "evening" to "ívening", "afternoon" to "áfter nun",
        "heart" to "Hart", "love" to "lov", "life" to "laif",
        "time" to "taim", "fire" to "fáier", "water" to "uóter",
        "school" to "escul", "office" to "ófis", "police" to "polís",
        "detective" to "detectiv", "inspector" to "inspéctor",
        "captain" to "céptin", "colonel" to "kérnel", "sergeant" to "sárgent",
    )

    fun forOffline(raw: String): String {
        var t = raw
        for ((re, rep) in PHRASES) {
            t = re.replace(t, rep)
        }
        t = t.replace(Regex("""\b([A-Za-z][A-Za-z''-]{1,40})\b""")) { m ->
            rewriteToken(m.groupValues[1])
        }
        return t
    }

    private fun rewriteToken(word: String): String {
        val key = word.lowercase().trimEnd('.')
        WORDS[key]?.let { mapped ->
            return preserveCap(word, mapped)
        }
        if (!looksEnglish(word, key)) return word
        return preserveCap(word, transcribe(key))
    }

    private fun preserveCap(original: String, spoken: String): String {
        if (original.all { it.isUpperCase() || !it.isLetter() }) return spoken.uppercase()
        if (original.firstOrNull()?.isUpperCase() == true) {
            return spoken.replaceFirstChar { it.uppercase() }
        }
        return spoken.replaceFirstChar { it.lowercase() }
    }

    fun looksEnglish(word: String, lower: String = word.lowercase()): Boolean {
        if (lower.length < 3) return false
        if (lower in SPANISH_SKIP) return false
        if (lower.any { it in "áéíóúüñ" }) return false
        if (ENGLISH_CLUSTERS.any { lower.contains(it) }) return true
        if (ENGLISH_SUFFIX.any { lower.endsWith(it) }) return true
        // e muda: Kate, Hope, Luke, Mike (nombres en inglés)
        if (word.first().isUpperCase() && SILENT_E.matches(lower)) return true
        // w / y vocálica inglesa en nombre propio
        if (word.first().isUpperCase() && (lower.contains('w') || lower.contains("ay") ||
                lower.contains("ey") || lower.contains("oy") || lower.contains("ow"))
        ) {
            return true
        }
        return false
    }

    /** Inglés → sílabas que un TTS es-AR lee como suenan en inglés. */
    private fun transcribe(raw: String): String {
        var s = raw
        val digraphs = listOf(
            "ayden" to "éiden",
            "aiden" to "éiden",
            "aight" to "eit",
            "ough" to "af",
            "augh" to "of",
            "ight" to "ait",
            "tion" to "shon",
            "sion" to "shon",
            "ture" to "cher",
            "sure" to "sher",
            "cian" to "shan",
            "kn" to "n",
            "wr" to "r",
            "wh" to "hu",
            "ph" to "f",
            "th" to "t",
            "ck" to "k",
            "dg" to "j",
            "qu" to "cu",
            "ee" to "i",
            "ea" to "i",
            "oo" to "u",
            "ou" to "au",
            "ow" to "au",
            "ay" to "ei",
            "ai" to "ei",
            "ey" to "ei",
            "ei" to "ei",
            "oy" to "oi",
            "oi" to "oi",
            "oa" to "o",
            "ew" to "iu",
            "aw" to "o",
            "au" to "o",
            "ch" to "ch",
            "sh" to "sh",
        )
        for ((from, to) in digraphs) {
            s = s.replace(from, to)
        }
        val silent = SILENT_E.matchEntire(s)
        if (silent != null) {
            val stem = silent.groupValues[1]
            val v = silent.groupValues[2]
            val c = silent.groupValues[3]
            val long = when (v) {
                "a" -> "ei"
                "i" -> "ai"
                "o" -> "ou"
                "u" -> "iu"
                "e" -> "i"
                else -> v
            }
            s = stem + long + c
        }
        s = s.replace('j', 'y').replace('w', 'u')
        if (s.endsWith('y') && s.length > 2) s = s.dropLast(1) + "i"
        return s
    }

    private val SILENT_E = Regex("""^(.*)([aeiou])([bcdfgklmnpstvzx])e$""")

    private val ENGLISH_CLUSTERS = listOf(
        "th", "wh", "gh", "ck", "ph", "kn", "wr", "ough", "ight", "tion",
        "ayden", "aiden", "ee", "oo", "qu",
    )

    private val ENGLISH_SUFFIX = listOf(
        "ayden", "aiden", "ton", "ham", "ley", "ford", "wood", "field",
        "wick", "worth", "stone", "well", "shire", "burg", "berg",
        "man", "son", "kins", "view", "land",
    )

    /** Español: no transcribir. */
    private val SPANISH_SKIP = setOf(
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
        "juan", "pedro", "carlos", "ana", "maria", "jose", "luis", "diego",
        "pablo", "andres", "miguel", "jorge", "alberto", "ricardo", "fernando",
        "francisco", "manuel", "antonio", "raul", "sergio", "gonzalo",
        "facundo", "agustin", "santiago", "mateo", "thiago", "ramiro",
        "nicolas", "emiliano", "valentina", "camila", "lucia", "sofia",
        "martina", "catalina", "emilia", "julieta", "florencia", "rocio",
        "mercedes", "carmen", "rosa", "teresa", "isabel", "elena", "tomas",
        "martín", "daniel", "laura", "sandra", "victoria", "cecilia",
        "gabriel", "rafael", "eduardo", "roberto", "alejandro", "patricia",
        "veronica", "silvia", "claudia", "adriana", "beatriz", "ines",
        "pilar", "soledad", "milagros", "esperanza", "concepcion",
        "bueno", "buena", "buenos", "buenas", "grande", "pequeño",
        "nuevo", "nueva", "viejo", "vieja", "mismo", "misma",
    )
}

object EdgeSsmlText {
    fun body(raw: String): String {
        val spoken = EnglishPronunciation.forOffline(raw)
        return insertHumanBreaks(xmlEscape(spoken))
    }

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
            }
            i++
        }
        return out.toString()
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
