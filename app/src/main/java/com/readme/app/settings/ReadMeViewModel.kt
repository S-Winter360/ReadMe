package com.readme.app.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.readme.app.reading.ReadingEngine
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionState
import com.readme.app.reading.SampleContent
import com.readme.app.speech.ReadMeSpeechEngine
import com.readme.app.speech.ReadMeVoice
import com.readme.app.speech.SpeechEngineListener
import com.readme.app.speech.TtsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReadMeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ReadMeSettingsRepository(application)
    
    val speechEngine = ReadMeSpeechEngine(application)
    val readingEngine = ReadingEngine(SampleContent.sampleDocument)

    val availableVoices: StateFlow<List<ReadMeVoice>> = speechEngine.availableVoices
    val ttsState: StateFlow<TtsState> = speechEngine.state
    val readingState: StateFlow<ReadingSessionState> = readingEngine.readingState
    val currentSegment: StateFlow<ReadingSegment?> = readingEngine.currentSegment

    val settings: StateFlow<ReadMeSettings> = repository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ReadMeSettings()
    )

    private var activeSessionId: Long = 0L
    private var restartJob: Job? = null

    init {
        speechEngine.setSpeechListener(object : SpeechEngineListener {
            override fun onSegmentStarted(segmentId: String, sessionId: Long) {
                // Segment speech started
            }

            override fun onSegmentCompleted(segmentId: String, sessionId: Long) {
                viewModelScope.launch {
                    handleSegmentCompleted(segmentId, sessionId)
                }
            }

            override fun onSegmentError(segmentId: String, sessionId: Long, errorCode: Int) {
                viewModelScope.launch {
                    handleSegmentError(segmentId, sessionId, errorCode)
                }
            }
        })

        viewModelScope.launch {
            combine(speechEngine.availableVoices, repository.settingsFlow) { voices, currentSettings ->
                voices to currentSettings
            }.collect { (voices, currentSettings) ->
                if (voices.isNotEmpty()) {
                    val currentVoiceId = currentSettings.selectedVoice
                    val voiceExists = voices.any { it.id == currentVoiceId }
                    if (!voiceExists || currentVoiceId == "natural_voice" || currentVoiceId.isBlank()) {
                        // Fallback to top-ranked available voice (device locale preferred)
                        val defaultVoice = voices.first()
                        repository.updateSelectedVoice(defaultVoice.id)
                    }
                }
            }
        }
    }

    private fun handleSegmentCompleted(segmentId: String, sessionId: Long) {
        if (sessionId != activeSessionId || activeSessionId == 0L) return
        if (readingEngine.readingState.value != ReadingSessionState.Reading) return

        val nextSegment = readingEngine.advance()
        if (nextSegment != null) {
            val currentSettings = settings.value
            speechEngine.speakSegment(
                segmentId = nextSegment.id,
                text = nextSegment.text,
                sessionId = activeSessionId,
                voiceId = currentSettings.selectedVoice,
                speed = currentSettings.speechSpeed,
                pitch = currentSettings.speechPitch,
                volume = currentSettings.speechVolume
            )
        }
    }

    private fun handleSegmentError(segmentId: String, sessionId: Long, errorCode: Int) {
        if (sessionId != activeSessionId || activeSessionId == 0L) return
        readingEngine.setError()
        speechEngine.stop()
    }

    private fun scheduleRestart(
        voiceId: String = settings.value.selectedVoice,
        speed: Float = settings.value.speechSpeed,
        pitch: Float = settings.value.speechPitch,
        volume: Float = settings.value.speechVolume,
        immediate: Boolean = false
    ) {
        if (readingEngine.readingState.value != ReadingSessionState.Reading) return
        restartJob?.cancel()
        if (immediate) {
            speechEngine.updateSettingsAndRestart(
                voiceId = voiceId,
                speed = speed,
                pitch = pitch,
                volume = volume
            )
        } else {
            restartJob = viewModelScope.launch {
                delay(50)
                speechEngine.updateSettingsAndRestart(
                    voiceId = voiceId,
                    speed = speed,
                    pitch = pitch,
                    volume = volume
                )
            }
        }
    }

    fun updateSelectedVoice(voice: String) {
        viewModelScope.launch {
            repository.updateSelectedVoice(voice)
        }
        scheduleRestart(voiceId = voice, immediate = true)
    }

    fun updateSpeechVolume(volume: Float) {
        viewModelScope.launch {
            repository.updateSpeechVolume(volume)
        }
        scheduleRestart(volume = volume)
    }

    fun updateSpeechSpeed(speed: Float) {
        viewModelScope.launch {
            repository.updateSpeechSpeed(speed)
        }
        scheduleRestart(speed = speed)
    }

    fun updateSpeechPitch(pitch: Float) {
        viewModelScope.launch {
            repository.updateSpeechPitch(pitch)
        }
        scheduleRestart(pitch = pitch)
    }

    fun startReading() {
        restartJob?.cancel()
        activeSessionId = System.currentTimeMillis()
        val firstSegment = readingEngine.startSession()
        if (firstSegment != null) {
            val currentSettings = settings.value
            speechEngine.speakSegment(
                segmentId = firstSegment.id,
                text = firstSegment.text,
                sessionId = activeSessionId,
                voiceId = currentSettings.selectedVoice,
                speed = currentSettings.speechSpeed,
                pitch = currentSettings.speechPitch,
                volume = currentSettings.speechVolume
            )
        }
    }

    fun stopReading() {
        restartJob?.cancel()
        activeSessionId = 0L
        readingEngine.stop()
        speechEngine.stop()
    }

    override fun onCleared() {
        super.onCleared()
        restartJob?.cancel()
        readingEngine.stop()
        speechEngine.shutdown()
    }
}
