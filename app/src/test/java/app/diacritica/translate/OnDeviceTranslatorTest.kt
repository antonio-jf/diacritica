package app.diacritica.translate

import app.diacritica.data.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnDeviceTranslatorTest {
    @Test fun languageTagsMapToTheAppsLanguages() {
        assertEquals(Lang.FR, OnDeviceTranslator.fromTag("fr"))
        assertEquals(Lang.ZH, OnDeviceTranslator.fromTag("zh-Latn"))
        assertEquals(Lang.ES, OnDeviceTranslator.fromTag("ES"))
        assertNull(OnDeviceTranslator.fromTag("und"))
        assertNull(OnDeviceTranslator.fromTag("pt"))
    }

    @Test fun everyLanguageHasAnMlKitCode() {
        Lang.entries.forEach { assertEquals(it, OnDeviceTranslator.fromTag(OnDeviceTranslator.code(it))) }
    }
}
