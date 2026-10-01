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

    private data class Ranked(val passageId: Long, val score: Double)

    private data class Named(val nameLength: Int, val score: Double, val corpus: String, val ids: List<Long>)

    /** Top [k] passages for [question], at most [perArticle] from one article. */
    fun search(question: String, k: Int = DEFAULT_K, perArticle: Int = 2, candidates: Int = 20): List<Passage> {
        val terms = QueryBuilder.queryTerms(question)
        if (terms.isEmpty()) return emptyList()

        val named = mutableListOf<Named>()
        val byCorpus = LinkedHashMap<String, List<Long>>()
        for ((corpus, db) in indexes) {
            named += nameChannel(corpus, db, question, terms)
            byCorpus[corpus] = passageChannel(db, terms, candidates)
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

        val results = mutableListOf<Passage>()
        val seen = HashSet<Pair<String, Long>>()
        val perSource = HashMap<String, Int>()
        for (entry in merged) {
            if (!seen.add(entry)) continue
            val passage = load(entry.first, entry.second) ?: continue
            val used = perSource[passage.sourceKey] ?: 0
            if (used >= perArticle) continue // one long article must not fill the whole list
            perSource[passage.sourceKey] = used + 1
            results += passage
            if (results.size == k) break
        }
        return results
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
    private fun passageChannel(db: SqlDatabase, terms: List<String>, limit: Int): List<Long> {
        val rows = ranked(db, QueryBuilder.match(terms, "AND"), limit)
        if (rows.size >= limit || terms.size < 2) return rows.map { it.passageId }
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

    private fun nameChannel(corpus: String, db: SqlDatabase, question: String, terms: List<String>): List<Named> {
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
            if (gram.length > 1) claimed += positions
            for (article in rows) articles.putIfAbsent(article.id, gram.length to article)
        }

        val anyTerm = QueryBuilder.match(terms, "OR")
        val scored = articles.values.mapNotNull { (length, article) ->
            // The article's passages that best match the whole question.
            val best = ranked(db, anyTerm, 2, article.first, article.first + article.count - 1)
            if (best.isEmpty()) null else Named(length, best[0].score, corpus, best.map { it.passageId })
        }
        return scored.sortedWith(compareBy<Named> { -it.nameLength }.thenBy { it.score }).take(NAME_ARTICLES)
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
