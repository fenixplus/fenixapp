package com.arflix.tv.cast.engine

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.Inflater

/** One Matroska track, as the remuxer reads it. */
internal class MkvTrack(
    val number: Int,
    /** 1 video, 2 audio, 17 subtitles. */
    val type: Int,
    val codecId: String,
    val codecPrivate: ByteArray?,
    val language: String?,
    val name: String?,
    val isDefault: Boolean,
    val forced: Boolean,
    val defaultDurationNs: Long,
    val codecDelayNs: Long,
    val width: Int,
    val height: Int,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    /** Bytes the muxer stripped off the front of every frame (header removal), put back on read. */
    val strippedHeader: ByteArray?,
    /** Frames squeezed with zlib, inflated on read. */
    val zlib: Boolean,
    val colourBits: Int,
)

internal class MkvHeader(
    val docType: String,
    /** Where the Segment's children start; SeekHead and Cues positions count from here. */
    val segmentStart: Long,
    val segmentEnd: Long,
    val timecodeScaleNs: Long,
    val durationNs: Long,
    val tracks: List<MkvTrack>,
    val firstCluster: Long,
    val cuesAt: Long?,
)

/** A cue: a time and the cluster to start reading from for it. */
internal class MkvCue(val timeNs: Long, val clusterPosition: Long)

/** One frame out of a Block or SimpleBlock, laced frames split apart. */
internal class MkvFrame(val track: Int, val timeNs: Long, val durationNs: Long, val keyframe: Boolean, val data: ByteArray)

/**
 * Just enough Matroska for casting: what the head says (the tracks and their codecs, the
 * duration, where the Cues are), the Cues, and the frames in order from any cluster. EBML ids
 * keep their marker bits, as the specification writes them.
 */
internal object Matroska {

    const val EBML = 0x1A45DFA3L
    const val DOC_TYPE = 0x4282L
    const val SEGMENT = 0x18538067L
    const val SEEK_HEAD = 0x114D9B74L
    const val SEEK = 0x4DBBL
    const val SEEK_ID = 0x53ABL
    const val SEEK_POSITION = 0x53ACL
    const val INFO = 0x1549A966L
    const val TIMECODE_SCALE = 0x2AD7B1L
    const val DURATION = 0x4489L
    const val TRACKS = 0x1654AE6BL
    const val TRACK_ENTRY = 0xAEL
    const val TRACK_NUMBER = 0xD7L
    const val TRACK_TYPE = 0x83L
    const val CODEC_ID = 0x86L
    const val CODEC_PRIVATE = 0x63A2L
    const val CODEC_DELAY = 0x56AAL
    const val LANGUAGE = 0x22B59CL
    const val LANGUAGE_BCP47 = 0x22B59DL
    const val NAME = 0x536EL
    const val FLAG_DEFAULT = 0x88L
    const val FLAG_FORCED = 0x55AAL
    const val DEFAULT_DURATION = 0x23E383L
    const val VIDEO = 0xE0L
    const val PIXEL_WIDTH = 0xB0L
    const val PIXEL_HEIGHT = 0xBAL
    const val COLOUR = 0x55B0L
    const val BITS_PER_CHANNEL = 0x55B2L
    const val AUDIO = 0xE1L
    const val SAMPLING_FREQUENCY = 0xB5L
    const val OUTPUT_SAMPLING_FREQUENCY = 0x78B5L
    const val CHANNELS = 0x9FL
    const val BIT_DEPTH = 0x6264L
    const val CONTENT_ENCODINGS = 0x6D80L
    const val CONTENT_ENCODING = 0x6240L
    const val CONTENT_COMPRESSION = 0x5034L
    const val CONTENT_COMP_ALGO = 0x4254L
    const val CONTENT_COMP_SETTINGS = 0x4255L
    const val CUES = 0x1C53BB6BL
    const val CUE_POINT = 0xBBL
    const val CUE_TIME = 0xB3L
    const val CUE_TRACK_POSITIONS = 0xB7L
    const val CUE_CLUSTER_POSITION = 0xF1L
    const val CLUSTER = 0x1F43B675L
    const val TIMECODE = 0xE7L
    const val SIMPLE_BLOCK = 0xA3L
    const val BLOCK_GROUP = 0xA0L
    const val BLOCK = 0xA1L
    const val BLOCK_DURATION = 0x9BL
    const val REFERENCE_BLOCK = 0xFBL
    const val TAGS = 0x1254C367L
    const val CHAPTERS = 0x1043A770L
    const val ATTACHMENTS = 0x1941A469L

