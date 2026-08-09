package com.arflix.tv.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import org.junit.Ignore
import kotlin.system.measureNanoTime
import java.util.concurrent.TimeUnit

class ApiProxyInterceptorBenchmark {
    @Ignore("Manual benchmark test, ignores CI")
    @Test
    fun benchmarkQueryParameterIteration() {
        val urlString = "https://api.themoviedb.org/3/discover/movie?api_key=test_key&language=en-US&sort_by=popularity.desc&include_adult=false&include_video=false&page=1&with_watch_monetization_types=flatrate"
        val originalUrl = urlString.toHttpUrl()

        val iterations = 1_000_000
        val warmup = 100_000

        val TMDB_PROXY_URL = "https://fake.supabase.co/functions/v1/tmdb-proxy"

        // WARMUP
        for (i in 0 until warmup) {
            val proxyUrlBuilder = TMDB_PROXY_URL.toHttpUrl().newBuilder()
                .addQueryParameter("path", "/test")

            for (j in 0 until originalUrl.querySize) {
                val name = originalUrl.queryParameterName(j)
                if (name != "api_key") {
                    val value = originalUrl.queryParameterValue(j)
                    if (value != null) {
                        proxyUrlBuilder.addQueryParameter(name, value)
                    }
                }
            }
            proxyUrlBuilder.build()
        }

        val optimizedTime = measureNanoTime {
            for (i in 0 until iterations) {
                val proxyUrlBuilder = TMDB_PROXY_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("path", "/test")

                for (j in 0 until originalUrl.querySize) {
                    val name = originalUrl.queryParameterName(j)
                    if (name != "api_key") {
                        val value = originalUrl.queryParameterValue(j)
                        if (value != null) {
                            proxyUrlBuilder.addQueryParameter(name, value)
                        }
                    }
                }
                proxyUrlBuilder.build()
            }
        }

        println("=== OPTIMIZED BENCHMARK ===")
        println("Iterations: $iterations")
        println("Total Time: ${TimeUnit.NANOSECONDS.toMillis(optimizedTime)} ms")
    }
}
