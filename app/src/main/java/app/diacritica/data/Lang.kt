package app.diacritica.data

import java.util.Locale

enum class Lang(
    val code: String,
    val short: String,
    val englishName: String,
    val nativeName: String,
    val locale: Locale,
) {
    EN("en", "EN", "English", "English", Locale.US),
    FR("fr", "FR", "French", "Français", Locale.FRANCE),
    DE("de", "DE", "German", "Deutsch", Locale.GERMANY),
    IT("it", "IT", "Italian", "Italiano", Locale.ITALY),
    ES("es", "ES", "Spanish", "Español", Locale("es", "ES")),
    ZH("zh", "中文", "Mandarin", "中文", Locale.SIMPLIFIED_CHINESE);

    companion object {
        val defaultOrder = listOf(EN, FR, DE, IT, ES, ZH)
        fun fromCode(code: String): Lang? = entries.firstOrNull { it.code == code }
    }
}

enum class ScriptPreference(val label: String) {
    SIMPLIFIED_FIRST("Simplified first"),
    TRADITIONAL_FIRST("Traditional first"),
    SIMPLIFIED_ONLY("Simplified only"),
    TRADITIONAL_ONLY("Traditional only"),
}
