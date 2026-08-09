package com.arflix.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.ui.theme.ArflixTypography
import kotlinx.coroutines.delay

enum class ToastType {
    SUCCESS, ERROR, INFO
}

/**
 * Toast notification component for temporary messages
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Toast(
    message: String,
    type: ToastType = ToastType.INFO,
    isVisible: Boolean,
    durationMs: Long = 3000,
    onDismiss: () -> Unit = {}
) {
    var visible by remember(isVisible, message, type) { mutableStateOf(isVisible) }

    LaunchedEffect(isVisible, message, type) {
        if (isVisible) {
            visible = true
            delay(durationMs)
            visible = false
            onDismiss()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
        ) {
            val (accentColor, icon, iconBgColor) = when (type) {
                ToastType.SUCCESS -> Triple(
                    Color(0xFF34D399),
                    Icons.Default.Check,
                    Color(0x2234D399)
                )
                ToastType.ERROR -> Triple(
                    Color(0xFFF87171),
                    Icons.Default.Close,
                    Color(0x22F87171)
                )
                ToastType.INFO -> Triple(
                    Color(0xFF60A5FA),
                    Icons.Default.Info,
                    Color(0x2260A5FA)
                )
            }

            Row(
                modifier = Modifier
                    .padding(bottom = 48.dp)
                    .shadow(
                        elevation = 18.dp,
                        shape = RoundedCornerShape(18.dp),
                        ambientColor = Color.Black.copy(alpha = 0.32f),
                        spotColor = Color.Black.copy(alpha = 0.32f)
                    )
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xE61A1E28))
                    .border(
                        width = 1.dp,
                        color = accentColor.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconBgColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = message,
                    style = ArflixTypography.body,
                    color = Color.White
                )
            }
        }
    }
}
