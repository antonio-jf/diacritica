package app.diacritica.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class UpdaterTest {
    @Test fun versions() {
        assertEquals(27, Updater.versionOf("v0.3.27"))
        assertNull(Updater.versionOf("data-packs"))
        assertNull(Updater.versionOf("v0.3"))
    }

    @Test fun parsesReleasesSkippingDataPacks() {
        val arr = Json.parseToJsonElement(
            """[
              {"tag_name": "data-packs", "draft": false, "html_url": "x", "assets": [{"name": "packs.json", "size": 10, "browser_download_url": "u0"}]},
              {"tag_name": "v0.3.30", "draft": false, "html_url": "https://g/r/30", "assets": [
                 {"name": "diacritica-0.3.30.apk", "size": 41000000, "browser_download_url": "https://g/a.apk"},
                 {"name": "diacritica-0.3.30.apk.sha256", "size": 88, "browser_download_url": "https://g/a.sha"}]},
              {"tag_name": "v0.3.24", "draft": false, "html_url": "h", "assets": [{"name": "diacritica-0.3.24.apk", "size": 1, "browser_download_url": "b"}]},
              {"tag_name": "v0.3.31", "draft": true, "html_url": "h", "assets": [{"name": "x.apk", "size": 1, "browser_download_url": "c"}]}
            ]""",
        ) as JsonArray
        val rs = Updater.parseReleases(arr)
        assertEquals(listOf(30, 24), rs.map { it.version })
        val r = rs.first()
        assertEquals("https://g/a.apk", r.apkUrl)
        assertEquals("https://g/a.sha", r.shaUrl)
        assertEquals(41000000L, r.bytes)
        assertNull(rs[1].shaUrl)
    }

    @Test fun sha256() {
        val f = File.createTempFile("upd", ".bin").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Updater.sha256(f))
        f.delete()
    }
}
