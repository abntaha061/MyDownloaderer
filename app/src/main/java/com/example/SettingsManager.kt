package com.example

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pure_download_settings")

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_DEFAULT_FOLDER = stringPreferencesKey("default_folder_uri")
        val KEY_DEFAULT_QUALITY = stringPreferencesKey("default_quality")
        val KEY_THEME_STYLE = stringPreferencesKey("theme_style")
    }

    val defaultFolderUri: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[KEY_DEFAULT_FOLDER]
    }

    val defaultQuality: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_DEFAULT_QUALITY] ?: "best"
    }

    val themeStyle: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_THEME_STYLE] ?: "system"
    }

    suspend fun setDefaultFolderUri(uri: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DEFAULT_FOLDER] = uri
        }
    }

    suspend fun setDefaultQuality(quality: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DEFAULT_QUALITY] = quality
        }
    }

    suspend fun setThemeStyle(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_THEME_STYLE] = theme
        }
    }
}
