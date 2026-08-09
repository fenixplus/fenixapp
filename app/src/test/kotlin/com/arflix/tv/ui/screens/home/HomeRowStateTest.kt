package com.arflix.tv.ui.screens.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeRowStateTest {

    @Test
    fun `row key depends on catalog identity and not its position`() {
        assertThat(stableHomeRowKey("tv", "trending_movies"))
            .isEqualTo("tv_home_row_trending_movies")
        assertThat(stableHomeRowKey("mobile", "trending_movies"))
            .isEqualTo("mobile_home_row_trending_movies")
    }

    @Test
    fun `focused catalog follows its id when rows are inserted above it`() {
        val updatedRows = listOf("continue_watching", "sports", "trending_movies", "trending_tv")

        val resolvedIndex = resolveHomeCategoryIndex(
            categoryIds = updatedRows,
            preferredCategoryId = "trending_tv",
            fallbackIndex = 2
        )

        assertThat(resolvedIndex).isEqualTo(3)
    }

    @Test
    fun `focused catalog follows its id when rows are reordered`() {
        val reorderedRows = listOf("trending_tv", "continue_watching", "trending_movies")

        val resolvedIndex = resolveHomeCategoryIndex(
            categoryIds = reorderedRows,
            preferredCategoryId = "trending_movies",
            fallbackIndex = 0
        )

        assertThat(resolvedIndex).isEqualTo(2)
    }

    @Test
    fun `missing focused catalog keeps a clamped fallback row`() {
        val resolvedIndex = resolveHomeCategoryIndex(
            categoryIds = listOf("continue_watching", "trending_movies"),
            preferredCategoryId = "removed_catalog",
            fallbackIndex = 8
        )

        assertThat(resolvedIndex).isEqualTo(1)
    }
}