    /** Elements that sit directly under the Segment: one of these ends an unknown-size Cluster. */
    val SEGMENT_CHILDREN = setOf(SEEK_HEAD, INFO, TRACKS, CUES, CLUSTER, TAGS, CHAPTERS, ATTACHMENTS)

    /** A size of all ones: "until the parent ends", which live muxers write for Segment and Cluster. */
    const val UNKNOWN = -1L

    fun isMatroska(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()

    class Element(val id: Long, val size: Long, val dataStart: Long) {
        val end: Long get() = if (size == UNKNOWN) Long.MAX_VALUE else dataStart + size
    }

    fun readId(c: CastCursor): Long? {
        val first = c.read()
        if (first < 0) return null
        if (first == 0) throw IOException("Bad EBML id at ${c.position - 1}")
        val length = Integer.numberOfLeadingZeros(first) - 23
        if (length > 4) throw IOException("Bad EBML id at ${c.position - 1}")
        var value = first.toLong()
        repeat(length - 1) {
            val b = c.read()
            if (b < 0) throw EOFException()
            value = (value shl 8) or b.toLong()
        }
        return value
    }

    fun readSize(c: CastCursor): Long {
        val first = c.read()
        if (first <= 0) throw IOException("Bad EBML size at ${c.position - 1}")
        val length = Integer.numberOfLeadingZeros(first) - 23
        var value = (first and (0xFF shr length)).toLong()
        var allOnes = value == (0xFF shr length).toLong()
        repeat(length - 1) {
            val b = c.read()
            if (b < 0) throw EOFException()
            if (b != 0xFF) allOnes = false
            value = (value shl 8) or b.toLong()
        }
        return if (allOnes) UNKNOWN else value
    }

    fun element(c: CastCursor): Element? {
        val id = readId(c) ?: return null
        val size = readSize(c)
        return Element(id, size, c.position)
    }

    fun uint(c: CastCursor, e: Element): Long {
        var v = 0L
        for (b in c.readBytes(e.size.toInt().coerceAtMost(8))) v = (v shl 8) or (b.toLong() and 0xFF)
        return v
    }

    fun float(c: CastCursor, e: Element): Double {
        val b = c.readBytes(e.size.toInt())
        return when (b.size) {
            4 -> ByteBuffer.wrap(b).float.toDouble()
            8 -> ByteBuffer.wrap(b).double
            else -> 0.0
        }
    }

    fun string(c: CastCursor, e: Element): String = String(c.readBytes(e.size.toInt()), Charsets.UTF_8).trimEnd('\u0000')

    /** The head: EBML header, then the Segment's children up to the first Cluster. */
    fun header(c: CastCursor): MkvHeader {
        c.seek(0)
        val ebml = element(c) ?: throw IOException("Empty file")
        if (ebml.id != EBML) throw IOException("Not Matroska")
        var docType = "matroska"
        while (c.position < ebml.end) {
            val e = element(c) ?: break
            if (e.id == DOC_TYPE) docType = string(c, e) else c.seek(e.end)
        }
        c.seek(ebml.end)
        val segment = element(c) ?: throw IOException("No Segment")
        if (segment.id != SEGMENT) throw IOException("No Segment")
        val segmentStart = segment.dataStart
        val segmentEnd = if (segment.size == UNKNOWN) c.length else minOf(segment.end, c.length)
        var scale = 1_000_000L
        var duration = 0.0
        var tracks: List<MkvTrack>? = null
        var cuesAt: Long? = null
        var firstCluster: Long? = null
        val seeks = HashMap<Long, Long>()
        while (c.position < segmentEnd) {
            val at = c.position
            val e = element(c) ?: break
            when (e.id) {
                SEEK_HEAD -> seeks.putAll(seekHead(c, e))
                INFO -> {
                    val (s, d) = info(c, e)
                    scale = s
                    duration = d
                }
                TRACKS -> tracks = tracks(c, e)
                CUES -> cuesAt = at
                CLUSTER -> {
                    firstCluster = at
                    break
                }
            }
            if (e.size == UNKNOWN) break
            c.seek(e.end)
        }
        // Info or Tracks after the media is rare, but the SeekHead says where they are.
        if (tracks == null) {
            seeks[TRACKS]?.let { pos ->
                c.seek(segmentStart + pos)
                element(c)?.takeIf { it.id == TRACKS }?.let { tracks = tracks(c, it) }
            }
        }
        if (duration == 0.0) {
            seeks[INFO]?.let { pos ->
                c.seek(segmentStart + pos)
                element(c)?.takeIf { it.id == INFO }?.let { val (s, d) = info(c, it); scale = s; duration = d }
            }
        }
        if (cuesAt == null) cuesAt = seeks[CUES]?.let { segmentStart + it }
        return MkvHeader(
            docType = docType,
            segmentStart = segmentStart,
            segmentEnd = segmentEnd,
            timecodeScaleNs = scale,
            durationNs = (duration * scale).toLong(),
            tracks = tracks ?: throw IOException("No Tracks"),
            firstCluster = firstCluster ?: seeks[CLUSTER]?.let { segmentStart + it } ?: throw IOException("No Cluster"),
            cuesAt = cuesAt,
        )
    }

    private fun seekHead(c: CastCursor, parent: Element): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        while (c.position < parent.end) {
            val seek = element(c) ?: break
            if (seek.id != SEEK) {
                c.seek(seek.end)
                continue
            }
            var id = 0L
            var pos = -1L
            while (c.position < seek.end) {
                val e = element(c) ?: break
                when (e.id) {
                    SEEK_ID -> id = uint(c, e)
                    SEEK_POSITION -> pos = uint(c, e)
                    else -> c.seek(e.end)
                }
            }
            // Only the first entry for an id: some files list a second Cues or Tags further on.
            if (pos >= 0 && id !in out) out[id] = pos
            c.seek(seek.end)
        }
        return out
    }

