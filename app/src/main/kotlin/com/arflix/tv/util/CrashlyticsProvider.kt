package com.arflix.tv.util

data object CrashlyticsProvider : AppLogger.CrashContextProvider {
    fun initialize(): Boolean = false
    override fun log(message: String) {}
    override fun recordException(throwable: Throwable) {}
    override fun setCustomKey(key: String, value: String) {}
    override fun setCustomKey(key: String, value: Boolean) {}
    override fun setCustomKey(key: String, value: Int) {}
    override fun setUserId(userId: String?) {}
}
