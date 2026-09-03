package com.readme.app.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingEngine
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionState
import com.readme.app.reading.content.DetectedFormat
import com.readme.app.reading.content.DocumentFormatDetector
import com.readme.app.reading.content.ReadingContentSource
import com.readme.app.reading.content.SampleContentSource
import com.readme.app.reading.content.TxtContentSource
import com.readme.app.reading.content.epub.EpubContentSource
import com.readme.app.reading.content.pdf.PdfContentSource
import com.readme.app.reading.content.pdf.PdfNotSupportedException
import com.readme.app.speech.ReadMeSpeechEngine
import com.readme.app.speech.ReadMeVoice
import com.readme.app.speech.SpeechEngineListener
import com.readme.app.speech.TtsState
import com.readme.app.ui.pdf.PdfViewerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReadMeViewModel @JvmOverloads constructor(
    application: Application,
    private val contentSource: ReadingContentSource = SampleContentSource()
) : AndroidViewModel(application) {

    private val repository = ReadMeSettingsRepository(application)
    
    val speechEngine = ReadMeSpeechEngine(application)
    val readingEngine = ReadingEngine()

    private var activeContentSource: ReadingContentSource = contentSource

    private val _selectedDocumentName = MutableStateFlow<String?>(null)
    val selectedDocumentName: StateFlow<String?> = _selectedDocumentName.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _pdfViewerState = MutableStateFlow<PdfViewerState>(PdfViewerState.Empty)
    val pdfViewerState: StateFlow<PdfViewerState> = _pdfViewerState.asStateFlow()

    val availableVoices: StateFlow<List<ReadMeVoice>> = speechEngine.availableVoices
    val ttsState: StateFlow<TtsState> = speechEngine.state
    val readingState: StateFlow<ReadingSessionState> = readingEngine.readingState
    val currentSegment: StateFlow<ReadingSegment?> = readingEngine.currentSegment
    val currentPosition: StateFlow<ReadingPosition?> = readingEngine.currentPosition

    val settings: StateFlow<ReadMeSettings> = repository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ReadMeSettings()
    )

    private var activeSessionId: Long = 0L
    private var restartJob: Job? = null

    init {
        // Load initial document from content source
        viewModelScope.launch {
            try {
                val document = activeContentSource.load()
                readingEngine.loadDocument(document)
            } catch (e: Exception) {
                readingEngine.setError()
            }
        }

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

    fun selectTextFile(uri: Uri) {
        selectDocument(uri)
    }

    fun selectDocument(uri: Uri) {
        stopReading()
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            // Some providers do not support persistable permissions; proceed safely
        }

        val resolver = getApplication<Application>().contentResolver
        val displayName = TxtContentSource.resolveDisplayName(resolver, uri)
        val mimeType = resolver.getType(uri)
        val format = DocumentFormatDetector.detect(mimeType, displayName)

        _selectedDocumentName.value = displayName
        _loadError.value = null

        when (format) {
            DetectedFormat.PDF -> {
                // Clear any existing visual PDF state immediately to prevent showing stale content
                _pdfViewerState.value = PdfViewerState.Loading(uri)
                val pdfSource = PdfContentSource(getApplication(), uri, displayName)
                activeContentSource = pdfSource

                viewModelScope.launch {
                    try {
                        val document = pdfSource.load()
                        if (document.sections.isEmpty() || document.allSegments().isEmpty()) {
                            _loadError.value = "No readable text found in selected PDF"
                            _selectedDocumentName.value = null
                            _pdfViewerState.value = PdfViewerState.Empty
                            readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
                            readingEngine.setError()
                        } else {
                            readingEngine.loadDocument(document)
                            _pdfViewerState.value = PdfViewerState.Active(uri, displayName)
                        }
                    } catch (e: com.readme.app.reading.content.pdf.PdfPasswordRequiredException) {
                        _loadError.value = "Password required for this PDF."
                        _selectedDocumentName.value = null
                        _pdfViewerState.value = PdfViewerState.Empty
                        readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
                        readingEngine.setError()
                    } catch (e: com.readme.app.reading.content.pdf.PdfNoSelectableTextException) {
                        _loadError.value = "No selectable text was found in this PDF."
                        _selectedDocumentName.value = null
                        _pdfViewerState.value = PdfViewerState.Empty
                        readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
                        readingEngine.setError()
                    } catch (e: Exception) {
                        _loadError.value = "Unable to read selected PDF file"
                        _selectedDocumentName.value = null
                        _pdfViewerState.value = PdfViewerState.Empty
                        readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
                        readingEngine.setError()
                    }
                }
            }
            DetectedFormat.EPUB -> {
                _pdfViewerState.value = PdfViewerState.Empty
                val epubSource = EpubContentSource(getApplication(), uri, displayName)
                activeContentSource = epubSource

                viewModelScope.launch {
                    try {
                        val document = epubSource.load()
                        if (document.sections.isEmpty() || document.allSegments().isEmpty()) {
                            _loadError.value = "No readable text found in selected EPUB"
                            _selectedDocumentName.value = null
                            readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
                            readingEngine.setError()
                        } else {
                            readingEngine.loadDocument(document)
                        }
                    } catch (e: com.readme.app.reading.content.epub.EpubDrmException) {
                        _loadError.value = "DRM-protected EPUB files are not supported"
                        _selectedDocumentName.value = null
                        readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
                        readingEngine.setError()
                    } catch (e: Exception) {
                        _loadError.value = "Unable to read selected EPUB file"
                        _selectedDocumentName.value = null
                        readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
                        readingEngine.setError()
                    }
                }
            }
            DetectedFormat.TXT -> {
                _pdfViewerState.value = PdfViewerState.Empty
                val txtSource = TxtContentSource(getApplication(), uri, displayName)
                activeContentSource = txtSource

                viewModelScope.launch {
                    try {
                        val document = txtSource.load()
                        readingEngine.loadDocument(document)
                    } catch (e: Exception) {
                        _loadError.value = "Unable to read selected text file"
                        _selectedDocumentName.value = null
                        readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
                        readingEngine.setError()
                    }
                }
            }
            DetectedFormat.UNKNOWN -> {
                _pdfViewerState.value = PdfViewerState.Empty
                _loadError.value = "Unsupported document format"
                _selectedDocumentName.value = null
                readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
                readingEngine.setError()
            }
        }
    }

    fun startReading() {
        restartJob?.cancel()
        val currentSettings = settings.value
        activeSessionId = System.currentTimeMillis()
        val thisSessionId = activeSessionId

        viewModelScope.launch {
            if (readingEngine.totalSegments() == 0 && _selectedDocumentName.value == null) {
                try {
                    val document = activeContentSource.load()
                    readingEngine.loadDocument(document)
                } catch (e: Exception) {
                    readingEngine.setError()
                    return@launch
                }
            }

            if (activeSessionId != thisSessionId || activeSessionId == 0L) return@launch

            val segmentToSpeak = if (readingEngine.readingState.value == ReadingSessionState.Stopped && readingEngine.currentPosition.value != null) {
                readingEngine.resumeFromCurrentPosition()
            } else {
                readingEngine.startFromBeginning()
            }

            if (segmentToSpeak != null) {
                speechEngine.speakSegment(
                    segmentId = segmentToSpeak.id,
                    text = segmentToSpeak.text,
                    sessionId = thisSessionId,
                    voiceId = currentSettings.selectedVoice,
                    speed = currentSettings.speechSpeed,
                    pitch = currentSettings.speechPitch,
                    volume = currentSettings.speechVolume
                )
            }
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
