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
)

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
@Database(entities = [MyBook::class, BookChapter::class, BookParagraph::class, BookNote::class], version = 2, exportSchema = false)
abstract class BookshelfDatabase : RoomDatabase() {
    abstract fun dao(): BookshelfDao

    companion object {
        fun create(context: Context): BookshelfDatabase =
            Room.databaseBuilder(context, BookshelfDatabase::class.java, "bookshelf.db").addMigrations(MIGRATION_1_2).build()

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
    }
}
