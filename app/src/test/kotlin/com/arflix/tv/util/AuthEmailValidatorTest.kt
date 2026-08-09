package com.arflix.tv.util

import com.arflix.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthEmailValidatorTest {

    @Test
    fun `new disposable domains require a real email`() {
        val domains = listOf(
            "trashmail.com",
            "trashmail.at",
            "trashmail.io",
            "sharklasers.com",
            "throwam.com",
            "dispostable.com",
            "mailnull.com",
            "spamgourmet.com",
            "discard.email",
            "fakeinbox.com",
            "spamherelots.com",
            "maildrop.cc"
        )

        domains.forEach { domain ->
            assertEquals(
                domain,
                R.string.auth_email_real_required,
                AuthEmailValidator.validate("user@$domain")
            )
        }
    }

    @Test
    fun `normal public email domain is accepted`() {
        assertNull(AuthEmailValidator.validate("user@gmail.com"))
    }

    @Test
    fun `disposable domain matching normalizes case and whitespace`() {
        assertEquals(
            R.string.auth_email_real_required,
            AuthEmailValidator.validate("  User@TrashMail.COM  ")
        )
    }

    @Test
    fun `existing disposable domain remains rejected`() {
        assertEquals(
            R.string.auth_email_real_required,
            AuthEmailValidator.validate("user@mailinator.com")
        )
    }

    @Test
    fun `malformed email remains invalid`() {
        assertEquals(
            R.string.auth_email_invalid,
            AuthEmailValidator.validate("not-an-email")
        )
    }

    @Test
    fun `disposable domain is accepted when rejection is disabled`() {
        assertNull(
            AuthEmailValidator.validate(
                email = "user@trashmail.com",
                rejectDisposable = false
            )
        )
    }
}
