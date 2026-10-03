package app.offlineresearch.rag

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.Deflater

/** [SqlDatabase] over JDBC, so the retriever's SQL runs in plain JVM tests. */
class JdbcSqlDatabase(file: File) : SqlDatabase {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    override fun <T> query(sql: String, args: List<String>, map: (SqlRow) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, arg -> statement.setString(index + 1, arg) }
            statement.executeQuery().use { results ->
                val row = object : SqlRow {
                    override fun long(column: Int) = results.getLong(column + 1)
                    override fun double(column: Int) = results.getDouble(column + 1)
                    override fun string(column: Int): String = results.getString(column + 1)
                    override fun blob(column: Int): ByteArray = results.getBytes(column + 1)
                }
                val out = mutableListOf<T>()
                while (results.next()) out += map(row)
                out
            }
        }

    override fun close() = connection.close()
}

/** An article for a test index: its passages are indexed exactly as build_index.py does it. */
data class FixtureArticle(
    val title: String,
    val passages: List<String>,
    val aliases: List<String> = emptyList(),
    val popularity: Double = 1e-6,
)

object IndexFixture {
    // The schema of data-pipeline/build_index.py (INDEX_SCHEMA), which the retriever's SQL depends on.
    private val SCHEMA = listOf(
        "CREATE TABLE articles(id INTEGER PRIMARY KEY, page_id INTEGER NOT NULL, title TEXT NOT NULL, " +
            "url TEXT NOT NULL, popularity REAL NOT NULL, first_passage_id INTEGER NOT NULL, passage_count INTEGER NOT NULL)",
        "CREATE TABLE passages(id INTEGER PRIMARY KEY, article_id INTEGER NOT NULL, seq INTEGER NOT NULL, body BLOB NOT NULL)",
        "CREATE VIRTUAL TABLE passages_fts USING fts5(title, aliases, body, content='', tokenize='porter unicode61')",
        "CREATE TABLE names(key TEXT NOT NULL, article_id INTEGER NOT NULL, is_title INTEGER NOT NULL)",
        "CREATE INDEX names_key ON names(key)",
    )

    fun deflate(text: String): ByteArray {
        val deflater = Deflater(9)
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    /** Writes a corpus index to [file] and opens it. */
    fun build(
        file: File,
        articles: List<FixtureArticle>,
        urlBase: String = "https://example.org/wiki/",
        meta: Map<String, String> = emptyMap(),
    ): SqlDatabase {
        file.delete()
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s -> SCHEMA.forEach(s::execute) }
            c.createStatement().use { s -> s.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)") }
            meta.forEach { (key, value) ->
                c.prepareStatement("INSERT INTO meta VALUES (?,?)").use { s ->
                    s.setString(1, key)
                    s.setString(2, value)
                    s.execute()
                }
            }
            var passageId = 0L
            articles.forEachIndexed { index, article ->
                val articleId = index + 1L
                c.prepareStatement("INSERT INTO articles VALUES (?,?,?,?,?,?,?)").use { s ->
                    s.setLong(1, articleId)
                    s.setLong(2, 1000 + articleId)
                    s.setString(3, article.title)
                    s.setString(4, urlBase + article.title.replace(' ', '_'))
                    s.setDouble(5, article.popularity)
                    s.setLong(6, passageId + 1)
                    s.setLong(7, article.passages.size.toLong())
                    s.execute()
                }
                article.passages.forEachIndexed { seq, text ->
                    passageId++
                    c.prepareStatement("INSERT INTO passages VALUES (?,?,?,?)").use { s ->
                        s.setLong(1, passageId)
                        s.setLong(2, articleId)
                        s.setInt(3, seq)
                        s.setBytes(4, deflate("${article.title}: $text"))
                        s.execute()
                    }
                    c.prepareStatement("INSERT INTO passages_fts(rowid, title, aliases, body) VALUES (?,?,?,?)").use { s ->
                        s.setLong(1, passageId)
                        s.setString(2, article.title)
                        s.setString(3, if (seq == 0) article.aliases.joinToString(" | ") else "")
                        s.setString(4, indexText(text))
                        s.execute()
                    }
                }
                val names = linkedMapOf(tokenize(article.title).joinToString(" ") to 1)
                article.aliases.forEach { names.putIfAbsent(tokenize(it).joinToString(" "), 0) }
                names.forEach { (key, isTitle) ->
                    c.prepareStatement("INSERT INTO names VALUES (?,?,?)").use { s ->
                        s.setString(1, key)
                        s.setLong(2, articleId)
                        s.setInt(3, isTitle)
                        s.execute()
                    }
                }
            }
        }
        return JdbcSqlDatabase(file)
    }
}
