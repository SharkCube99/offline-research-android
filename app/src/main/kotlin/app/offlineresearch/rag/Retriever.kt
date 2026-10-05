package app.offlineresearch.rag

import java.util.zip.Inflater

/** A retrieved passage with its source metadata. */
data class Passage(
    val corpus: String,
    val id: Long,
    val title: String,
    val url: String,
    val seq: Int,
    /** "Title: passage text", exactly as stored in the index. */
    val text: String,
    /** What the model is given: the whole passage, or the sentences chosen from it. */
    val excerpt: String = text,
    /** The question contains this article's title or one of its redirects. */
    val named: Boolean = false,
    /** A search the planner wrote named this article: a suggestion, weaker than the question naming it. */
    val suggested: Boolean = false,
) {
    /** Unique across corpora. */
    val passageId: String get() = "$corpus:$id"

    /** Passages from the same article share a source. */
    val sourceKey: String get() = "$corpus:$title"
}

/**
 * Searches the corpus indexes. Port of data-pipeline/retrieval.py; the two
 * must behave the same. Two channels are interleaved:
 *
 * 1. Name channel: word n-grams of the question are looked up in the `names`
 *    table (titles and redirects), so "heart attack" finds "Myocardial infarction".
 * 2. Passage channel: BM25 over all passages, requiring every content word and
 *    relaxing one word at a time if that finds too little.
 */
