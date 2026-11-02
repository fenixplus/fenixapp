package com.arflix.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.arflix.tv.R

/**
 * Next episode overlay shown at the end of an episode
 * Matches the webapp's "Up Next" modal design
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NextEpisodeOverlay(
    isVisible: Boolean,
    showTitle: String,
    episodeTitle: String,
    seasonNumber: Int,
    episodeNumber: Int,
    episodeImage: String?,
    countdownSeconds: Int = 10,
    focusedButtonOverride: Int? = null,
    onFocusedButtonChange: ((Int) -> Unit)? = null,
    onPlayNext: () -> Unit,
    onCancel: () -> Unit
) {
    var internalFocusedButton by remember(isVisible) { mutableIntStateOf(0) } // 0 = play, 1 = cancel
    var countdown by remember(isVisible) { mutableIntStateOf(countdownSeconds) }
    var progress by remember(isVisible) { mutableFloatStateOf(1f) }
    val playFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }
    var playFocused by remember { mutableStateOf(false) }
    var cancelFocused by remember { mutableStateOf(false) }
    val focusedButton = focusedButtonOverride ?: internalFocusedButton

    fun updateFocusedButton(value: Int) {
        val clamped = value.coerceIn(0, 1)
        if (focusedButtonOverride == null) {
            internalFocusedButton = clamped
        }
        onFocusedButtonChange?.invoke(clamped)
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            delay(150)
            val req = if (focusedButton == 1) cancelFocusRequester else playFocusRequester
            runCatching { req.requestFocus() }
            delay(100)
            if (!playFocused && !cancelFocused) {
                runCatching { req.requestFocus() }
            }
        }
    }

    LaunchedEffect(focusedButton, isVisible) {
        if (isVisible) {
            if (focusedButton == 0 && !playFocused) {
                runCatching { playFocusRequester.requestFocus() }
            } else if (focusedButton == 1 && !cancelFocused) {
                runCatching { cancelFocusRequester.requestFocus() }
            }
        }
    }

    // Countdown timer
    LaunchedEffect(isVisible) {
        if (isVisible) {
            countdown = countdownSeconds
            while (countdown > 0) {
                delay(1000)
                countdown--
                progress = countdown.toFloat() / countdownSeconds.toFloat()
            }
            if (countdown == 0) {
                onPlayNext()
            }
        }
    }

    fun isSelectKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val code = event.nativeKeyEvent.keyCode
        return event.key == Key.Enter ||
            event.key == Key.DirectionCenter ||
            event.key == Key.NumPadEnter ||
            code == 23 || code == 66 || code == 160 || code == 96
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
        exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it })
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomEnd
        ) {
            // Card positioned at bottom right
            Box(
                modifier = Modifier
                    .padding(48.dp)
                    .width(500.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.95f),
                                Color(0xFF1A1A1A).copy(alpha = 0.98f)
                            )
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                    .padding(24.dp)
            ) {
                Column {
                    // Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = null,
                            tint = Pink,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.next).uppercase(),
                            style = ArflixTypography.label,
                            color = Pink
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "in ${countdown}s",
                            style = ArflixTypography.body,
                            color = TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Episode preview
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Thumbnail
                        Box(
                            modifier = Modifier
                                .width(160.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            if (episodeImage != null) {
                                AsyncImage(
                                    model = episodeImage,
                                    contentDescription = episodeTitle,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF222222)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            // Progress bar at bottom
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .height(4.dp)
                                    .fillMaxSize()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxSize()
                                        .background(Color.White.copy(alpha = 0.3f))
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width((160 * progress).dp)
                                        .background(Pink)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        // Episode info
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = showTitle,
                                style = ArflixTypography.caption,
                                color = TextSecondary
                            )
                            Text(
                                text = episodeTitle,
                                style = ArflixTypography.cardTitle,
                                color = TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "S$seasonNumber E$episodeNumber",
                                style = ArflixTypography.badge,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Play button
                        val isPlayActive = playFocused || (focusedButton == 0 && !cancelFocused)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .focusRequester(playFocusRequester)
                                .onFocusChanged { state ->
                                    playFocused = state.isFocused
                                    if (state.isFocused) updateFocusedButton(0)
                                }
                                .focusable()
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when {
                                            isSelectKey(event) -> {
                                                onPlayNext()
                                                true
                                            }
                                            event.key == Key.DirectionRight -> {
                                                updateFocusedButton(1)
                                                runCatching { cancelFocusRequester.requestFocus() }
                                                true
                                            }
                                            event.key == Key.Back || event.key == Key.Escape || event.nativeKeyEvent.keyCode == 4 -> {
                                                onCancel()
                                                true
                                            }
                                            event.key == Key.DirectionUp || event.key == Key.DirectionDown -> true
                                            else -> false
                                        }
                                    } else false
                                }
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isPlayActive) Color.White else Color.White.copy(alpha = 0.12f),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isPlayActive) Color.White else Color.White.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    updateFocusedButton(0)
                                    onPlayNext()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = if (isPlayActive) Color.Black else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.play).uppercase(),
                                    style = ArflixTypography.button,
                                    color = if (isPlayActive) Color.Black else Color.White
                                )
                            }
                        }

                        // Cancel button
                        val isCancelActive = cancelFocused || (focusedButton == 1 && !playFocused)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .focusRequester(cancelFocusRequester)
                                .onFocusChanged { state ->
                                    cancelFocused = state.isFocused
                                    if (state.isFocused) updateFocusedButton(1)
                                }
                                .focusable()
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when {
                                            isSelectKey(event) -> {
                                                onCancel()
                                                true
                                            }
                                            event.key == Key.DirectionLeft -> {
                                                updateFocusedButton(0)
                                                runCatching { playFocusRequester.requestFocus() }
                                                true
                                            }
                                            event.key == Key.Back || event.key == Key.Escape || event.nativeKeyEvent.keyCode == 4 -> {
                                                onCancel()
                                                true
                                            }
                                            event.key == Key.DirectionUp || event.key == Key.DirectionDown -> true
                                            else -> false
                                        }
                                    } else false
                                }
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isCancelActive) Color.White else Color.White.copy(alpha = 0.12f),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isCancelActive) Color.White else Color.White.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    updateFocusedButton(1)
                                    onCancel()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel),
                                tint = if (isCancelActive) Color.Black else Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
