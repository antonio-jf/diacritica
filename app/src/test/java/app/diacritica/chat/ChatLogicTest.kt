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
}
