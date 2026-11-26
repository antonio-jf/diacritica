package app.diacritica

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * "Ask Diacritica" in the text-selection menu of other apps: opens Diacritica's Ask chat with the selected
 * text quoted, ready for a question. Has no screen of its own.
 */
class AskActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = if (intent.action == Intent.ACTION_PROCESS_TEXT) intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() else null
        if (!text.isNullOrBlank()) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(ACTION_ASK)
                    .putExtra(Intent.EXTRA_TEXT, text.trim())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }

    companion object {
        const val ACTION_ASK = "app.diacritica.action.ASK"
    }
}
