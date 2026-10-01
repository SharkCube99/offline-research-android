package app.offlineresearch.rag

import java.io.Closeable

/**
 * The little of SQLite the retriever needs. The app implements it with the
 * bundled SQLite; unit tests implement it with JDBC, so the same SQL is tested
 * off the device.
 */
interface SqlDatabase : Closeable {
    /** Runs a read-only query. [args] are bound as text. */
    fun <T> query(sql: String, args: List<String> = emptyList(), map: (SqlRow) -> T): List<T>
}

/** One result row; columns are numbered from 0. */
interface SqlRow {
    fun long(column: Int): Long
    fun double(column: Int): Double
    fun string(column: Int): String
    fun blob(column: Int): ByteArray
}
