package com.vault.legendas

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lê a faixa de áudio de um vídeo e entrega PCM mono 16 kHz (float) em blocos, como o Whisper precisa. */
object AudioDecoder {
    const val RATE = 16000

    /**
     * [onChunk] recebe cada bloco e o início dele em ms. [isActive] permite cancelar.
     * Lança exceção se o vídeo não tiver áudio.
     */
    fun decode(
        ctx: Context, uri: Uri, chunkSec: Int,
        isActive: () -> Boolean,
        onInfo: (durationMs: Long) -> Unit,
        onChunk: (FloatArray, Long) -> Unit,
    ) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw IllegalStateException("Este vídeo não tem faixa de áudio")
            ex.selectTrack(track)
            onInfo(if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L)

            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false

            val chunkSamples = RATE * chunkSec
            val buf = FloatArray(chunkSamples)
            var filled = 0
            var emitted = 0L
            var t = 0.0       // posição da próxima amostra de saída, relativa ao início do bloco de entrada atual
            var prev = 0f     // última amostra do bloco anterior (para interpolar na emenda)

            fun flushFull() {
                onChunk(buf, emitted * 1000 / RATE)
                emitted += filled
                filled = 0
            }

            fun process(ob: ByteBuffer) {
                ob.order(ByteOrder.nativeOrder())
                val mono: FloatArray
                if (floatPcm) {
                    val fb = ob.asFloatBuffer()
                    val frames = fb.remaining() / channels
                    mono = FloatArray(frames) {
                        var s = 0f
                        for (k in 0 until channels) s += fb.get()
                        s / channels
                    }
                } else {
                    val sb = ob.asShortBuffer()
                    val frames = sb.remaining() / channels
                    mono = FloatArray(frames) {
                        var s = 0
                        for (k in 0 until channels) s += sb.get().toInt()
                        s / (channels * 32768f)
                    }
                }
                val n = mono.size
                if (n == 0) return
                val ratio = inRate / RATE.toDouble()
                while (t < n - 1) {
                    val i = Math.floor(t).toInt()
                    val fr = (t - i).toFloat()
                    val a = if (i < 0) prev else mono[i]
                    val b = mono[i + 1]
                    buf[filled++] = a + (b - a) * fr
                    if (filled == chunkSamples) flushFull()
                    t += ratio
                }
                t -= n
                prev = mono[n - 1]
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone && isActive()) {
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = c.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                when {
                    oi >= 0 -> {
                        val ob = c.getOutputBuffer(oi)!!
                        if (info.size > 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            process(ob)
                        }
                        c.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = c.outputFormat
                        inRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                }
            }
            if (isActive() && filled > 0) onChunk(buf.copyOf(filled), emitted * 1000 / RATE)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}
