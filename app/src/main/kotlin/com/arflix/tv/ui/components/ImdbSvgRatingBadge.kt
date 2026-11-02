package com.arflix.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.arflix.tv.R
import com.arflix.tv.ui.theme.ArflixTypography

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ImdbSvgRatingBadge(
    rating: String,
    imageLoader: ImageLoader,
    ratingFontSize: Int,
    logoWidth: Dp,
    logoHeight: Dp,
    textShadow: Shadow = Shadow.None
) {
    val context = LocalContext.current
    val request = remember(context) {
        val uri = "android.resource://${context.packageName}/${R.raw.logo_imdb_rectangle}"
        ImageRequest.Builder(context)
            .data(uri)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .allowRgb565(false)
            .build()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        AsyncImage(
            model = request,
            imageLoader = imageLoader,
            contentDescription = "IMDb",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(logoWidth)
                .height(logoHeight)
        )
        Text(
            text = rating,
            style = ArflixTypography.caption.copy(
                fontSize = ratingFontSize.sp,
                fontWeight = FontWeight.Bold,
                shadow = textShadow
            ),
            color = Color.White,
            maxLines = 1
        )
    }
}
