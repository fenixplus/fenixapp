package com.arflix.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pluginDataStore: DataStore<Preferences> by preferencesDataStore(name = "plugin_settings")

@Singleton
class ProfileDataStoreFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun get(profileId: Int, feature: String): DataStore<Preferences> {
        return context.pluginDataStore
    }
}
