package com.readme.app.accessibility.autonav

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.readme.app.settings.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Manages persistence and quick lookup of user-calibrated page-turn taps according to Phase 9AD.
 */
class PageTurnCalibrationRepository(private val context: Context) {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _calibrations = MutableStateFlow<Map<String, PageTurnCalibration>>(emptyMap())
    val calibrationsFlow: StateFlow<Map<String, PageTurnCalibration>> = _calibrations.asStateFlow()

    private object PreferencesKeys {
        val PAGE_TURN_CALIBRATIONS = stringPreferencesKey("page_turn_calibrations_v2")
    }

    init {
        repositoryScope.launch {
            context.dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }
                .map { preferences ->
                    val rawData = preferences[PreferencesKeys.PAGE_TURN_CALIBRATIONS]
                    parseCalibrations(rawData)
                }
                .collect { map ->
                    _calibrations.value = map
                }
        }
    }

    fun getCalibration(packageName: String, orientation: Int? = null): PageTurnCalibration? {
        val all = _calibrations.value
        val exact = all[packageName] ?: return null
        if (orientation != null && exact.orientation != orientation) {
            return exact
        }
        return exact
    }

    fun hasCalibration(packageName: String): Boolean {
        return _calibrations.value.containsKey(packageName)
    }

    suspend fun saveCalibration(calibration: PageTurnCalibration) {
        val updated = _calibrations.value.toMutableMap()
        updated[calibration.packageName] = calibration
        _calibrations.value = updated

        try {
            val serialized = serializeCalibrations(updated)
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.PAGE_TURN_CALIBRATIONS] = serialized
            }
        } catch (_: Throwable) {}
    }

    suspend fun clearCalibration(packageName: String) {
        val updated = _calibrations.value.toMutableMap()
        updated.remove(packageName)
        _calibrations.value = updated

        try {
            val serialized = serializeCalibrations(updated)
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.PAGE_TURN_CALIBRATIONS] = serialized
            }
        } catch (_: Throwable) {}
    }

    suspend fun clearAllCalibrations() {
        _calibrations.value = emptyMap()
        try {
            context.dataStore.edit { preferences ->
                preferences.remove(PreferencesKeys.PAGE_TURN_CALIBRATIONS)
            }
        } catch (_: Throwable) {}
    }

    fun setCalibrationForTesting(calibration: PageTurnCalibration) {
        val updated = _calibrations.value.toMutableMap()
        updated[calibration.packageName] = calibration
        _calibrations.value = updated
    }

    fun clearCalibrationForTesting(packageName: String) {
        val updated = _calibrations.value.toMutableMap()
        updated.remove(packageName)
        _calibrations.value = updated
    }

    companion object {
        @Volatile
        private var instance: PageTurnCalibrationRepository? = null

        fun getInstance(context: Context): PageTurnCalibrationRepository {
            return instance ?: synchronized(this) {
                instance ?: PageTurnCalibrationRepository(context.applicationContext).also { instance = it }
            }
        }

        private fun parseCalibrations(raw: String?): Map<String, PageTurnCalibration> {
            if (raw.isNullOrBlank()) return emptyMap()
            val result = mutableMapOf<String, PageTurnCalibration>()
            raw.split("||").forEach { itemStr ->
                val cal = PageTurnCalibration.fromJson(itemStr)
                if (cal != null) {
                    result[cal.packageName] = cal
                }
            }
            return result
        }

        private fun serializeCalibrations(calibrations: Map<String, PageTurnCalibration>): String {
            return calibrations.values.joinToString("||") { it.toJson() }
        }
    }
}
