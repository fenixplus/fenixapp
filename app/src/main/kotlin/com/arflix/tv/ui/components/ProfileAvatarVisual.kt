package com.arflix.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.repository.ProfileAvatarImageManager
import com.arflix.tv.util.ProfileAvatarFiles
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@Composable
fun ProfileAvatarVisual(
    profile: Profile,
    modifier: Modifier = Modifier,
    letterFontSize: TextUnit = 14.sp,
    iconPadding: Dp = 4.dp
) {
    val context = LocalContext.current
    val avatarManager = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ProfileAvatarVisualEntryPoint::class.java
        ).profileAvatarImageManager()
    }
    var customFile by remember(profile.id, profile.avatarImageVersion, context) {
        mutableStateOf(
            ProfileAvatarFiles.localFile(context, profile)?.takeIf { it.exists() && it.length() > 0L }
        )
    }

    LaunchedEffect(profile.id, profile.avatarImageVersion, profile.avatarImageStoragePath) {
        if (profile.avatarImageVersion <= 0L) {
            customFile = null
            return@LaunchedEffect
        }

        val existingFile = ProfileAvatarFiles.localFile(context, profile)
            ?.takeIf { it.exists() && it.length() > 0L }
        if (existingFile != null) {
            customFile = existingFile
            return@LaunchedEffect
        }

        avatarManager.restoreAvatarIfNeeded(profile)
        customFile = ProfileAvatarFiles.localFile(context, profile)
            ?.takeIf { it.exists() && it.length() > 0L }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (customFile != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(customFile)
                    .memoryCacheKey("profile-avatar-${profile.id}-${profile.avatarImageVersion}")
                    .diskCacheKey("profile-avatar-${profile.id}-${profile.avatarImageVersion}")
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            return@Box
        }

        if (profile.avatarId > 0) {
            val (c1, c2) = AvatarRegistry.gradientColors(profile.avatarId)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(c1, c2))),
                contentAlignment = Alignment.Center
            ) {
                AvatarIcon(
                    avatarId = profile.avatarId,
                    modifier = Modifier.fillMaxSize().padding(iconPadding)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(profile.avatarColor)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = profile.name.firstOrNull()?.uppercase() ?: "?",
                    fontSize = letterFontSize,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface ProfileAvatarVisualEntryPoint {
    fun profileAvatarImageManager(): ProfileAvatarImageManager
}
