package com.readme.app.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class TtsState {
    Uninitialized,
    Initializing,
    Ready,
    Speaking,
    Stopped,
    Error
}

data class ReadMeVoice(
    val id: String,
    val displayName: String,
    val locale: Locale,
    val isNetworkConnectionRequired: Boolean
)

class ReadMeSpeechEngine(context: Context) {

    private var tts: TextToSpeech? = null
    
    private val _state = MutableStateFlow(TtsState.Uninitialized)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<ReadMeVoice>>(emptyList())
    val availableVoices: StateFlow<List<ReadMeVoice>> = _availableVoices.asStateFlow()

    init {
        _state.value = TtsState.Initializing
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                setupUtteranceListener()
                _state.value = TtsState.Ready
                discoverVoices()
            } else {
                _state.value = TtsState.Error
            }
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _state.value = TtsState.Speaking
            }

            override fun onDone(utteranceId: String?) {
                _state.value = TtsState.Ready
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _state.value = TtsState.Ready
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _state.value = TtsState.Ready
            }
        })
    }

    private fun discoverVoices() {
        val ttsInstance = tts ?: return
        try {
            val rawVoices: Set<Voice>? = ttsInstance.voices
            if (rawVoices.isNullOrEmpty()) {
                _availableVoices.value = emptyList()
                return
            }

            val defaultLocale = Locale.getDefault()
            val voiceList = rawVoices.toList()

            // Group voices by locale display name to distinguish multiple voices in the same locale
            val localeGroups = voiceList.groupBy { 
                if (it.locale.displayName.isNotBlank()) it.locale.displayName else it.name 
            }

            val readMeVoices = voiceList.map { voice ->
                val localeDisplayName = if (voice.locale.displayName.isNotBlank()) {
                    voice.locale.displayName
                } else {
                    voice.name
                }
                
                val group = localeGroups[localeDisplayName].orEmpty()
                val displayName = if (group.size > 1) {
                    val index = group.indexOf(voice) + 1
                    "$localeDisplayName • Voice $index"
                } else {
                    localeDisplayName
                }

                ReadMeVoice(
                    id = voice.name,
                    displayName = displayName,
                    locale = voice.locale,
                    isNetworkConnectionRequired = voice.isNetworkConnectionRequired
                )
            }.sortedWith(
                compareBy<ReadMeVoice> { voice ->
                    // Prioritize default device locale
                    if (voice.locale.language == defaultLocale.language && voice.locale.country == defaultLocale.country) 0
                    else if (voice.locale.language == defaultLocale.language) 1
                    else 2
                }.thenBy { it.locale.displayName }
                 .thenBy { it.displayName }
            )

            _availableVoices.value = readMeVoices
        } catch (e: Exception) {
            _state.value = TtsState.Error
        }
    }

    fun speak(
        text: String,
        voiceId: String,
        speed: Float,
        pitch: Float,
        volume: Float
    ) {
        val ttsInstance = tts
        if (ttsInstance == null || (_state.value != TtsState.Ready && _state.value != TtsState.Speaking && _state.value != TtsState.Stopped)) {
            return
        }

        try {
            // 1. Resolve and apply Voice
            val rawVoices = ttsInstance.voices
            val selectedVoice = rawVoices?.find { it.name == voiceId }
            if (selectedVoice != null) {
                ttsInstance.voice = selectedVoice
            } else {
                val fallbackVoice = rawVoices?.firstOrNull()
                if (fallbackVoice != null) {
                    ttsInstance.voice = fallbackVoice
                }
            }

            // 2. Speech Rate: speed is in 0.5f..2.0f. Android TTS normal is 1.0f.
            ttsInstance.setSpeechRate(speed.coerceIn(0.5f, 2.0f))

            // 3. Pitch: pitch is normalized 0.0f..1.0f with 0.5f as Mid (normal).
            // Android TTS normal pitch is 1.0f.
            // Map 0.0f..1.0f to 0.5f..1.5f so 0.5f -> 1.0f (normal pitch).
            val ttsPitch = (0.5f + pitch * 1.0f).coerceIn(0.5f, 2.0f)
            ttsInstance.setPitch(ttsPitch)

            // 4. Volume: Utterance-level volume via KEY_PARAM_VOLUME in Bundle
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0.0f, 1.0f))
            }

            val utteranceId = "readme_sample_utterance_${System.currentTimeMillis()}"

            // QUEUE_FLUSH prevents duplicate speech accumulation
            val result = ttsInstance.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result == TextToSpeech.SUCCESS) {
                _state.value = TtsState.Speaking
            } else {
                _state.value = TtsState.Error
            }
        } catch (e: Exception) {
            _state.value = TtsState.Error
        }
    }

    fun stop() {
        try {
            tts?.stop()
            if (_state.value == TtsState.Speaking) {
                _state.value = TtsState.Stopped
            }
        } catch (e: Exception) {
            // Gracefully ignore stop errors
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            // Gracefully ignore shutdown errors
        } finally {
            tts = null
            _state.value = TtsState.Uninitialized
            _availableVoices.value = emptyList()
        }
    }
}
