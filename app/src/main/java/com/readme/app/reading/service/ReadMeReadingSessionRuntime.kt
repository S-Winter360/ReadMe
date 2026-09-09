package com.readme.app.reading.service

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingEngine
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionCoordinator
import com.readme.app.reading.ReadingSessionState
import com.readme.app.reading.progress.PersistentReadingProgressRepository
import com.readme.app.reading.progress.ReadingProgress
import com.readme.app.reading.progress.ReadingProgressRepository
import com.readme.app.reading.progress.ReadingProgressValidator
import com.readme.app.reading.progress.SavedProgressState
import com.readme.app.settings.ReadMeSettings
import com.readme.app.settings.ReadMeSettingsRepository
import com.readme.app.speech.ReadMeSpeechEngine
import com.readme.app.speech.SpeechEngineListener
import com.readme.app.speech.TtsState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 9E: Single authoritative reading-session runtime that survives Activity recreation.
 *
 * Coordinates:
 * - [ReadingSessionCoordinator] as session authority
 * - [ReadingEngine] as reading progression authority
 * - [ReadMeSpeechEngine] as TTS synthesis authority
 * - [ReadingProgressRepository] as persistence authority
 */
private object NoOpReadingProgressRepository : ReadingProgressRepository {
    override suspend fun saveProgress(progress: ReadingProgress) {}
    override suspend fun loadProgress(documentId: String): ReadingProgress? = null
    override suspend fun clearProgress(documentId: String) {}
}

/**
 * Bounded by [runtimeScope] (application/service lifetime), so reading and speech progression
 * do not depend on the Compose Activity remaining alive.
 */
