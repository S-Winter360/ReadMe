package com.readme.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        val SYSTEM_BUBBLE_ENABLED = booleanPreferencesKey("system_bubble_enabled")
        val SCREEN_OCR_CONSENT_GRANTED = booleanPreferencesKey("screen_ocr_consent_granted")
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
            val isBubbleEnabled = preferences[PreferencesKeys.SYSTEM_BUBBLE_ENABLED] ?: false
            val isOcrConsentGranted = preferences[PreferencesKeys.SCREEN_OCR_CONSENT_GRANTED] ?: false

            // Validate/clamp numeric values
            val clampedVolume = volume.coerceIn(0.0f, 1.0f)
            val clampedSpeed = speed.coerceIn(0.5f, 2.0f)
            val clampedPitch = pitch.coerceIn(0.0f, 1.0f)

            ReadMeSettings(
                selectedVoice = voice,
                speechVolume = clampedVolume,
                speechSpeed = clampedSpeed,
                speechPitch = clampedPitch,
                isSystemBubbleEnabled = isBubbleEnabled,
                isScreenOcrConsentGranted = isOcrConsentGranted
            )
        }

    suspend fun updateSelectedVoice(voice: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SELECTED_VOICE] = voice
        }
    }

    suspend fun updateSpeechVolume(volume: Float) {
        val clamped = volume.coerceIn(0.0f, 1.0f)
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SPEECH_VOLUME] = clamped
        }
    }

    suspend fun updateSpeechSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SPEECH_SPEED] = clamped
        }
    }

    suspend fun updateSpeechPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.0f, 1.0f)
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SPEECH_PITCH] = clamped
        }
    }

    suspend fun updateSystemBubbleEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SYSTEM_BUBBLE_ENABLED] = enabled
        }
    }

    suspend fun updateScreenOcrConsentGranted(granted: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SCREEN_OCR_CONSENT_GRANTED] = granted
        }
    }
}
