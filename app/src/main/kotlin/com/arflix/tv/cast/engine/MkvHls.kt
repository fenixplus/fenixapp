package com.arflix.tv.cast.engine

import com.arflix.tv.cast.engine.CastLogger as Logger
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.ceil

/** One soundtrack of an HLS cast, and whether this device turns it into AAC first. */
data class HlsAudio(val number: Int, val convert: Boolean) {
    /** Its name in the rendition's file names: "2" as it is, "2c" turned into AAC. */
    val key: String get() = if (convert) "${number}c" else number.toString()
}

/** A subtitle file loaded beside the video, as cues, for an HLS subtitle rendition. */
class HlsSubtitleFile(val id: Long, val name: String, val language: String?, val cues: List<WebVtt.Cue>)

/**
 * One rendition of an HLS cast as the sender knows it: its playlist [uri] (relative to the
 * master), the [name] and [language] written into the master, and what it is in the video, so the
 * receiver's track list can be matched back to the phone's pickers ([HlsTrackMatch]).
 */
data class HlsRendition(
    val kind: Kind,
    val uri: String,
    val name: String,
    val language: String?,
    /** The Matroska track it comes from, or null for a subtitle file. */
    val trackNumber: Int?,
    /** The subtitle file's id, or null. */
    val fileId: Long?,
    val isDefault: Boolean,
    /** A soundtrack turned into AAC on this device, rather than sent as it is. */
    val converted: Boolean = false,
) {
    enum class Kind { Audio, Text }
}

/** One stretch of the video as HLS cuts it: from a cue to the next cut, read from [clusterPosition]. */
internal class HlsSegment(val index: Int, val startNs: Long, val endNs: Long, val clusterPosition: Long, val cueIndex: Int) {
    val durationNs: Long get() = endNs - startNs
    override fun toString() = "HlsSegment($index, ${startNs / 1_000_000}..${endNs / 1_000_000} ms @ $clusterPosition)"
}

internal object HlsSegments {

    /** How long a segment aims to be: a few keyframes, so a seek lands near and a fetch stays small. */
    const val TARGET_NS = 6_000_000_000L

    /**
     * Cuts the video at its cues: each segment starts at a cue (a keyframe, the cluster that holds
     * it known) and runs to the first cue at least [targetNs] later, the last to the end of the
     * video. The first starts at zero, from the first cluster, whatever time the first cue has.
     */
    fun cut(cues: List<MkvCue>, durationNs: Long, firstCluster: Long, targetNs: Long = TARGET_NS): List<HlsSegment> {
        val sorted = cues.sortedBy { it.timeNs }.distinctBy { it.timeNs }
        if (sorted.isEmpty()) return emptyList()
        val starts = ArrayList<Int>()
        starts += 0
        var segStart = 0L
        for (i in 1 until sorted.size) {
            if (sorted[i].timeNs - segStart >= targetNs) {
                starts += i
                segStart = sorted[i].timeNs
            }
        }
        // A last stretch shorter than a third of the target goes into the one before it.
        val lastStart = sorted[starts.last()].timeNs
        if (starts.size > 1 && durationNs > lastStart && durationNs - lastStart < targetNs / 3) starts.removeAt(starts.size - 1)
        val out = ArrayList<HlsSegment>(starts.size)
        for ((n, cueIndex) in starts.withIndex()) {
            val start = if (n == 0) 0L else sorted[cueIndex].timeNs
            val end = if (n + 1 < starts.size) {
                sorted[starts[n + 1]].timeNs
            } else {
                val last = sorted[cueIndex].timeNs
                if (durationNs > last) durationNs else last + targetNs
            }
            val position = if (n == 0) minOf(firstCluster, sorted[0].clusterPosition) else sorted[cueIndex].clusterPosition
            out += HlsSegment(n, start, end, position, cueIndex)
        }
        return out
    }
}

