package app.diacritica.chat

import app.diacritica.data.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatLogicTest {
    private fun user(id: Long, text: String, quote: Quote? = null) = ChatMessage(id, fromUser = true, text = text, quote = quote)
    private fun model(id: Long, text: String) = ChatMessage(id, fromUser = false, text = text)

    @Test fun quoteComesBeforeTheQuestion() {
        val t = ChatLogic.userText(user(1, "Why is it dative?", Quote("Ich helfe dem Mann.", "Rehearse feedback")))
        assertTrue(t.startsWith("Quoted text (Rehearse feedback):"))
        assertTrue(t.contains("Ich helfe dem Mann."))
        assertTrue(t.trimEnd().endsWith("Why is it dative?"))
    }

    @Test fun quoteAloneAsksForAnExplanation() {
        val t = ChatLogic.userText(user(1, "", Quote("我吃了饭。")))
        assertTrue(t.startsWith("Quoted text:"))
        assertTrue(t.endsWith(ChatLogic.DEFAULT_QUESTION))
    }

    @Test fun plainQuestionIsSentAsIs() {
        assertEquals("Por o para?", ChatLogic.userText(user(1, "Por o para?")))
    }

    @Test fun historyStartsWithAQuestionAndKeepsRoles() {
        val msgs = (1L..30L).map { if (it % 2 == 1L) user(it, "q$it") else model(it, "a$it") }
        val turns = ChatLogic.turns(msgs)
        assertTrue(turns.size <= ChatLogic.HISTORY)
        assertEquals("user", turns.first().role)
        assertEquals("a30", turns.last().text)
        assertEquals("model", turns.last().role)
    }

    @Test fun encodeAndDecodeRoundTrip() {
        val msgs = listOf(
            user(1, "What does \"doch\" mean here?", Quote("Das ist doch klar!\nNeue Zeile", "From “Der Prozess”")),
            model(2, "**Doch** adds emphasis…"),
            user(3, "Thanks"),
        )
        assertEquals(msgs, ChatLogic.decode(ChatLogic.encode(msgs)))
    }

    @Test fun badStorageGivesAnEmptyChat() {
        assertEquals(emptyList<ChatMessage>(), ChatLogic.decode("not json"))
        assertEquals(emptyList<ChatMessage>(), ChatLogic.decode(null))
    }

    @Test fun systemPromptNamesTheLanguagesBeingLearned() {
        val s = ChatLogic.system(listOf(Lang.EN, Lang.ES, Lang.DE, Lang.ZH))
        assertTrue(s.contains("German, Mandarin"))
        assertFalse(ChatLogic.system(listOf(Lang.EN, Lang.ES)).contains("studying in the app now"))
    }

    @Test fun threadsRoundTripNewestFirstAndCapped() {
        val threads = (1L..12L).map { ChatThread(it, updatedAt = it * 1000, messages = listOf(user(1, "q$it"), model(2, "a$it"))) }
        val back = ChatLogic.decodeThreads(ChatLogic.encodeThreads(threads))
        assertEquals(ChatLogic.MAX_HISTORY, back.size)
        assertEquals(12L, back.first().id)
        assertEquals(threads.last(), back.first())
    }

    @Test fun titleIsTheFirstQuestionOrQuote() {
        assertEquals("Por o para?", ChatLogic.title(listOf(user(1, "  Por o\npara? "), model(2, "…"))))
        assertEquals("“Ich helfe dem Mann.”", ChatLogic.title(listOf(user(1, "", Quote("Ich helfe dem Mann.")))))
        assertTrue(ChatLogic.title(listOf(user(1, "x".repeat(200)))).endsWith("…"))
    }

    @Test fun madeLessonsAndTestsAreKept() {
        val msgs = listOf(
            user(1, "Dative?"),
            ChatMessage(2, fromUser = false, text = "…", made = listOf(
                Made(Made.Kind.LESSON, "Dative after mit", "de|dative", Lang.DE),
                Made(Made.Kind.TEST, "Mit and bei", "gen-1", null),
            )),
        )
        assertEquals(msgs, ChatLogic.decode(ChatLogic.encode(msgs)))
    }

    @Test fun exchangeTakesTheQuestionBeforeTheAnswer() {
        val msgs = listOf(user(1, "old"), model(2, "old answer"), user(3, "Why dem?", Quote("mit dem Auto")), model(4, "Because mit takes the dative."))
        val e = ChatLogic.exchange(msgs, 4)!!
        assertTrue(e.contains("mit dem Auto") && e.contains("Why dem?") && e.contains("takes the dative"))
        assertFalse(e.contains("old answer"))
        assertEquals(null, ChatLogic.exchange(msgs, 3))
    }

    @Test fun planNeedsAKnownLanguage() {
        val langs = listOf(Lang.EN, Lang.DE)
        fun obj(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject
        val p = ChatLogic.parsePlan(obj("""{"lang":"de","lesson":"German dative after mit","practise":"","focus":"nope"}"""), langs)!!
        assertEquals(Lang.DE, p.lang)
        assertEquals("mixed", p.focus)
        assertEquals("German dative after mit", p.practise)
        assertEquals(null, ChatLogic.parsePlan(obj("""{"lang":"fr","lesson":"x","practise":"x","focus":"mixed"}"""), langs))
    }
}
