package app.diacritica.data.user

import android.content.Context
import androidx.room.AutoMigration
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
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** The user's own data (history, saved words, review cards). Separate from the dictionary file. */
@Entity(tableName = "history")
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val lang: String,
    val at: Long = System.currentTimeMillis(),
)

@Entity(tableName = "saved_word", primaryKeys = ["lang", "word"])
data class SavedWord(
    val lang: String,
    val word: String,
    val note: String? = null,
    val deck: String = "Saved",
    val at: Long = System.currentTimeMillis(),
)

/** Kinds of review card. Word cards are generated from saved words; SENTENCE cards come from Rehearse. */
enum class CardKind(val label: String) {
    RECOGNITION("Meaning"),
    RECALL("Recall"),
    GENDER("Article"),
    PINYIN("Pinyin"),
    LISTENING("Listening"),
    SENTENCE("Sentence"),
}

/** A spaced-repetition card with its FSRS memory state. */
@Entity(
    tableName = "card",
    indices = [Index(value = ["lang", "word", "kind"], unique = true), Index(value = ["due"])],
)
data class Card(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lang: String,
    /** The saved word; for SENTENCE cards a unique key. */
    val word: String,
    val kind: String,
    /** Only for SENTENCE cards: prompt and answer. */
    val front: String? = null,
    val back: String? = null,
    val due: Long = System.currentTimeMillis(),
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val reps: Int = 0,
    val lapses: Int = 0,
    /** 0 new, 1 learning, 2 review, 3 relearning */
    val state: Int = 0,
    val lastReview: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "review_log", indices = [Index(value = ["at"])])
data class ReviewLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val at: Long,
    /** 1 Again, 2 Hard, 3 Good, 4 Easy */
    val rating: Int,
    val scheduledDays: Double,
)

/** Reference translations written by the AI when Tatoeba has none (cached, so each is generated once). */
@Entity(tableName = "generated_ref", primaryKeys = ["groupId", "lang"])
data class GeneratedRef(
    val groupId: Long,
    val lang: String,
    val text: String,
    val at: Long = System.currentTimeMillis(),
)

/** One finished Rehearse round: the sentence, your answers and all feedback (JSON list of LangFeedback). */
@Entity(tableName = "rehearse_round", indices = [Index(value = ["at"])])
data class RehearseRound(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long = System.currentTimeMillis(),
    val groupId: Long,
    val promptLang: String,
    val prompt: String,
    val level: String,
    val practiceWord: String? = null,
    val correct: Int,
    val total: Int,
    val feedbackJson: String,
)

/** A parallel story for the Reader. Its sentences are aligned 1:1 across languages. */
@Entity(tableName = "story")
data class Story(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Title per language, as JSON {"en": "...", "fr": "..."}. */
    val titles: String,
    val level: String,
    val topic: String? = null,
    /** "starter", "gemini"; later other sources */
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Reading progress: index of the last highlighted sentence. */
    val lastSentence: Int = 0,
    val sentenceCount: Int,
    val lastOpenedAt: Long? = null,
)

@Entity(tableName = "story_sentence", primaryKeys = ["storyId", "idx", "lang"], indices = [Index(value = ["storyId"])])
data class StorySentence(val storyId: Long, val idx: Int, val lang: String, val text: String)

@Dao
interface StoryDao {
    @Query("SELECT * FROM story ORDER BY COALESCE(lastOpenedAt, createdAt) DESC")
    fun all(): Flow<List<Story>>

    @Query("SELECT COUNT(*) FROM story")
    suspend fun count(): Int

    @Query("SELECT * FROM story WHERE id = :id")
    suspend fun byId(id: Long): Story?

    @Query("SELECT id FROM story WHERE source = :source LIMIT 1")
    suspend fun idBySource(source: String): Long?

    @Query("SELECT * FROM story_sentence WHERE storyId = :id ORDER BY idx")
    suspend fun sentences(id: Long): List<StorySentence>

    @Insert
    suspend fun insert(story: Story): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSentences(s: List<StorySentence>)

    @Query("UPDATE story SET lastSentence = :idx, lastOpenedAt = :at WHERE id = :id")
    suspend fun setProgress(id: Long, idx: Int, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM story_sentence WHERE storyId = :id")
    suspend fun deleteSentences(id: Long)

    @Query("DELETE FROM story WHERE id = :id")
    suspend fun deleteStory(id: Long)
}

@Dao
interface RehearseRoundDao {
    @Query("SELECT * FROM rehearse_round ORDER BY at DESC LIMIT 1000")
    fun all(): Flow<List<RehearseRound>>

    /** Searches the prompt and everything stored with it (answers, corrections, explanations). */
    @Query("SELECT * FROM rehearse_round WHERE prompt LIKE '%' || :q || '%' OR feedbackJson LIKE '%' || :q || '%' ORDER BY at DESC LIMIT 1000")
    fun search(q: String): Flow<List<RehearseRound>>

    @Query("SELECT * FROM rehearse_round WHERE id = :id")
    suspend fun byId(id: Long): RehearseRound?

    @Query("SELECT * FROM rehearse_round WHERE at >= :since ORDER BY at")
    suspend fun since(since: Long): List<RehearseRound>

