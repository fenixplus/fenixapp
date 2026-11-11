package com.arflix.tv.cast.engine

/**
 * What the MP4 boxes for each soundtrack need from the codec, and how long each frame plays: read
 * from Matroska's CodecPrivate and from the first frames themselves.
 */
internal object AudioFrames {

    private val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** An AAC AudioSpecificConfig: the track's own, or an AAC-LC one made from its rate and channels. */
    fun aacConfig(private: ByteArray?, sampleRate: Int, channels: Int): ByteArray {
        if (private != null && private.size >= 2) return private
        val index = AAC_RATES.indexOf(sampleRate).takeIf { it >= 0 } ?: 4
        val config = channels.coerceIn(1, 7)
        val objectType = 2
        return byteArrayOf(
            ((objectType shl 3) or (index shr 1)).toByte(),
            (((index and 1) shl 7) or (config shl 3)).toByte(),
        )
    }

    /** The core sample rate an AudioSpecificConfig gives; with SBR the output is twice it. */
    fun aacCoreRate(config: ByteArray): Int? {
        if (config.size < 2) return null
        val index = ((config[0].toInt() and 0x07) shl 1) or ((config[1].toInt() and 0x80) shr 7)
        return AAC_RATES.getOrNull(index)
    }

    /** Samples per frame of an MPEG audio frame (MP3): 1152 for MPEG-1 Layer III, 576 for 2 and 2.5. */
    fun mp3Samples(frame: ByteArray): Int {
        if (frame.size < 4 || (frame[0].toInt() and 0xFF) != 0xFF || (frame[1].toInt() and 0xE0) != 0xE0) return 1152
        val version = (frame[1].toInt() shr 3) and 0x03
        val layer = (frame[1].toInt() shr 1) and 0x03
        return when {
            layer == 3 -> 384 // Layer I
            layer == 2 -> 1152 // Layer II
            version == 3 -> 1152
            else -> 576
        }
    }

    /** The `dac3` box payload from the first AC-3 frame, or null when it is not one. */
    fun dac3(frame: ByteArray): ByteArray? {
        if (frame.size < 8 || (frame[0].toInt() and 0xFF) != 0x0B || (frame[1].toInt() and 0xFF) != 0x77) return null
        val r = BitReader(frame, 4)
        val fscod = r.bits(2)
        val frmsizecod = r.bits(6)
        val bsid = r.bits(5)
        val bsmod = r.bits(3)
        val acmod = r.bits(3)
        if ((acmod and 0x01) != 0 && acmod != 1) r.bits(2) // cmixlev
        if ((acmod and 0x04) != 0) r.bits(2) // surmixlev
        if (acmod == 2) r.bits(2) // dsurmod
        val lfeon = r.bits(1)
        val w = BitWriter()
        w.put(fscod, 2); w.put(bsid, 5); w.put(bsmod, 3); w.put(acmod, 3); w.put(lfeon, 1)
        w.put(frmsizecod shr 1, 5); w.put(0, 5)
        return w.bytes()
    }

    /** One E-AC-3 syncframe header's fields. */
    class Eac3Header(val strmtyp: Int, val substreamId: Int, val frameBytes: Int, val fscod: Int, val sampleRate: Int, val blocks: Int, val acmod: Int, val lfeon: Int, val bsid: Int)

    fun eac3Header(b: ByteArray, at: Int = 0): Eac3Header? {
        if (b.size < at + 6 || (b[at].toInt() and 0xFF) != 0x0B || (b[at + 1].toInt() and 0xFF) != 0x77) return null
        val r = BitReader(b, at + 2)
        val strmtyp = r.bits(2)
        val substreamId = r.bits(3)
        val frmsiz = r.bits(11)
        val fscod = r.bits(2)
        val numblkscod: Int
        val rate: Int
        if (fscod == 3) {
            val fscod2 = r.bits(2)
            numblkscod = 3
            rate = intArrayOf(24000, 22050, 16000, 0).getOrElse(fscod2) { 0 }
        } else {
            numblkscod = r.bits(2)
            rate = intArrayOf(48000, 44100, 32000).getOrElse(fscod) { 48000 }
        }
        val acmod = r.bits(3)
        val lfeon = r.bits(1)
        val bsid = r.bits(5)
        val blocks = intArrayOf(1, 2, 3, 6)[numblkscod]
        return Eac3Header(strmtyp, substreamId, (frmsiz + 1) * 2, fscod, rate, blocks, acmod, lfeon, bsid)
    }

    /** The `dec3` box payload from the first E-AC-3 access unit. */
    fun dec3(frame: ByteArray): ByteArray? {
        val first = eac3Header(frame) ?: return null
        var dependents = 0
        var at = first.frameBytes
        while (at < frame.size) {
            val h = eac3Header(frame, at) ?: break
            if (h.strmtyp == 1) dependents++
            if (h.frameBytes <= 0) break
            at += h.frameBytes
        }
        val kbps = if (first.sampleRate > 0) (frame.size.toLong() * 8 * first.sampleRate / (first.blocks * 256) / 1000).toInt() else 0
        val fscod = if (first.fscod == 3) 3 else first.fscod
        val w = BitWriter()
        w.put(kbps.coerceIn(0, 8191), 13); w.put(0, 3) // num_ind_sub - 1
        w.put(fscod, 2); w.put(first.bsid, 5); w.put(0, 1); w.put(0, 1); w.put(0, 3)
        w.put(first.acmod, 3); w.put(first.lfeon, 1); w.put(0, 3)
        w.put(dependents.coerceAtMost(15), 4)
        if (dependents > 0) w.put(0, 9) else w.put(0, 1)
        return w.bytes()
    }

