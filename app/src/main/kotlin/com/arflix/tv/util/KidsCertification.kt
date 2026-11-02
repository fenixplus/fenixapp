package com.arflix.tv.util

import com.arflix.tv.data.api.TmdbContentRatingsResponse
import com.arflix.tv.data.api.TmdbReleaseDatesResponse

/**
 * Minimum viewing age behind a TMDB certification, for the kids profile filter.
 *
 * Certifications are country specific: the profile's own country and the US are both
 * read and the stricter one counts. A value this table cannot read counts as missing,
 * and a title with nothing readable returns null: the filter then treats it as
 * unrated and blocks it.
 */
object KidsCertification {

    /**
     * Named certifications. Bare numbers ("12", "FSK 16", "R15+", "PG12") are read by
     * [minimumAge] directly, so only the lettered systems need an entry here.
     */
    private val NAMED_AGES = mapOf(
        // US film. PG is "parental guidance suggested", not an age: it covers most current
        // family films (Toy Story, Moana, Coco), so it sits inside the youngest level.
        "G" to 0, "PG" to 7, "PG-13" to 13, "R" to 17, "NC-17" to 18,
        // US TV
        "TV-Y" to 0, "TV-Y7" to 7, "TV-Y7-FV" to 7, "TV-G" to 0, "TV-PG" to 10,
        "TV-14" to 14, "TV-MA" to 17,
        // UK
        "U" to 0, "UC" to 0, "12A" to 12, "R18" to 18,
        // All-ages markers of other systems (BR, NL, FR, KR, CA). "E" (exempt) is left out
        // on purpose: it means unclassified, not suitable for children.
        "L" to 0, "LIVRE" to 0, "ER" to 0, "AL" to 0, "TP" to 0, "ALL" to 0, "C" to 0, "C8" to 8,
        // AU
        "M" to 15
    )

    private val DIGITS = Regex("""(\d{1,2})""")

    /** The minimum age for [certification], or null when it cannot be read. */
    fun minimumAge(certification: String?): Int? {
        val value = certification?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        NAMED_AGES[value]?.let { return it }
        val digits = DIGITS.findAll(value).toList()
        // "MA15+", "PG12", "FSK 16" carry one number; anything with two is not a rating.
        return digits.singleOrNull()?.value?.toIntOrNull()?.takeIf { it in 0..21 }
    }

    /**
     * Age certification:
     * 1. Uses Brazilian certification (ClassInd) first if present.
     * 2. Uses US certification (MPAA / TV Guidelines) as backup if Brazilian is absent.
     */
    fun forMovie(response: TmdbReleaseDatesResponse?, region: String = "BR"): Int? {
        val targetRegion = if (region.isBlank()) "BR" else region
        val ownCert = ContentRating.selectMovie(response, targetRegion)?.takeUnless { it.isFallback }?.certification
        val ownAge = minimumAge(ownCert)
        if (ownAge != null) return ownAge

        val usCert = ContentRating.selectMovie(response, ContentRating.FALLBACK_REGION)?.certification
        return minimumAge(usCert)
    }

    fun forTv(response: TmdbContentRatingsResponse?, region: String = "BR"): Int? {
        val targetRegion = if (region.isBlank()) "BR" else region
        val ownCert = ContentRating.selectTv(response, targetRegion)?.takeUnless { it.isFallback }?.certification
        val ownAge = minimumAge(ownCert)
        if (ownAge != null) return ownAge

        val usCert = ContentRating.selectTv(response, ContentRating.FALLBACK_REGION)?.certification
        return minimumAge(usCert)
    }
}
