package com.arflix.tv.ui.screens.player

enum class SubtitleAiModel {
    GROQ_LLAMA_70B,
    // Now maps to gemini-3.5-flash-lite (2.5 retired July 2026). Name kept: the enum value is
    // persisted in DataStore and cloud backups — renaming breaks restore on other devices.
    GEMINI_FLASH_25
}
