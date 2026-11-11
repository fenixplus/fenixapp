package com.arflix.tv.cast.engine

import java.io.IOException
import java.util.Locale

/** A soundtrack's MP4 sample entry, its clock, how long each frame plays, and its RFC 6381 name. */
internal class AudioEntry(
    val entry: ByteArray,
    val timescale: Long,
    val samplesPerFrame: (ByteArray) -> Int,
    /** "mp4a.40.2", "ac-3", "ec-3", "opus", "flac", for an HLS playlist's CODECS. */
    val codecs: String,
    val channels: Int,
)

/**
 * The MP4 boxes a Matroska track becomes, for both ways a Matroska file reaches a receiver: the
 * one rewrapped stream ([MkvRemuxer]) and the HLS segments ([MkvHls]).
 */
internal object Mp4Entries {

    fun video(t: MkvTrack): ByteArray {
        val private = t.codecPrivate ?: throw IOException("Video track has no configuration")
        return when (Codecs.fromMatroska(t.codecId)) {
            Codecs.H264 -> Fmp4.visualEntry("avc1", t.width, t.height, "avcC" to private)
            Codecs.HEVC -> Fmp4.visualEntry("hev1", t.width, t.height, "hvcC" to private)
            Codecs.AV1 -> Fmp4.visualEntry("av01", t.width, t.height, "av1C" to private)
            else -> throw IOException("Cannot rewrap ${t.codecId}")
        }
    }

    /** The picture's codec as HLS and MSE name it, read from its configuration record. */
    fun videoCodecs(t: MkvTrack): String {
        val p = t.codecPrivate ?: ByteArray(0)
        return when (Codecs.fromMatroska(t.codecId)) {
            Codecs.H264 -> if (p.size >= 4) String.format(Locale.ROOT, "avc1.%02X%02X%02X", p[1].toInt() and 0xFF, p[2].toInt() and 0xFF, p[3].toInt() and 0xFF) else "avc1.640028"
            Codecs.HEVC -> hevcCodecs(p)
            Codecs.AV1 -> av1Codecs(p, t.colourBits)
            else -> "unknown"
        }
    }

    /**
     * ISO/IEC 14496-15 annex E: hev1.<space><profile>.<compatibility, bit reversed>.<tier><level>
     * then the constraint bytes, trailing zero bytes dropped.
     */
    fun hevcCodecs(p: ByteArray): String {
        if (p.size < 13) return "hev1.1.6.L120.90"
        val b1 = p[1].toInt() and 0xFF
        val space = when (b1 shr 6) {
            1 -> "A"
            2 -> "B"
            3 -> "C"
            else -> ""
        }
        val tier = if ((b1 shr 5) and 1 == 1) "H" else "L"
        val profile = b1 and 0x1F
        var compat = 0L
        for (i in 2..5) compat = (compat shl 8) or (p[i].toLong() and 0xFF)
        val reversed = java.lang.Integer.reverse(compat.toInt()).toLong() and 0xFFFFFFFFL
        val constraints = (6..11).map { p[it].toInt() and 0xFF }.dropLastWhile { it == 0 }
        val level = p[12].toInt() and 0xFF
        val sb = StringBuilder("hev1.").append(space).append(profile).append('.')
            .append(java.lang.Long.toHexString(reversed).uppercase(Locale.ROOT)).append('.')
            .append(tier).append(level)
        for (c in constraints) sb.append('.').append(String.format(Locale.ROOT, "%02X", c))
        return sb.toString()
    }

