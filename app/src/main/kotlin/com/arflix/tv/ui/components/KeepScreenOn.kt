package com.arflix.tv.ui.components

import android.app.Activity
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/**
 * Keeps the screen on while this composable is in the composition and [active] is true.
 * Releases the flag when [active] becomes false or the composable leaves composition.
 *
 * Use in any screen where media is playing (player, live TV, trailer).
 */
@Composable
fun KeepScreenOn(active: Boolean = true) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(active, context, view) {
        if (!active) return@DisposableEffect onDispose {}
        val window = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
            ?.window
        val previousViewKeepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        window?.let { KeepScreenOnRegistry.acquire(it) }
        onDispose {
            view.keepScreenOn = previousViewKeepScreenOn
            window?.let { KeepScreenOnRegistry.release(it) }
        }
    }
}

private object KeepScreenOnRegistry {
    private val activeWindows = WeakHashMap<Window, Int>()

    @Synchronized
    fun acquire(window: Window) {
        val count = activeWindows[window] ?: 0
        if (count == 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        activeWindows[window] = count + 1
    }

    @Synchronized
    fun release(window: Window) {
        val count = activeWindows[window] ?: return
        if (count <= 1) {
            activeWindows.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activeWindows[window] = count - 1
        }
    }
}
