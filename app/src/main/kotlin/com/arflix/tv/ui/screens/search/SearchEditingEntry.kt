package com.arflix.tv.ui.screens.search

/**
 * Why the search screen must not start in typing mode, and what decides it.
 */
internal fun startsSearchEditing(nowMs: Long, suppressSelectUntilMs: Long, repeatCount: Int = 0): Boolean =
    repeatCount == 0 && nowMs >= suppressSelectUntilMs

/**
 * How long after entering the screen a select press is taken for the one that opened it.
 */
internal const val SEARCH_SELECT_SUPPRESS_MS = 150L

/**
 * Whether typing mode should stay on, given what the keyboard is doing.
 */
internal fun searchEditingSurvivesKeyboard(imeVisible: Boolean, keyboardWasSeen: Boolean): Boolean =
    imeVisible || !keyboardWasSeen