    private fun info(c: CastCursor, parent: Element): Pair<Long, Double> {
        var scale = 1_000_000L
        var duration = 0.0
        while (c.position < parent.end) {
            val e = element(c) ?: break
            when (e.id) {
                TIMECODE_SCALE -> scale = uint(c, e).takeIf { it > 0 } ?: 1_000_000L
                DURATION -> duration = float(c, e)
                else -> c.seek(e.end)
            }
        }
        return scale to duration
    }

    private fun tracks(c: CastCursor, parent: Element): List<MkvTrack> {
        val out = ArrayList<MkvTrack>()
        while (c.position < parent.end) {
            val e = element(c) ?: break
            if (e.id == TRACK_ENTRY) out += trackEntry(c, e)
            c.seek(e.end)
        }
        return out
    }

    private fun trackEntry(c: CastCursor, parent: Element): MkvTrack {
        var number = 0
        var type = 0
        var codecId = ""
        var codecPrivate: ByteArray? = null
        var language: String? = null
        var bcp47: String? = null
        var name: String? = null
        var isDefault = true
        var forced = false
        var defaultDuration = 0L
        var codecDelay = 0L
        var width = 0
        var height = 0
        var rate = 0.0
        var outRate = 0.0
        var channels = 1
        var bitDepth = 0
        var stripped: ByteArray? = null
        var zlib = false
        var colourBits = 0
        while (c.position < parent.end) {
            val e = element(c) ?: break
            when (e.id) {
                TRACK_NUMBER -> number = uint(c, e).toInt()
                TRACK_TYPE -> type = uint(c, e).toInt()
                CODEC_ID -> codecId = string(c, e)
                CODEC_PRIVATE -> codecPrivate = c.readBytes(e.size.toInt())
                LANGUAGE -> language = string(c, e)
                LANGUAGE_BCP47 -> bcp47 = string(c, e)
                NAME -> name = string(c, e)
                FLAG_DEFAULT -> isDefault = uint(c, e) != 0L
                FLAG_FORCED -> forced = uint(c, e) != 0L
                DEFAULT_DURATION -> defaultDuration = uint(c, e)
                CODEC_DELAY -> codecDelay = uint(c, e)
                VIDEO -> while (c.position < e.end) {
                    val v = element(c) ?: break
                    when (v.id) {
                        PIXEL_WIDTH -> width = uint(c, v).toInt()
                        PIXEL_HEIGHT -> height = uint(c, v).toInt()
                        COLOUR -> while (c.position < v.end) {
                            val k = element(c) ?: break
                            if (k.id == BITS_PER_CHANNEL) colourBits = uint(c, k).toInt() else c.seek(k.end)
                        }
                        else -> c.seek(v.end)
                    }
                }
                AUDIO -> while (c.position < e.end) {
                    val a = element(c) ?: break
                    when (a.id) {
                        SAMPLING_FREQUENCY -> rate = float(c, a)
                        OUTPUT_SAMPLING_FREQUENCY -> outRate = float(c, a)
                        CHANNELS -> channels = uint(c, a).toInt()
                        BIT_DEPTH -> bitDepth = uint(c, a).toInt()
                        else -> c.seek(a.end)
                    }
                }
                CONTENT_ENCODINGS -> while (c.position < e.end) {
                    val enc = element(c) ?: break
                    if (enc.id != CONTENT_ENCODING) {
                        c.seek(enc.end)
                        continue
                    }
                    while (c.position < enc.end) {
                        val comp = element(c) ?: break
                        if (comp.id != CONTENT_COMPRESSION) {
                            c.seek(comp.end)
                            continue
                        }
                        var algo = 0L
                        var settings: ByteArray? = null
                        while (c.position < comp.end) {
                            val k = element(c) ?: break
                            when (k.id) {
                                CONTENT_COMP_ALGO -> algo = uint(c, k)
                                CONTENT_COMP_SETTINGS -> settings = c.readBytes(k.size.toInt())
                                else -> c.seek(k.end)
                            }
                        }
                        when (algo) {
                            0L -> zlib = true
                            3L -> stripped = settings
                        }
                        c.seek(comp.end)
                    }
                    c.seek(enc.end)
                }
                else -> c.seek(e.end)
            }
            c.seek(e.end)
        }
        // HE-AAC files give the core rate and the doubled output rate; the output is what plays.
        val sampleRate = (if (outRate > 0) outRate else rate).toInt()
        return MkvTrack(
            number = number,
            type = type,
            codecId = codecId,
            codecPrivate = codecPrivate,
            language = bcp47 ?: language?.takeIf { it != "und" },
            name = name,
            isDefault = isDefault,
            forced = forced,
            defaultDurationNs = defaultDuration,
            codecDelayNs = codecDelay,
            width = width,
            height = height,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            strippedHeader = stripped,
            zlib = zlib,
            colourBits = colourBits,
        )
    }

