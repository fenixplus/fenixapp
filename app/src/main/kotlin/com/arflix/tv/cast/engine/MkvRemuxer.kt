package com.arflix.tv.cast.engine

import com.arflix.tv.cast.engine.CastLogger as Logger
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream

/** An AAC-LC AudioSpecificConfig for [sampleRate] and [channels], for a converter's output. */
fun aacLcConfig(sampleRate: Int, channels: Int): ByteArray = AudioFrames.aacConfig(null, sampleRate, channels)

/** The AAC a [AudioConverter] makes. */
class AacOutput(val sampleRate: Int, val channels: Int, val config: ByteArray)

/** One AAC frame out of a [AudioConverter], at [ptsUs] on the source's clock. */
class ConvertedAudio(val data: ByteArray, val ptsUs: Long)

/**
 * Turns a soundtrack the receiver cannot play into AAC on this device. Android has one (FFmpeg's
 * decoders through NextLib, MediaCodec's AAC encoder); a platform without one passes none, and
 * the plan then never asks for [CastRoute.ConvertAudio].
 */
interface AudioConverter : Closeable {
    /** Gets ready for [track]; the AAC it will make. Throws when it cannot decode the track. */
    fun start(track: AudioTrack): AacOutput

    /** Feeds one source frame; whatever AAC frames are ready come back. */
    fun convert(frame: ByteArray, ptsUs: Long): List<ConvertedAudio>

    /** The frames still inside, at the end of the stream. */
    fun finish(): List<ConvertedAudio>
}

/**
 * A Matroska file rewrapped as fragmented MP4 while it is sent: the picture's frames as they are,
 * one soundtrack as it is (or through an [AudioConverter]), from any point in the video.
 *
 * A receiver's Chrome plays MP4 and WebM, not Matroska (except on the newest models), and nearly
 * every Matroska file holds codecs MP4 carries perfectly well: H.264, HEVC or AV1 with AAC, AC-3,
 * E-AC-3, MP3, Opus or FLAC. Rewrapping costs no quality and next to no CPU. Fragments follow the
 * picture's keyframes. The output has no index and no length, so a seek is a new request that
 * starts at the cue before [write]'s start; its timestamps begin at zero there, and the sender
 * adds the start back on its clock.
 */
