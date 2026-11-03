package com.arflix.tv.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Fails any request whose URL is not HTTPS.
 *
 * Plugin repositories hand the app manifests and code that it then runs (JS scrapers in
 * QuickJS, `.cs3` DEX extensions in-process). Over plain HTTP anyone on the network path
 * can replace that code. Register this with `addNetworkInterceptor` so OkHttp also checks
 * every redirect hop, not only the first URL.
 */
class HttpsOnlyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps) {
            throw IOException("Refusing plain HTTP plugin request to ${url.host}; use https://")
        }
        return chain.proceed(chain.request())
    }
}
