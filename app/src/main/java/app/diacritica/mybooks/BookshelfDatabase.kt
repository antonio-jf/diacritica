package app.diacritica.mybooks

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** A book you imported. Its position is a chapter and a paragraph in that chapter. */
@Entity(tableName = "book")
data class MyBook(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    /** Lang code of the book's text. */
    val lang: String,
    val chapters: Int,
    val paragraphs: Int,
    val words: Int,
    /** Cover image file in app storage, if the book has one. */
    val cover: String?,
    val fileName: String?,
    val addedAt: Long = System.currentTimeMillis(),
    val openedAt: Long? = null,
    val chapter: Int = 0,
    val paragraph: Int = 0,
    /** 0–1, by paragraphs read. */
    val progress: Float = 0f,
    /** Furthest paragraph reached, counted across the whole book (only moving past it counts as reading). */
    @androidx.room.ColumnInfo(defaultValue = "0") val furthest: Int = 0,
)

@Entity(tableName = "book_chapter", primaryKeys = ["bookId", "idx"])
data class BookChapter(
    val bookId: Long,
    val idx: Int,
    val title: String,
    /** Paragraphs before this chapter, for overall progress. */
    val start: Int,
    val paragraphs: Int,
)

@Entity(tableName = "book_paragraph", primaryKeys = ["bookId", "chapter", "idx"], indices = [Index("bookId")])
data class BookParagraph(
    val bookId: Long,
    val chapter: Int,
    val idx: Int,
    val text: String,
    val heading: Boolean,
)

/**
 * A passage you selected in a book: saved, translated or broken down. One note per passage; a breakdown is kept so the
 * same passage never costs a second Gemini request. [lessons] are the Grammar lessons you made from it (JSON list).
 */
@Entity(tableName = "book_note", indices = [Index("bookId")])
data class BookNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapter: Int,
    val paragraph: Int,
    val passage: String,
    val translation: String? = null,
    /** [Breakdown] as JSON. */
    val breakdown: String? = null,
    val lessons: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Shown highlighted in the book's text. */
    @androidx.room.ColumnInfo(defaultValue = "0") val highlight: Boolean = false,
)

/** A bookmark: a place in a book, labelled with the start of its paragraph. */
@Entity(tableName = "book_mark", indices = [Index("bookId")])
data class BookMark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapter: Int,
    val paragraph: Int,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Words read in a book on a day (epoch day, local time): for Progress. */
@Entity(tableName = "book_read", primaryKeys = ["bookId", "day"])
data class BookRead(val bookId: Long, val day: Long, val lang: String, val words: Int)

data class LangWords(val lang: String, val words: Int)

data class BookSearchHit(val chapter: Int, val idx: Int, val text: String)

@Dao
interface BookshelfDao {
    @Query("SELECT * FROM book ORDER BY COALESCE(openedAt, addedAt) DESC")
    fun all(): Flow<List<MyBook>>

    @Query("SELECT * FROM book WHERE id = :id")
    suspend fun book(id: Long): MyBook?

    @Query("SELECT * FROM book WHERE title = :title AND lang = :lang LIMIT 1")
    suspend fun sameBook(title: String, lang: String): MyBook?

    @Query("SELECT * FROM book_chapter WHERE bookId = :bookId ORDER BY idx")
    suspend fun chapters(bookId: Long): List<BookChapter>

    @Query("SELECT * FROM book_paragraph WHERE bookId = :bookId AND chapter = :chapter ORDER BY idx")
    suspend fun paragraphs(bookId: Long, chapter: Int): List<BookParagraph>

    @Query(
        "SELECT chapter, idx, text FROM book_paragraph WHERE bookId = :bookId AND text LIKE '%' || :q || '%' " +
            "ORDER BY chapter, idx LIMIT :limit",
    )
    suspend fun search(bookId: Long, q: String, limit: Int = 100): List<BookSearchHit>

