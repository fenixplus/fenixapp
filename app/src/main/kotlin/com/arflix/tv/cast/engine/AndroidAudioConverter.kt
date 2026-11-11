package com.arflix.tv.cast.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioBridge
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The phone's half of [CastRoute.ConvertAudio]: a soundtrack the TV cannot play (DTS, TrueHD,
 * MPEG audio layer 2, ALAC, PCM in Matroska) decoded to PCM with NextLib's FFmpeg build,
 * the same decoders the phone plays such tracks with, folded down to stereo, and encoded to AAC
 * with the phone's own MediaCodec encoder. The picture is never touched.
 *
 * Runs on the cast server's thread for the stream: every call blocks until its frames are out.
 */
@UnstableApi
class AndroidAudioConverter : AudioConverter {

    private var decoder: SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, *>? = null
    private var encoder: MediaCodec? = null
    private var pcm16Raw = false
    private var sourceRate = 48000
    private var outputRate = 48000
    private val info = MediaCodec.BufferInfo()

    override fun start(track: AudioTrack): AacOutput {
        sourceRate = track.sampleRate.takeIf { it > 0 } ?: 48000
        channels = track.channels.coerceAtLeast(1)
        // The encoder's rates top out at 48 kHz; a 96 kHz master is halved.
        outputRate = if (sourceRate > 48000) sourceRate / 2 else sourceRate
        if (track.codec == Codecs.PCM && (track.bitDepth == 16 || track.bitDepth == 0)) {
            pcm16Raw = true
        } else {
            val mime = mimeFor(track.codec) ?: throw IOException("No decoder for ${track.codec}")
            val format = Format.Builder()
                .setSampleMimeType(mime)
                .setChannelCount(track.channels.coerceAtLeast(1))
                .setSampleRate(sourceRate)
                .apply { track.codecPrivate?.let { setInitializationData(listOf(it)) } }
                .build()
            decoder = FfmpegAudioBridge.decoder(format)
        }
        val aac = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outputRate, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(aac, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        return AacOutput(outputRate, CHANNELS, aacLcConfig(outputRate, CHANNELS))
    }

    private var channels = 0

    override fun convert(frame: ByteArray, ptsUs: Long): List<ConvertedAudio> {
        val out = ArrayList<ConvertedAudio>()
        if (pcm16Raw) {
            // Matroska's A_PCM/INT/LIT: little endian 16 bit, the channels interleaved already.
            encode(frame, ptsUs, channels, out)
            return out
        }
        val d = decoder ?: return out
        // Wait for a free input buffer, draining output meanwhile. Asked for once more after one is
        // held, SimpleDecoder throws (one input buffer out at a time), so the wait stops at the first.
        var input: DecoderInputBuffer? = null
        var tries = 0
        while (input == null && tries++ < WAIT_TRIES) {
            input = d.dequeueInputBuffer()
            if (input == null) {
                drainDecoder(d, out)
                Thread.sleep(2)
            }
        }
        val buffer = input ?: throw IOException("Audio decoder stalled")
        buffer.ensureSpaceForWrite(frame.size)
        buffer.data!!.put(frame)
        // Flipped as a renderer does: the decoder reads up to the limit, and unflipped that is the
        // whole buffer, the frame and the garbage after it ("Error in avcodec_send_packet").
        buffer.flip()
        buffer.timeUs = ptsUs
        d.queueInputBuffer(buffer)
        // Give the decoder's thread a moment, then take what it has.
        repeat(DRAIN_TRIES) {
            if (drainDecoder(d, out)) return out
            Thread.sleep(1)
        }
        return out
    }

    /** True when it took at least one decoded buffer. */
    private fun drainDecoder(d: SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, *>, out: MutableList<ConvertedAudio>): Boolean {
        var took = false
        while (true) {
            val decoded = d.dequeueOutputBuffer() ?: break
            took = true
            try {
                if (decoded.isEndOfStream) break
                val data = decoded.data ?: continue
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                channels = FfmpegAudioBridge.channelCount(d).coerceAtLeast(1)
                encode(bytes, decoded.timeUs, channels, out)
            } finally {
                decoded.release()
            }
        }
        return took
    }

    /** Folds [pcm] (16 bit, [sourceChannels] interleaved) to stereo at [outputRate] and encodes it. */
    private fun encode(pcm: ByteArray, ptsUs: Long, sourceChannels: Int, out: MutableList<ConvertedAudio>) {
        val stereo = downmix(pcm, sourceChannels, halve = sourceRate > 48000)
        val e = encoder ?: return
        var offset = 0
        var pts = ptsUs
        while (offset < stereo.size) {
            val index = e.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drainEncoder(e, out)
                continue
            }
            val buffer = e.getInputBuffer(index) ?: continue
            buffer.clear()
            val n = minOf(buffer.remaining(), stereo.size - offset)
            buffer.put(stereo, offset, n)
            e.queueInputBuffer(index, 0, n, pts, 0)
            pts += n / (2L * CHANNELS) * 1_000_000 / outputRate
            offset += n
            drainEncoder(e, out)
        }
    }

