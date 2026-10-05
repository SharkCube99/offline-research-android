package app.offlineresearch.rag

// Must stay identical to data-pipeline/textproc.py and retrieval.py: the index
// was built with these word lists and this tokenisation. The shared test
// vectors in data-pipeline/eval/query_vectors.json check both sides.

/** Words with no topical meaning. They are not in the index and are dropped from queries. */
val FUNCTION_WORDS: Set<String> = """
a about above after again all also am an and any are as at be because been before being below
between both but by can could did do does doing down during each few for from further had has
have having he her here hers him his how i if in into is it its just me more most my no nor not
now of off on once only or other our out over own same she should so some such than that the
their them then there these they this those through to too under until up very was we were what
when where which while who whom why will with would you your
""".trim().split(Regex("\\s+")).toSet()

/** Common in how questions are phrased, but still meaningful inside an article. */
val QUERY_FILLER: Set<String> = """
best way ways know need tell much many good thing things work works make makes use used using
get difference differences different versus vs explain
""".trim().split(Regex("\\s+")).toSet()

private val TOKEN = Regex("[\\p{L}\\p{N}]+")

/** Lower-cased alphanumeric tokens, split the way the FTS5 unicode61 tokenizer splits. */
fun tokenize(text: String): List<String> =
    TOKEN.findAll(text.lowercase()).map { it.value }.toList()

/** The text as it went into the full-text index: content words only. */
fun indexText(text: String): String = tokenize(text).filter { it !in FUNCTION_WORDS }.joinToString(" ")

/**
 * Two forms of one word: tide/tides, causes/caused, emergency/emergencies,
 * confused/confusion. After the common endings are taken off, one word starts
 * the other or they differ only in a short ending. Words under four letters
 * must match exactly.
 */
fun sameWord(a: String, b: String): Boolean {
    if (a == b) return true
    if (a.length < 4 || b.length < 4) return false
    val x = withoutEnding(a)
    val y = withoutEnding(b)
    if (x == y) return true
    val common = x.commonPrefixWith(y).length
    return common >= 4 && (common == minOf(x.length, y.length) || common >= maxOf(x.length, y.length) - 2)
}

/** The word without a plural, past or -ing ending. Rough on purpose: it only has to bring two forms together. */
private fun withoutEnding(word: String): String = when {
    word.length > 5 && word.endsWith("ies") -> word.dropLast(3) + "y"
    word.length > 6 && word.endsWith("ing") -> word.dropLast(3)
    word.length > 5 && word.endsWith("ed") -> word.dropLast(2)
    word.length > 5 && word.endsWith("es") -> word.dropLast(2)
    word.length > 4 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
    else -> word
}

/**
 * True when the question names this article: every content word of the title
 * is one of the question's words, and the title covers a fair share of the
 * question. "Tide" is on topic for "What causes the tides?"; "Theory of tides"
 * and "Sea kayaking" are not, and neither is "Hiking" for a long question
 * about a snakebite that happens to mention a hike.
 */
fun titleIsNamed(title: String, questionTerms: List<String>): Boolean {
    val words = tokenize(title).filter { it !in FUNCTION_WORDS }
    return words.isNotEmpty() && coversEnough(words.size, questionTerms.size) &&
        words.all { word -> questionTerms.any { sameWord(it, word) } }
}

/** A name is what a question is about only if it accounts for at least a third of the question's content words. */
fun coversEnough(nameWords: Int, questionTerms: Int): Boolean = nameWords * 3 >= questionTerms
