package com.arflix.tv.cast.engine

import java.nio.ByteBuffer

/**
 * The tracks of an MP4 (or MOV) file, from its moov: at the front of a file made for streaming,
 * or after the media data, where the size of the mdat box says. Only the sample entries are read,
 * which is what decides whether a receiver plays it as it is.
 */
internal object Mp4Probe {

    private const val MAX_MOOV = 64 * 1024 * 1024

    fun isMp4(head: ByteArray): Boolean {
        if (head.size < 8) return false
        val type = String(head, 4, 4, Charsets.ISO_8859_1)
        return type in setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pnot")
    }

    fun probe(source: CastSource): MediaProbe {
        var at = 0L
        var moov: ByteArray? = null
        var guard = 0
        while (at + 8 <= source.length && guard++ < 64) {
            val header = source.readAt(at, 16)
            if (header.size < 8) break
            var size = u32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8
            if (size == 1L && header.size >= 16) {
                size = ByteBuffer.wrap(header, 8, 8).long
                headerSize = 16
            } else if (size == 0L) {
                size = source.length - at
            }
            if (size < headerSize) break
            if (type == "moov") {
                if (size > MAX_MOOV) break
                moov = source.readAt(at + headerSize, (size - headerSize).toInt())
                break
            }
            at += size
        }
        val box = moov ?: return MediaProbe(Container.Mp4)
        return parseMoov(box)
    }

    fun parseMoov(moov: ByteArray): MediaProbe {
        var durationMs = 0L
        var video: VideoTrack? = null
        val audio = ArrayList<AudioTrack>()
        val text = ArrayList<TextTrack>()
        var trackIndex = 0
        for ((type, start, end) in children(moov, 0, moov.size)) {
            when (type) {
                "mvhd" -> {
                    val version = moov[start].toInt()
                    val (scale, duration) = if (version == 1) {
                        u32(moov, start + 20) to ByteBuffer.wrap(moov, start + 24, 8).long
                    } else {
                        u32(moov, start + 12) to u32(moov, start + 16)
                    }
                    if (scale > 0) durationMs = duration * 1000 / scale
                }
                "trak" -> {
                    trackIndex++
                    val t = trak(moov, start, end, trackIndex) ?: continue
                    when (t) {
                        is VideoTrack -> if (video == null) video = t
                        is AudioTrack -> audio += t
                        is TextTrack -> text += t
                    }
                }
            }
        }
        return MediaProbe(Container.Mp4, durationMs, video, audio, text)
    }

    private fun trak(b: ByteArray, from: Int, to: Int, number: Int): Any? {
        val mdia = children(b, from, to).firstOrNull { it.first == "mdia" } ?: return null
        var handler = ""
        var language: String? = null
        var entry: Triple<String, Int, Int>? = null
        for ((type, start, end) in children(b, mdia.second, mdia.third)) {
            when (type) {
                "hdlr" -> handler = String(b, start + 8, 4, Charsets.ISO_8859_1)
                "mdhd" -> {
                    val version = b[start].toInt()
                    val at = start + if (version == 1) 32 else 20
                    if (at + 2 <= end) language = mdhdLanguage(((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF))
                }
                "minf" -> {
                    val stbl = children(b, start, end).firstOrNull { it.first == "stbl" } ?: continue
                    val stsd = children(b, stbl.second, stbl.third).firstOrNull { it.first == "stsd" } ?: continue
                    // FullBox header and entry count, then the first sample entry.
                    entry = children(b, stsd.second + 8, stsd.third).firstOrNull()
                }
            }
        }
        val (fourcc, start, end) = entry ?: return null
        return when (handler) {
            "vide" -> {
                // VisualSampleEntry: 6 reserved, 2 index, 16 predefined, then width and height.
                val width = if (start + 28 <= end) (((b[start + 24].toInt() and 0xFF) shl 8) or (b[start + 25].toInt() and 0xFF)) else 0
                val height = if (start + 28 <= end) (((b[start + 26].toInt() and 0xFF) shl 8) or (b[start + 27].toInt() and 0xFF)) else 0
                val inner = if (start + 78 <= end) children(b, start + 78, end) else emptyList()
                val config = inner.firstOrNull { it.first in setOf("avcC", "hvcC", "av1C", "vpcC") }
                val private = config?.let { b.copyOfRange(it.second, it.third) }
                val codec = Codecs.fromMp4(fourcc)
                val tenBit = private != null && when (codec) {
                    Codecs.HEVC -> private.size > 1 && (private[1].toInt() and 0x1F) == 2
                    Codecs.AV1 -> private.size > 2 && (private[2].toInt() and 0x40) != 0
                    else -> false
                }
                VideoTrack(number, codec, width, height, private, tenBit = tenBit)
            }
            "soun" -> {
                // AudioSampleEntry: 8 reserved and index, 8 reserved, channels, sample size, 4, rate.
                val channels = if (start + 18 <= end) (((b[start + 16].toInt() and 0xFF) shl 8) or (b[start + 17].toInt() and 0xFF)) else 0
                val rate = if (start + 28 <= end) (((b[start + 24].toInt() and 0xFF) shl 8) or (b[start + 25].toInt() and 0xFF)) else 0
                var objectType = 0
                if (fourcc == "mp4a" && start + 28 <= end) {
                    children(b, start + 28, end).firstOrNull { it.first == "esds" }?.let { esds ->
                        objectType = esdsObjectType(b, esds.second + 4, esds.third)
                    }
                }
                AudioTrack(number, Codecs.fromMp4(fourcc, objectType), language, null, channels, rate)
            }
            "sbtl", "text", "subt" -> TextTrack(number, Codecs.fromMp4(fourcc), language)
            else -> null
        }
    }

    /** The DecoderConfigDescriptor's objectTypeIndication inside an esds, or 0. */
    private fun esdsObjectType(b: ByteArray, from: Int, to: Int): Int {
        var p = from
        fun tagLength(): Int {
            var len = 0
            repeat(4) {
                if (p >= to) return len
                val v = b[p++].toInt() and 0xFF
                len = (len shl 7) or (v and 0x7F)
                if (v and 0x80 == 0) return len
            }
            return len
        }
        if (p >= to || b[p].toInt() != 0x03) return 0
        p++
        tagLength()
        if (p + 3 > to) return 0
        val flags = b[p + 2].toInt() and 0xFF
        p += 3
        if (flags and 0x80 != 0) p += 2
        if (flags and 0x40 != 0) p += 1 + (b[p].toInt() and 0xFF)
        if (flags and 0x20 != 0) p += 2
        if (p >= to || b[p].toInt() != 0x04) return 0
        p++
        tagLength()
        return if (p < to) b[p].toInt() and 0xFF else 0
    }

    private fun mdhdLanguage(packed: Int): String? {
        if (packed == 0 || packed == 0x7FFF) return null
        val s = charArrayOf(
            ((packed shr 10 and 0x1F) + 0x60).toChar(),
            ((packed shr 5 and 0x1F) + 0x60).toChar(),
            ((packed and 0x1F) + 0x60).toChar(),
        ).concatToString()
        return s.takeIf { it != "und" && it.all { c -> c in 'a'..'z' } }
    }

    /** The boxes in `[from, to)`: their type and where their payload starts and ends. */
    fun children(b: ByteArray, from: Int, to: Int): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(b, p)
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (p + 16 > to) break
                size = ByteBuffer.wrap(b, p + 8, 8).long
                header = 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            if (size < header || p + size > to) break
            out += Triple(type, p + header, (p + size).toInt())
            p += size.toInt()
        }
        return out
    }

    fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
}

