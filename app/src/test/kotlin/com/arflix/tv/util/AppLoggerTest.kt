package com.arflix.tv.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLoggerTest {

    @Test
    fun `sanitizeEmail masks email correctly`() {
        val email = "user@example.com"
        val sanitized = email.sanitizeEmail()

        assertThat(sanitized).isEqualTo("u***@***.com")
    }

    @Test
    fun `sanitizeEmail handles short emails`() {
        val email = "a@b.co"
        val sanitized = email.sanitizeEmail()

        assertThat(sanitized).isEqualTo("a***@***.co")
    }

    @Test
    fun `sanitizeEmail handles invalid format`() {
        val invalid = "notanemail"
        val sanitized = invalid.sanitizeEmail()

        assertThat(sanitized).isEqualTo("[EMAIL]")
    }

    @Test
    fun `hash produces consistent short hash`() {
        val input = "user-123-uuid"
        val hash1 = input.hash()
        val hash2 = input.hash()

        assertThat(hash1).isEqualTo(hash2)
        assertThat(hash1.length).isEqualTo(12)  // 6 bytes = 12 hex chars
    }

    @Test
    fun `hash produces different results for different inputs`() {
        val hash1 = "user1".hash()
        val hash2 = "user2".hash()

        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `maskToken shows first 4 chars`() {
        val token = "sk-12345678901234567890"
        val masked = token.maskToken()

        assertThat(masked).isEqualTo("sk-1***")
    }

    @Test
    fun `maskToken handles short tokens`() {
        val token = "abc"
        val masked = token.maskToken()

        assertThat(masked).isEqualTo("***")
    }

    @Test
    fun `maskToken handles exactly 4 char token`() {
        val token = "abcd"
        val masked = token.maskToken()

        assertThat(masked).isEqualTo("***")
    }

    @Test
    fun `maskToken handles 5 char token`() {
        val token = "abcde"
        val masked = token.maskToken()

        assertThat(masked).isEqualTo("abcd***")
    }
}
