package com.syncit.voice

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Android's own Opus codec (MediaCodec, Android 10+). Nothing bundled, nothing native of ours. */
internal object Opus {
    const val DECODED_RATE = 48_000

    /** 20 ms: Opus' usual frame, and what Android's encoder emits per packet. */
    const val FRAME_MS = 20

    val encoderAvailable: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }
}

/** Mono PCM 16-bit in, one Opus packet per 20 ms out. Use from one thread. */
internal class OpusEncoder(val sampleRate: Int, bitrate: Int) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, sampleRate / 1000 * Opus.FRAME_MS * 2 * 4)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Feeds one frame; returns the packets that came out (usually one, sometimes none). */
    fun encode(pcm: ShortArray, length: Int): List<ByteArray> {
        val index = codec.dequeueInputBuffer(10_000)
        if (index >= 0) {
            val buffer = codec.getInputBuffer(index)!!
            buffer.clear()
            buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, length)
            codec.queueInputBuffer(index, 0, length * 2, ptsUs, 0)
            ptsUs += length * 1_000_000L / sampleRate
        }
        val out = ArrayList<ByteArray>(1)
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 0)
            if (o < 0) break
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val buffer = codec.getOutputBuffer(o)!!
                buffer.position(info.offset)
                out += ByteArray(info.size).also { buffer.get(it) }
            }
            codec.releaseOutputBuffer(o, false)
        }
        return out
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

/** One Opus packet in, mono PCM 16-bit at [outputRate] (48 kHz on every phone so far) out. */
internal class OpusDecoder {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L
    private var scratch = ShortArray(Opus.DECODED_RATE / 10)
    var outputRate = Opus.DECODED_RATE
        private set

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, Opus.DECODED_RATE, 1).apply {
            setByteBuffer("csd-0", opusHead())
            setByteBuffer("csd-1", nativeLong(0L)) // codec delay, ns
            setByteBuffer("csd-2", nativeLong(80_000_000L)) // seek pre-roll, ns
        }
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** Decodes [packet]; calls [sink] with whatever PCM is ready (maybe from an earlier packet). */
    fun decode(packet: ByteArray, sink: (ShortArray, Int) -> Unit) {
        val index = codec.dequeueInputBuffer(10_000)
        if (index >= 0) {
            val buffer = codec.getInputBuffer(index)!!
            buffer.clear()
            buffer.put(packet)
            codec.queueInputBuffer(index, 0, packet.size, ptsUs, 0)
            ptsUs += Opus.FRAME_MS * 1_000L
        }
        var wait = 5_000L
        while (true) {
            val o = codec.dequeueOutputBuffer(info, wait)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                continue
            }
            if (o < 0) break
            wait = 0L
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val buffer = codec.getOutputBuffer(o)!!
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                val n = shorts.remaining()
                if (scratch.size < n) scratch = ShortArray(n)
                shorts.get(scratch, 0, n)
                sink(scratch, n)
            }
            codec.releaseOutputBuffer(o, false)
        }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private companion object {
        /** RFC 7845 identification header: mono, no pre-skip, 48 kHz, family 0. */
        fun opusHead(): ByteBuffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(1)
            put(1)
            putShort(0)
            putInt(Opus.DECODED_RATE)
            putShort(0)
            put(0)
            flip()
        }

        fun nativeLong(value: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }
    }
}
