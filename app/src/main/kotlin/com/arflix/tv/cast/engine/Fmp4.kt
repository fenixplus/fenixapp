package com.arflix.tv.cast.engine

import java.io.ByteArrayOutputStream

/** One track of the fragmented MP4 the remuxer writes. */
internal class Fmp4Track(
    val id: Int,
    val video: Boolean,
    val timescale: Long,
    /** The whole sample entry box (avc1, hev1, av01, mp4a, ac-3, ec-3, Opus, fLaC). */
    val sampleEntry: ByteArray,
    val width: Int = 0,
    val height: Int = 0,
)

internal class Fmp4Sample(val data: ByteArray, val duration: Long, val ctsOffset: Long, val sync: Boolean)

internal class Fmp4Run(val trackId: Int, val baseDecodeTime: Long, val samples: List<Fmp4Sample>)

/**
 * Fragmented MP4 (ISO/IEC 14496-12), written for a receiver to play as it arrives: an init
 * segment (ftyp and a moov with empty sample tables and an mvex), then moof and mdat pairs, one
 * per stretch of the video. Timing is per track; composition offsets are signed (trun version 1),
 * so decode times can simply be the presentation times in order.
 */
internal object Fmp4 {

    fun init(tracks: List<Fmp4Track>, durationMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(box("ftyp") {
            fourcc("isom"); u32(0x200); fourcc("isom"); fourcc("iso6"); fourcc("mp41"); fourcc("dash")
        })
        out.write(box("moov") {
            raw(fullBox("mvhd", 0, 0) {
                u32(0); u32(0); u32(1000); u32(durationMs.coerceIn(0, 0xFFFFFFFFL))
                u32(0x00010000); u16(0x0100); u16(0); u32(0); u32(0)
                matrix()
                repeat(6) { u32(0) }
                u32((tracks.maxOfOrNull { it.id } ?: 0) + 1L)
            })
            for (t in tracks) raw(trak(t))
            raw(box("mvex") {
                raw(fullBox("mehd", 0, 0) { u32(durationMs.coerceIn(0, 0xFFFFFFFFL)) })
                for (t in tracks) raw(fullBox("trex", 0, 0) { u32(t.id.toLong()); u32(1); u32(0); u32(0); u32(0) })
            })
        })
        return out.toByteArray()
    }

    private fun trak(t: Fmp4Track): ByteArray = box("trak") {
        raw(fullBox("tkhd", 0, 0x000003) {
            u32(0); u32(0); u32(t.id.toLong()); u32(0); u32(0)
            u32(0); u32(0); u16(0); u16(if (t.video) 0 else 1)
            u16(if (t.video) 0 else 0x0100); u16(0)
            matrix()
            u32(t.width.toLong() shl 16); u32(t.height.toLong() shl 16)
        })
        raw(box("mdia") {
            raw(fullBox("mdhd", 0, 0) { u32(0); u32(0); u32(t.timescale); u32(0); u16(0x55C4); u16(0) })
            raw(fullBox("hdlr", 0, 0) {
                u32(0); fourcc(if (t.video) "vide" else "soun"); u32(0); u32(0); u32(0)
                raw((if (t.video) "VideoHandler" else "SoundHandler").toByteArray() + byteArrayOf(0))
            })
            raw(box("minf") {
                if (t.video) raw(fullBox("vmhd", 0, 1) { u16(0); u16(0); u16(0); u16(0) })
                else raw(fullBox("smhd", 0, 0) { u16(0); u16(0) })
                raw(box("dinf") { raw(fullBox("dref", 0, 0) { u32(1); raw(fullBox("url ", 0, 1) {}) }) })
                raw(box("stbl") {
                    raw(fullBox("stsd", 0, 0) { u32(1); raw(t.sampleEntry) })
                    raw(fullBox("stts", 0, 0) { u32(0) })
                    raw(fullBox("stsc", 0, 0) { u32(0) })
                    raw(fullBox("stsz", 0, 0) { u32(0); u32(0) })
                    raw(fullBox("stco", 0, 0) { u32(0) })
                })
            })
        })
    }