    /** av01.<profile>.<level><tier>.<bit depth>, from an av1C record. */
    fun av1Codecs(p: ByteArray, colourBits: Int): String {
        if (p.size < 3) return "av01.0.08M.08"
        val profile = (p[1].toInt() shr 5) and 0x07
        val level = p[1].toInt() and 0x1F
        val tier = if ((p[2].toInt() shr 7) and 1 == 1) "H" else "M"
        val high = (p[2].toInt() shr 6) and 1 == 1
        val twelve = (p[2].toInt() shr 5) and 1 == 1
        val depth = when {
            high && twelve -> 12
            high -> 10
            colourBits > 8 -> colourBits
            else -> 8
        }
        return String.format(Locale.ROOT, "av01.%d.%02d%s.%02d", profile, level, tier, depth)
    }

    /** The soundtrack passed through as it is, or null when MP4 has no place for its codec. */
    fun audio(t: MkvTrack, first: ByteArray?): AudioEntry? {
        val rate = t.sampleRate.takeIf { it > 0 } ?: 48000
        return when (Codecs.fromMatroska(t.codecId)) {
            Codecs.AAC -> {
                val config = AudioFrames.aacConfig(t.codecPrivate, rate, t.channels)
                val core = AudioFrames.aacCoreRate(config) ?: rate
                // HE-AAC: the stream's rate is twice the core's, and so is each frame.
                val sbr = rate >= core * 2
                val objectType = (config[0].toInt() and 0xFF) shr 3
                AudioEntry(
                    Fmp4.audioEntry("mp4a", t.channels, rate, Fmp4.esds(0x40, config)),
                    rate.toLong(),
                    { if (sbr) 2048 else 1024 },
                    "mp4a.40.${if (objectType in 1..30) objectType else 2}",
                    t.channels,
                )
            }
            Codecs.MP3 -> AudioEntry(Fmp4.audioEntry("mp4a", t.channels, rate, Fmp4.esds(0x6B, null)), rate.toLong(), AudioFrames::mp3Samples, "mp4a.40.34", t.channels)
            Codecs.AC3 -> {
                val dac3 = first?.let(AudioFrames::dac3) ?: throw IOException("No AC-3 frame to describe")
                AudioEntry(Fmp4.audioEntry("ac-3", t.channels, rate, Fmp4.box("dac3") { raw(dac3) }), rate.toLong(), { 1536 }, "ac-3", t.channels)
            }
            Codecs.EAC3 -> {
                val dec3 = first?.let(AudioFrames::dec3) ?: throw IOException("No E-AC-3 frame to describe")
                AudioEntry(Fmp4.audioEntry("ec-3", t.channels, rate, Fmp4.box("dec3") { raw(dec3) }), rate.toLong(), AudioFrames::eac3Samples, "ec-3", t.channels)
            }
            Codecs.OPUS -> AudioEntry(
                Fmp4.audioEntry("Opus", t.channels, 48000, Fmp4.box("dOps") { raw(AudioFrames.dOps(t.codecPrivate, t.channels, rate)) }),
                48000,
                AudioFrames::opusSamples,
                "opus",
                t.channels,
            )
            Codecs.FLAC -> {
                val blocks = AudioFrames.dfLa(t.codecPrivate) ?: throw IOException("FLAC track has no STREAMINFO")
                AudioEntry(Fmp4.audioEntry("fLaC", t.channels, rate, Fmp4.fullBox("dfLa", 0, 0) { raw(blocks) }), rate.toLong(), AudioFrames::flacSamples, "flac", t.channels)
            }
            else -> null
        }
    }

    /** The AAC an [AudioConverter] makes. */
    fun converted(aac: AacOutput): AudioEntry =
        AudioEntry(Fmp4.audioEntry("mp4a", aac.channels, aac.sampleRate, Fmp4.esds(0x40, aac.config)), aac.sampleRate.toLong(), { 1024 }, "mp4a.40.2", aac.channels)

    /** [t] as the [AudioTrack] a converter is started with. */
    fun audioTrack(t: MkvTrack): AudioTrack =
        AudioTrack(t.number, Codecs.fromMatroska(t.codecId), t.language, t.name, t.channels, t.sampleRate, t.codecPrivate, t.isDefault, t.bitDepth, t.codecDelayNs)
}

