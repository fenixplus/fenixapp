package com.arflix.tv.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CardLayoutModeTest {

    @Test
    fun `normalizes card layout values`() {
        assertThat(normalizeCardLayoutMode("Poster")).isEqualTo(CARD_LAYOUT_MODE_POSTER)
        assertThat(normalizeCardLayoutMode("poster")).isEqualTo(CARD_LAYOUT_MODE_POSTER)
        assertThat(normalizeCardLayoutMode("Landscape")).isEqualTo(CARD_LAYOUT_MODE_LANDSCAPE)
        assertThat(normalizeCardLayoutMode("unexpected")).isEqualTo(CARD_LAYOUT_MODE_LANDSCAPE)
        assertThat(normalizeCardLayoutMode(null)).isEqualTo(CARD_LAYOUT_MODE_LANDSCAPE)
    }

    @Test
    fun `normalizes catalogue row keys for stable preferences`() {
        assertThat(normalizeCatalogueRowLayoutKey(" Home:Trending Movies "))
            .isEqualTo("home:trending_movies")
        assertThat(normalizeCatalogueRowLayoutKey("")).isEqualTo("default")
    }

    @Test
    fun `builds and parses profile row layout preference names`() {
        val preferenceName = profileCatalogueRowLayoutPreferenceName(
            profileId = "p1",
            rowKey = "Home:Trending Movies"
        )

        assertThat(preferenceName)
            .isEqualTo("profile_p1_catalogue_row_layout_home:trending_movies")
        assertThat(catalogueRowLayoutKeyFromPreferenceName("p1", preferenceName))
            .isEqualTo("home:trending_movies")
        assertThat(catalogueRowLayoutKeyFromPreferenceName("p2", preferenceName))
            .isNull()
    }

    @Test
    fun `toggles between poster and landscape`() {
        assertThat(toggledCardLayoutMode(CardLayoutMode.LANDSCAPE))
            .isEqualTo(CARD_LAYOUT_MODE_POSTER)
        assertThat(toggledCardLayoutMode(CardLayoutMode.POSTER))
            .isEqualTo(CARD_LAYOUT_MODE_LANDSCAPE)
    }
}
