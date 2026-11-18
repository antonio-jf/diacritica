package app.diacritica.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import app.diacritica.data.Lang

/**
 * Tells the keyboard which language a field is for, so Samsung Keyboard / Gboard switch to the French,
 * German or Chinese layout (and its autocorrect dictionary) on their own when the field gets focus.
 * The keyboard needs that language installed; otherwise it keeps the current one.
 */
fun Lang.keyboardLocales(): LocaleList = LocaleList(
    Locale(
        when (this) {
            Lang.ZH -> "zh-CN"
            else -> code
        },
    ),
)

/** [base] with the keyboard language set to [lang] (unchanged when null, e.g. "Detect"). */
fun keyboardFor(lang: Lang?, base: KeyboardOptions = KeyboardOptions.Default): KeyboardOptions =
    if (lang == null) base else base.copy(hintLocales = lang.keyboardLocales())
