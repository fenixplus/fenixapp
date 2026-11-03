package com.arflix.tv.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramStreamAccessTest {

    private fun tokenOf(path: String): String = path.removePrefix("/").substringBefore("/file/")

    @Test
    fun `registered file is served with the token from its own url`() {
        val access = TelegramStreamAccess()
        val path = access.pathFor(42)

        assertTrue(access.isAllowed(tokenOf(path), 42))
    }

    @Test
    fun `file ids that were never turned into stream urls are refused`() {
        val access = TelegramStreamAccess()
        val token = tokenOf(access.pathFor(42))

        assertFalse(access.isAllowed(token, 43))
        assertFalse(access.isAllowed(token, 1))
    }

    @Test
    fun `wrong or missing token is refused`() {
        val access = TelegramStreamAccess()
        val token = tokenOf(access.pathFor(42))

        assertFalse(access.isAllowed(null, 42))
        assertFalse(access.isAllowed("", 42))
        assertFalse(access.isAllowed(token.dropLast(1), 42))
        assertFalse(access.isAllowed(token.reversed(), 42))
    }

    @Test
    fun `each proxy instance gets a different 256-bit token`() {
        val first = tokenOf(TelegramStreamAccess().pathFor(1))
        val second = tokenOf(TelegramStreamAccess().pathFor(1))

        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(first != second)
    }

    @Test
    fun `path keeps the file segment at the end`() {
        val path = TelegramStreamAccess(token = "abc").pathFor(7)

        assertEquals("/abc/file/7", path)
    }

    @Test
    fun `no range header selects the whole file`() {
        assertEquals(ByteRange(0, 999), resolveByteRange(null, 1000))
        assertEquals(ByteRange(0, 999), resolveByteRange("", 1000))
    }

    @Test
    fun `closed and open ranges are resolved`() {
        assertEquals(ByteRange(0, 499), resolveByteRange("bytes=0-499", 1000))
        assertEquals(ByteRange(500, 999), resolveByteRange("bytes=500-", 1000))
        assertEquals(500L, resolveByteRange("bytes=500-999", 1000)?.length)
    }

    @Test
    fun `end beyond the file is clamped to the last byte`() {
        assertEquals(ByteRange(900, 999), resolveByteRange("bytes=900-5000", 1000))
    }

    @Test
    fun `suffix range selects the last bytes`() {
        assertEquals(ByteRange(900, 999), resolveByteRange("bytes=-100", 1000))
        assertEquals(ByteRange(0, 999), resolveByteRange("bytes=-5000", 1000))
    }

    @Test
    fun `range starting after the end of the file is unsatisfiable`() {
        assertNull(resolveByteRange("bytes=1000-", 1000))
        assertNull(resolveByteRange("bytes=2000-3000", 1000))
        assertNull(resolveByteRange("bytes=-0", 1000))
    }

    @Test
    fun `malformed headers fall back to the whole file`() {
        assertEquals(ByteRange(0, 999), resolveByteRange("bytes=500-100", 1000))
        assertEquals(ByteRange(0, 999), resolveByteRange("bytes=abc-def", 1000))
        assertEquals(ByteRange(0, 999), resolveByteRange("bytes=--5", 1000))
        assertEquals(ByteRange(0, 999), resolveByteRange("bytes=0-1,5-9", 1000))
        assertEquals(ByteRange(0, 999), resolveByteRange("items=0-10", 1000))
        assertEquals(ByteRange(0, 999), resolveByteRange("bytes=99999999999999999999-", 1000))
    }

    @Test
    fun `empty file has no satisfiable range`() {
        assertNull(resolveByteRange(null, 0))
    }
}
