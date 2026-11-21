package app.diacritica.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Records one short spoken attempt as a WAV file (16 kHz, mono, 16-bit) for the Gemini pronunciation check.
 * Stops on its own after about a second of silence once you've started speaking, or after [maxMillis].
 */
object WavRecorder {
    private const val RATE = 16_000

    /** Returns WAV bytes, or null when nothing was said (or the microphone couldn't be opened). */
    @SuppressLint("MissingPermission") // The screen asks for the microphone before recording.
    suspend fun record(maxMillis: Long = 7_000, onLevel: (Float) -> Unit = {}): ByteArray? = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext null
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        }.getOrNull() ?: return@withContext null
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext null }
        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(RATE / 20) // 50 ms
        val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        var spoke = false
        var quietMs = 0L
        var elapsed = 0L
        try {
            rec.startRecording()
            while (coroutineContext.isActive && elapsed < maxMillis) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                var peak = 0
                bytes.clear()
                for (i in 0 until n) { bytes.putShort(buf[i]); peak = maxOf(peak, abs(buf[i].toInt())) }
                pcm.write(bytes.array(), 0, n * 2)
                val level = peak / 32768f
                onLevel(level)
                val ms = n * 1000L / RATE
                elapsed += ms
                if (level > 0.06f) { spoke = true; quietMs = 0 } else if (spoke) quietMs += ms
                if (spoke && quietMs >= 1_100) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (!spoke) null else wav(pcm.toByteArray())
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }
}
