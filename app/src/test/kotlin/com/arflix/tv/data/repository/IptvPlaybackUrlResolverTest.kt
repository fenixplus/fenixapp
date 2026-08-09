package com.arflix.tv.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class IptvPlaybackUrlResolverTest {

    @Test
    fun `extensionless slug live URL resolves redirect and HLS type`() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls.incrementAndGet()
                val finalRequest = chain.request().newBuilder()
                    .url("http://provider.test/hls/animal-planet/index.m3u8")
                    .build()
                Response.Builder()
                    .request(finalRequest)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/x-mpegURL")
                    .body("".toResponseBody("application/x-mpegURL".toMediaType()))
                    .build()
            }
            .build()
        val resolver = IptvPlaybackUrlResolver(client)

        val first = resolver.resolve(
            rawUrl = "http://provider.test/live/user/pass/animal-planet",
            headers = emptyMap(),
        )
        val cached = resolver.resolve(
            rawUrl = "http://provider.test/live/user/pass/animal-planet",
            headers = emptyMap(),
        )

        assertThat(first.url).isEqualTo("http://provider.test/hls/animal-planet/index.m3u8")
        assertThat(first.isHls).isTrue()
        assertThat(cached).isEqualTo(first)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `HLS content type is retained when redirect target has no extension`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/vnd.apple.mpegurl")
                    .body("".toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                    .build()
            }
            .build()
        val resolver = IptvPlaybackUrlResolver(client)

        val target = resolver.resolve(
            rawUrl = "http://provider.test/live/user/pass/channel-slug",
            headers = emptyMap(),
        )

        assertThat(target.url).isEqualTo("http://provider.test/live/user/pass/channel-slug")
        assertThat(target.isHls).isTrue()
    }

    @Test
    fun `GET probe detects HLS body when provider rejects HEAD`() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val call = calls.incrementAndGet()
                if (chain.request().method == "HEAD") {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(405)
                        .message("Method Not Allowed")
                        .header("Content-Type", "text/html")
                        .body("".toResponseBody("text/html".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/plain")
                        .body("#EXTM3U\n#EXT-X-VERSION:3".toResponseBody("text/plain".toMediaType()))
                        .build()
                }.also {
                    assertThat(call).isAtMost(2)
                }
            }
            .build()
        val resolver = IptvPlaybackUrlResolver(client)

        val target = resolver.resolve(
            rawUrl = "http://provider.test/live/user/pass/channel-slug",
            headers = emptyMap(),
        )

        assertThat(target.isHls).isTrue()
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `known direct and adaptive URLs skip redirect probe`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { error("Redirect probe should not run") }
            .build()
        val resolver = IptvPlaybackUrlResolver(client)

        val numericXtream = resolver.resolve(
            rawUrl = "http://provider.test/live/user/pass/12345",
            headers = emptyMap(),
        )
        val transportStream = resolver.resolve(
            rawUrl = "http://provider.test/live/user/pass/channel.ts",
            headers = emptyMap(),
        )
        val hls = resolver.resolve(
            rawUrl = "http://provider.test/hls/channel/index.m3u8",
            headers = emptyMap(),
        )

        assertThat(numericXtream.isHls).isFalse()
        assertThat(transportStream.isHls).isFalse()
        assertThat(hls.isHls).isTrue()
    }
}
