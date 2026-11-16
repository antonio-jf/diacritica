package app.diacritica.undo

import app.diacritica.AppContainer
import app.diacritica.data.user.HistoryItem

/*
 * Deletions that can be undone for a few seconds. Each one keeps what it removes in memory and hands
 * UndoManager a way to put it back exactly (same ids, so review history and progress reconnect).
 */

/** Unsaves a word and deletes its Review cards; Undo restores both, scheduling and history included. */
suspend fun AppContainer.removeSavedWord(lang: String, word: String) {
    val saved = userDb.saved().get(lang, word)
    val cards = userDb.cards().wordCards(lang, word)
    userDb.saved().remove(lang, word)
    userDb.cards().deleteWordCards(lang, word)
    undo.offer("Removed “$word” from saved words") {
        saved?.let { userDb.saved().save(it) }
        if (cards.isNotEmpty()) userDb.cards().restore(cards)
    }
}

suspend fun AppContainer.deleteHistoryItem(item: HistoryItem) {
    userDb.history().deleteById(item.id)
    undo.offer("Removed “${item.query}” from history") { userDb.history().restore(listOf(item)) }
}

suspend fun AppContainer.clearHistory() {
    val all = userDb.history().allNow()
    if (all.isEmpty()) return
    userDb.history().clear()
    undo.offer("Search history cleared (${all.size})") { userDb.history().restore(all) }
}

/** Removes a story from the shelf; Undo restores it with its sentences and reading progress. */
suspend fun AppContainer.deleteStory(id: Long) {
    val dao = userDb.stories()
    val story = dao.byId(id) ?: return
    val sentences = dao.sentences(id)
    stories.delete(id)
    undo.offer("Story removed") {
        dao.insert(story)
        dao.insertSentences(sentences)
    }
}

suspend fun AppContainer.deleteRehearseRound(id: Long) {
    val dao = userDb.rehearseRounds()
    val round = dao.byId(id) ?: return
    dao.delete(id)
    undo.offer("Round removed from the archive") { dao.insert(round) }
}

suspend fun AppContainer.deleteTestAttempt(id: Long) {
    val attempt = tests.get(id) ?: return
    tests.delete(id)
    undo.offer("“${attempt.title}” deleted") { tests.save(attempt) }
}