class Retriever(
    /** Corpus name to database, in a stable order. */
    private val indexes: Map<String, SqlDatabase>,
    private val weights: Triple<Double, Double, Double> = Triple(4.0, 3.0, 1.0),
) {
    private val rank = "bm25(${weights.first}, ${weights.second}, ${weights.third})"

    /**
     * Special-purpose packs (Ethereum texts, places) mark themselves "strict" in
     * their meta table. They take part only when an article of theirs is named
     * in the question or a passage holds every content word of it; otherwise a
     * question about tides would pull in whatever proposal mentions "causes".
     */
    private val strict: Set<String> = indexes.filter { (_, db) -> matchMode(db) in setOf("strict", "names") }.keys

    /**
     * A pack of lists of proper names (places by city) marks itself "names". It
     * is strict, and its passages are never searched by their words: with
     * millions of shop names, some list holds the words of almost any question.
     * It answers only when one of its lists is named.
     */
    private val namesOnly: Set<String> = indexes.filter { (_, db) -> matchMode(db) == "names" }.keys

    private fun matchMode(db: SqlDatabase): String? =
        try {
            db.query("SELECT value FROM meta WHERE key = 'match'") { it.string(0) }.firstOrNull()
        } catch (e: Exception) {
            null // an index without that row, or without a meta table, is a general one
        }

    private data class Ranked(val passageId: Long, val score: Double)

    private data class Named(val nameLength: Int, val score: Double, val corpus: String, val ids: List<Long>)

    /**
     * Top [k] passages for [question], at most [perArticle] from one article.
     *
     * [context] is the user's question when [question] is a search the planner
     * wrote. An article the planner names is then represented by its passages
     * that match the user's words, and is left out if none does: "Hypothermia"
     * for someone shivering and confused stays, a title the planner made up
     * that has nothing to do with the question goes.
     *
     * With neither [context] nor [implied] this is the search of the Python
     * reference, passage for passage.
     */
    fun search(
        question: String,
        k: Int = DEFAULT_K,
        perArticle: Int = 2,
        candidates: Int = 20,
        context: String? = null,
        implied: List<String> = emptyList(),
    ): List<Passage> {
        val terms = QueryBuilder.queryTerms(question)
        if (terms.isEmpty()) return emptyList()
        val contextTerms = context?.let { QueryBuilder.queryTerms(it) }?.ifEmpty { null } ?: terms

        val named = mutableListOf<Named>()
        val byCorpus = LinkedHashMap<String, List<Long>>()
        for ((corpus, db) in indexes) {
            named += nameChannel(corpus, db, question, terms, contextTerms, implied)
            byCorpus[corpus] =
                if (corpus in namesOnly) emptyList() else passageChannel(db, terms, candidates, relax = corpus !in strict)
        }
        named.sortWith(compareBy<Named> { -it.nameLength }.thenBy { it.score })

        // Name channel order: the best passage of each named article, then the second best.
        val nameList = named.map { it.corpus to it.ids[0] } +
            named.filter { it.ids.size > 1 }.map { it.corpus to it.ids[1] }
        // Passage channel order: corpora take turns, each in its own BM25 order.
        val passageList = mutableListOf<Pair<String, Long>>()
        for (position in 0 until candidates) {
            for ((corpus, ids) in byCorpus) {
                if (position < ids.size) passageList += corpus to ids[position]
            }
        }

        val merged = mutableListOf<Pair<String, Long>>()
        for (position in 0 until maxOf(nameList.size, passageList.size)) {
            nameList.getOrNull(position)?.let { merged += it }
            passageList.getOrNull(position)?.let { merged += it }
        }

        // An article counts as named by the query only if its name is a fair share of
        // the query: the whole of a planner's "Hypothermia", not the "hiking" in a
        // long question about something else.
        val fromNames = named.filter { coversEnough(it.nameLength, terms.size) }
            .flatMap { name -> name.ids.map { name.corpus to it } }.toSet()
        val results = mutableListOf<Passage>()
        val seen = HashSet<Pair<String, Long>>()
        val perSource = HashMap<String, Int>()
        for (entry in merged) {
            if (!seen.add(entry)) continue
            val passage = load(entry.first, entry.second)?.let { if (entry in fromNames) it.copy(named = true) else it } ?: continue
            val used = perSource[passage.sourceKey] ?: 0
            if (used >= perArticle) continue // one long article must not fill the whole list
            perSource[passage.sourceKey] = used + 1
            results += passage
            if (results.size == k) break
        }
        return results
    }

    /**
     * Puts the opening passage of the articles the question names (by title, or
     * by a redirect such as "heart attack" for "Myocardial infarction") at the front
     * of [ranked], if search did not return it. Search ranks passages by the
     * question's words, and for "What causes the tides?" that favours the
     * history section, where "caused" appears in every sentence, over the
     * opening of "Tide", which states the answer.
     */
    fun withLeads(question: String, ranked: List<Passage>, maxArticles: Int = 3): List<Passage> {
        val terms = QueryBuilder.queryTerms(question)
        val named = ranked.filter { it.named || it.suggested || titleIsNamed(it.title, terms) }
            .distinctBy { it.sourceKey }.take(maxArticles)
        val leads = named.mapNotNull { passage ->
            if (passage.seq == 0) return@mapNotNull passage
            val first = indexes.getValue(passage.corpus).query(
                "SELECT a.first_passage_id FROM passages p JOIN articles a ON a.id = p.article_id WHERE p.id = ${passage.id}",
            ) { it.long(0) }.firstOrNull() ?: return@mapNotNull null
            (ranked.firstOrNull { it.corpus == passage.corpus && it.id == first } ?: load(passage.corpus, first))
                ?.copy(named = passage.named, suggested = passage.suggested)
        }
        val leadIds = leads.map { it.passageId }.toSet()
        // The openings first, then everything in its searched order. Named passages
        // are not moved up: a planner's guess must not push out a passage that
        // matched the question's own words.
        return leads + ranked.filter { it.passageId !in leadIds }
    }

    /** Best first. Optionally only passages [first]..[last]. */
    private fun ranked(db: SqlDatabase, ftsQuery: String, limit: Int, first: Long? = null, last: Long? = null): List<Ranked> {
        // The numbers are our own integers, so they are written into the SQL;
        // only the query text is bound.
        val range = if (first != null && last != null) " AND rowid BETWEEN $first AND $last" else ""
        return db.query(
            "SELECT rowid, rank FROM passages_fts WHERE passages_fts MATCH ? AND rank MATCH ?" +
                "$range ORDER BY rank LIMIT $limit",
            listOf(ftsQuery, rank),
        ) { Ranked(it.long(0), it.double(1)) }
    }

    /** Passage ids, best first: all words required, then all but one. */
    private fun passageChannel(db: SqlDatabase, terms: List<String>, limit: Int, relax: Boolean = true): List<Long> {
        val rows = ranked(db, QueryBuilder.match(terms, "AND"), limit)
        if (rows.size >= limit || terms.size < 2 || !relax) return rows.map { it.passageId }
        val found = rows.map { it.passageId }.toSet()
        val candidates = if (terms.size == 2) {
            ranked(db, QueryBuilder.match(terms, "OR"), limit)
        } else {
            terms.indices.flatMap { skipped ->
                ranked(db, QueryBuilder.match(terms.filterIndexed { i, _ -> i != skipped }, "AND"), limit)
            }
        }
        val relaxed = LinkedHashMap<Long, Double>()
        for (row in candidates) {
            if (row.passageId in found) continue
            val best = relaxed[row.passageId]
            if (best == null || row.score < best) relaxed[row.passageId] = row.score
        }
        // Passages with every word always come before passages missing one.
        val extra = relaxed.entries.sortedBy { it.value }.take(limit - rows.size).map { it.key }
        return rows.map { it.passageId } + extra
    }

    private data class Article(val id: Long, val first: Long, val count: Long)

    private fun nameChannel(
        corpus: String,
        db: SqlDatabase,
        question: String,
        terms: List<String>,
        contextTerms: List<String> = terms,
        /** Words the question implies (see QueryBuilder.impliedTerms); they help choose which passages of a named article to take. */
        implied: List<String> = emptyList(),
    ): List<Named> {
        val claimed = HashSet<Int>() // word positions already matched by a longer name
        val articles = LinkedHashMap<Long, Pair<Int, Article>>()
        for (gram in QueryBuilder.nameGrams(question)) {
            val positions = gram.start until gram.start + gram.length
            // "heart" and "attack" add nothing once "heart attack" matched.
            if (positions.all { it in claimed }) continue
            val variants = if (gram.key.endsWith("s")) listOf(gram.key, gram.key.dropLast(1)) else listOf(gram.key)
            var rows = emptyList<Article>()
            for (variant in variants) {
                rows = db.query(
                    "SELECT a.id, a.first_passage_id, a.passage_count " +
                        "FROM names n JOIN articles a ON a.id = n.article_id " +
                        "WHERE n.key = ? ORDER BY n.is_title DESC, a.popularity DESC LIMIT $ARTICLES_PER_NAME",
                    listOf(variant),
                ) { Article(it.long(0), it.long(1), it.long(2)) }
                if (rows.isNotEmpty()) break
            }
            if (rows.isEmpty()) continue
            // A whole-question match does not claim its words: the question may also
            // be the title of a song or a film, and then the names inside it still count.
            if (gram.length > 1 && QueryBuilder.isPlainName(gram.key.split(' '))) claimed += positions
            for (article in rows) articles.putIfAbsent(article.id, gram.length to article)
        }
        // Special-purpose packs also list their entries under two-part names
        // ("pharmacy nairobi"), which a question can contain with words in between.
        if (corpus in strict) {
            for ((length, key) in QueryBuilder.splitNames(question)) {
                val rows = db.query(
                    "SELECT a.id, a.first_passage_id, a.passage_count " +
                        "FROM names n JOIN articles a ON a.id = n.article_id " +
                        "WHERE n.key = ? ORDER BY n.is_title DESC, a.popularity DESC LIMIT $ARTICLES_PER_NAME",
                    listOf(key),
                ) { Article(it.long(0), it.long(1), it.long(2)) }
                for (article in rows) articles.putIfAbsent(article.id, length to article)
            }
        }

        val anyTerm = QueryBuilder.match((contextTerms + implied).distinct(), "OR")
        val planned = contextTerms !== terms
        val scored = articles.values.mapNotNull { (length, article) ->
            // The article's passages that best match the whole question.
            val best = ranked(db, anyTerm, 2, article.first, article.first + article.count - 1)
            when {
                best.isEmpty() -> null
                // A title the planner proposed has to earn its place: its best passage
                // must hold two of the question's words, not one. "Plug" (a comic
                // character) matches a question about Brazil's plugs in a single word.
                planned && contextTerms.size >= 3 && hits(corpus, best[0].passageId, contextTerms) < 2 -> null
                else -> Named(length, best[0].score, corpus, best.map { it.passageId })
            }
        }
        return scored.sortedWith(compareBy<Named> { -it.nameLength }.thenBy { it.score }).take(NAME_ARTICLES)
    }

    /** How many of [terms] the passage contains, in any form of the word. */
    private fun hits(corpus: String, passageId: Long, terms: List<String>): Int {
        val tokens = tokenize(load(corpus, passageId)?.text ?: return 0).toSet()
        return terms.count { term -> term in tokens || tokens.any { sameWord(term, it) } }
    }

    private fun load(corpus: String, passageId: Long): Passage? =
        indexes.getValue(corpus).query(
            "SELECT a.title, a.url, p.seq, p.body FROM passages p " +
                "JOIN articles a ON a.id = p.article_id WHERE p.id = $passageId",
        ) { Passage(corpus, passageId, it.string(0), it.string(1), it.long(2).toInt(), inflate(it.blob(3))) }
            .firstOrNull()

    companion object {
        const val DEFAULT_K = 20
        /** How many articles the name channel may contribute, per corpus. */
        const val NAME_ARTICLES = 3
        /** How many articles one name may resolve to (titles first, then most viewed). */
        const val ARTICLES_PER_NAME = 2

        /** Passage bodies are stored zlib-compressed. */
        fun inflate(compressed: ByteArray): String {
            val inflater = Inflater()
            inflater.setInput(compressed)
            val out = java.io.ByteArrayOutputStream(compressed.size * 3)
            val buffer = ByteArray(4096)
            try {
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buffer, 0, n)
                }
            } finally {
                inflater.end()
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