    /** Samples in one E-AC-3 access unit: every independent syncframe of substream 0. */
    fun eac3Samples(frame: ByteArray): Int {
        var at = 0
        var samples = 0
        while (at < frame.size) {
            val h = eac3Header(frame, at) ?: break
            if (h.strmtyp != 1 && h.substreamId == 0) samples += h.blocks * 256
            if (h.frameBytes <= 0) break
            at += h.frameBytes
        }
        return if (samples > 0) samples else 1536
    }

    /** `dOps` from an `OpusHead` (little endian) as the MP4 binding has it (big endian). */
    fun dOps(opusHead: ByteArray?, channels: Int, sampleRate: Int): ByteArray {
        val h = opusHead
        if (h != null && h.size >= 19 && String(h, 0, 8, Charsets.ISO_8859_1) == "OpusHead") {
            val out = java.io.ByteArrayOutputStream()
            out.write(0) // version
            out.write(h[9].toInt()) // channel count
            out.write(h[11].toInt()); out.write(h[10].toInt()) // pre-skip
            out.write(h[15].toInt()); out.write(h[14].toInt()); out.write(h[13].toInt()); out.write(h[12].toInt()) // rate
            out.write(h[17].toInt()); out.write(h[16].toInt()) // gain
            val family = h[18].toInt() and 0xFF
            out.write(family)
            if (family != 0 && h.size > 19) out.write(h, 19, h.size - 19)
            return out.toByteArray()
        }
        val rate = if (sampleRate > 0) sampleRate else 48000
        return byteArrayOf(
            0, channels.coerceIn(1, 2).toByte(), 0x01, 0x38,
            (rate ushr 24).toByte(), (rate ushr 16).toByte(), (rate ushr 8).toByte(), rate.toByte(),
            0, 0, 0,
        )
    }

    /** Samples (at 48 kHz) in one Opus packet, from its TOC byte. */
    fun opusSamples(packet: ByteArray): Int {
        if (packet.isEmpty()) return 960
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frame = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
            config < 16 -> intArrayOf(480, 960)[config and 1]
            else -> intArrayOf(120, 240, 480, 960)[config and 3]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size > 1) packet[1].toInt() and 0x3F else 1
        }
        return frame * frames
    }

    /** `dfLa`'s metadata blocks: the CodecPrivate past its "fLaC" marker, the last block flagged. */
    fun dfLa(private: ByteArray?): ByteArray? {
        val p = private ?: return null
        val start = if (p.size >= 4 && String(p, 0, 4, Charsets.ISO_8859_1) == "fLaC") 4 else 0
        if (p.size < start + 4 + 34) return null
        // Only the STREAMINFO block, marked last, which is all the binding requires.
        val out = ByteArray(4 + 34)
        System.arraycopy(p, start, out, 0, 4 + 34)
        out[0] = (0x80 or 0).toByte()
        return out
    }

    /** Samples in one FLAC frame, from the block size code in its header. */
    fun flacSamples(frame: ByteArray): Int {
        if (frame.size < 4) return 4096
        val code = (frame[2].toInt() shr 4) and 0x0F
        return when (code) {
            1 -> 192
            in 2..5 -> 576 shl (code - 2)
            in 8..15 -> 256 shl (code - 8)
            // 6 and 7 carry the size after the frame number, which is UTF-8 coded.
            6, 7 -> {
                var p = 4
                val first = frame[p].toInt() and 0xFF
                p += when {
                    first < 0x80 -> 1
                    first < 0xE0 -> 2
                    first < 0xF0 -> 3
                    first < 0xF8 -> 4
                    first < 0xFC -> 5
                    first < 0xFE -> 6
                    else -> 7
                }
                if (code == 6) (frame.getOrNull(p)?.toInt()?.and(0xFF) ?: 4095) + 1
                else (((frame.getOrNull(p)?.toInt() ?: 0) and 0xFF) shl 8 or ((frame.getOrNull(p + 1)?.toInt() ?: 0) and 0xFF)) + 1
            }
            else -> 4096
        }
    }

    class BitReader(private val b: ByteArray, startByte: Int) {
        private var bit = startByte * 8

        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = bit ushr 3
                val value = if (byte < b.size) (b[byte].toInt() shr (7 - (bit and 7))) and 1 else 0
                v = (v shl 1) or value
                bit++
            }
            return v
        }
    }

    class BitWriter {
        private val out = java.io.ByteArrayOutputStream()
        private var current = 0
        private var count = 0

        fun put(value: Int, n: Int) {
            for (i in n - 1 downTo 0) {
                current = (current shl 1) or ((value shr i) and 1)
                count++
                if (count == 8) {
                    out.write(current)
                    current = 0
                    count = 0
                }
            }
        }

        fun bytes(): ByteArray {
            if (count > 0) {
                out.write(current shl (8 - count))
                current = 0
                count = 0
            }
            return out.toByteArray()
        }
    }
}

