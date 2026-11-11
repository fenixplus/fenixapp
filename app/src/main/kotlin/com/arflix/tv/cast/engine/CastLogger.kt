package com.arflix.tv.cast.engine

import android.util.Log

object CastLogger {
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error != null) {
            Log.w(tag, message, error)
        } else {
            Log.w(tag, message)
        }
    }
}