    /** The probe casting decides on, from the head. */
    fun probe(c: CastCursor): MediaProbe {
        val h = header(c)
        val video = h.tracks.firstOrNull { it.type == 1 }?.let { t ->
            VideoTrack(
                number = t.number,
                codec = Codecs.fromMatroska(t.codecId),
                width = t.width,
                height = t.height,
                codecPrivate = t.codecPrivate,
                defaultDurationNs = t.defaultDurationNs,
                tenBit = t.colourBits > 8 || tenBitFromPrivate(Codecs.fromMatroska(t.codecId), t.codecPrivate),
            )
        }
        val audio = h.tracks.filter { it.type == 2 }.map { t ->
            AudioTrack(
                number = t.number,
                codec = Codecs.fromMatroska(t.codecId),
                language = t.language,
                name = t.name,
                channels = t.channels,
                sampleRate = t.sampleRate,
                codecPrivate = t.codecPrivate,
                isDefault = t.isDefault,
                bitDepth = t.bitDepth,
                codecDelayNs = t.codecDelayNs,
            )
        }
        val text = h.tracks.filter { it.type == 17 }.map { t ->
            TextTrack(t.number, Codecs.fromMatroska(t.codecId), t.language, t.name, t.isDefault, t.forced)
        }
        val container = if (h.docType == "webm") Container.WebM else Container.Matroska
        return MediaProbe(container, h.durationNs / 1_000_000, video, audio, text, h)
    }

    /** HEVC Main 10 and AV1 high bit depth say so in their configuration records. */
    private fun tenBitFromPrivate(codec: String, private: ByteArray?): Boolean {
        val p = private ?: return false
        return when (codec) {
            // HEVCDecoderConfigurationRecord: general_profile_idc in byte 1's low five bits; 2 is Main 10.
            Codecs.HEVC -> p.size > 1 && (p[1].toInt() and 0x1F) == 2
            // av1C: byte 2 has high_bitdepth in bit 6.
            Codecs.AV1 -> p.size > 2 && (p[2].toInt() and 0x40) != 0
            else -> false
        }
    }

