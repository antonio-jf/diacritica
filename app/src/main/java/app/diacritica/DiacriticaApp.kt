package app.diacritica

import app.diacritica.backup.BackupManager
import app.diacritica.translate.OnDeviceTranslator
import app.diacritica.packs.PackManager
import android.app.Application
import app.diacritica.data.DictionaryDatabase
import app.diacritica.data.DictionaryRepository
import app.diacritica.data.FalseFriendIndex
import app.diacritica.tests.TestStore
import app.diacritica.data.online.OnlineLookup
import app.diacritica.rehearse.GeminiClient
import app.diacritica.rehearse.SentenceRepository
import app.diacritica.reader.StoryRepository
import app.diacritica.reader.BooksRepository
import app.diacritica.review.ReviewRepository
import app.diacritica.data.user.UserDatabase
import app.diacritica.settings.SettingsRepository
import app.diacritica.tts.Speaker

/** Manual dependency container; small enough that a DI framework isn't worth it yet. */
class AppContainer(app: Application) {
    val dictionaryDb = DictionaryDatabase(app)
    val dictionary = DictionaryRepository(dictionaryDb)
    val userDb = UserDatabase.create(app)
    val settings = SettingsRepository(app)
    val speaker = Speaker(app)
    val online = OnlineLookup(app, dictionaryDb)
    val review = ReviewRepository(userDb, dictionary)
    val sentences = SentenceRepository(app)
    val corpora = SentenceRepository(app, "corpora/corpora.db")
    val translator = OnDeviceTranslator()
    val books = BooksRepository(app)
    val packs = PackManager(app, dictionaryDb, sentences, corpora, books)
    val gemini = GeminiClient()
    val stories = StoryRepository(userDb, gemini)
    val backup = BackupManager(app, userDb)
    val tests = TestStore(app)
    val falseFriends: FalseFriendIndex by lazy { FalseFriendIndex.get(app) }
}

class DiacriticaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
