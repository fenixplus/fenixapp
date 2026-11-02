package com.arflix.tv.util

import com.arflix.tv.data.api.TmdbContentRatingResult
import com.arflix.tv.data.api.TmdbContentRatingsResponse
import com.arflix.tv.data.api.TmdbReleaseDatesResponse
import com.arflix.tv.data.api.TmdbReleaseDatesResult

/**
 * Turns TMDB age certifications into the short label shown on a details screen.
 *
 * The certification is data, not translatable text: a German title keeps its
 * "FSK 16" even when the interface runs in English, and a US title stays
 * "Rated R" for a viewer with a German interface. The system names below
 * therefore live in code and deliberately not in `strings.xml` — they follow
 * where the certification comes from, not the interface language.
 *
 * The country comes from the profile's TMDB content language ("de-DE" -> "DE"),
 * so no extra setting is needed. When that country has no certification the US
 * value is shown, marked with its country code so nobody mistakes it for a
 * national rating. With nothing at all the caller gets `null` and hides the
 * badge.
 */
object ContentRating {

    /** Used when the content language carries no usable country, and as fallback source. */
    const val FALLBACK_REGION = "US"

    /** TMDB release type for the theatrical release, preferred over TV/streaming. */
    private const val TYPE_THEATRICAL = 3

    /**
     * Countries whose certifications are bare numbers and would otherwise read
     * as a second score next to the IMDb rating. Small on purpose: every other
     * country falls back to its country code, which stays readable.
     */
    private val RATING_SYSTEMS = mapOf(
        "DE" to "FSK",
        "GB" to "BBFC",
        "BR" to "ClassInd",
        "AU" to "ACB",
        "NL" to "Kijkwijzer"
    )

    /** A certification that was actually found, and where it came from. */
    data class Selection(
        val certification: String,
        val region: String,
        val isFallback: Boolean
    )

    /**
     * Country code from a TMDB content language such as "de-DE" or "pt-BR".
     * Anything without a usable two-letter country (e.g. "en", "zh-Hans")
     * falls back to [FALLBACK_REGION].
     */
    fun regionOf(contentLanguage: String?): String {
        val region = contentLanguage
            ?.substringAfter('-', "")
            ?.trim()
            ?.uppercase()
            .orEmpty()
        return region.takeIf { it.length == 2 && it.all { char -> char in 'A'..'Z' } }
            ?: FALLBACK_REGION
    }

    /** Finished label for a movie, or null when neither the own nor the US country has one. */
    fun forMovie(response: TmdbReleaseDatesResponse?, contentLanguage: String?): String? =
        label(selectMovie(response, regionOf(contentLanguage)))

    /** Finished label for a TV show, or null when neither the own nor the US country has one. */
    fun forTv(response: TmdbContentRatingsResponse?, contentLanguage: String?): String? =
        label(selectTv(response, regionOf(contentLanguage)))

    /**
     * Picks the movie certification for [region], falling back to the US value.
     * A country can hold several certifications that disagree (cinema 16, TV 12);
     * the theatrical one wins, otherwise the lowest known release type.
     */
    fun selectMovie(response: TmdbReleaseDatesResponse?, region: String): Selection? {
        val results = response?.results ?: return null
        movieCertification(results, region)?.let { return Selection(it, region, isFallback = false) }
        if (region == FALLBACK_REGION) return null
        return movieCertification(results, FALLBACK_REGION)
            ?.let { Selection(it, FALLBACK_REGION, isFallback = true) }
    }

    /** Picks the TV certification for [region], falling back to the US value. */
    fun selectTv(response: TmdbContentRatingsResponse?, region: String): Selection? {
        val results = response?.results ?: return null
        tvCertification(results, region)?.let { return Selection(it, region, isFallback = false) }
        if (region == FALLBACK_REGION) return null
        return tvCertification(results, FALLBACK_REGION)
            ?.let { Selection(it, FALLBACK_REGION, isFallback = true) }
    }

    /**
     * Applies the labelling rule:
     * a value that speaks for itself stays untouched ("R", "PG-13", "TV-MA"),
     * a bare number gets its rating system ("16" in DE -> "FSK 16") or else its
     * country code ("12" in FR -> "FR 12"), and anything taken from the fallback
     * country is always marked ("US · R").
     */
    fun label(selection: Selection?): String? {
        if (selection == null) return null
        val value = selection.certification
        if (selection.isFallback) return "${selection.region} · $value"
        if (!value.all { it.isDigit() }) return value
        RATING_SYSTEMS[selection.region]?.let { return "$it $value" }
        return "${selection.region} $value"
    }

    private fun movieCertification(
        results: List<TmdbReleaseDatesResult>,
        region: String
    ): String? {
        val entry = results.firstOrNull { it.iso31661.equals(region, ignoreCase = true) }
            ?: return null
        val candidates = entry.releaseDates.mapNotNull { release ->
            release.certification?.trim()?.takeIf { it.isNotEmpty() }?.let { it to release.type }
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.second == TYPE_THEATRICAL }?.first
            ?: candidates.minByOrNull { it.second }?.first
    }

    private fun tvCertification(
        results: List<TmdbContentRatingResult>,
        region: String
    ): String? = results
        .firstOrNull { it.iso31661.equals(region, ignoreCase = true) }
        ?.rating
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
