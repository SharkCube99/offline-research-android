package app.offlineresearch.rag

/** Chooses, and possibly shortens, the retrieved passages that go into the prompt. */
fun interface SourceSelector {
    /** [ranked] is best first. The result keeps that order; `excerpt` is what the model will read. */
    fun select(question: String, ranked: List<Passage>): List<Passage>
}

// A sentence ends at . ! or ? (plus any closing quotes or brackets) followed by
// whitespace and something that looks like the start of a new sentence. Same
// rule as data-pipeline/textproc.py, which cut the passages on these boundaries.
private val SENTENCE_BREAK = Regex("(?<=[.!?])[\"')\\]]*\\s+(?=[\"'(\\[]?[A-Z0-9])")

// Wikipedia's editing notes, left in the text by the dump. They say nothing
// about the subject and are dropped from excerpts.
private val EDITOR_NOTE = Regex(
    """\[(?:citation needed|not in body|dead link|permanent dead link|update|needs update|clarification needed|""" +
        """better source needed|when\?|who\?|which\?|by whom\?|according to whom\?|failed verification|""" +
        """verification needed|original research\??|unreliable source\??|dubious[^\]]*|vague|page needed)]""",
    RegexOption.IGNORE_CASE,
)

// Marks of a reference list or link section at the end of an article: it
// repeats the article's name, so it matches any question about the subject.
private val REFERENCE_DEBRIS = Regex("""\b(?:ISBN|ISSN|doi:|Retrieved \d|Archived from|Template:|Wikimedia Commons|Wikidata|KML file|pp?\.(?: \d|$))""")

/** How many opening sentences of the article the question names are wanted regardless of their words. */
private const val LEAD_SENTENCES = 4

/** Weight of a sentence from an article whose title shares no word with the question. */
private const val OFF_TOPIC_WEIGHT = 0.6

/** A sentence scoring below this share of the best one is not worth its tokens. */
private const val WEAKEST_KEPT = 0.4

/** A "sentence" longer than this is a list or table that lost its line breaks; it is not worth its tokens. */
private const val MAX_SENTENCE_CHARS = 400

fun splitSentences(text: String): List<String> = SENTENCE_BREAK.split(text).filter { it.isNotBlank() }

/** About four characters per token for English text; good enough to pack a budget without a tokenizer call per sentence. */
fun roughTokens(text: String): Int = (text.length + 3) / 4

/**
 * Sentence-level compression: instead of two or three whole passages, the
 * prompt gets the sentences that bear on the question from more passages.
 * Reading the prompt is most of the wait for an answer, and its cost grows
 * with its length, so fewer and better-chosen tokens are a direct saving.
 *
 * Extractive only: sentences are copied, never rewritten, so a citation still
 * points at text that is in the source.
 */
