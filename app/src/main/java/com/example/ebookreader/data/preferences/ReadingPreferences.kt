package com.example.ebookreader.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "reading_preferences")

@Singleton
class ReadingPreferences @Inject constructor(
    @ApplicationContext private val context: Context  // Add @ApplicationContext annotation
) {
    companion object {
        private val FONT_SIZE_KEY = floatPreferencesKey("font_size")
        private val THEME_KEY = stringPreferencesKey("reading_theme")
        private val VOLUME_BUTTONS_ENABLED_KEY = stringPreferencesKey("volume_buttons_enabled")
    }

    val fontSize: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[FONT_SIZE_KEY] ?: 16f
    }

    val theme: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[THEME_KEY] ?: "LIGHT"
    }

    val volumeButtonsEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[VOLUME_BUTTONS_ENABLED_KEY]?.toBoolean() ?: true
    }

    suspend fun setFontSize(fontSize: Float) {
        context.dataStore.edit { preferences ->
            preferences[FONT_SIZE_KEY] = fontSize
        }
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = theme
        }
    }

    suspend fun setVolumeButtonsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VOLUME_BUTTONS_ENABLED_KEY] = enabled.toString()
        }
    }
}