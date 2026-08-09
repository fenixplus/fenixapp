package com.arflix.tv.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

class ResultTest {

    @Test
    fun `Success contains data`() {
        val result: Result<String> = Result.success("hello")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.isError).isFalse()
        assertThat(result.getOrNull()).isEqualTo("hello")
        assertThat(result.exceptionOrNull()).isNull()
    }

    @Test
    fun `Error contains exception`() {
        val exception = AppException.Network.NO_CONNECTION
        val result: Result<String> = Result.error(exception)

        assertThat(result.isSuccess).isFalse()
        assertThat(result.isError).isTrue()
        assertThat(result.getOrNull()).isNull()
        assertThat(result.exceptionOrNull()).isEqualTo(exception)
    }

    @Test
    fun `getOrDefault returns default on error`() {
        val result: Result<Int> = Result.error(AppException.Network.TIMEOUT)

        assertThat(result.getOrDefault(42)).isEqualTo(42)
    }

    @Test
    fun `getOrDefault returns value on success`() {
        val result: Result<Int> = Result.success(100)

        assertThat(result.getOrDefault(42)).isEqualTo(100)
    }

    @Test
    fun `map transforms success value`() {
        val result: Result<Int> = Result.success(5)
        val mapped = result.map { it * 2 }

        assertThat(mapped.getOrNull()).isEqualTo(10)
    }

    @Test
    fun `map preserves error`() {
        val exception = AppException.Auth.SESSION_EXPIRED
        val result: Result<Int> = Result.error(exception)
        val mapped = result.map { it * 2 }

        assertThat(mapped.isError).isTrue()
        assertThat(mapped.exceptionOrNull()).isEqualTo(exception)
    }

    @Test
    fun `flatMap chains operations`() {
        val result: Result<Int> = Result.success(5)
        val chained = result.flatMap { value ->
            if (value > 0) Result.success(value.toString())
            else Result.error(AppException.Unknown("Invalid value"))
        }

        assertThat(chained.getOrNull()).isEqualTo("5")
    }

    @Test
    fun `flatMap short-circuits on error`() {
        val exception = AppException.Network.NO_CONNECTION
        val result: Result<Int> = Result.error(exception)
        var called = false

        val chained = result.flatMap {
            called = true
            Result.success(it.toString())
        }

        assertThat(called).isFalse()
        assertThat(chained.exceptionOrNull()).isEqualTo(exception)
    }

    @Test
    fun `onSuccess executes on success`() {
        var captured: String? = null
        val result: Result<String> = Result.success("test")

        result.onSuccess { captured = it }

        assertThat(captured).isEqualTo("test")
    }

    @Test
    fun `onSuccess skips on error`() {
        var captured: String? = null
        val result: Result<String> = Result.error(AppException.Network.TIMEOUT)

        result.onSuccess { captured = it }

        assertThat(captured).isNull()
    }

    @Test
    fun `onError executes on error`() {
        var captured: AppException? = null
        val exception = AppException.Auth.INVALID_CREDENTIALS
        val result: Result<String> = Result.error(exception)

        result.onError { captured = it }

        assertThat(captured).isEqualTo(exception)
    }

    @Test
    fun `onError skips on success`() {
        var captured: AppException? = null
        val result: Result<String> = Result.success("test")

        result.onError { captured = it }

        assertThat(captured).isNull()
    }

    @Test
    fun `runCatching catches exceptions`() = runTest {
        val result = com.arflix.tv.util.runCatching {
            throw IOException("Network error")
        }

        assertThat(result.isError).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(AppException.Unknown::class.java)
    }

    @Test
    fun `runCatching returns success on no exception`() = runTest {
        val result = com.arflix.tv.util.runCatching {
            "hello"
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("hello")
    }

    @Test
    fun `IOException converts to Network exception`() {
        val throwable: Throwable = IOException("test")
        val appException = throwable.toAppException()

        assertThat(appException).isInstanceOf(AppException.Unknown::class.java)
    }

    @Test
    fun `SocketTimeoutException converts to timeout`() {
        val throwable: Throwable = SocketTimeoutException("timeout")
        val appException = throwable.toAppException()

        assertThat(appException).isInstanceOf(AppException.Network::class.java)
        assertThat(appException.errorCode).isEqualTo("ERR_NETWORK")
    }

    @Test
    fun `SSLException converts to SSL error`() {
        val throwable: Throwable = SSLException("ssl failed")
        val appException = throwable.toAppException()

        assertThat(appException).isInstanceOf(AppException.Network::class.java)
        assertThat(appException.errorCode).isEqualTo("ERR_NETWORK")
    }

    @Test
    fun `Unknown exception converts to Unknown`() {
        val throwable: Throwable = IllegalStateException("weird error")
        val appException = throwable.toAppException()

        assertThat(appException).isInstanceOf(AppException.Unknown::class.java)
    }
}
