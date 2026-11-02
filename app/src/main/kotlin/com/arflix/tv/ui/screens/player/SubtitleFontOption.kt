package com.arflix.tv.ui.screens.player

/** Fonts bundled specifically for subtitle rendering. */
enum class SubtitleFontOption(val preferenceValue: String) {
    SYSTEM("System"),
    INTER("Inter"),
    NOTO_SANS("Noto Sans"),
    ATKINSON_HYPERLEGIBLE("Atkinson Hyperlegible"),
    LEXEND("Lexend"),
    ROBOTO_CONDENSED("Roboto Condensed"),
    NUNITO_SANS("Nunito Sans"),
    QUICKSAND("Quicksand"),
    RUBIK("Rubik"),
    VARELA_ROUND("Varela Round");

    companion object {
        const val DefaultPreference = "System"

        fun fromPreference(value: String?): SubtitleFontOption =
            entries.firstOrNull { it.preferenceValue == value } ?: SYSTEM

        fun nextPreference(value: String?): String {
            val current = fromPreference(value)
            return entries[(current.ordinal + 1) % entries.size].preferenceValue
        }

        fun previousPreference(value: String?): String {
            val current = fromPreference(value)
            return entries[(current.ordinal - 1 + entries.size) % entries.size].preferenceValue
        }
    }
}

internal fun shouldPreserveEmbeddedSubtitleStyles(
    fontPreference: String?,
    stylizedSubtitlesEnabled: Boolean,
): Boolean = stylizedSubtitlesEnabled &&
    SubtitleFontOption.fromPreference(fontPreference) == SubtitleFontOption.SYSTEM
