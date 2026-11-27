package app.diacritica.update

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/**
 * Applies a "DIAPAT01" update patch (made by pipeline/make_patch.py: bsdiff with zlib) to the installed
 * APK, rebuilding the new release APK byte for byte. Streams everything, so memory stays small even
 * for a 60 MB APK.
 */
object Bspatch {
    private val MAGIC = "DIAPAT01".toByteArray()

    class Header(val oldSize: Long, val newSize: Long, val oldSha: String, val newSha: String, val ctrlLen: Long, val diffLen: Long) {
        val dataStart get() = 104L
    }

    /** The patch is for a different installed APK (another version, or a build from Android Studio). */
    class WrongBase(message: String) : Exception(message)

    fun header(patch: File): Header = DataInputStream(FileInputStream(patch).buffered()).use { d ->
        val magic = ByteArray(8).also { d.readFully(it) }
        if (!magic.contentEquals(MAGIC)) error("Not an update patch")
        val oldSize = d.readLong(); val newSize = d.readLong()
        val oldSha = ByteArray(32).also { d.readFully(it) }.hex()
        val newSha = ByteArray(32).also { d.readFully(it) }.hex()
        Header(oldSize, newSize, oldSha, newSha, d.readLong(), d.readLong())
    }

    /**
     * Writes the new APK to [out]. Throws [WrongBase] when [old] isn't the APK the patch was made from,
     * and an error when the result doesn't match the patch's checksum (then [out] is deleted).
     */
    fun apply(old: File, patch: File, out: File, oldSha: String? = null) {
        val h = header(patch)
        if (old.length() != h.oldSize) throw WrongBase("Installed app differs in size")
        if ((oldSha ?: sha256(old)) != h.oldSha) throw WrongBase("Installed app differs")
        val ctrl = section(patch, h.dataStart, h.ctrlLen)
        val diff = section(patch, h.dataStart + h.ctrlLen, h.diffLen)
        val extra = section(patch, h.dataStart + h.ctrlLen + h.diffLen, Long.MAX_VALUE)
        val md = MessageDigest.getInstance("SHA-256")
        try {
            RandomAccessFile(old, "r").use { src ->
                BufferedOutputStream(out.outputStream(), 1 shl 16).use { dst ->
                    val buf = ByteArray(1 shl 16)
                    val obuf = ByteArray(1 shl 16)
                    var oldPos = 0L
                    var newPos = 0L
                    while (newPos < h.newSize) {
                        val x = ctrl.readLong(); val y = ctrl.readLong(); val z = ctrl.readLong()
                        if (x < 0 || y < 0 || newPos + x + y > h.newSize) error("Damaged patch")
                        // x bytes: diff + old.
                        var left = x
                        while (left > 0) {
                            val n = minOf(left, buf.size.toLong()).toInt()
                            diff.readFully(buf, 0, n)
                            readOld(src, h.oldSize, oldPos, obuf, n)
                            for (i in 0 until n) buf[i] = (buf[i] + obuf[i]).toByte()
                            dst.write(buf, 0, n); md.update(buf, 0, n)
                            oldPos += n; newPos += n; left -= n
                        }
                        // y bytes: new data as is.
                        left = y
                        while (left > 0) {
                            val n = minOf(left, buf.size.toLong()).toInt()
                            extra.readFully(buf, 0, n)
                            dst.write(buf, 0, n); md.update(buf, 0, n)
                            newPos += n; left -= n
                        }
                        oldPos += z
                    }
                }
            }
            if (md.digest().hex() != h.newSha) error("The rebuilt update doesn't match")
        } catch (e: EOFException) {
            out.delete(); error("Damaged patch")
        } catch (e: Throwable) {
            out.delete(); throw e
        } finally {
            ctrl.close(); diff.close(); extra.close()
        }
    }

    /** Old bytes [pos, pos+n) into [into]; bytes outside the old file count as 0 (as in bsdiff). */
    private fun readOld(src: RandomAccessFile, size: Long, pos: Long, into: ByteArray, n: Int) {
        java.util.Arrays.fill(into, 0, n, 0)
        val lo = maxOf(pos, 0L); val hi = minOf(pos + n, size)
        if (lo >= hi) return
        src.seek(lo)
        src.readFully(into, (lo - pos).toInt(), (hi - lo).toInt())
    }

    private fun section(patch: File, offset: Long, @Suppress("UNUSED_PARAMETER") len: Long): DataInputStream {
        val f = FileInputStream(patch)
        f.channel.position(offset)
        // zlib streams end by themselves, so no length bound is needed.
        return DataInputStream(BufferedInputStream(InflaterInputStream(BufferedInputStream(f, 1 shl 16)), 1 shl 16))
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
