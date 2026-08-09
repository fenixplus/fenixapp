package com.arflix.tv.ui.screens.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsIptvGroupOrderTest {

    @Test
    fun staleSavedGroupLabelsCannotReappearAfterProviderRefresh() {
        val ordered = orderedIptvGroups(
            playlistId = "list_1",
            availableGroups = listOf("Entertainment", "Kids", "Movies"),
            groupOrder = listOf("list_1|[B] Kids", "list_1|Movies"),
        )

        assertThat(ordered).containsExactly("Movies", "Entertainment", "Kids").inOrder()
        assertThat(ordered).doesNotContain("[B] Kids")
    }
}