    @Query("SELECT * FROM book_note WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun notes(bookId: Long): Flow<List<BookNote>>

    @Query("SELECT * FROM book_note WHERE lessons IS NOT NULL")
    suspend fun notesWithLessons(): List<BookNote>

    @Query("SELECT * FROM book_note WHERE bookId = :bookId AND passage = :passage LIMIT 1")
    suspend fun noteFor(bookId: Long, passage: String): BookNote?

    @Query("SELECT * FROM book_note WHERE id = :id")
    suspend fun note(id: Long): BookNote?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNote(n: BookNote): Long

    @Query("DELETE FROM book_note WHERE id = :id")
    suspend fun deleteNote(id: Long)

    @Query("DELETE FROM book_note WHERE bookId = :id")
    suspend fun deleteNotes(id: Long)

    @Query("SELECT * FROM book WHERE openedAt IS NOT NULL")
    suspend fun openedBooks(): List<MyBook>

    @Query("SELECT * FROM book_paragraph WHERE bookId = :bookId AND chapter = :chapter AND idx = :idx")
    suspend fun paragraph(bookId: Long, chapter: Int, idx: Int): BookParagraph?

    @Query("UPDATE book SET furthest = :furthest WHERE id = :id")
    suspend fun setFurthest(id: Long, furthest: Int)

    @Query("SELECT * FROM book_read WHERE bookId = :bookId AND day = :day")
    suspend fun readOn(bookId: Long, day: Long): BookRead?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRead(r: BookRead)

    @Query("SELECT lang, SUM(words) AS words FROM book_read WHERE day >= :fromDay GROUP BY lang ORDER BY words DESC")
    suspend fun wordsSince(fromDay: Long): List<LangWords>

    @Query("SELECT COUNT(DISTINCT day) FROM book_read WHERE day >= :fromDay")
    suspend fun daysReadSince(fromDay: Long): Int

    @Query("SELECT * FROM book_mark WHERE bookId = :bookId ORDER BY chapter, paragraph")
    fun marks(bookId: Long): Flow<List<BookMark>>

    @Insert
    suspend fun addMark(m: BookMark): Long

    @Query("DELETE FROM book_mark WHERE id = :id")
    suspend fun deleteMark(id: Long)

    @Query("DELETE FROM book_mark WHERE bookId = :bookId AND chapter = :chapter AND paragraph = :paragraph")
    suspend fun deleteMarkAt(bookId: Long, chapter: Int, paragraph: Int)

    @Query("DELETE FROM book_mark WHERE bookId = :id")
    suspend fun deleteMarks(id: Long)

    @Query("DELETE FROM book_read WHERE bookId = :id")
    suspend fun deleteReads(id: Long)

    @Insert
    suspend fun insertBook(b: MyBook): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(c: List<BookChapter>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParagraphs(p: List<BookParagraph>)

    @Query("UPDATE book SET chapter = :chapter, paragraph = :paragraph, progress = :progress, openedAt = :at WHERE id = :id")
    suspend fun setPosition(id: Long, chapter: Int, paragraph: Int, progress: Float, at: Long = System.currentTimeMillis())

    @Query("UPDATE book SET cover = :path WHERE id = :id")
    suspend fun setCover(id: Long, path: String)

    @Query("DELETE FROM book_paragraph WHERE bookId = :id")
    suspend fun deleteParagraphs(id: Long)

    @Query("DELETE FROM book_chapter WHERE bookId = :id")
    suspend fun deleteChapters(id: Long)

    @Query("DELETE FROM book WHERE id = :id")
    suspend fun deleteBook(id: Long)
}

/**
 * Imported books live in their own database, apart from your words and Review cards: a novel is several MB of
 * text, and the daily backup copies the user database. Re-import the EPUB to get a book back on a new phone.
 */
@Database(
    entities = [MyBook::class, BookChapter::class, BookParagraph::class, BookNote::class, BookMark::class, BookRead::class],
    version = 3, exportSchema = false,
)
abstract class BookshelfDatabase : RoomDatabase() {
    abstract fun dao(): BookshelfDao

    companion object {
        fun create(context: Context): BookshelfDatabase =
            Room.databaseBuilder(context, BookshelfDatabase::class.java, "bookshelf.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        /** v2: notes on passages (breakdowns, translations, lessons). Must match [BookNote] exactly. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_note` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, " +
                        "`chapter` INTEGER NOT NULL, `paragraph` INTEGER NOT NULL, `passage` TEXT NOT NULL, `translation` TEXT, " +
                        "`breakdown` TEXT, `lessons` TEXT, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_note_bookId` ON `book_note` (`bookId`)")
            }
        }

        /** v3: furthest position, highlights, bookmarks and the reading log. Must match the entities exactly. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `book` ADD COLUMN `furthest` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `book_note` ADD COLUMN `highlight` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_mark` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, " +
                        "`chapter` INTEGER NOT NULL, `paragraph` INTEGER NOT NULL, `label` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_mark_bookId` ON `book_mark` (`bookId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_read` (`bookId` INTEGER NOT NULL, `day` INTEGER NOT NULL, `lang` TEXT NOT NULL, " +
                        "`words` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `day`))",
                )
            }
        }
    }
}