    /** Rounds with at least one language not right, newest first (Weak spots). */
    @Query("SELECT * FROM rehearse_round WHERE correct < total ORDER BY at DESC LIMIT :limit")
    suspend fun weak(limit: Int = 200): List<RehearseRound>

    @Insert
    suspend fun insert(round: RehearseRound): Long

    @Query("UPDATE rehearse_round SET feedbackJson = :json, correct = :correct WHERE id = :id")
    suspend fun updateFeedback(id: Long, json: String, correct: Int)

    @Query("DELETE FROM rehearse_round WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY at DESC LIMIT 200")
    fun recent(): Flow<List<HistoryItem>>

    @Query("DELETE FROM history WHERE `query` = :query AND lang = :lang")
    suspend fun deleteSame(query: String, lang: String)

    @Query("SELECT * FROM history WHERE at > :since ORDER BY at")
    suspend fun since(since: Long): List<HistoryItem>

    @Query("SELECT * FROM history ORDER BY at DESC LIMIT :limit")
    suspend fun recentNow(limit: Int): List<HistoryItem>

    @Insert
    suspend fun insert(item: HistoryItem)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM history")
    suspend fun allNow(): List<HistoryItem>

    /** Puts back deleted entries (Undo), with their original ids and times. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restore(items: List<HistoryItem>)
}

@Dao
interface SavedWordDao {
    @Query("SELECT * FROM saved_word ORDER BY at DESC")
    fun all(): Flow<List<SavedWord>>

    @Query("SELECT * FROM saved_word")
    suspend fun allNow(): List<SavedWord>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_word WHERE lang = :lang AND word = :word)")
    fun isSaved(lang: String, word: String): Flow<Boolean>

    @Query("SELECT lang || ':' || word FROM saved_word")
    fun keys(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(word: SavedWord)

    @Query("DELETE FROM saved_word WHERE lang = :lang AND word = :word")
    suspend fun remove(lang: String, word: String)

    @Query("SELECT * FROM saved_word WHERE lang = :lang AND word = :word")
    suspend fun get(lang: String, word: String): SavedWord?
}

@Dao
interface CardDao {
    /** Review cards due now, oldest first, then up to [newLimit] new cards. */
    @Query(
        "SELECT * FROM card WHERE (state != 0 AND due <= :now) " +
            "OR id IN (SELECT id FROM card WHERE state = 0 ORDER BY createdAt LIMIT :newLimit) " +
            "ORDER BY state = 0, due LIMIT 200",
    )
    suspend fun dueNow(now: Long, newLimit: Int): List<Card>

    @Query("SELECT COUNT(*) FROM card WHERE state != 0 AND due <= :now")
    fun dueCount(now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM card WHERE state = 0")
    fun newCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM card")
    fun total(): Flow<Int>

    @Query("SELECT MIN(due) FROM card WHERE state != 0")
    fun nextDue(): Flow<Long?>

    @Query("SELECT kind FROM card WHERE lang = :lang AND word = :word")
    suspend fun kindsFor(lang: String, word: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(card: Card): Long

    @Update
    suspend fun update(card: Card)

    @Query("DELETE FROM card WHERE lang = :lang AND word = :word AND kind != 'SENTENCE'")
    suspend fun deleteWordCards(lang: String, word: String)

    @Query("SELECT * FROM card WHERE lang = :lang AND word = :word AND kind != 'SENTENCE'")
    suspend fun wordCards(lang: String, word: String): List<Card>

    /** Puts back deleted cards (Undo) with their ids, so their review history reconnects. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restore(cards: List<Card>)

    @Insert
    suspend fun log(entry: ReviewLog)

    /** Every Review rating since [since], with its card's language (Progress page). */
    @Query("SELECT c.lang AS lang, l.at AS at, l.rating AS rating FROM review_log l JOIN card c ON c.id = l.cardId WHERE l.at >= :since")
    suspend fun reviewEvents(since: Long): List<app.diacritica.progress.ReviewEvent>

    @Query("SELECT COUNT(*) FROM card WHERE stability >= 21")
    suspend fun matureCount(): Int

    @Query("SELECT at FROM review_log WHERE at >= :since ORDER BY at DESC")
    fun reviewTimesSince(since: Long): Flow<List<Long>>

    /** Cards whose first-ever review happened after [since] (new cards introduced today). */
    @Query("SELECT COUNT(*) FROM (SELECT cardId, MIN(at) AS firstAt FROM review_log GROUP BY cardId) WHERE firstAt >= :since")
    suspend fun newIntroducedSince(since: Long): Int
}

@Dao
interface GeneratedRefDao {
    @Query("SELECT * FROM generated_ref WHERE groupId = :groupId")
    suspend fun forGroup(groupId: Long): List<GeneratedRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(ref: GeneratedRef)
}

@Database(
    entities = [
        HistoryItem::class, SavedWord::class, Card::class, ReviewLog::class, GeneratedRef::class, RehearseRound::class,
        Story::class, StorySentence::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun saved(): SavedWordDao
    abstract fun cards(): CardDao
    abstract fun generatedRefs(): GeneratedRefDao
    abstract fun rehearseRounds(): RehearseRoundDao
    abstract fun stories(): StoryDao

    companion object {
        fun create(context: Context): UserDatabase =
            Room.databaseBuilder(context, UserDatabase::class.java, "user.db").build()
    }
}
