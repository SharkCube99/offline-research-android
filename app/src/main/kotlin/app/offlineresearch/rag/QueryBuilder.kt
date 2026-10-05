package app.offlineresearch.rag

/** Turns a question into FTS5 queries and article-name candidates. Port of retrieval.py. */
object QueryBuilder {
    /** Longest article name looked for in a question, in words. */
    const val MAX_NAME_WORDS = 5

    /** Longest question looked up whole, in words. */
    const val MAX_QUESTION_NAME_WORDS = 12

    /** How many two-part names are looked up per question in a special-purpose pack. */
    const val MAX_SPLIT_NAMES = 150

    data class NameGram(val start: Int, val length: Int, val key: String)

    /** Lower-cased content words of the question, in order, without repeats. */
    fun queryTerms(question: String): List<String> {
        val tokens = tokenize(question)
        val terms = tokens.filter { it !in FUNCTION_WORDS && it !in QUERY_FILLER && it.length > 1 }
        // Function words are absent from passage bodies but still present in
        // titles ("The Who"), so a question made only of them can still match.
        return terms.ifEmpty { tokens }.distinct()
    }

    private val GETTING_THERE = Regex(
        """\b(?:get|getting|go|going|travel|travelling|traveling)\s+(?:from|to|into|around)\b|\bway (?:from|to|into)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Words a question implies without saying. "How do I get from the airport to
     * the city center?" is about transport, and the passage that answers it speaks
     * of the metro, buses and taxis. They are used to pick passages and sentences
     * inside articles already found, never to search for articles.
     */
    fun impliedTerms(question: String): List<String> =
        if (GETTING_THERE.containsMatchIn(question)) listOf("transport", "metro", "bus", "taxi", "train", "shuttle") else emptyList()

    /**
     * Names made of two phrases of the question with other words between them,
     * as (number of words, key). "Is there a pharmacy open late in central
     * Nairobi?" never says "pharmacy in Nairobi", but a places pack lists that
     * entry under "pharmacy nairobi" too. The first phrase has one or two words,
     * the second up to three; neither starts or ends with a function word.
     * Longest first, then in reading order. Used only for special-purpose
     * packs, whose entries carry such names.
     */
    fun splitNames(question: String): List<Pair<Int, String>> {
        val parts = nameGrams(question, maxWords = 3).filter { isPlainName(it.key.split(' '), 3) }
        val pairs = mutableListOf<Pair<Int, String>>()
        for (first in parts) {
            if (first.length > 2) continue
            for (second in parts) {
                // Touching phrases are an ordinary n-gram already.
                if (first.start + first.length < second.start) pairs += (first.length + second.length) to "${first.key} ${second.key}"
            }
        }
        return pairs.sortedByDescending { it.first }.take(MAX_SPLIT_NAMES)
    }

    /** True for the n-grams that [nameGrams] builds from parts of the question. */
    fun isPlainName(gram: List<String>, maxWords: Int = MAX_NAME_WORDS): Boolean =
        gram.size <= maxWords && gram.first() !in FUNCTION_WORDS && gram.last() !in FUNCTION_WORDS

    // American and British spellings of the same word. The index holds whichever
    // the article used, so a question with "center" must also find "centre".
    private val SPELLINGS = listOf(
        "center" to "centre",
        "meter" to "metre",
        "kilometer" to "kilometre",
        "liter" to "litre",
        "theater" to "theatre",
        "harbor" to "harbour",
        "color" to "colour",
        "neighborhood" to "neighbourhood",
        "labor" to "labour",
        "traveler" to "traveller",
        "jewelry" to "jewellery",
        "airplane" to "aeroplane",
        "program" to "programme",
        "gray" to "grey",
        "tire" to "tyre",
        "license" to "licence",
        "defense" to "defence",
        "aluminum" to "aluminium",
        "fiber" to "fibre",
    )
    private val SPELLING: Map<String, String> = SPELLINGS.toMap() + SPELLINGS.associate { it.second to it.first }

    /** Terms joined with AND or OR, each quoted so no FTS5 syntax can leak in; a word with two spellings matches either. */
    fun match(terms: List<String>, operator: String): String =
        terms.joinToString(" $operator ") { term ->
            val other = SPELLING[term]
            if (other != null) "(\"$term\" OR \"$other\")" else "\"$term\""
        }

    /**
     * Word n-grams that could be an article name, longest first. An n-gram may
     * contain function words ("war of the currents") but not start or end with
     * one. Single filler words are not looked up.
     *
     * The whole question comes first when it is not already one of those
     * n-grams: Wikipedia has redirects that are questions, such as "Why is the
     * sky blue", and they point at the article that answers them.
     */
    fun nameGrams(question: String, maxWords: Int = MAX_NAME_WORDS): List<NameGram> {
        val tokens = tokenize(question)
        val grams = mutableListOf<NameGram>()
        if (tokens.size in 2..MAX_QUESTION_NAME_WORDS && !isPlainName(tokens, maxWords)) {
            grams += NameGram(0, tokens.size, tokens.joinToString(" "))
        }
        for (length in maxWords downTo 1) {
            for (start in 0..tokens.size - length) {
                val gram = tokens.subList(start, start + length)
                if (gram.first() in FUNCTION_WORDS || gram.last() in FUNCTION_WORDS) continue
                if (length == 1 && (gram[0] in QUERY_FILLER || gram[0].length < 3)) continue
                grams += NameGram(start, length, gram.joinToString(" "))
            }
        }
        return grams
    }
}
