package io.github.pixelblue.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Listens to the phone microphone and turns the sound into [bands] values
 * (0..255, low to high frequencies) about 30 times per second.
 * Nothing is recorded or stored: each block is analysed and dropped.
 */
class MusicAnalyzer(
    private val bands: Int,
    private val onFrame: (ByteArray, Float) -> Unit,
) {
    var gain: () -> Float = { 1f }

    private val rate = 22050
    private val n = 1024
    @Volatile private var running = false
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission") // checked by the UI before starting
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, n * 4))
        } catch (_: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }

        running = true
        thread = Thread({ loop(rec) }, "music").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop(rec: AudioRecord) {
        val pcm = ShortArray(n)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val smooth = FloatArray(bands)
        var peak = 1e-3f
        // Log-spaced band edges between 60 Hz and 9 kHz.
        val edges = IntArray(bands + 1) { i ->
            val f = 60.0 * (9000.0 / 60.0).pow(i.toDouble() / bands)
            (f * n / rate).toInt().coerceIn(1, n / 2 - 1)
        }
        var last = 0L
        try {
            rec.startRecording()
            while (running) {
                var got = 0
                while (got < n && running) {
                    val r = rec.read(pcm, got, n - got)
                    if (r <= 0) break
                    got += r
                }
                if (got < n) continue
                for (i in 0 until n) { re[i] = pcm[i] / 32768.0 * window[i]; im[i] = 0.0 }
                fft(re, im)

                val out = ByteArray(bands)
                var sum = 0f
                for (b in 0 until bands) {
                    val lo = edges[b]
                    val hi = max(lo + 1, edges[b + 1])
                    var e = 0.0
                    for (k in lo until hi) e += re[k] * re[k] + im[k] * im[k]
                    // dB-ish scale, then auto gain against a slowly falling peak.
                    val v = (ln(1.0 + sqrt(e / (hi - lo)) * 400.0)).toFloat() * gain()
                    peak = max(peak * 0.9995f, v)
                    val norm = min(1f, v / max(peak, 0.3f))
                    smooth[b] = if (norm > smooth[b]) norm else smooth[b] * 0.82f + norm * 0.18f
                    out[b] = (smooth[b] * 255).toInt().coerceIn(0, 255).toByte()
                    sum += smooth[b]
                }
                val now = System.currentTimeMillis()
                if (now - last >= 33) {
                    last = now
                    val level = sum / bands
                    main.post { if (running) onFrame(out, level) }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val size = re.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < size) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