    /** A moof and its mdat: each run's samples, laid end to end in run order. */
    fun fragment(sequence: Int, runs: List<Fmp4Run>, videoTracks: Set<Int>): ByteArray {
        // The trafs are written twice: once to learn the moof's size, which the data offsets need.
        fun moof(offsets: IntArray): ByteArray = box("moof") {
            raw(fullBox("mfhd", 0, 0) { u32(sequence.toLong()) })
            runs.forEachIndexed { index, run ->
                val video = run.trackId in videoTracks
                raw(box("traf") {
                    raw(fullBox("tfhd", 0, 0x020000) { u32(run.trackId.toLong()) })
                    raw(fullBox("tfdt", 1, 0) { u64(run.baseDecodeTime) })
                    val flags = 0x000001 or 0x000100 or 0x000200 or 0x000400 or (if (video) 0x000800 else 0)
                    raw(fullBox("trun", 1, flags) {
                        u32(run.samples.size.toLong())
                        u32(offsets[index].toLong())
                        for (s in run.samples) {
                            u32(s.duration.coerceIn(0, 0xFFFFFFFFL))
                            u32(s.data.size.toLong())
                            u32(if (s.sync) 0x02000000L else 0x01010000L)
                            if (video) u32(s.ctsOffset and 0xFFFFFFFFL)
                        }
                    })
                })
            }
        }
        val placeholder = moof(IntArray(runs.size))
        val offsets = IntArray(runs.size)
        var at = placeholder.size + 8
        runs.forEachIndexed { i, run ->
            offsets[i] = at
            at += run.samples.sumOf { it.data.size }
        }
        val out = ByteArrayOutputStream(at)
        out.write(moof(offsets))
        val mdatSize = 8 + runs.sumOf { r -> r.samples.sumOf { it.data.size } }
        out.write(be32(mdatSize.toLong()))
        out.write("mdat".toByteArray(Charsets.ISO_8859_1))
        for (run in runs) for (s in run.samples) out.write(s.data)
        return out.toByteArray()
    }

    // ---- sample entries ---------------------------------------------------------------------

    fun visualEntry(fourcc: String, width: Int, height: Int, config: Pair<String, ByteArray>): ByteArray = box(fourcc) {
        repeat(6) { u8(0) }; u16(1)
        u16(0); u16(0); u32(0); u32(0); u32(0)
        u16(width); u16(height)
        u32(0x00480000); u32(0x00480000); u32(0); u16(1)
        repeat(32) { u8(0) }
        u16(0x0018); u16(0xFFFF)
        raw(box(config.first) { raw(config.second) })
    }

    fun audioEntry(fourcc: String, channels: Int, sampleRate: Int, config: ByteArray?): ByteArray = box(fourcc) {
        repeat(6) { u8(0) }; u16(1)
        u32(0); u32(0)
        u16(channels.coerceAtLeast(1)); u16(16); u16(0); u16(0)
        u32((sampleRate.toLong() and 0xFFFF) shl 16)
        if (config != null) raw(config)
    }

    /** esds for AAC (0x40) or MP3 (0x6B), with [dsi] as the decoder specific info. */
    fun esds(objectType: Int, dsi: ByteArray?): ByteArray = fullBox("esds", 0, 0) {
        val dsiDesc = dsi?.let { descriptor(0x05, it) } ?: ByteArray(0)
        val decoderConfig = descriptor(0x04, Builder().apply {
            u8(objectType); u8(0x15); u8(0); u16(0); u32(0); u32(0); raw(dsiDesc)
        }.bytes())
        val sl = descriptor(0x06, byteArrayOf(0x02))
        raw(descriptor(0x03, Builder().apply { u16(0); u8(0); raw(decoderConfig); raw(sl) }.bytes()))
    }

    private fun descriptor(tag: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = body.size
        out.write(0x80 or ((n shr 21) and 0x7F)); out.write(0x80 or ((n shr 14) and 0x7F)); out.write(0x80 or ((n shr 7) and 0x7F)); out.write(n and 0x7F)
        out.write(body)
        return out.toByteArray()
    }

    fun box(type: String, body: Builder.() -> Unit): ByteArray {
        val b = Builder().apply(body).bytes()
        return be32(8L + b.size) + type.toByteArray(Charsets.ISO_8859_1) + b
    }

    fun fullBox(type: String, version: Int, flags: Int, body: Builder.() -> Unit): ByteArray = box(type) {
        u8(version); u8(flags shr 16); u8(flags shr 8); u8(flags)
        body()
    }

    private fun be32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    class Builder {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }
        fun u32(v: Long) = out.write(be32(v))
        fun u64(v: Long) { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }
        fun fourcc(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun raw(b: ByteArray) = out.write(b)
        fun matrix() {
            u32(0x00010000); u32(0); u32(0)
            u32(0); u32(0x00010000); u32(0)
            u32(0); u32(0); u32(0x40000000)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }
}

