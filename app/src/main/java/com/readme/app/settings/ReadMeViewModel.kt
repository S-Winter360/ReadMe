package com.readme.app.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.readme.app.speech.ReadMeSpeechEngine
import com.readme.app.speech.ReadMeVoice
import com.readme.app.speech.TtsState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReadMeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ReadMeSettingsRepository(application)
    
    val speechEngine = ReadMeSpeechEngine(application)

    val availableVoices: StateFlow<List<ReadMeVoice>> = speechEngine.availableVoices
    val ttsState: StateFlow<TtsState> = speechEngine.state

    val settings: StateFlow<ReadMeSettings> = repository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ReadMeSettings()
    )

    init {
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

    fun updateSelectedVoice(voice: String) {
        viewModelScope.launch {
            repository.updateSelectedVoice(voice)
        }
    }

    fun updateSpeechVolume(volume: Float) {
        viewModelScope.launch {
            repository.updateSpeechVolume(volume)
        }
    }

    fun updateSpeechSpeed(speed: Float) {
        viewModelScope.launch {
            repository.updateSpeechSpeed(speed)
        }
    }

    fun updateSpeechPitch(pitch: Float) {
        viewModelScope.launch {
            repository.updateSpeechPitch(pitch)
        }
    }

    fun startReading() {
        val currentSettings = settings.value
        val samplePassage = "Welcome to ReadMe. This is a test of your selected voice, speech speed, pitch, and volume. ReadMe is ready to read."
        speechEngine.speak(
            text = samplePassage,
            voiceId = currentSettings.selectedVoice,
            speed = currentSettings.speechSpeed,
            pitch = currentSettings.speechPitch,
            volume = currentSettings.speechVolume
        )
    }

    fun stopReading() {
        speechEngine.stop()
    }

    override fun onCleared() {
        super.onCleared()
        speechEngine.shutdown()
    }
}