    /** Every cue in the file, in time order; empty when there are none or they cannot be read. */
    fun cues(c: CastCursor, h: MkvHeader, track: Int?, strict: Boolean = false): List<MkvCue> {
        val at = h.cuesAt ?: return emptyList()
        return runCatching {
            c.seek(at)
            val cues = element(c)?.takeIf { it.id == CUES } ?: return emptyList()
            val out = ArrayList<MkvCue>()
            while (c.position < cues.end) {
                val point = element(c) ?: break
                if (point.id != CUE_POINT) {
                    c.seek(point.end)
                    continue
                }
                var time = -1L
                var cluster = -1L
                while (c.position < point.end) {
                    val e = element(c) ?: break
                    when (e.id) {
                        CUE_TIME -> time = uint(c, e)
                        CUE_TRACK_POSITIONS -> {
                            var t = 0L
                            var pos = -1L
                            while (c.position < e.end) {
                                val k = element(c) ?: break
                                when (k.id) {
                                    0xF7L -> t = uint(c, k)
                                    CUE_CLUSTER_POSITION -> pos = uint(c, k)
                                    else -> c.seek(k.end)
                                }
                            }
                            if (pos >= 0 && (cluster < 0 || track == null || t == track.toLong())) {
                                if (!strict || track == null || t == track.toLong()) cluster = pos
                            }
                        }
                        else -> c.seek(e.end)
                    }
                    c.seek(e.end)
                }
                if (time >= 0 && cluster >= 0) out += MkvCue(time * h.timecodeScaleNs, h.segmentStart + cluster)
                c.seek(point.end)
            }
            out.sortedBy { it.timeNs }
        }.getOrDefault(emptyList())
    }

    /**
     * The frames of [wanted] tracks from the cluster at [from] on, in file order, laced frames
     * split and header removal undone. Stops at the end of the segment.
     */
    class Frames(private val c: CastCursor, private val h: MkvHeader, from: Long, val wanted: Set<Int>) {

        private val byNumber = h.tracks.associateBy { it.number }
        private var clusterEnd = Long.MAX_VALUE
        private var clusterTime = 0L
        private val pending = ArrayDeque<MkvFrame>()
        private var done = false

        init {
            c.seek(from)
        }

        /** Where the next element is read from, for the remuxer's progress. */
        val position: Long get() = c.position

        /** Once a block of any track is later than this, [next] says the frames have ended. */
        var stopAfterNs: Long = Long.MAX_VALUE

        fun next(): MkvFrame? {
            while (pending.isEmpty()) {
                if (done || lastTimeNs > stopAfterNs) return null
                step()
            }
            return pending.removeFirst()
        }

        private fun step() {
            if (c.position >= h.segmentEnd) {
                done = true
                return
            }
            val e = try {
                element(c)
            } catch (_: EOFException) {
                null
            }
            if (e == null) {
                done = true
                return
            }
            if (e.id == CLUSTER) {
                clusterEnd = if (e.size == UNKNOWN) Long.MAX_VALUE else e.end
                return
            }
            if (c.position > clusterEnd || e.id in SEGMENT_CHILDREN) {
                // A Cues, Tags or other top-level element between clusters: past it.
                if (e.size == UNKNOWN) {
                    done = true
                    return
                }
                c.seek(e.end)
                return
            }
            when (e.id) {
                TIMECODE -> clusterTime = uint(c, e)
                SIMPLE_BLOCK -> wantedBlock(e)?.let { block(it, simple = true, duration = -1, reference = false) }
                BLOCK_GROUP -> {
                    var data: ByteArray? = null
                    var duration = -1L
                    var reference = false
                    while (c.position < e.end) {
                        val k = element(c) ?: break
                        when (k.id) {
                            BLOCK -> data = wantedBlock(k)
                            BLOCK_DURATION -> duration = uint(c, k)
                            REFERENCE_BLOCK -> {
                                reference = true
                                c.seek(k.end)
                            }
                            else -> c.seek(k.end)
                        }
                    }
                    data?.let { block(it, simple = false, duration = duration, reference = reference) }
                    c.seek(e.end)
                }
                else -> if (e.size == UNKNOWN) done = true else c.seek(e.end)
            }
        }

        /**
         * The time of the last block passed, of any track, wanted or not: how a reader that wants
         * one stretch of the video knows it has gone past it.
         */
        var lastTimeNs: Long = Long.MIN_VALUE
            private set

