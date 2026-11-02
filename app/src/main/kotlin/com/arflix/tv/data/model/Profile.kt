package com.arflix.tv.data.model

import java.util.UUID

/**
 * User profile - each profile has independent settings, Trakt, addons, etc.
 */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val avatarColor: Long = ProfileColors.random(),
    val avatarId: Int = 0, // 0 = legacy letter+color, 1-24 = Compose-drawn avatar
    val avatarImageVersion: Long = 0L, // 0 = no custom uploaded photo
    val avatarImageStoragePath: String? = null,
    val isKidsProfile: Boolean = false,
    val kidsAgeLevel: String? = null,
    val kidsStarterRowsAdded: Boolean = false,
    val parentPin: String? = null,
    val pin: String? = null, // 4-5 digit PIN, null if not set
    val isLocked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long? = null
) {
    /** The age cap this profile enforces, or null for a regular profile. */
    val kidsLevel: KidsAgeLevel?
        get() = if (isKidsProfile) KidsAgeLevel.fromName(kidsAgeLevel) else null
}

/**
 * How old a kids profile's viewer is. [maxAge] is the highest minimum viewing age a
 * title may carry: PG (7) passes every level, TV-PG (10) "older kids" and up, PG-13 (13)
 * and TV-14 (14) only "teens".
 */
enum class KidsAgeLevel(val maxAge: Int) {
    LITTLE_KIDS(7),
    OLDER_KIDS(10),
    PRE_TEENS(12),
    TEENS(14);

    companion object {
        val DEFAULT = OLDER_KIDS

        fun fromName(name: String?): KidsAgeLevel =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Predefined profile avatar colors (Netflix-style)
 */
object ProfileColors {
    val colors = listOf(
        0xFFE50914L, // Netflix Red
        0xFF1DB954L, // Green
        0xFF3B82F6L, // Blue
        0xFFF59E0BL, // Orange
        0xFF8B5CF6L, // Purple
        0xFFEC4899L, // Pink
        0xFF14B8A6L, // Teal
        0xFF6366F1L  // Indigo
    )

    fun random(): Long = colors.random()

    fun getByIndex(index: Int): Long = colors[index % colors.size]
}
