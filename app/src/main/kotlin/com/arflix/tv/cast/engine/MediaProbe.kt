package com.arflix.tv.cast.engine

import java.util.Locale

/** The container a file is in, as far as casting cares. */
enum class Container { Mp4, Matroska, WebM, MpegTs, Avi, Other }

/** One video track: its codec in [Codecs]' words and what the remuxer needs to rewrap it. */
class VideoTrack(
    val number: Int,
    val codec: String,
    val width: Int = 0,
    val height: Int = 0,
    val codecPrivate: ByteArray? = null,
    /** The frame duration the file declares, in nanoseconds, or 0. */
    val defaultDurationNs: Long = 0,
    /** HDR or 10 bit, which some receivers turn away even in a codec they know. */
    val tenBit: Boolean = false,
) {
    override fun toString() = "VideoTrack($number, $codec, ${width}x$height)"
}

class AudioTrack(
    val number: Int,
    val codec: String,
    val language: String? = null,
    val name: String? = null,
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val codecPrivate: ByteArray? = null,
    val isDefault: Boolean = false,
    val bitDepth: Int = 0,
    /** Matroska's CodecDelay in nanoseconds (Opus's pre-skip), or 0. */
    val codecDelayNs: Long = 0,
) {
    override fun toString() = "AudioTrack($number, $codec, ${channels}ch, $language)"
}

class TextTrack(
    val number: Int,
    val codec: String,
    val language: String? = null,
    val name: String? = null,
    val isDefault: Boolean = false,
    val forced: Boolean = false,
) {
    /** A format that can become WebVTT: SubRip, ASS or SSA, WebVTT. Pictures (PGS, VobSub) cannot. */
    val isText: Boolean get() = codec in Codecs.TEXT_SUBTITLES

    override fun toString() = "TextTrack($number, $codec, $language)"
}

/** What a file is, read from its first bytes (and its index, where that is elsewhere). */
class MediaProbe internal constructor(
    val container: Container,
    val durationMs: Long = 0,
    val video: VideoTrack? = null,
    val audio: List<AudioTrack> = emptyList(),
    val text: List<TextTrack> = emptyList(),
    /** Matroska's own reading of the head, kept for the remuxer so it does not read it twice. */
    internal val matroska: MkvHeader? = null,
) {
    override fun toString() = "MediaProbe($container, ${durationMs}ms, $video, $audio, $text)"

    companion object {
        /**
         * Reads [source]: Matroska and WebM from their head and Tracks, MP4 from its moov (at the
         * front, or after the media data). Anything else is known by [fileName] and [mimeType]
         * alone, with no tracks.
         */
        fun of(source: CastSource, fileName: String, mimeType: String = ""): MediaProbe {
            val head = runCatching { source.readAt(0, 16) }.getOrNull() ?: ByteArray(0)
            return when {
                Matroska.isMatroska(head) -> CastCursor(source).use { Matroska.probe(it) }
                Mp4Probe.isMp4(head) -> Mp4Probe.probe(source)
                else -> MediaProbe(guessContainer(fileName, mimeType, head))
            }
        }

        fun guessContainer(fileName: String, mimeType: String, head: ByteArray = ByteArray(0)): Container {
            val name = fileName.lowercase(Locale.ROOT)
            val mime = mimeType.lowercase(Locale.ROOT)
            return when {
                head.size >= 1 && head[0] == 0x47.toByte() && (name.endsWith(".ts") || name.endsWith(".m2ts") || "mp2t" in mime) -> Container.MpegTs
                name.endsWith(".webm") || "webm" in mime -> Container.WebM
                name.endsWith(".mkv") || "matroska" in mime -> Container.Matroska
                name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".mov") || mime == "video/mp4" || "quicktime" in mime -> Container.Mp4
                name.endsWith(".ts") || name.endsWith(".m2ts") || "mp2t" in mime -> Container.MpegTs
                name.endsWith(".avi") || "avi" in mime -> Container.Avi
                else -> Container.Other
            }
        }
    }
}