    private fun drainEncoder(e: MediaCodec, out: MutableList<ConvertedAudio>, endOfStream: Boolean = false) {
        while (true) {
            val index = e.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US * 10 else 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
                return
            }
            if (index < 0) continue
            val buffer = e.getOutputBuffer(index)
            if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buffer.get(bytes)
                out += ConvertedAudio(bytes, info.presentationTimeUs)
            }
            val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            e.releaseOutputBuffer(index, false)
            if (last) return
        }
    }

    override fun finish(): List<ConvertedAudio> {
        val out = ArrayList<ConvertedAudio>()
        val e = encoder ?: return out
        runCatching {
            val index = e.dequeueInputBuffer(TIMEOUT_US * 10)
            if (index >= 0) e.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainEncoder(e, out, endOfStream = true)
        }
        return out
    }

    override fun close() {
        runCatching { decoder?.release() }
        decoder = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
    }

    companion object {
        private const val TAG = "CastAudio"
        private const val CHANNELS = 2
        private const val BITRATE = 192_000
        private const val TIMEOUT_US = 10_000L
        private const val WAIT_TRIES = 500
        private const val DRAIN_TRIES = 20

        /** What this phone can turn into AAC: everything NextLib's FFmpeg decodes, and 16 bit PCM. */
        fun available(): Boolean = FfmpegAudioBridge.decodes(MimeTypes.AUDIO_DTS)

        fun mimeFor(codec: String): String? = when (codec) {
            Codecs.DTS -> MimeTypes.AUDIO_DTS
            Codecs.TRUEHD -> MimeTypes.AUDIO_TRUEHD
            Codecs.AC3 -> MimeTypes.AUDIO_AC3
            Codecs.EAC3 -> MimeTypes.AUDIO_E_AC3
            Codecs.MP2 -> MimeTypes.AUDIO_MPEG_L2
            Codecs.ALAC -> MimeTypes.AUDIO_ALAC
            Codecs.FLAC -> MimeTypes.AUDIO_FLAC
            Codecs.MP3 -> MimeTypes.AUDIO_MPEG
            Codecs.OPUS -> MimeTypes.AUDIO_OPUS
            else -> null
        }?.takeIf { FfmpegAudioBridge.decodes(it) }.also { if (it == null) Log.i(TAG, "No FFmpeg decoder for $codec") }

        /**
         * 16 bit interleaved PCM of [channels] as stereo. FFmpeg's order: front left, front right,
         * centre, LFE, then the back or side pairs; the centre and the surrounds go in at -3 dB and
         * the LFE is left out, as a stereo fold-down usually has it. With [halve], every other
         * frame pair is averaged, for a 96 kHz source.
         */
        fun downmix(pcm: ByteArray, channels: Int, halve: Boolean = false): ByteArray {
            val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val frames = src.remaining() / channels.coerceAtLeast(1)
            val outFrames = if (halve) frames / 2 else frames
            val out = ByteBuffer.allocate(outFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
            fun sample(frame: Int, ch: Int): Int = if (ch < channels) src.get(frame * channels + ch).toInt() else 0
            fun fold(frame: Int): Pair<Int, Int> {
                if (channels == 1) return sample(frame, 0).let { it to it }
                if (channels == 2) return sample(frame, 0) to sample(frame, 1)
                val fl = sample(frame, 0)
                val fr = sample(frame, 1)
                val c = if (channels >= 3) sample(frame, 2) else 0
                var sl = 0
                var sr = 0
                if (channels >= 6) {
                    sl = sample(frame, 4)
                    sr = sample(frame, 5)
                }
                if (channels >= 8) {
                    sl = (sl + sample(frame, 6)) / 2
                    sr = (sr + sample(frame, 7)) / 2
                }
                val l = 0.5 * fl + 0.354 * c + 0.354 * sl
                val r = 0.5 * fr + 0.354 * c + 0.354 * sr
                return clip(l * 1.4) to clip(r * 1.4)
            }
            for (i in 0 until outFrames) {
                val (l, r) = if (halve) {
                    val a = fold(i * 2)
                    val b = fold(i * 2 + 1)
                    ((a.first + b.first) / 2) to ((a.second + b.second) / 2)
                } else {
                    fold(i)
                }
                out.putShort(l.toShort())
                out.putShort(r.toShort())
            }
            return out.array()
        }

        private fun clip(v: Double): Int = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }
}

