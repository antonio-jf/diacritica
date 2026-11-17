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
@Database(entities = [MyBook::class, BookChapter::class, BookParagraph::class], version = 1, exportSchema = false)
abstract class BookshelfDatabase : RoomDatabase() {
    abstract fun dao(): BookshelfDao

    companion object {
        fun create(context: Context): BookshelfDatabase =
            Room.databaseBuilder(context, BookshelfDatabase::class.java, "bookshelf.db").build()
    }
}
