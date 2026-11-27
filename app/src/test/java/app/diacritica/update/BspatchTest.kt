package app.diacritica.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** The fixtures are made by pipeline/make_patch.py, so this checks the phone side against the CI side. */
class BspatchTest {
    private fun res(name: String) = File(javaClass.classLoader!!.getResource("patch/$name")!!.toURI())

    @Test fun rebuildsTheNewFileExactly() {
        val out = File.createTempFile("new", ".bin")
        Bspatch.apply(res("old.bin"), res("old-to-new.patch"), out)
        assertArrayEquals(res("new.bin").readBytes(), out.readBytes())
        assertEquals(Bspatch.sha256(res("new.bin")), Bspatch.header(res("old-to-new.patch")).newSha)
        out.delete()
    }

    @Test(expected = Bspatch.WrongBase::class)
    fun refusesAnotherBase() {
        val out = File.createTempFile("new", ".bin")
        try { Bspatch.apply(res("new.bin"), res("old-to-new.patch"), out) } finally { assertFalse(out.length() > 0); out.delete() }
    }
}
