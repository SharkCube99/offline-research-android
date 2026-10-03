package app.offlineresearch.rag

/** Turns a question into FTS5 queries and article-name candidates. Port of retrieval.py. */
object QueryBuilder {
    /** Longest article name looked for in a question, in words. */
    const val MAX_NAME_WORDS = 5

    /** Longest question looked up whole, in words. */
    const val MAX_QUESTION_NAME_WORDS = 12

    data class NameGram(val start: Int, val length: Int, val key: String)

    /** Lower-cased content words of the question, in order, without repeats. */
    fun queryTerms(question: String): List<String> {
        val tokens = tokenize(question)
        val terms = tokens.filter { it !in FUNCTION_WORDS && it !in QUERY_FILLER && it.length > 1 }
        // Function words are absent from passage bodies but still present in
        // titles ("The Who"), so a question made only of them can still match.
        return terms.ifEmpty { tokens }.distinct()
    }

    /** True for the n-grams that [nameGrams] builds from parts of the question. */
    fun isPlainName(gram: List<String>, maxWords: Int = MAX_NAME_WORDS): Boolean =
        gram.size <= maxWords && gram.first() !in FUNCTION_WORDS && gram.last() !in FUNCTION_WORDS

    /** Terms joined with AND or OR, each quoted so no FTS5 syntax can leak in. */
    fun match(terms: List<String>, operator: String): String =
        terms.joinToString(" $operator ") { "\"$it\"" }

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
