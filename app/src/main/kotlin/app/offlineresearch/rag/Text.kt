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
