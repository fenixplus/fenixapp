package com.arflix.tv.ui.screens.player

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.arflix.tv.R

internal fun resolveSubtitleTypeface(
    context: Context,
    preference: String?,
    bold: Boolean,
): Typeface {
    val option = SubtitleFontOption.fromPreference(preference)
    val base = when (option) {
        SubtitleFontOption.SYSTEM -> Typeface.DEFAULT
        else -> runCatching {
            ResourcesCompat.getFont(context, R.font.inter_variablefont_opsz_wght)
        }.getOrNull() ?: Typeface.DEFAULT
    }
    return Typeface.create(base, if (bold) Typeface.BOLD else Typeface.NORMAL)
}