/** The codec names used across casting, whatever each container calls them. */
object Codecs {
    const val H264 = "h264"
    const val HEVC = "hevc"
    const val AV1 = "av1"
    const val VP8 = "vp8"
    const val VP9 = "vp9"
    const val MPEG4 = "mpeg4"
    const val MPEG2 = "mpeg2"
    const val VC1 = "vc1"

    const val AAC = "aac"
    const val MP3 = "mp3"
    const val MP2 = "mp2"
    const val OPUS = "opus"
    const val VORBIS = "vorbis"
    const val FLAC = "flac"
    const val AC3 = "ac3"
    const val EAC3 = "eac3"
    const val DTS = "dts"
    const val TRUEHD = "truehd"
    const val PCM = "pcm"
    const val ALAC = "alac"

    const val SRT = "srt"
    const val ASS = "ass"
    const val SSA = "ssa"
    const val VTT = "vtt"
    const val PGS = "pgs"
    const val VOBSUB = "vobsub"

    val TEXT_SUBTITLES = setOf(SRT, ASS, SSA, VTT)

    /** A Matroska CodecID as one of the names above, or "other:<id>". */
    fun fromMatroska(id: String): String = when {
        id == "V_MPEG4/ISO/AVC" -> H264
        id == "V_MPEGH/ISO/HEVC" -> HEVC
        id == "V_AV1" -> AV1
        id == "V_VP8" -> VP8
        id == "V_VP9" -> VP9
        id.startsWith("V_MPEG4/ISO/") || id == "V_MS/VFW/FOURCC" -> MPEG4
        id == "V_MPEG2" || id == "V_MPEG1" -> MPEG2
        id.startsWith("A_AAC") -> AAC
        id == "A_MPEG/L3" -> MP3
        id == "A_MPEG/L2" || id == "A_MPEG/L1" -> MP2
        id == "A_OPUS" -> OPUS
        id == "A_VORBIS" -> VORBIS
        id == "A_FLAC" -> FLAC
        id == "A_AC3" || id.startsWith("A_AC3/") -> AC3
        id == "A_EAC3" -> EAC3
        id.startsWith("A_DTS") -> DTS
        id == "A_TRUEHD" || id == "A_MLP" -> TRUEHD
        id.startsWith("A_PCM") -> PCM
        id == "A_ALAC" -> ALAC
        id == "S_TEXT/UTF8" || id == "S_TEXT/ASCII" -> SRT
        id == "S_TEXT/ASS" || id == "S_ASS" -> ASS
        id == "S_TEXT/SSA" || id == "S_SSA" -> SSA
        id == "S_TEXT/WEBVTT" || id == "D_WEBVTT/SUBTITLES" -> VTT
        id == "S_HDMV/PGS" -> PGS
        id == "S_VOBSUB" -> VOBSUB
        else -> "other:$id"
    }

    /** An MP4 sample entry's four letters as a name, or "other:<fourcc>". */
    fun fromMp4(fourcc: String, objectType: Int = 0): String = when (fourcc) {
        "avc1", "avc3" -> H264
        "hvc1", "hev1", "dvh1", "dvhe" -> HEVC
        "av01" -> AV1
        "vp08" -> VP8
        "vp09" -> VP9
        "mp4v" -> MPEG4
        "mp4a" -> when (objectType) {
            0x69, 0x6B -> MP3
            0xA5 -> AC3
            0xA6 -> EAC3
            0xA9, 0xAA, 0xAB, 0xAC -> DTS
            0xDD -> VORBIS
            else -> AAC
        }
        ".mp3" -> MP3
        "ac-3" -> AC3
        "ec-3" -> EAC3
        "Opus" -> OPUS
        "fLaC" -> FLAC
        "dtsc", "dtsh", "dtsl", "dtse" -> DTS
        "mlpa" -> TRUEHD
        "alac" -> ALAC
        "lpcm", "sowt", "twos", "ipcm" -> PCM
        "tx3g" -> SRT
        "wvtt" -> VTT
        else -> "other:$fourcc"
    }
}

