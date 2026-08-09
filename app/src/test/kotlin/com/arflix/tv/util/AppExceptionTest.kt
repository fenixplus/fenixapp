package com.arflix.tv.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppExceptionTest {

    @Test
    fun `Network exception is retryable`() {
        assertThat(AppException.Network.NO_CONNECTION.isRetryable()).isTrue()
        assertThat(AppException.Network.TIMEOUT.isRetryable()).isTrue()
        assertThat(AppException.Network.SSL_ERROR.isRetryable()).isTrue()
    }

    @Test
    fun `Auth exception is not retryable`() {
        assertThat(AppException.Auth.SESSION_EXPIRED.isRetryable()).isFalse()
        assertThat(AppException.Auth.INVALID_CREDENTIALS.isRetryable()).isFalse()
        assertThat(AppException.Auth.ACCESS_DENIED.isRetryable()).isFalse()
    }

    @Test
    fun `Server 5xx errors are retryable`() {
        assertThat(AppException.Server.internalError().isRetryable()).isTrue()
        assertThat(AppException.Server.serviceUnavailable().isRetryable()).isTrue()
    }

    @Test
    fun `Server 4xx errors are not retryable`() {
        assertThat(AppException.Server.notFound().isRetryable()).isFalse()
        assertThat(AppException.Server("Bad request", 400).isRetryable()).isFalse()
    }

    @Test
    fun `Unknown exception is not retryable`() {
        assertThat(AppException.Unknown("weird error").isRetryable()).isFalse()
    }

    @Test
    fun `toSupportString includes error code`() {
        val exception = AppException.Network.NO_CONNECTION
        val supportString = exception.toSupportString()

        assertThat(supportString).contains("ERR_NO_CONNECTION")
        assertThat(supportString).contains("No internet connection")
    }

    @Test
    fun `Server exception includes HTTP code`() {
        val exception = AppException.Server.notFound("Movie")

        assertThat(exception.httpCode).isEqualTo(404)
        assertThat(exception.message).contains("Movie")
    }

    @Test
    fun `Network exceptions have distinct error codes`() {
        val codes = listOf(
            AppException.Network.NO_CONNECTION.errorCode,
            AppException.Network.TIMEOUT.errorCode,
            AppException.Network.SSL_ERROR.errorCode
        )

        assertThat(codes.distinct()).hasSize(3)
    }

    @Test
    fun `Auth exceptions have distinct error codes`() {
        val codes = listOf(
            AppException.Auth.SESSION_EXPIRED.errorCode,
            AppException.Auth.INVALID_CREDENTIALS.errorCode,
            AppException.Auth.ACCESS_DENIED.errorCode,
            AppException.Auth.TOKEN_INVALID.errorCode
        )

        assertThat(codes.distinct()).hasSize(4)
    }

    @Test
    fun `Custom Network exception preserves cause`() {
        val cause = RuntimeException("original")
        val exception = AppException.Network("Custom network error", cause)

        assertThat(exception.cause).isEqualTo(cause)
    }
}
