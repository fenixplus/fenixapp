package com.arflix.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

@OptIn(UnstableApi::class)
enum class AspectRatioMode(val label: String, val resizeMode: Int) {
    AUTO("Auto", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FIT("Fit to Screen", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    STRETCH("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    CROP("Crop", AspectRatioFrameLayout.RESIZE_MODE_ZOOM);

    companion object {
        fun fromLabel(label: String?): AspectRatioMode {
            if (label.isNullOrBlank()) return AUTO
            return when (label.trim().lowercase()) {
                "auto" -> AUTO
                "fit", "fit to screen" -> FIT
                "fill", "stretch" -> STRETCH
                "zoom", "crop" -> CROP
                else -> entries.firstOrNull { it.label.equals(label, ignoreCase = true) || it.name.equals(label, ignoreCase = true) } ?: AUTO
            }
        }
    }
}
