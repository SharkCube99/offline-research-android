package app.offlineresearch.rag

import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * [SqlDatabase] over the SQLite bundled with the app (requery sqlite-android),
 * which always has FTS5. The phone's own SQLite may not.
 */
class AndroidSqlDatabase(file: File) : SqlDatabase {
    private val db: SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    override fun <T> query(sql: String, args: List<String>, map: (SqlRow) -> T): List<T> =
        db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            val row = object : SqlRow {
                override fun long(column: Int) = cursor.getLong(column)
                override fun double(column: Int) = cursor.getDouble(column)
                override fun string(column: Int): String = cursor.getString(column)
                override fun blob(column: Int): ByteArray = cursor.getBlob(column)
            }
            val out = ArrayList<T>(cursor.count)
            while (cursor.moveToNext()) out += map(row)
            out
        }

    override fun close() = db.close()
}

/** Finds and opens the corpus index files pushed to the device. */
object IndexFiles {
    const val DIR = "index"

    /** `*.db` files in [dirs], by corpus name; the first directory that has a corpus wins. */
    fun find(dirs: List<File>): Map<String, File> {
        val found = sortedMapOf<String, File>()
        for (dir in dirs) {
            dir.listFiles { file -> file.isFile && file.name.endsWith(".db") && file.canRead() }
                ?.forEach { found.putIfAbsent(it.name.removeSuffix(".db"), it) }
        }
        return found
    }

    fun open(files: Map<String, File>): Map<String, SqlDatabase> =
        files.mapValuesTo(LinkedHashMap()) { AndroidSqlDatabase(it.value) }
}
