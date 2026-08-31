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

interface SpeechEngineListener {
    fun onSegmentStarted(segmentId: String, sessionId: Long)
    fun onSegmentCompleted(segmentId: String, sessionId: Long)
    fun onSegmentError(segmentId: String, sessionId: Long, errorCode: Int)
}

class ReadMeSpeechEngine(context: Context) {

    private val lock = Any()
    private var tts: TextToSpeech? = null
    
    private val _state = MutableStateFlow(TtsState.Uninitialized)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<ReadMeVoice>>(emptyList())
    val availableVoices: StateFlow<List<ReadMeVoice>> = _availableVoices.asStateFlow()

    private var speechListener: SpeechEngineListener? = null

    private var activeSessionId: Long = 0L
    private var activeSubId: Long = 0L
    private var activeSegmentId: String = ""
    private var activeSegmentText: String = ""
    private var isSpeakingActive: Boolean = false

    private var activeVoiceId: String = ""
    private var activeSpeed: Float = 1.0f
    private var activePitch: Float = 0.50f
    private var activeVolume: Float = 0.70f

    companion object {
        private const val UTTERANCE_PREFIX = "readme_seg"
    }

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

    fun setSpeechListener(listener: SpeechEngineListener?) {
        synchronized(lock) {
            this.speechListener = listener
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == null || !utteranceId.startsWith(UTTERANCE_PREFIX)) return

                val parts = utteranceId.split("|")
                if (parts.size >= 4) {
                    val sessionId = parts[1].toLongOrNull() ?: return
                    val subId = parts[2].toLongOrNull() ?: return
                    val segmentId = parts[3]

                    synchronized(lock) {
                        if (sessionId == activeSessionId && subId == activeSubId && isSpeakingActive) {
                            _state.value = TtsState.Speaking
                            speechListener?.onSegmentStarted(segmentId, sessionId)
                        }
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == null || !utteranceId.startsWith(UTTERANCE_PREFIX)) return

                val parts = utteranceId.split("|")
                if (parts.size >= 4) {
                    val sessionId = parts[1].toLongOrNull() ?: return
                    val subId = parts[2].toLongOrNull() ?: return
                    val segmentId = parts[3]

                    var notifyListener = false
                    var listenerRef: SpeechEngineListener? = null

                    synchronized(lock) {
                        if (sessionId == activeSessionId && subId == activeSubId && isSpeakingActive) {
                            _state.value = TtsState.Ready
                            notifyListener = true
                            listenerRef = speechListener
                        }
                    }

                    if (notifyListener) {
                        listenerRef?.onSegmentCompleted(segmentId, sessionId)
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                // Legacy callback
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == null || !utteranceId.startsWith(UTTERANCE_PREFIX)) return

                val parts = utteranceId.split("|")
                if (parts.size >= 4) {
                    val sessionId = parts[1].toLongOrNull() ?: return
                    val subId = parts[2].toLongOrNull() ?: return
                    val segmentId = parts[3]

                    var notifyError = false
                    var listenerRef: SpeechEngineListener? = null

                    synchronized(lock) {
                        if (sessionId == activeSessionId && subId == activeSubId && isSpeakingActive) {
                            isSpeakingActive = false
                            _state.value = TtsState.Ready
                            notifyError = true
                            listenerRef = speechListener
                        }
                    }

                    if (notifyError) {
                        listenerRef?.onSegmentError(segmentId, sessionId, errorCode)
                    }
                }
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

    /**
     * Synthesizes a single segment.
     */
    fun speakSegment(
        segmentId: String,
        text: String,
        sessionId: Long,
        voiceId: String,
        speed: Float,
        pitch: Float,
        volume: Float
    ) {
        val ttsInstance = tts
        if (ttsInstance == null || (_state.value != TtsState.Ready && _state.value != TtsState.Speaking && _state.value != TtsState.Stopped)) {
            return
        }

        synchronized(lock) {
            activeSessionId = sessionId
            activeSubId = System.currentTimeMillis()
            activeSegmentId = segmentId
            activeSegmentText = text
            activeVoiceId = voiceId
            activeSpeed = speed
            activePitch = pitch
            activeVolume = volume
            isSpeakingActive = true

            performSynthesis(segmentId, text, sessionId, activeSubId)
        }
    }

    /**
     * Updates speech settings and immediately restarts synthesis of the active segment.
     * Stale callbacks from the previous sub-session are invalidated via incremented activeSubId.
     */
    fun updateSettingsAndRestart(
        voiceId: String,
        speed: Float,
        pitch: Float,
        volume: Float
    ) {
        synchronized(lock) {
            activeVoiceId = voiceId
            activeSpeed = speed
            activePitch = pitch
            activeVolume = volume

            if (!isSpeakingActive || activeSegmentText.isBlank()) {
                return
            }

            activeSubId = System.currentTimeMillis()
            tts?.stop()
            performSynthesis(activeSegmentId, activeSegmentText, activeSessionId, activeSubId)
        }
    }

    private fun performSynthesis(
        segmentId: String,
        text: String,
        sessionId: Long,
        subId: Long
    ) {
        val ttsInstance = tts ?: return
        try {
            // 1. Resolve and apply Voice
            val rawVoices = ttsInstance.voices
            val selectedVoice = rawVoices?.find { it.name == activeVoiceId }
            if (selectedVoice != null) {
                ttsInstance.voice = selectedVoice
            } else {
                val fallbackVoice = rawVoices?.firstOrNull()
                if (fallbackVoice != null) {
                    ttsInstance.voice = fallbackVoice
                }
            }

            // 2. Speech Rate: speed is in 0.5f..2.0f. Android TTS normal is 1.0f.
            ttsInstance.setSpeechRate(activeSpeed.coerceIn(0.5f, 2.0f))

            // 3. Pitch: pitch is normalized 0.0f..1.0f with 0.5f as Mid (normal).
            // Map 0.0f..1.0f to 0.5f..1.5f so 0.5f -> 1.0f (normal pitch).
            val ttsPitch = (0.5f + activePitch * 1.0f).coerceIn(0.5f, 2.0f)
            ttsInstance.setPitch(ttsPitch)

            // 4. Volume: Utterance-level volume via KEY_PARAM_VOLUME in Bundle
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, activeVolume.coerceIn(0.0f, 1.0f))
            }

            val utteranceId = "$UTTERANCE_PREFIX|$sessionId|$subId|$segmentId"

            val result = ttsInstance.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result == TextToSpeech.SUCCESS) {
                _state.value = TtsState.Speaking
            } else {
                _state.value = TtsState.Error
                isSpeakingActive = false
                speechListener?.onSegmentError(segmentId, sessionId, result)
            }
        } catch (e: Exception) {
            _state.value = TtsState.Error
            isSpeakingActive = false
            speechListener?.onSegmentError(segmentId, sessionId, -1)
        }
    }

    fun stop() {
        synchronized(lock) {
            isSpeakingActive = false
            activeSessionId = 0L
            activeSubId = 0L
            activeSegmentId = ""
            activeSegmentText = ""
            try {
                tts?.stop()
                _state.value = TtsState.Ready
            } catch (e: Exception) {
                // Gracefully ignore stop errors
            }
        }
    }

    fun shutdown() {
        synchronized(lock) {
            isSpeakingActive = false
            activeSessionId = 0L
            activeSubId = 0L
            activeSegmentId = ""
            activeSegmentText = ""
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
}