class MkvRemuxer(
    private val source: CastSource,
    private val probe: MediaProbe,
    private val audioNumber: Int?,
    private val converterFactory: (() -> AudioConverter)? = null,
) {

    private val header: MkvHeader = probe.matroska ?: CastCursor(source).use { Matroska.header(it) }

    @Volatile
    private var cues: List<MkvCue>? = null

    private val videoTrack = header.tracks.firstOrNull { it.type == 1 && Codecs.fromMatroska(it.codecId) in CastFormats.MP4_VIDEO }
    private val audioTrack = audioNumber?.let { n -> header.tracks.firstOrNull { it.number == n && it.type == 2 } }

    /** Where in the video a stream that asked for [startMs] will really begin: its cue. */
    fun actualStartMs(startMs: Long): Long {
        if (startMs <= 0) return 0
        val cue = cueFor(startMs * 1_000_000) ?: return 0
        return cue.timeNs / 1_000_000
    }

    /** The total length in milliseconds, as the file's Info says. */
    val durationMs: Long get() = header.durationNs / 1_000_000

    private fun cueFor(startNs: Long): MkvCue? {
        val all = cues ?: CastCursor(source).use { c -> Matroska.cues(c, header, videoTrack?.number) }.also { cues = it }
        return all.lastOrNull { it.timeNs <= startNs }
    }

    /**
     * Writes the stream from [startMs] (the cue at or before it) to [out] until the end of the
     * file, or until [out] fails because the receiver hung up.
     */
    fun write(startMs: Long, out: OutputStream) {
        val startNs = startMs.coerceAtLeast(0) * 1_000_000
        val cue = if (startNs > 0) cueFor(startNs) else null
        val from = cue?.clusterPosition ?: header.firstCluster
        // Without cues a seek reads from the start and drops frames up to the start.
        val dropBeforeNs = if (cue == null) startNs else cue.timeNs
        val wanted = setOfNotNull(videoTrack?.number, audioTrack?.number)
        if (wanted.isEmpty()) throw IOException("Nothing to send")
        val converter = audioTrack?.takeIf { converterFactory != null }?.let { converterFactory!!.invoke() }
        CastCursor(source).use { cursor ->
            try {
                Session(Matroska.Frames(cursor, header, from, wanted), dropBeforeNs, converter, out).run()
            } finally {
                runCatching { converter?.close() }
            }
        }
    }

    private inner class Session(
        private val frames: Matroska.Frames,
        private val dropBeforeNs: Long,
        private val converter: AudioConverter?,
        private val out: OutputStream,
    ) {
        private val videoId = 1
        private val audioId = 2
        private var originNs = Long.MIN_VALUE
        private var sequence = 1
        private var audioScale = 48000L
        private var audioSamplesPerFrame: (ByteArray) -> Int = { 1024 }
        private var lastVideoDts = -1L
        private var lastVideoDuration = 3003L
        private var audioOn = false
        private var videoDelay: Long? = null

        private val videoFrames = ArrayList<MkvFrame>()
        private val audioFrames = ArrayList<Pair<ByteArray, Long>>() // data, pts in ns (rebased)

        fun run() {
            // The first frames: the video's first keyframe at or after the cue, and the first
            // sound, which some boxes need (AC-3's and E-AC-3's are read from it).
            val head = ArrayList<MkvFrame>()
            var firstAudio: MkvFrame? = null
            var keyframe: MkvFrame? = null
            var guard = 0
            while (true) {
                val f = frames.next() ?: break
                if (videoTrack != null && f.track == videoTrack.number) {
                    if (keyframe == null) {
                        if (!f.keyframe || f.timeNs < dropBeforeNs - 1_000_000) continue
                        keyframe = f
                    }
                    head += f
                } else if (audioTrack != null && f.track == audioTrack.number) {
                    if (videoTrack != null && keyframe == null) continue
                    if (f.timeNs < dropBeforeNs - 1_000_000 && videoTrack == null) continue
                    head += f
                    if (firstAudio == null) firstAudio = f
                }
                val ready = (videoTrack == null || keyframe != null) && (audioTrack == null || firstAudio != null)
                if (ready || ++guard > HEAD_FRAME_LIMIT) break
            }
            if (head.isEmpty()) return
            originNs = keyframe?.timeNs ?: firstAudio?.timeNs ?: 0L
            val tracks = ArrayList<Fmp4Track>()
            videoTrack?.let { tracks += Fmp4Track(videoId, true, VIDEO_TIMESCALE, videoEntry(it), it.width, it.height) }
            val audioEntry = audioTrack?.let { audioEntry(it, firstAudio?.data) }
            if (audioEntry != null) tracks += Fmp4Track(audioId, false, audioScale, audioEntry)
            audioOn = audioEntry != null
            val remaining = (header.durationNs - originNs).coerceAtLeast(0) / 1_000_000
            out.write(Fmp4.init(tracks, remaining))
            for (f in head) take(f)
            while (true) {
                val f = frames.next() ?: break
                take(f)
            }
            if (audioOn) converter?.finish()?.forEach { audioFrames += it.data to (it.ptsUs * 1000 - originNs) }
            flush(nextVideoPtsNs = null)
            out.flush()
        }

        private fun take(f: MkvFrame) {
            if (videoTrack != null && f.track == videoTrack.number) {
                if (f.keyframe && videoFrames.isNotEmpty()) flush(nextVideoPtsNs = f.timeNs - originNs)
                videoFrames += f
                if (videoFrames.sumOf { it.data.size } > MAX_FRAGMENT_BYTES) flush(nextVideoPtsNs = null)
            } else if (audioOn && audioTrack != null && f.track == audioTrack.number) {
                val rebased = f.timeNs - originNs
                if (rebased < -AUDIO_LEAD_NS) return
                if (converter != null) {
                    converter.convert(f.data, f.timeNs / 1000).forEach { audioFrames += it.data to (it.ptsUs * 1000 - originNs) }
                } else {
                    audioFrames += f.data to rebased
                }
                // Sound alone is cut every couple of seconds; with a picture the keyframes decide.
                if (videoTrack == null && audioFrames.size > 1 &&
                    audioFrames.last().second - audioFrames.first().second >= AUDIO_FRAGMENT_NS
                ) {
                    flush(nextVideoPtsNs = null)
                }
            }
        }

        private fun flush(nextVideoPtsNs: Long?) {
            val runs = ArrayList<Fmp4Run>()
            if (videoFrames.isNotEmpty()) {
                val pts = videoFrames.map { toVideoTs(it.timeNs - originNs) }
                val sorted = pts.sorted()
                val dts = LongArray(pts.size)
                for (i in sorted.indices) {
                    var d = sorted[i]
                    if (d <= lastVideoDts) d = lastVideoDts + 1
                    dts[i] = d
                    lastVideoDts = d
                }
                // Presentation runs a few frames behind decoding, the reorder depth of the first
                // stretch, so no frame is shown before it is decoded and nothing starts below zero.
                val delay = videoDelay ?: pts.indices.maxOf { dts[it] - pts[it] }.coerceAtLeast(0).also { videoDelay = it }
                val samples = ArrayList<Fmp4Sample>(pts.size)
                for (i in pts.indices) {
                    val duration = when {
                        i + 1 < dts.size -> dts[i + 1] - dts[i]
                        nextVideoPtsNs != null -> toVideoTs(nextVideoPtsNs) - dts[i]
                        videoTrack!!.defaultDurationNs > 0 -> toVideoTs(videoTrack.defaultDurationNs)
                        else -> lastVideoDuration
                    }.coerceAtLeast(1)
                    lastVideoDuration = duration
                    samples += Fmp4Sample(videoFrames[i].data, duration, pts[i] + delay - dts[i], videoFrames[i].keyframe)
                }
                runs += Fmp4Run(videoId, dts[0], samples)
                videoFrames.clear()
            }
            if (audioFrames.isNotEmpty()) {
                val delayNs = (videoDelay ?: 0L) * 1_000_000_000 / VIDEO_TIMESCALE
                val first = (audioFrames.first().second + delayNs).coerceAtLeast(0)
                val base = first * audioScale / 1_000_000_000
                val samples = audioFrames.map { (data, _) -> Fmp4Sample(data, audioSamplesPerFrame(data).toLong(), 0, true) }
                runs += Fmp4Run(audioId, base, samples)
                audioFrames.clear()
            }
            if (runs.isEmpty()) return
            out.write(Fmp4.fragment(sequence++, runs, setOf(videoId)))
        }

        private fun toVideoTs(ns: Long): Long = Math.floorDiv(ns * VIDEO_TIMESCALE, 1_000_000_000L)

        private fun videoEntry(t: MkvTrack): ByteArray = Mp4Entries.video(t)

        private fun audioEntry(t: MkvTrack, first: ByteArray?): ByteArray? {
            val entry = if (converter != null) {
                Mp4Entries.converted(converter.start(Mp4Entries.audioTrack(t)))
            } else {
                Mp4Entries.audio(t, first)
            }
            if (entry == null) {
                Logger.w(TAG, "No MP4 form for ${t.codecId}; sending the picture alone")
                return null
            }
            audioScale = entry.timescale
            audioSamplesPerFrame = entry.samplesPerFrame
            return entry.entry
        }
    }

    companion object {
        private const val TAG = "MkvRemuxer"
        const val VIDEO_TIMESCALE = 90_000L
        private const val HEAD_FRAME_LIMIT = 4000
        private const val MAX_FRAGMENT_BYTES = 24 * 1024 * 1024
        private const val AUDIO_FRAGMENT_NS = 2_000_000_000L

        /** Sound a little before the first keyframe is kept, so the first words are not clipped. */
        private const val AUDIO_LEAD_NS = 0L
    }
}

