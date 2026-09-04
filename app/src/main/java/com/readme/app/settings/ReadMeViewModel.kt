package com.readme.app.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import com.readme.app.reading.content.pdf.PdfReadingPositionMapper
import com.readme.app.reading.content.pdf.PdfReadingSyncState
import com.readme.app.speech.ReadMeSpeechEngine
import com.readme.app.speech.ReadMeVoice
import com.readme.app.speech.SpeechEngineListener
import com.readme.app.speech.TtsState
import com.readme.app.ui.pdf.PdfNavigationCoordinator
import com.readme.app.ui.pdf.PdfPageNavigator
import com.readme.app.ui.pdf.PdfViewerState
import com.readme.app.ui.pdf.PdfViewportState
import com.readme.app.ui.pdf.PendingPdfNavigation
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
    private var pdfMapper: PdfReadingPositionMapper? = null

    private val _selectedDocumentName = MutableStateFlow<String?>(null)
    val selectedDocumentName: StateFlow<String?> = _selectedDocumentName.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _pdfViewerState = MutableStateFlow<PdfViewerState>(PdfViewerState.Empty)
    val pdfViewerState: StateFlow<PdfViewerState> = _pdfViewerState.asStateFlow()

    private val _pdfViewportState = MutableStateFlow(PdfViewportState())
    val pdfViewportState: StateFlow<PdfViewportState> = _pdfViewportState.asStateFlow()

    private val _pdfReadingSyncState = MutableStateFlow(PdfReadingSyncState.Empty)
    val pdfReadingSyncState: StateFlow<PdfReadingSyncState> = _pdfReadingSyncState.asStateFlow()

    private val navigationCoordinator = PdfNavigationCoordinator()
    val pendingPdfNavigation: StateFlow<PendingPdfNavigation?> = navigationCoordinator.pendingNavigation

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

        viewModelScope.launch {
            readingEngine.currentPosition.collect { position ->
                updatePdfSyncState()
                evaluatePdfNavigation(position)
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
        } else {
            navigationCoordinator.setReadingState(ReadingSessionState.Completed)
            updatePdfSyncState()
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
        navigationCoordinator.clearDocument()
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
                pdfMapper = null
                _pdfViewportState.value = PdfViewportState()
                _pdfViewerState.value = PdfViewerState.Loading(uri)
                updatePdfSyncState()

                val pdfSource = PdfContentSource(getApplication(), uri, displayName)
                activeContentSource = pdfSource

                viewModelScope.launch {
                    try {
                        val document = pdfSource.load()
                        if (document.sections.isEmpty() || document.allSegments().isEmpty()) {
                            _loadError.value = "No readable text found in selected PDF"
                            _selectedDocumentName.value = null
                            pdfMapper = null
                            _pdfViewportState.value = PdfViewportState()
                            _pdfViewerState.value = PdfViewerState.Empty
                            readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
                            readingEngine.setError()
                            updatePdfSyncState()
                        } else {
                            readingEngine.loadDocument(document)
                            val mapper = PdfReadingPositionMapper.fromDocument(document)
                            pdfMapper = mapper
                            navigationCoordinator.setPdfDocument(mapper, isActive = true)
                            _pdfViewerState.value = PdfViewerState.Active(uri, displayName)
                            updatePdfSyncState()
                        }
                    } catch (e: com.readme.app.reading.content.pdf.PdfPasswordRequiredException) {
                        _loadError.value = "Password required for this PDF."
                        _selectedDocumentName.value = null
                        pdfMapper = null
                        _pdfViewportState.value = PdfViewportState()
                        _pdfViewerState.value = PdfViewerState.Empty
                        readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
                        readingEngine.setError()
                        updatePdfSyncState()
                    } catch (e: com.readme.app.reading.content.pdf.PdfNoSelectableTextException) {
                        _loadError.value = "No selectable text was found in this PDF."
                        _selectedDocumentName.value = null
                        pdfMapper = null
                        _pdfViewportState.value = PdfViewportState()
                        _pdfViewerState.value = PdfViewerState.Empty
                        readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
                        readingEngine.setError()
                        updatePdfSyncState()
                    } catch (e: Exception) {
                        _loadError.value = "Unable to read selected PDF file"
                        _selectedDocumentName.value = null
                        pdfMapper = null
                        _pdfViewportState.value = PdfViewportState()
                        _pdfViewerState.value = PdfViewerState.Empty
                        readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
                        readingEngine.setError()
                        updatePdfSyncState()
                    }
                }
            }
            DetectedFormat.EPUB -> {
                pdfMapper = null
                _pdfViewportState.value = PdfViewportState()
                _pdfViewerState.value = PdfViewerState.Empty
                updatePdfSyncState()

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
                pdfMapper = null
                _pdfViewportState.value = PdfViewportState()
                _pdfViewerState.value = PdfViewerState.Empty
                updatePdfSyncState()

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
                pdfMapper = null
                _pdfViewportState.value = PdfViewportState()
                _pdfViewerState.value = PdfViewerState.Empty
                _loadError.value = "Unsupported document format"
                _selectedDocumentName.value = null
                readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
                readingEngine.setError()
                updatePdfSyncState()
            }
        }
    }

    /**
     * Called when the visual PDF viewport changes in [com.readme.app.ui.pdf.PdfReaderView].
     * Updates the observable viewport state and re-evaluates reading synchronization state.
     * Does NOT scroll the PDF or alter speech position.
     * Clears pending navigation if the viewport has reached the target page.
     */
    fun onPdfViewportChanged(viewportState: PdfViewportState) {
        _pdfViewportState.value = viewportState
        navigationCoordinator.onViewportChanged(viewportState)
        updatePdfSyncState()
    }

    private fun updatePdfSyncState() {
        val mapper = pdfMapper
        val isPdf = _pdfViewerState.value is PdfViewerState.Active
        if (mapper == null || !isPdf) {
            _pdfReadingSyncState.value = PdfReadingSyncState.Empty
            return
        }
        _pdfReadingSyncState.value = mapper.computeSyncState(
            position = readingEngine.currentPosition.value,
            viewportState = _pdfViewportState.value,
            isViewerActive = true,
            pendingNavigationPage = navigationCoordinator.pendingNavigation.value?.pageIndex
        )
    }

    fun getPdfReadingMapper(): PdfReadingPositionMapper? = pdfMapper

    fun getPdfNavigationCoordinator(): PdfNavigationCoordinator = navigationCoordinator

    @androidx.annotation.VisibleForTesting
    fun setPdfMapperForTest(mapper: PdfReadingPositionMapper?, isPdf: Boolean = true) {
        pdfMapper = mapper
        if (isPdf) {
            _pdfViewerState.value = PdfViewerState.Active(Uri.parse("content://test"), "Test")
        } else {
            _pdfViewerState.value = PdfViewerState.Empty
        }
        navigationCoordinator.setPdfDocument(mapper, isActive = isPdf)
        updatePdfSyncState()
    }

    /**
     * Connects or disconnects the [PdfPageNavigator] abstraction.
     * If a pending navigation is waiting for the viewer to become ready, it is executed.
     */
    fun setPdfPageNavigator(navigator: PdfPageNavigator?) {
        navigationCoordinator.setNavigator(navigator)
        updatePdfSyncState()
    }

    /**
     * Evaluates whether an automatic speech-driven PDF page navigation should occur.
     */
    fun evaluatePdfNavigation(position: ReadingPosition?) {
        navigationCoordinator.evaluateNavigation(position)
        updatePdfSyncState()
    }

    /**
     * Phase 8F: Explicitly requests that reading continue from the PDF page currently being viewed.
     */
    fun reconcilePdfReadingPosition() {
        val position = com.readme.app.ui.pdf.PdfPositionReconciler.reconcile(
            document = readingEngine.currentDocument,
            mapper = pdfMapper,
            viewportState = _pdfViewportState.value,
            isPdfActive = _pdfViewerState.value is PdfViewerState.Active
        ) ?: return
        
        val wasReading = readingEngine.readingState.value == ReadingSessionState.Reading
        
        if (wasReading) {
            // Stop speech and engine safely
            activeSessionId = 0L
            speechEngine.stop()
            readingEngine.stop()
            
            // Set the new position and resume reading
            readingEngine.setPosition(position)
            startReading()
        } else {
            readingEngine.setPosition(position)
        }
        updatePdfSyncState()
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
                navigationCoordinator.onReadingStarted(readingEngine.currentPosition.value)
                updatePdfSyncState()
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
        navigationCoordinator.onReadingStopped()
        readingEngine.stop()
        speechEngine.stop()
        updatePdfSyncState()
    }

    override fun onCleared() {
        super.onCleared()
        restartJob?.cancel()
        readingEngine.stop()
        speechEngine.shutdown()
    }
}
