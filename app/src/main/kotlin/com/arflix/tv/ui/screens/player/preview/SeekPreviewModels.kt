package com.arflix.tv.ui.screens.player.preview

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class SeekPreviewFrame(
    val bitmap: Bitmap,
    val positionMs: Long,
    val requestedPositionMs: Long = positionMs
)

@Composable
fun ReadySeekPreview(
    frame: SeekPreviewFrame?,
    positionMs: Long,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    sourceGeneration: Long = -1L,
    cornerRadius: Dp = 5.dp,
    placeholder: Boolean = false,
) {
    // Lightweight no-op placeholder when frame is null
}
