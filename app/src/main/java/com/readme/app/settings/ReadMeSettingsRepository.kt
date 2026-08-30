package com.readme.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val USER_PREFERENCES_NAME = "readme_settings"

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = USER_PREFERENCES_NAME
)

class ReadMeSettingsRepository(private val context: Context) {

    private object PreferencesKeys {
        val SELECTED_VOICE = stringPreferencesKey("selected_voice")
        val SPEECH_VOLUME = floatPreferencesKey("speech_volume")
        val SPEECH_SPEED = floatPreferencesKey("speech_speed")
        val SPEECH_PITCH = floatPreferencesKey("speech_pitch")
    }

    val settingsFlow: Flow<ReadMeSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val voice = preferences[PreferencesKeys.SELECTED_VOICE] ?: "natural_voice"
            val volume = preferences[PreferencesKeys.SPEECH_VOLUME] ?: 0.70f
            val speed = preferences[PreferencesKeys.SPEECH_SPEED] ?: 1.0f
            val pitch = preferences[PreferencesKeys.SPEECH_PITCH] ?: 0.50f

            // Validate/clamp numeric values
            val clampedVolume = volume.coerceIn(0.0f, 1.0f)
            val clampedSpeed = speed.coerceIn(0.5f, 2.0f)
            val clampedPitch = pitch.coerceIn(0.0f, 1.0f)

            ReadMeSettings(
                selectedVoice = voice,
                speechVolume = clampedVolume,
                speechSpeed = clampedSpeed,
                speechPitch = clampedPitch
            )
        }

    suspend fun updateSelectedVoice(voice: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SELECTED_VOICE] = voice
        }
    }

    suspend fun updateSpeechVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0.0f, 1.0f)
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SPEECH_VOLUME] = clampedVolume
        }
    }

    suspend fun updateSpeechSpeed(speed: Float) {
        val clampedSpeed = speed.coerceIn(0.5f, 2.0f)
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SPEECH_SPEED] = clampedSpeed
        }
    }

    suspend fun updateSpeechPitch(pitch: Float) {
        val clampedPitch = pitch.coerceIn(0.0f, 1.0f)
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SPEECH_PITCH] = clampedPitch
        }
    }
}
