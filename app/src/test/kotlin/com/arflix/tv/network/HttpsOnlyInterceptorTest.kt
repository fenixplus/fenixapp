package com.arflix.tv.network

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HttpsOnlyInterceptorTest {

    private var reachedNetwork = false

    // The second interceptor stands in for the network, so these tests never connect.
    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpsOnlyInterceptor())
        .addInterceptor { chain ->
            reachedNetwork = true
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody())
                .build()
        }
        .build()

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun `https request goes through`() {
        get("https://example.com/plugins.json").use { response ->
            assertEquals(200, response.code)
        }
        assertTrue(reachedNetwork)
    }

    @Test
    fun `plain http request is refused before reaching the network`() {
        val error = runCatching { get("http://example.com/plugins.json") }.exceptionOrNull()

        assertTrue(error is IOException)
        assertFalse(reachedNetwork)
    }

    @Test
    fun `plain http on a local address is refused too`() {
        val error = runCatching { get("http://192.168.0.10:8080/repo.json") }.exceptionOrNull()

        assertTrue(error is IOException)
        assertFalse(reachedNetwork)
    }
}
