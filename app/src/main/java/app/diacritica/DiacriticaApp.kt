package app.diacritica

import app.diacritica.mybooks.Bookshelf
import app.diacritica.chat.ChatSession
import app.diacritica.grammar.LessonLibrary
import app.diacritica.grammar.DailyLessons
import app.diacritica.grammar.DailyNotifier
import app.diacritica.backup.BackupManager
import app.diacritica.backup.AutoBackup
import app.diacritica.translate.OnDeviceTranslator
import app.diacritica.packs.PackManager
import android.app.Application
import app.diacritica.data.DictionaryDatabase
import app.diacritica.data.DictionaryRepository
import app.diacritica.data.FalseFriendIndex
import app.diacritica.data.ZhLexicon
import app.diacritica.tests.TestStore
import app.diacritica.data.online.OnlineLookup
import app.diacritica.rehearse.GeminiClient
import app.diacritica.rehearse.SentenceRepository
import app.diacritica.reader.StoryRepository
import app.diacritica.reader.BooksRepository
import app.diacritica.reader.ReadAloud
import app.diacritica.undo.UndoManager
import app.diacritica.update.Updater
import app.diacritica.review.ReviewRepository
import app.diacritica.data.user.UserDatabase
import app.diacritica.settings.SettingsRepository
import app.diacritica.tts.Speaker

/** Manual dependency container; small enough that a DI framework isn't worth it yet. */
class AppContainer(app: Application) {
    val dictionaryDb = DictionaryDatabase(app)
    /** Bundled Mandarin data: HSK levels, everyday readings, character frequency. */
    val zhLexicon: ZhLexicon by lazy { ZhLexicon.fromAssets(app) }
    val dictionary = DictionaryRepository(dictionaryDb, lazy { zhLexicon })
    val userDb = UserDatabase.create(app)
    val settings = SettingsRepository(app)
    val speaker = Speaker(app)
    val readAloud = ReadAloud(app, speaker)
    val undo = UndoManager()
    val updater = Updater(app)
    val online = OnlineLookup(app, dictionaryDb).also { it.englishForChinese = { q -> dictionary.englishForChinese(q) } }
    val review = ReviewRepository(userDb, dictionary)
    val sentences = SentenceRepository(app)
    val corpora = SentenceRepository(app, "corpora/corpora.db")
    val translator = OnDeviceTranslator()
    val books = BooksRepository(app)
    /** Your own imported EPUBs (the Books section); [books] is the Classics pack. */
    val bookshelf = Bookshelf(app) { text -> translator.detect(text) }
    val packs = PackManager(app, dictionaryDb, sentences, corpora, books)
    val gemini = GeminiClient()
    val stories = StoryRepository(userDb, gemini)
    val backup = BackupManager(app, userDb)
    val autoBackup = AutoBackup(app)
    val tests = TestStore(app)
    val lessons by lazy { LessonLibrary(bookshelf, gemini, settings) { online.isOnline() } }
    /** Lesson of the day (Home card and optional notification). */
    val daily by lazy { DailyLessons(this, app) }
    val falseFriends: FalseFriendIndex by lazy { FalseFriendIndex.get(app) }
    /** Work in progress that should survive Android closing the app in the background (Rehearse answers). */
    val drafts: android.content.SharedPreferences = app.getSharedPreferences("drafts", android.content.Context.MODE_PRIVATE)
    /** The Ask chat (one conversation, kept until you start a new one). */
    val chat by lazy { ChatSession(gemini, settings, drafts, lessons, tests) }
}

class DiacriticaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Re-register the daily backup (no-op if already scheduled or no folder chosen).
        runCatching { container.autoBackup.schedule() }
        runCatching { DailyNotifier.schedule(this) }
    }
}