/**
 * A Matroska file served as HLS: a VOD master playlist, the picture in one rendition, every
 * soundtrack the receiver can be given in a rendition of its own (converted to AAC on this device
 * where it has to be), and every text subtitle track, inside the file or loaded beside it, as
 * segmented WebVTT. The Default Media Receiver plays HLS best of everything it takes: the
 * playlist gives it the whole length and every segment's place, so it can seek anywhere itself
 * and switch soundtracks and subtitles on its own, where one endless rewrapped stream left it
 * guessing at the length and stalling.
 *
 * Segments are cut at the cues ([HlsSegments.cut]); building the playlists reads only the head
 * and the Cues. Each segment is made when it is asked for: fragmented MP4 (CMAF) for the picture
 * and each soundtrack, read from the cluster of its cue, with decode times taken from the frames'
 * own presentation times so they run on unbroken from one segment to the next, and composition
 * offsets signed (trun version 1), as CMAF has them. The file's bytes come through [source], the
 * platform's ordinary streaming path, so split pieces and Low storage mode work underneath.
 */
class MkvHls internal constructor(
    private val source: CastSource,
    private val header: MkvHeader,
    private val cues: List<MkvCue>,
    internal val segments: List<HlsSegment>,
    private val audio: List<HlsAudio>,
    private val defaultAudio: Int?,
    private val files: List<HlsSubtitleFile>,
    private val converterFactory: (() -> AudioConverter)?,
    subtitles: Boolean = true,
) {

    private val video: MkvTrack = header.tracks.first { it.type == 1 && Codecs.fromMatroska(it.codecId) in CastFormats.MP4_VIDEO }
    private val audioTracks: List<MkvTrack> = audio.map { it.number }.distinct().mapNotNull { n -> header.tracks.firstOrNull { it.number == n && it.type == 2 } }

    /** The soundtracks as sent, by [HlsAudio.key]: one the file has, as it is or as AAC. */
    private val sent: Map<String, HlsAudio> = audio.filter { a -> audioTracks.any { it.number == a.number } }.associateBy { it.key }

    private fun audioOf(r: HlsRendition): HlsAudio? = r.trackNumber?.let { n -> sent[HlsAudio(n, r.converted).key] }
    private val textTracks: List<MkvTrack> =
        if (subtitles) header.tracks.filter { it.type == 17 && Codecs.fromMatroska(it.codecId) in Codecs.TEXT_SUBTITLES } else emptyList()

    val durationMs: Long get() = segments.lastOrNull()?.endNs?.div(1_000_000) ?: 0L

    /** Every rendition in the master's order: the soundtracks, then the subtitles. */
    val renditions: List<HlsRendition> by lazy { buildRenditions() }

    private fun buildRenditions(): List<HlsRendition> = buildList {
        // Names are unique within a kind: the sender finds a rendition by its name among its kind.
        val used = HashMap<HlsRendition.Kind, HashSet<String>>()
        fun unique(kind: HlsRendition.Kind, label: String): String {
            val names = used.getOrPut(kind) { HashSet() }
            var name = label
            var n = 2
            while (!names.add(name)) name = "$label ${n++}"
            return name
        }
        // A soundtrack whose sample entry cannot be made (no frame to describe, no converter) is left out.
        val usable = sent.values.filter { audioEntry(it) != null }
        val chosen = defaultAudio?.takeIf { d -> usable.any { it.number == d } } ?: usable.firstOrNull()?.number
        for (a in usable) {
            val t = audioTracks.first { it.number == a.number }
            val base = HlsNames.label(t.name, t.language, "Audio ${t.number}")
            // The AAC copy of a Dolby track sent as it is too gets a name of its own.
            val label = if (a.convert && usable.any { it.number == a.number && !it.convert }) "$base (AAC)" else base
            // Each copy of the chosen track is the default of its own group (one group per codec).
            add(HlsRendition(HlsRendition.Kind.Audio, "a${a.key}.m3u8", unique(HlsRendition.Kind.Audio, label), HlsNames.languageTag(t.language), t.number, null, t.number == chosen, a.convert))
        }
        for (t in textTracks) {
            add(HlsRendition(HlsRendition.Kind.Text, "s${t.number}.m3u8", unique(HlsRendition.Kind.Text, HlsNames.label(t.name, t.language, "Subtitles ${t.number}")), HlsNames.languageTag(t.language), t.number, null, false))
        }
        for (f in files) {
            add(HlsRendition(HlsRendition.Kind.Text, "f${f.id}.m3u8", unique(HlsRendition.Kind.Text, f.name.ifBlank { "Subtitles" }), HlsNames.languageTag(f.language), null, f.id, false))
        }
    }

    // ---- playlists ---------------------------------------------------------------------------

    private val targetDuration: Int get() = segments.maxOf { ceil(it.durationNs / 1e9).toInt() }.coerceAtLeast(1)

    /** The master playlist: one variant per audio codec (an MSE source buffer takes one), each naming its group. */
    fun master(): String {
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        val audioByCodec = LinkedHashMap<String, MutableList<Pair<HlsRendition, AudioEntry>>>()
        for (r in renditions.filter { it.kind == HlsRendition.Kind.Audio }) {
            val entry = audioOf(r)?.let(::audioEntry) ?: continue
            audioByCodec.getOrPut(entry.codecs) { ArrayList() } += r to entry
        }
        // The variant with the default soundtrack goes first: a player starts on the first variant.
        val groups = audioByCodec.entries.sortedByDescending { e -> e.value.any { it.first.isDefault } }
        groups.forEachIndexed { g, (_, list) ->
            for ((r, entry) in list) {
                sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud$g\",NAME=\"").append(quoted(r.name)).append('"')
                r.language?.let { sb.append(",LANGUAGE=\"").append(it).append('"') }
                sb.append(",DEFAULT=").append(if (r.isDefault || (list.none { it.first.isDefault } && r === list.first().first)) "YES" else "NO")
                sb.append(",AUTOSELECT=YES")
                sb.append(",CHANNELS=\"").append(entry.channels.coerceAtLeast(1)).append('"')
                sb.append(",URI=\"").append(r.uri).append("\"\n")
            }
        }
        val texts = renditions.filter { it.kind == HlsRendition.Kind.Text }
        for (r in texts) {
            sb.append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"").append(quoted(r.name)).append('"')
            r.language?.let { sb.append(",LANGUAGE=\"").append(it).append('"') }
            sb.append(",DEFAULT=NO,AUTOSELECT=NO,FORCED=NO,URI=\"").append(r.uri).append("\"\n")
        }
        val videoCodecs = Mp4Entries.videoCodecs(video)
        val bandwidth = bandwidth()
        fun variant(audioGroup: String?, audioCodec: String?) {
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth)
            sb.append(",CODECS=\"").append(listOfNotNull(videoCodecs, audioCodec).joinToString(",")).append('"')
            if (video.width > 0 && video.height > 0) sb.append(",RESOLUTION=").append(video.width).append('x').append(video.height)
            audioGroup?.let { sb.append(",AUDIO=\"").append(it).append('"') }
            if (texts.isNotEmpty()) sb.append(",SUBTITLES=\"subs\"")
            sb.append("\nv.m3u8\n")
        }
        if (groups.isEmpty()) {
            variant(null, null)
        } else {
            groups.forEachIndexed { g, (codec, _) -> variant("aud$g", codec) }
        }
        return sb.toString()
    }

    private fun quoted(s: String) = s.replace('"', '\'').replace('\n', ' ')

    /** Roughly the file's rate, a little over: HLS asks for the peak, and no player needs it exact. */
    private fun bandwidth(): Long {
        val seconds = (durationMs / 1000.0).coerceAtLeast(1.0)
        return ((source.length * 8 / seconds) * 1.25).toLong().coerceAtLeast(64_000)
    }

    private fun media(map: String?, segment: (Int) -> String): String {
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n")
        sb.append("#EXT-X-TARGETDURATION:").append(targetDuration).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        if (map != null) sb.append("#EXT-X-MAP:URI=\"").append(map).append("\"\n")
        for (s in segments) {
            sb.append("#EXTINF:").append(String.format(Locale.ROOT, "%.3f", s.durationNs / 1e9)).append(",\n")
            sb.append(segment(s.index)).append('\n')
        }
        sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    fun videoPlaylist(): String = media("v-init.mp4") { "v-$it.m4s" }
    fun audioPlaylist(number: Int): String = audioPlaylist(number.toString())

    /** A soundtrack's playlist by its [HlsAudio.key]: "2" as it is, "2c" as AAC. */
    fun audioPlaylist(key: String): String = media("a$key-init.mp4") { "a$key-$it.m4s" }
    fun textPlaylist(prefix: String): String = media(null) { "$prefix-$it.vtt" }

    /** What the server answers for [path] under this cast's folder, or null for a 404. */
    fun resolve(path: String): CastResource? {
        val name = path.substringAfterLast('/')
        return when {
            name == "master.m3u8" -> CastResource.Text(master(), PLAYLIST_MIME)
            name == "v.m3u8" -> CastResource.Text(videoPlaylist(), PLAYLIST_MIME)
            name == "v-init.mp4" -> CastResource.Text(videoInit(), "video/mp4")
            name.startsWith("v-") && name.endsWith(".m4s") -> segmentIndex(name.removePrefix("v-").removeSuffix(".m4s"))?.let { i ->
                CastResource.Chunked("video/mp4") { out -> writeVideoSegment(i, out) }
            }
            name.startsWith("a") && name.endsWith(".m3u8") -> sent[name.removePrefix("a").removeSuffix(".m3u8")]?.let {
                CastResource.Text(audioPlaylist(it.key), PLAYLIST_MIME)
            }
            name.startsWith("a") && name.endsWith("-init.mp4") -> sent[name.removePrefix("a").removeSuffix("-init.mp4")]?.let { a ->
                audioInit(a)?.let { CastResource.Text(it, "audio/mp4") }
            }
            name.startsWith("a") && name.endsWith(".m4s") -> {
                val parts = name.removePrefix("a").removeSuffix(".m4s").split('-')
                val a = parts.getOrNull(0)?.let { sent[it] }
                val i = parts.getOrNull(1)?.let(::segmentIndex)
                if (a == null || i == null) null else CastResource.Chunked("audio/mp4") { out -> writeAudioSegment(a, i, out) }
            }
            (name.startsWith("s") || name.startsWith("f")) && name.endsWith(".m3u8") -> {
                val prefix = name.removeSuffix(".m3u8")
                if (hasText(prefix)) CastResource.Text(textPlaylist(prefix), PLAYLIST_MIME) else null
            }
            (name.startsWith("s") || name.startsWith("f")) && name.endsWith(".vtt") -> {
                val prefix = name.substringBeforeLast('-')
                val i = segmentIndex(name.substringAfterLast('-').removeSuffix(".vtt"))
                if (!hasText(prefix) || i == null) null else CastResource.Text(textSegment(prefix, i), WebVtt.MIME)
            }
            else -> null
        }
    }

    private fun segmentIndex(s: String): Int? = s.toIntOrNull()?.takeIf { it in segments.indices }
    private fun hasText(prefix: String): Boolean = when (prefix.firstOrNull()) {
        's' -> prefix.drop(1).toIntOrNull()?.let { n -> textTracks.any { it.number == n } } == true
        'f' -> prefix.drop(1).toLongOrNull()?.let { id -> files.any { it.id == id } } == true
        else -> false
    }

    // ---- init segments -----------------------------------------------------------------------

    @Volatile
    private var videoInitBytes: ByteArray? = null
    private val audioEntries = HashMap<String, AudioEntry?>()

    fun videoInit(): ByteArray = videoInitBytes ?: Fmp4.init(
        listOf(Fmp4Track(TRACK_ID, true, VIDEO_TIMESCALE, Mp4Entries.video(video), video.width, video.height)),
        durationMs,
    ).also { videoInitBytes = it }

    /** The init segment of track [number] as it is sent first: as it is where it goes so, else as AAC. */
    fun audioInit(number: Int): ByteArray? =
        (sent[number.toString()] ?: sent["${number}c"])?.let(::audioInit)

    fun audioInit(a: HlsAudio): ByteArray? {
        val entry = audioEntry(a) ?: return null
        return Fmp4.init(listOf(Fmp4Track(TRACK_ID, false, entry.timescale, entry.entry)), durationMs)
    }

    /** A soundtrack's sample entry, from its first frame where the codec needs one (AC-3, E-AC-3). */
    @Synchronized
    private fun audioEntry(a: HlsAudio): AudioEntry? {
        if (audioEntries.containsKey(a.key)) return audioEntries[a.key]
        val number = a.number
        val t = audioTracks.firstOrNull { it.number == number } ?: return null
        val convert = a.convert
        val entry = runCatching {
            if (convert) {
                val factory = converterFactory ?: throw IOException("No audio converter")
                factory().use { c -> Mp4Entries.converted(c.start(Mp4Entries.audioTrack(t))) }
            } else {
                val needsFrame = Codecs.fromMatroska(t.codecId) in setOf(Codecs.AC3, Codecs.EAC3)
                Mp4Entries.audio(t, if (needsFrame) firstFrame(t.number) else null)
            }
        }.onFailure { Logger.w(TAG, "No sample entry for track ${a.key}", it) }.getOrNull()
        audioEntries[a.key] = entry
        return entry
    }

    private fun firstFrame(track: Int): ByteArray? = CastCursor(source, header.firstCluster).use { c ->
        val frames = Matroska.Frames(c, header, header.firstCluster, setOf(track))
        frames.next()?.data
    }

    // ---- the picture -------------------------------------------------------------------------

    /**
     * Segment [index] of the picture: from the first keyframe at or after its start, every frame in
     * decode order up to (not including) the keyframe that starts the next one.
     */
    fun writeVideoSegment(index: Int, out: OutputStream) {
        val seg = segments[index]
        val last = index == segments.size - 1
        val writer = VideoWriter(index, out)
        CastCursor(source, seg.clusterPosition).use { c ->
            val frames = Matroska.Frames(c, header, seg.clusterPosition, setOf(video.number))
            var started = false
            while (true) {
                val f = frames.next() ?: break
                if (!started) {
                    if (!f.keyframe || f.timeNs < seg.startNs - SLACK_NS) {
                        if (f.timeNs > seg.endNs + RUNAWAY_NS && !last) break
                        continue
                    }
                    started = true
                } else if (!last && f.keyframe && f.timeNs >= seg.endNs - SLACK_NS) {
                    break
                } else if (!last && f.timeNs > seg.endNs + RUNAWAY_NS) {
                    break
                }
                writer.add(f)
            }
        }
        writer.finish()
    }

    /**
     * Frames to samples with decode times. A frame's decode time is the next smallest presentation
     * time not yet given out, known once [LOOKAHEAD] frames later have been read (further than any
     * encoder reorders), so a segment never has to be held whole and the times carry on from one
     * segment into the next without a gap.
     */
    private inner class VideoWriter(private val index: Int, private val out: OutputStream) {
        private val pending = ArrayDeque<MkvFrame>()
        private val presentation = PriorityQueue<Long>()
        private var openFrame: MkvFrame? = null
        private var openDts = 0L
        private var lastDts = Long.MIN_VALUE
        private var lastDuration = video.defaultDurationNs.takeIf { it > 0 }?.let(::toTs) ?: 3750L
        private val samples = ArrayList<Fmp4Sample>()
        private var sampleBytes = 0
        private var fragmentDts = 0L
        private var fragment = 0

        fun add(f: MkvFrame) {
            pending += f
            presentation += toTs(f.timeNs)
            if (pending.size > LOOKAHEAD) emit(pending.removeFirst())
        }

        private fun emit(f: MkvFrame) {
            val polled = presentation.poll()
            var dts: Long = polled ?: 0L
            if (lastDts != Long.MIN_VALUE && dts <= lastDts) dts = lastDts + 1
            lastDts = dts
            close(dts)
            openFrame = f
            openDts = dts
        }

        /** The open sample ends where the next begins; it goes into the fragment. */
        private fun close(nextDts: Long?) {
            val f = openFrame ?: return
            val duration = (nextDts?.let { it - openDts } ?: lastDuration).coerceAtLeast(1)
            lastDuration = duration
            if (samples.isEmpty()) fragmentDts = openDts
            samples += Fmp4Sample(f.data, duration, toTs(f.timeNs) - openDts, f.keyframe)
            sampleBytes += f.data.size
            openFrame = null
            if (sampleBytes >= FRAGMENT_BYTES) flush()
        }

        fun finish() {
            while (pending.isNotEmpty()) emit(pending.removeFirst())
            close(null)
            flush()
            out.flush()
        }

        private fun flush() {
            if (samples.isEmpty()) return
            out.write(Fmp4.fragment(index * 1000 + ++fragment, listOf(Fmp4Run(TRACK_ID, fragmentDts, samples.toList())), setOf(TRACK_ID)))
            samples.clear()
            sampleBytes = 0
        }
    }

    // ---- the sound ---------------------------------------------------------------------------

    /** Segment [index] of soundtrack [number]: every frame that starts within the segment. */
    fun writeAudioSegment(number: Int, index: Int, out: OutputStream) {
        val a = sent[number.toString()] ?: sent["${number}c"] ?: throw IOException("No track $number")
        writeAudioSegment(a, index, out)
    }

    /** Segment [index] of soundtrack [a], as it is or as AAC. */
    fun writeAudioSegment(a: HlsAudio, index: Int, out: OutputStream) {
        val number = a.number
        val t = audioTracks.firstOrNull { it.number == number } ?: throw IOException("No track $number")
        val entry = audioEntry(a) ?: throw IOException("Track ${a.key} cannot be sent")
        val seg = segments[index]
        val last = index == segments.size - 1
        if (a.convert) {
            writeConverted(t, entry, seg, last, out)
            return
        }
        val writer = AudioWriter(index, entry, out)
        // The cluster of the segment's cue, or the one before it when the sound for the start was
        // stored ahead of the keyframe (a muxer interleaving loosely).
        var from = seg.clusterPosition
        for (attempt in 0..1) {
            var firstSeen: Long? = null
            var restart = false
            CastCursor(source, from).use { c ->
                val frames = Matroska.Frames(c, header, from, setOf(t.number))
                while (true) {
                    val f = frames.next() ?: break
                    if (firstSeen == null) {
                        firstSeen = f.timeNs
                        if (attempt == 0 && index > 0 && f.timeNs > seg.startNs + AUDIO_GAP_NS && seg.cueIndex > 0) {
                            restart = true
                            break
                        }
                    }
                    if (f.timeNs < seg.startNs) continue
                    if (!last && f.timeNs >= seg.endNs) break
                    writer.add(f.data, f.timeNs)
                }
            }
            if (!restart) break
            from = cues[seg.cueIndex - 1].clusterPosition
        }
        writer.finish()
    }

    private inner class AudioWriter(private val index: Int, private val entry: AudioEntry, private val out: OutputStream) {
        private val samples = ArrayList<Fmp4Sample>()
        private var bytes = 0
        private var base = -1L
        private var fragment = 0

        fun add(data: ByteArray, timeNs: Long) {
            if (base < 0) base = Math.floorDiv(timeNs * entry.timescale, 1_000_000_000L).coerceAtLeast(0)
            samples += Fmp4Sample(data, entry.samplesPerFrame(data).toLong(), 0, true)
            bytes += data.size
            if (bytes >= FRAGMENT_BYTES) flush()
        }

        /** Converted frames come with their own time; the first sets the segment's start. */
        fun addAt(data: ByteArray, ptsUs: Long) {
            if (base < 0) base = Math.floorDiv(ptsUs * entry.timescale, 1_000_000L).coerceAtLeast(0)
            samples += Fmp4Sample(data, 1024, 0, true)
            bytes += data.size
            if (bytes >= FRAGMENT_BYTES) flush()
        }

        fun finish() {
            flush()
            out.flush()
        }

        private fun flush() {
            if (samples.isEmpty()) return
            out.write(Fmp4.fragment(index * 1000 + ++fragment, listOf(Fmp4Run(TRACK_ID, base, samples.toList())), emptySet()))
            base += samples.sumOf { it.duration }
            samples.clear()
            bytes = 0
        }
    }

    /**
     * A converted segment: the source decoded from a little before the segment's start, so the
     * encoder's first frames (its priming) fall before it and are dropped, and the AAC frames that
     * start inside the segment kept.
     */
    private fun writeConverted(t: MkvTrack, entry: AudioEntry, seg: HlsSegment, last: Boolean, out: OutputStream) {
        val factory = converterFactory ?: throw IOException("No audio converter")
        val writer = AudioWriter(seg.index, entry, out)
        val leadNs = (seg.startNs - CONVERT_LEAD_NS).coerceAtLeast(0)
        val from = if (seg.index == 0 || seg.cueIndex == 0) seg.clusterPosition else cues[seg.cueIndex - 1].clusterPosition
        factory().use { converter ->
            converter.start(Mp4Entries.audioTrack(t))
            fun keep(list: List<ConvertedAudio>) {
                for (a in list) {
                    val ns = a.ptsUs * 1000
                    if (ns < seg.startNs) continue
                    if (!last && ns >= seg.endNs) continue
                    writer.addAt(a.data, a.ptsUs)
                }
            }
            CastCursor(source, from).use { c ->
                val frames = Matroska.Frames(c, header, from, setOf(t.number))
                while (true) {
                    val f = frames.next() ?: break
                    if (f.timeNs < leadNs) continue
                    if (!last && f.timeNs >= seg.endNs + CONVERT_TAIL_NS) break
                    keep(converter.convert(f.data, f.timeNs / 1000))
                }
            }
            keep(converter.finish())
        }
        writer.finish()
    }

    // ---- subtitles ---------------------------------------------------------------------------

    private val textCache = object : LinkedHashMap<Int, Map<Int, List<WebVtt.Cue>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<Int, List<WebVtt.Cue>>>): Boolean = size > TEXT_CACHE
    }

    /** Subtitle segment [index] of rendition [prefix] ("s3" for track 3, "f1000" for a file). */
    fun textSegment(prefix: String, index: Int): String {
        val seg = segments[index]
        val startMs = seg.startNs / 1_000_000
        val endMs = if (index == segments.size - 1) Long.MAX_VALUE else seg.endNs / 1_000_000
        val cues = when (prefix.first()) {
            'f' -> files.firstOrNull { it.id == prefix.drop(1).toLong() }?.cues.orEmpty().filter { it.startMs in startMs until endMs }
            else -> insideCues(index)[prefix.drop(1).toInt()].orEmpty()
        }
        return WebVtt.segment(cues)
    }

    /**
     * The cues of every text track inside the file that start in segment [index], read in one pass
     * over its clusters (only the subtitle blocks are copied) and kept for the next track asked for.
     */
    private fun insideCues(index: Int): Map<Int, List<WebVtt.Cue>> {
        synchronized(textCache) { textCache[index] }?.let { return it }
        val seg = segments[index]
        val last = index == segments.size - 1
        val byTrack = HashMap<Int, MutableList<WebVtt.Cue>>()
        val numbers = textTracks.associateBy { it.number }
        CastCursor(source, seg.clusterPosition).use { c ->
            val frames = Matroska.Frames(c, header, seg.clusterPosition, numbers.keys)
            if (!last) frames.stopAfterNs = seg.endNs + TEXT_TAIL_NS
            while (true) {
                val f = frames.next() ?: break
                if (f.timeNs < seg.startNs || (!last && f.timeNs >= seg.endNs)) continue
                val t = numbers[f.track] ?: continue
                val startMs = f.timeNs / 1_000_000
                val durationMs = if (f.durationNs > 0) f.durationNs / 1_000_000 else DEFAULT_CUE_MS
                WebVtt.fromMatroska(Codecs.fromMatroska(t.codecId), f.data, startMs, startMs + durationMs)?.let {
                    byTrack.getOrPut(t.number) { ArrayList() } += it
                }
            }
        }
        val result: Map<Int, List<WebVtt.Cue>> = byTrack
        synchronized(textCache) { textCache[index] = result }
        return result
    }

    private fun toTs(ns: Long): Long = Math.floorDiv(ns * VIDEO_TIMESCALE, 1_000_000_000L)

    companion object {
        private const val TAG = "MkvHls"
        const val PLAYLIST_MIME = "application/vnd.apple.mpegurl"

        /** What a Cast LOAD names an HLS stream as. */
        const val CONTENT_TYPE = "application/x-mpegurl"

        const val VIDEO_TIMESCALE = 90_000L
        private const val TRACK_ID = 1
        private const val LOOKAHEAD = 32
        private const val FRAGMENT_BYTES = 2 * 1024 * 1024
        private const val SLACK_NS = 1_000_000L
        private const val RUNAWAY_NS = 60_000_000_000L
        private const val AUDIO_GAP_NS = 150_000_000L
        private const val CONVERT_LEAD_NS = 500_000_000L
        private const val CONVERT_TAIL_NS = 300_000_000L
        private const val TEXT_TAIL_NS = 2_000_000_000L
        private const val DEFAULT_CUE_MS = 4_000L
        private const val TEXT_CACHE = 64

        /**
         * Reads what HLS needs of [source] (the head and the Cues) and plans the segments, or throws
         * when the file has no picture HLS can carry or no Cues to cut it at; the caller then falls
         * back to the one rewrapped stream.
         */
        fun prepare(
            source: CastSource,
            probe: MediaProbe,
            audio: List<HlsAudio>,
            defaultAudio: Int?,
            files: List<HlsSubtitleFile> = emptyList(),
            converterFactory: (() -> AudioConverter)? = null,
            targetNs: Long = HlsSegments.TARGET_NS,
            /** The file's own text tracks and [files] as subtitle renditions; false casts without them. */
            subtitles: Boolean = true,
        ): MkvHls {
            val header = probe.matroska ?: CastCursor(source).use { Matroska.header(it) }
            val video = header.tracks.firstOrNull { it.type == 1 && Codecs.fromMatroska(it.codecId) in CastFormats.MP4_VIDEO }
                ?: throw IOException("No picture HLS can carry")
            val cues = CastCursor(source).use { Matroska.cues(it, header, video.number, strict = true) }
                .sortedBy { it.timeNs }.distinctBy { it.timeNs }
            if (cues.size < 2 && (header.durationNs <= 0 || header.durationNs > targetNs)) throw IOException("No cues to cut the video at")
            val usable = if (cues.isEmpty()) listOf(MkvCue(0, header.firstCluster)) else cues
            val segments = HlsSegments.cut(usable, header.durationNs, header.firstCluster, targetNs)
            if (segments.isEmpty()) throw IOException("No segments")
            Logger.i(TAG, "HLS: ${segments.size} segments from ${cues.size} cues, ${audio.size} soundtracks, ${files.size} subtitle files")
            return MkvHls(source, header, usable, segments, audio, defaultAudio, if (subtitles) files else emptyList(), converterFactory, subtitles)
        }
    }
}