        /**
         * The whole of the block [e] when its track is wanted, or null with the block skipped. Only
         * its head (the track number and the time) is read for a track nobody asked for, so a pass
         * for the sound alone does not copy the picture's frames.
         */
        private fun wantedBlock(e: Element): ByteArray? {
            val size = e.size.toInt()
            if (size < 4) {
                c.seek(e.end)
                return null
            }
            val first = c.read()
            if (first <= 0) throw IOException("Bad block at ${c.position - 1}")
            val len = Integer.numberOfLeadingZeros(first) - 23
            if (len + 3 > size) {
                c.seek(e.end)
                return null
            }
            val head = ByteArray(len + 2)
            head[0] = first.toByte()
            val restOfHead = c.readBytes(len + 1)
            System.arraycopy(restOfHead, 0, head, 1, restOfHead.size)
            var track = (first and (0xFF shr len)).toLong()
            for (i in 1 until len) track = (track shl 8) or (head[i].toLong() and 0xFF)
            val relative = ((head[len].toInt() shl 8) or (head[len + 1].toInt() and 0xFF)).toShort().toLong()
            lastTimeNs = (clusterTime + relative) * h.timecodeScaleNs
            if (track.toInt() !in wanted) {
                c.seek(e.end)
                return null
            }
            return head + c.readBytes(size - head.size)
        }

        private fun block(b: ByteArray, simple: Boolean, duration: Long, reference: Boolean) {
            var p = 0
            // Track number, an EBML vint with its marker dropped.
            val first = b[p].toInt() and 0xFF
            val len = Integer.numberOfLeadingZeros(first) - 23
            var track = (first and (0xFF shr len)).toLong()
            for (i in 1 until len) track = (track shl 8) or (b[p + i].toLong() and 0xFF)
            p += len
            if (track.toInt() !in wanted) return
            val t = byNumber[track.toInt()] ?: return
            val relative = ((b[p].toInt() shl 8) or (b[p + 1].toInt() and 0xFF)).toShort().toLong()
            p += 2
            val flags = b[p].toInt() and 0xFF
            p++
            val keyframe = if (simple) (flags and 0x80) != 0 else !reference
            val lacing = (flags shr 1) and 0x03
            val time = (clusterTime + relative) * h.timecodeScaleNs
            val sizes: IntArray = when (lacing) {
                0 -> intArrayOf(b.size - p)
                else -> {
                    val count = (b[p].toInt() and 0xFF) + 1
                    p++
                    val out = IntArray(count)
                    when (lacing) {
                        1 -> { // Xiph
                            var used = 0
                            for (i in 0 until count - 1) {
                                var s = 0
                                while (true) {
                                    val v = b[p++].toInt() and 0xFF
                                    s += v
                                    if (v != 255) break
                                }
                                out[i] = s
                                used += s
                            }
                            out[count - 1] = b.size - p - used
                        }
                        3 -> { // EBML
                            var used = 0
                            val (firstSize, n1) = vint(b, p)
                            p += n1
                            out[0] = firstSize.toInt()
                            used += out[0]
                            for (i in 1 until count - 1) {
                                val (raw, n) = vint(b, p)
                                p += n
                                val bias = (1L shl (7 * n - 1)) - 1
                                out[i] = (out[i - 1] + (raw - bias)).toInt()
                                used += out[i]
                            }
                            out[count - 1] = b.size - p - used
                        }
                        else -> { // fixed
                            val each = (b.size - p) / count
                            for (i in 0 until count) out[i] = each
                        }
                    }
                    out
                }
            }
            val total = if (duration >= 0) duration * h.timecodeScaleNs else 0L
            val each = when {
                t.defaultDurationNs > 0 -> t.defaultDurationNs
                total > 0 -> total / sizes.size
                else -> 0L
            }
            for ((i, size) in sizes.withIndex()) {
                if (size < 0 || p + size > b.size) throw IOException("Bad lacing")
                var data = b.copyOfRange(p, p + size)
                p += size
                if (t.zlib) data = inflate(data)
                t.strippedHeader?.let { data = it + data }
                pending += MkvFrame(t.number, time + each * i, if (sizes.size == 1 && total > 0) total else each, keyframe && i == 0 || (keyframe && t.type == 2), data)
            }
        }

        private fun vint(b: ByteArray, at: Int): Pair<Long, Int> {
            val first = b[at].toInt() and 0xFF
            val len = Integer.numberOfLeadingZeros(first) - 23
            var v = (first and (0xFF shr len)).toLong()
            for (i in 1 until len) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v to len
        }

        private fun inflate(data: ByteArray): ByteArray {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 3)
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inflater.end()
            return out.toByteArray()
        }
    }
}