class SourceCompressor(
    private val budgetTokens: Int,
    private val maxPassages: Int = 6,
    private val maxPerSource: Int = 2,
    private val estimateTokens: (String) -> Int = ::roughTokens,
) : SourceSelector {

    private class Sentence(
        val passage: Int,
        val position: Int,
        val text: String,
        val score: Double,
        val hits: Int,
        /** Wanted as part of an article's opening, whatever words it has. */
        val lead: Boolean,
    )

    override fun select(question: String, ranked: List<Passage>): List<Passage> {
        val candidates = candidates(ranked)
        if (candidates.isEmpty()) return emptyList()
        val terms = QueryBuilder.queryTerms(question)

        val sentences = mutableListOf<Sentence>()
        candidates.forEachIndexed { rank, passage ->
            // Words of the question that the article's title already covers say
            // which article is on topic, not which of its sentences answers: in
            // "Visa policy of Japan" every sentence is about visas and Japan.
            val titleTokens = tokenize(passage.title).toSet()
            val (inTitle, other) = terms.partition { matches(it, titleTokens) }
            val named = titleIsNamed(passage.title, terms)
            splitSentences(body(passage)).forEachIndexed { position, raw ->
                val text = raw.replace(EDITOR_NOTE, "")
                if (text.length > MAX_SENTENCE_CHARS || REFERENCE_DEBRIS.containsMatchIn(text)) return@forEachIndexed
                val tokens = tokenize(text).toSet()
                val own = other.count { matches(it, tokens) }
                val repeated = inTitle.count { matches(it, tokens) }
                // The opening of the article the question names states what the
                // subject is and, usually, the answer; its first sentences are
                // wanted even when they repeat none of the question's words.
                val lead = when {
                    passage.seq != 0 -> 0.0
                    named && position < LEAD_SENTENCES -> 2.0 - 0.4 * position
                    position == 0 -> 1.0
                    else -> 0.0
                }
                // The question's other words count most. Then come on-topic articles,
                // better ranked passages and shorter sentences.
                var score = 2.0 * own + 0.5 * inTitle.size + 0.25 * repeated + lead + 1.0 / (1 + rank) - text.length / 400.0
                // A stray mention in an article about something else is worth less.
                if (inTitle.isEmpty()) score *= OFF_TOPIC_WEIGHT
                sentences += Sentence(rank, position, text, score, own + repeated, lead > 0)
            }
        }

        // Best sentences first until the budget is used. A sentence with none of
        // the question's words is taken only from an article's opening. Sentences
        // far weaker than the best are left out even if they would fit: a shorter
        // prompt is read sooner, and filler from side articles misleads.
        val floor = (sentences.maxOfOrNull { it.score } ?: 0.0) * WEAKEST_KEPT
        val chosen = HashMap<Int, MutableList<Sentence>>()
        var used = 0
        for (sentence in sentences.sortedWith(compareByDescending<Sentence> { it.score }.thenBy { it.passage }.thenBy { it.position })) {
            if (sentence.hits == 0 && !sentence.lead) continue
            if (sentence.score < floor) break
            val titleCost = if (sentence.passage in chosen) 0 else estimateTokens(candidates[sentence.passage].title) + 4
            val cost = estimateTokens(sentence.text) + titleCost
            if (used + cost > budgetTokens) continue
            chosen.getOrPut(sentence.passage) { mutableListOf() } += sentence
            used += cost
        }
        // Budget left over goes to the sentences that follow the chosen ones: an
        // answer often continues in a sentence that repeats none of the question's
        // words. Passages holding the best sentences are extended first.
        val byBestSentence = chosen.keys.sortedByDescending { rank -> chosen.getValue(rank).maxOf { it.score } }
        var added = chosen.isNotEmpty()
        while (added) {
            added = false
            for (rank in byBestSentence) {
                val picked = chosen.getValue(rank)
                val taken = picked.map { it.position }.toSet()
                val next = sentences.firstOrNull { it.passage == rank && it.position !in taken && it.position - 1 in taken } ?: continue
                val cost = estimateTokens(next.text)
                if (used + cost > budgetTokens) continue
                picked += next
                used += cost
                added = true
            }
        }
        // Nothing fit or matched: fall back to the start of the best passage, cut to the budget.
        if (chosen.isEmpty()) {
            val first = candidates[0]
            return listOf(first.copy(excerpt = "${first.title}: ${fit(splitSentences(body(first).replace(EDITOR_NOTE, "")))}"))
        }

        return candidates.mapIndexedNotNull { rank, passage ->
            val picked = chosen[rank]?.sortedBy { it.position } ?: return@mapIndexedNotNull null
            passage.copy(excerpt = "${passage.title}: ${join(picked)}")
        }
    }

    /** A short excerpt of the best passage, to show the moment search returns. */
    fun preview(question: String, ranked: List<Passage>, previewTokens: Int = 90): Passage? {
        val best = ranked.firstOrNull() ?: return null
        return SourceCompressor(previewTokens, maxPassages = 1, estimateTokens = estimateTokens)
            .select(question, listOf(best)).firstOrNull()
    }

    private fun candidates(ranked: List<Passage>): List<Passage> {
        val seen = HashSet<String>()
        val perSource = HashMap<String, Int>()
        val out = mutableListOf<Passage>()
        for (passage in ranked) {
            if (!seen.add(passage.passageId)) continue
            val used = perSource[passage.sourceKey] ?: 0
            if (used >= maxPerSource) continue
            perSource[passage.sourceKey] = used + 1
            out += passage
            if (out.size == maxPassages) break
        }
        return out
    }

    /** The passage without its "Title: " prefix. */
    private fun body(passage: Passage): String = passage.text.removePrefix("${passage.title}: ")

    /** Whether the word, or another form of it (tide/tides, causes/caused), is among the tokens. */
    private fun matches(term: String, tokens: Set<String>): Boolean = term in tokens || tokens.any { sameWord(term, it) }

    /** Sentences in reading order; a gap between them is marked, so the reader can see text was left out. */
    private fun join(picked: List<Sentence>): String = buildString {
        picked.forEachIndexed { index, sentence ->
            if (index > 0) append(if (sentence.position == picked[index - 1].position + 1) " " else " … ")
            append(sentence.text)
        }
    }

    /** As many leading sentences as fit; at least the first, cut to the budget if it alone is too long. */
    private fun fit(sentences: List<String>): String {
        val kept = mutableListOf<String>()
        var used = 0
        for (sentence in sentences) {
            val cost = estimateTokens(sentence)
            if (kept.isNotEmpty() && used + cost > budgetTokens) break
            kept += sentence
            used += cost
        }
        val text = kept.joinToString(" ")
        return if (estimateTokens(text) <= budgetTokens) text else text.take(budgetTokens * 4)
    }
}
