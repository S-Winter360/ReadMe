package com.readme.app.reading.progress

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.security.MessageDigest

private const val PROGRESS_DATASTORE_NAME = "readme_reading_progress"

val Context.readingProgressDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PROGRESS_DATASTORE_NAME
)

/**
 * Contract for persisting and retrieving reading progress per document.
 */
interface ReadingProgressRepository {
    suspend fun saveProgress(progress: ReadingProgress)
    suspend fun loadProgress(documentId: String): ReadingProgress?
    suspend fun clearProgress(documentId: String)
}

/**
 * Production implementation backed by AndroidX DataStore Preferences.
 *
 * Keys are deterministically hashed (SHA-256) to ensure valid, unambiguous preference keys
 * regardless of document URI characters, slashes, or path segments.
 */
class PersistentReadingProgressRepository(
    private val context: Context,
    private val customDataStore: DataStore<Preferences>? = null
) : ReadingProgressRepository {

    private val dataStore: DataStore<Preferences>
        get() = customDataStore ?: context.readingProgressDataStore

    override suspend fun saveProgress(progress: ReadingProgress) {
        if (progress.documentId.isBlank()) return
        try {
            val key = preferenceKeyFor(progress.documentId)
            val json = progress.toJson()
            dataStore.edit { prefs ->
                prefs[key] = json
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving reading progress for ${progress.documentId}", e)
        }
    }

    override suspend fun loadProgress(documentId: String): ReadingProgress? {
        if (documentId.isBlank()) return null
        return try {
            val key = preferenceKeyFor(documentId)
            val prefs = dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }
                .first()

            val json = prefs[key] ?: return null
            val progress = ReadingProgress.fromJson(json) ?: return null
            if (progress.documentId != documentId) return null
            progress
        } catch (e: Exception) {
            Log.e(TAG, "Error loading reading progress for $documentId", e)
            null
        }
    }

    override suspend fun clearProgress(documentId: String) {
        if (documentId.isBlank()) return
        try {
            val key = preferenceKeyFor(documentId)
            dataStore.edit { prefs ->
                prefs.remove(key)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing reading progress for $documentId", e)
        }
    }

    companion object {
        private const val TAG = "ReadingProgressRepo"

        fun preferenceKeyFor(documentId: String): Preferences.Key<String> {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(documentId.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return stringPreferencesKey("prog_$hex")
        }
    }
}

/**
 * In-memory repository implementation useful for fast, isolated unit testing without Android context.
 */
class InMemoryReadingProgressRepository : ReadingProgressRepository {
    private val storage = mutableMapOf<String, ReadingProgress>()

    override suspend fun saveProgress(progress: ReadingProgress) {
        if (progress.documentId.isNotBlank()) {
            storage[progress.documentId] = progress
        }
    }

    override suspend fun loadProgress(documentId: String): ReadingProgress? {
        return storage[documentId]
    }

    override suspend fun clearProgress(documentId: String) {
        storage.remove(documentId)
    }

    fun getAllProgress(): Map<String, ReadingProgress> = storage.toMap()

    fun clearAll() {
        storage.clear()
    }
}

/**
 * No-op repository implementation to ensure ephemeral/external sessions strictly bypass persistence.
 */
class NoOpReadingProgressRepository : ReadingProgressRepository {
    override suspend fun saveProgress(progress: ReadingProgress) {}
    override suspend fun loadProgress(documentId: String): ReadingProgress? = null
    override suspend fun clearProgress(documentId: String) {}
}