/** The names and language tags written into a master playlist. */
object HlsNames {

    private val twoLetter: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (code in Locale.getISOLanguages()) {
            runCatching { Locale.forLanguageTag(code).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { map[it] = code }
        }
        // ISO 639-2 has a second, bibliographic code for some languages, which Matroska files use.
        mapOf("fre" to "fr", "ger" to "de", "chi" to "zh", "dut" to "nl", "gre" to "el", "per" to "fa", "cze" to "cs", "rum" to "ro", "slo" to "sk", "ice" to "is", "mac" to "mk", "may" to "ms", "bur" to "my", "arm" to "hy", "geo" to "ka", "baq" to "eu", "alb" to "sq", "wel" to "cy", "tib" to "bo")
            .forEach { (k, v) -> map[k] = v }
        map
    }

    /** A Matroska language (ISO 639-2 or BCP 47) as the BCP 47 tag HLS asks for, or null. */
    fun languageTag(language: String?): String? {
        val l = language?.trim()?.takeIf { it.isNotEmpty() && it != "und" && it != "zxx" && it != "mul" } ?: return null
        if (l.length == 3 && l.all { it.isLetter() }) return twoLetter[l.lowercase(Locale.ROOT)] ?: l.lowercase(Locale.ROOT)
        return l
    }

    /** A rendition's name: the track's own, else its language in words, else [fallback]. */
    fun label(name: String?, language: String?, fallback: String): String {
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val tag = languageTag(language) ?: return fallback
        val words = runCatching { Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH) }.getOrNull()
        return words?.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) } ?: tag
    }
}

