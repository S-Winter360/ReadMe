package com.readme.app.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReadMeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ReadMeSettingsRepository(application)

    val settings: StateFlow<ReadMeSettings> = repository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ReadMeSettings()
    )

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
}