class ReadMeReadingSessionRuntime(
    val context: Context? = null,
    val progressRepository: ReadingProgressRepository = context?.let { PersistentReadingProgressRepository(it) } ?: NoOpReadingProgressRepository,
    val readingEngine: ReadingEngine = ReadingEngine(),
    val sessionCoordinator: ReadingSessionCoordinator = ReadingSessionCoordinator(readingEngine, progressRepository),
    val speechEngine: ReadMeSpeechEngine = ReadMeSpeechEngine(context),
    val settingsRepository: ReadMeSettingsRepository? = context?.let { ReadMeSettingsRepository(it) },
    val runtimeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    interface PlaybackLifecycleListener {
        fun onReadingStarted() {}
        fun onReadingStopped() {}
        fun onReadingCompleted() {}
        fun onReadingError(message: String?) {}
    }

    private val listeners = CopyOnWriteArrayList<PlaybackLifecycleListener>()

    val activeDocumentState: StateFlow<ActiveDocumentState> = sessionCoordinator.activeDocumentState
    val readingSessionState: StateFlow<ActiveReadingSessionState> = sessionCoordinator.readingSessionState

    private val _savedProgressState = MutableStateFlow<SavedProgressState>(SavedProgressState.None)
    val savedProgressState: StateFlow<SavedProgressState> = _savedProgressState.asStateFlow()

    private val _appForegroundState = MutableStateFlow(false)
    val appForegroundState: StateFlow<Boolean> = _appForegroundState.asStateFlow()

    fun setAppForeground(isForeground: Boolean) {
        _appForegroundState.value = isForeground
    }

    private var activeSessionId: Long = 0L
    val currentSessionId: Long
        @VisibleForTesting get() = activeSessionId

    private var restartJob: Job? = null
    private var latestSettings: ReadMeSettings = ReadMeSettings()

    init {
        // Collect speech state to synchronize session coordinator
        runtimeScope.launch {
            speechEngine.state.collect { tts ->
                sessionCoordinator.updateSpeechState(tts)
            }
        }

        // Collect reading position changes to synchronize session coordinator
        runtimeScope.launch {
            readingEngine.currentPosition.collect {
                sessionCoordinator.syncSessionState()
            }
        }

        // Cache latest settings for background segment speech
        settingsRepository?.let { repo ->
            runtimeScope.launch {
                repo.settingsFlow.collect { settings ->
                    latestSettings = settings
                }
            }

            // Select fallback voice if required
            runtimeScope.launch {
                combine(speechEngine.availableVoices, repo.settingsFlow) { voices, currentSettings ->
                    voices to currentSettings
                }.collect { (voices, currentSettings) ->
                    if (voices.isNotEmpty()) {
                        val currentVoiceId = currentSettings.selectedVoice
                        val voiceExists = voices.any { it.id == currentVoiceId }
                        if (!voiceExists || currentVoiceId == "natural_voice" || currentVoiceId.isBlank()) {
                            val defaultVoice = voices.first()
                            repo.updateSelectedVoice(defaultVoice.id)
                        }
                    }
                }
            }
        }

        // Set up the authoritative speech progression listener
        speechEngine.setSpeechListener(object : SpeechEngineListener {
            override fun onSegmentStarted(segmentId: String, sessionId: Long) {
                // Segment speech started
            }

            override fun onSegmentCompleted(segmentId: String, sessionId: Long) {
                runtimeScope.launch {
                    handleSegmentCompleted(segmentId, sessionId)
                }
            }

            override fun onSegmentError(segmentId: String, sessionId: Long, errorCode: Int) {
                runtimeScope.launch {
                    handleSegmentError(segmentId, sessionId, errorCode)
                }
            }
        })
    }

    fun addLifecycleListener(listener: PlaybackLifecycleListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeLifecycleListener(listener: PlaybackLifecycleListener) {
        listeners.remove(listener)
    }

    private fun handleSegmentCompleted(segmentId: String, sessionId: Long) {
        if (sessionId != activeSessionId || activeSessionId == 0L) return
        if (readingEngine.readingState.value != ReadingSessionState.Reading) return

        val nextSegment = sessionCoordinator.advanceReading()
        if (nextSegment != null) {
            saveCurrentProgress(isCompleted = false)
            val settings = latestSettings
            speechEngine.speakSegment(
                segmentId = nextSegment.id,
                text = nextSegment.text,
                sessionId = activeSessionId,
                voiceId = settings.selectedVoice,
                speed = settings.speechSpeed,
                pitch = settings.speechPitch,
                volume = settings.speechVolume
            )
        } else {
            sessionCoordinator.onReadingCompleted()
            saveCurrentProgress(isCompleted = true)
            notifyCompleted()
        }
    }

    private fun handleSegmentError(segmentId: String, sessionId: Long, errorCode: Int) {
        if (sessionId != activeSessionId || activeSessionId == 0L) return
        val message = "Speech error: $errorCode"
        sessionCoordinator.onReadingError(message)
        notifyError(message)
    }

    private fun notifyStarted() {
        for (listener in listeners) {
            listener.onReadingStarted()
        }
    }

    private fun notifyStopped() {
        for (listener in listeners) {
            listener.onReadingStopped()
        }
    }

    private fun notifyCompleted() {
        for (listener in listeners) {
            listener.onReadingCompleted()
        }
    }

    private fun notifyError(message: String?) {
        for (listener in listeners) {
            listener.onReadingError(message)
        }
    }

    /**
     * Starts reading from the current position (or beginning if unstarted/completed).
     * Enforces that a valid active document exists before starting speech.
     */
    fun startReading(): ReadingSegment? {
        restartJob?.cancel()
        if (!sessionCoordinator.hasActiveDocument) {
            return null
        }

        activeSessionId = System.currentTimeMillis()
        val thisSessionId = activeSessionId

        val segmentToSpeak = sessionCoordinator.startReading()
        if (segmentToSpeak != null) {
            saveCurrentProgress(isCompleted = false)
            val settings = latestSettings
            speechEngine.speakSegment(
                segmentId = segmentToSpeak.id,
                text = segmentToSpeak.text,
                sessionId = thisSessionId,
                voiceId = settings.selectedVoice,
                speed = settings.speechSpeed,
                pitch = settings.speechPitch,
                volume = settings.speechVolume
            )
            notifyStarted()
        }
        return segmentToSpeak
    }

    /**
     * Stops the reading session and halts speech synthesis.
     * Preserves current document and position.
     */
    fun stopReading() {
        restartJob?.cancel()
        activeSessionId = 0L
        sessionCoordinator.stopReading()
        speechEngine.stop()
        saveCurrentProgress(isCompleted = false)
        notifyStopped()
    }

    /**
     * Restarts reading from the first segment of the document, resetting saved progress.
     */
    fun restartReadingFromBeginning() {
        stopReading()
        val doc = readingEngine.currentDocument
        if (doc.id.isNotBlank()) {
            runtimeScope.launch(ioDispatcher) {
                progressRepository.clearProgress(doc.id)
            }
        }
        readingEngine.reset()
        _savedProgressState.value = SavedProgressState.None
        startReading()
    }

    /**
     * Restores saved progress for [documentId] if valid for the loaded document.
     */
    suspend fun restoreProgressIfAvailable(documentId: String) {
        val doc = readingEngine.currentDocument
        if (doc.id != documentId || doc.id.isBlank()) {
            _savedProgressState.value = SavedProgressState.None
            return
        }

        val progress = progressRepository.loadProgress(documentId)
        if (progress != null && ReadingProgressValidator.validate(progress, doc)) {
            if (progress.isCompleted) {
                _savedProgressState.value = SavedProgressState.Completed(progress)
            } else {
                val position = progress.toReadingPosition()
                sessionCoordinator.setPosition(position)
                _savedProgressState.value = SavedProgressState.Resumable(progress)
            }
        } else {
            _savedProgressState.value = SavedProgressState.None
        }
    }

    /**
     * Persists the current reading position to the repository.
     */
    fun saveCurrentProgress(isCompleted: Boolean = false) {
        val position = readingEngine.currentPosition.value ?: return
        val doc = readingEngine.currentDocument
        if (doc.id.isBlank() || position.documentId != doc.id) return

        val progress = ReadingProgress.fromPosition(
            position = position,
            sourceType = doc.metadata.sourceType,
            isCompleted = isCompleted,
            lastUpdated = System.currentTimeMillis()
        )

        runtimeScope.launch(ioDispatcher) {
            try {
                progressRepository.saveProgress(progress)
                if (!isCompleted) {
                    _savedProgressState.value = SavedProgressState.Resumable(progress)
                } else {
                    _savedProgressState.value = SavedProgressState.Completed(progress)
                }
            } catch (e: Exception) {
                Log.e("ReadMeSessionRuntime", "Failed to save reading progress", e)
            }
        }
    }

    /**
     * Clears saved progress state in memory.
     */
    fun clearSavedProgressState() {
        _savedProgressState.value = SavedProgressState.None
    }

    /**
     * Cleanly shuts down the runtime and speech engine.
     */
    fun shutdown() {
        stopReading()
        speechEngine.shutdown()
        listeners.clear()
    }

    @VisibleForTesting
    fun handleSegmentCompletedForTesting(segmentId: String, sessionId: Long) {
        handleSegmentCompleted(segmentId, sessionId)
    }

    @VisibleForTesting
    fun handleSegmentErrorForTesting(segmentId: String, sessionId: Long, errorCode: Int) {
        handleSegmentError(segmentId, sessionId, errorCode)
    }

    companion object {
        @Volatile
        private var instance: ReadMeReadingSessionRuntime? = null

        fun getInstance(context: Context): ReadMeReadingSessionRuntime {
            return instance ?: synchronized(this) {
                instance ?: ReadMeReadingSessionRuntime(context.applicationContext).also {
                    instance = it
                }
            }
        }

        @VisibleForTesting
        fun createForTesting(
            context: Context,
            progressRepository: ReadingProgressRepository
        ): ReadMeReadingSessionRuntime {
            val runtime = ReadMeReadingSessionRuntime(
                context = context.applicationContext,
                progressRepository = progressRepository
            )
            instance = runtime
            return runtime
        }

        @VisibleForTesting
        fun resetForTesting() {
            instance?.shutdown()
            instance = null
        }

        @VisibleForTesting
        fun setInstanceForTesting(runtime: ReadMeReadingSessionRuntime?) {
            instance = runtime
        }
    }
}
