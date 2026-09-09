package com.readme.app.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.reading.DocumentLoadState
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingEngine
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionCoordinator
import com.readme.app.reading.ReadingSessionState
import com.readme.app.reading.content.DetectedFormat
import com.readme.app.reading.content.DocumentFormatDetector
import com.readme.app.reading.content.ReadingContentSource
import com.readme.app.reading.content.TxtContentSource
import com.readme.app.reading.content.epub.EpubContentSource
import com.readme.app.reading.content.pdf.PdfContentSource
import com.readme.app.reading.content.pdf.PdfNotSupportedException
import com.readme.app.reading.content.pdf.PdfReadingPositionMapper
import com.readme.app.reading.content.pdf.PdfReadingSyncState
import com.readme.app.reading.progress.PersistentReadingProgressRepository
import com.readme.app.reading.progress.ReadingProgress
import com.readme.app.reading.progress.ReadingProgressRepository
import com.readme.app.reading.progress.ReadingProgressValidator
import com.readme.app.reading.progress.SavedProgressState
import com.readme.app.reading.service.ReadMeReadingService
import com.readme.app.reading.service.ReadMeReadingSessionRuntime
import com.readme.app.speech.ReadMeSpeechEngine
import com.readme.app.speech.ReadMeVoice
import com.readme.app.speech.SpeechEngineListener
import com.readme.app.speech.TtsState
import com.readme.app.ui.pdf.PdfNavigationCoordinator
import com.readme.app.ui.pdf.PdfPageNavigator
import com.readme.app.ui.pdf.PdfViewerState
import com.readme.app.ui.pdf.PdfViewportState
import com.readme.app.ui.pdf.PendingPdfNavigation
import kotlinx.coroutines.Dispatchers
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
    private val contentSource: ReadingContentSource? = null,
    val progressRepository: ReadingProgressRepository = PersistentReadingProgressRepository(application),
    val sessionRuntime: ReadMeReadingSessionRuntime = ReadMeReadingSessionRuntime.getInstance(application)
) : AndroidViewModel(application) {

    private val repository = sessionRuntime.settingsRepository ?: ReadMeSettingsRepository(application)
    
    val speechEngine = sessionRuntime.speechEngine
    val readingEngine = sessionRuntime.readingEngine
    val sessionCoordinator = sessionRuntime.sessionCoordinator

    private var activeContentSource: ReadingContentSource? = contentSource
    private var pdfMapper: PdfReadingPositionMapper? = null

    val activeDocumentState: StateFlow<ActiveDocumentState> = sessionCoordinator.activeDocumentState
    val readingSessionState: StateFlow<ActiveReadingSessionState> = sessionCoordinator.readingSessionState

    val savedProgressState: StateFlow<SavedProgressState> = sessionRuntime.savedProgressState

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

    private var restartJob: Job? = null
    private var documentLoadJob: Job? = null

    init {
        // Collect reading position changes to update sync and navigation
        viewModelScope.launch {
            readingEngine.currentPosition.collect { position ->
                updatePdfSyncState()
                evaluatePdfNavigation(position)
            }
        }

        // Collect reading session state to update navigation and sync state
        viewModelScope.launch {
            readingSessionState.collect { state ->
                if (state.isReading) {
                    navigationCoordinator.onReadingStarted(readingEngine.currentPosition.value)
                } else {
                    navigationCoordinator.onReadingStopped()
                }
                updatePdfSyncState()
            }
        }

        // Keep legacy selectedDocumentName and loadError flows synchronized with activeDocumentState
        viewModelScope.launch {
            activeDocumentState.collect { state ->
                when (state.loadState) {
                    is DocumentLoadState.NoDocument -> {
                        _selectedDocumentName.value = null
                        _loadError.value = null
                    }
                    is DocumentLoadState.Loading -> {
                        _selectedDocumentName.value = state.displayName.ifBlank { null }
                        _loadError.value = null
                    }
                    is DocumentLoadState.Loaded -> {
                        _selectedDocumentName.value = state.displayName.ifBlank { state.title }.ifBlank { null }
                        _loadError.value = null
                    }
                    is DocumentLoadState.Error -> {
                        _selectedDocumentName.value = null
                        _loadError.value = state.loadState.message
                    }
                }
            }
        }

        // Load initial document if provided
        if (contentSource != null) {
            val token = sessionCoordinator.startLoading("Document")
            viewModelScope.launch {
                try {
                    val document = contentSource.load()
                    if (sessionCoordinator.isCurrentLoadToken(token)) {
                        sessionCoordinator.onDocumentLoaded(token, document, document.title)
                        restoreProgressIfAvailable(document.id)
                    }
                } catch (e: Exception) {
                    if (sessionCoordinator.isCurrentLoadToken(token)) {
                        sessionCoordinator.onDocumentLoadFailed(token, "Unable to load document")
                        sessionRuntime.clearSavedProgressState()
                    }
                }
            }
        }
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

    fun setSystemBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSystemBubbleEnabled(enabled)
        }
        ReadMeReadingService.syncService(getApplication())
    }

    fun selectTextFile(uri: Uri) {
        selectDocument(uri)
    }

    fun selectDocument(uri: Uri) {
        stopReading()
        navigationCoordinator.clearDocument()
        documentLoadJob?.cancel()
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

        // Stop any active reading before loading a new document
        if (sessionCoordinator.readingSessionState.value.isReading) {
            stopReading()
        }

        // Clear PDF visual state
        pdfMapper = null
        sessionRuntime.clearSavedProgressState()
        _pdfViewportState.value = PdfViewportState()
        if (format == DetectedFormat.PDF) {
            _pdfViewerState.value = PdfViewerState.Loading(uri)
        } else {
            _pdfViewerState.value = PdfViewerState.Empty
        }
        updatePdfSyncState()

        val loadToken = sessionCoordinator.startLoading(displayName)

        when (format) {
            DetectedFormat.PDF -> {
                val pdfSource = PdfContentSource(getApplication(), uri, displayName)
                activeContentSource = pdfSource

                documentLoadJob = viewModelScope.launch {
                    try {
                        val document = pdfSource.load()
                        if (!sessionCoordinator.isCurrentLoadToken(loadToken) || activeContentSource !== pdfSource) return@launch

                        if (document.sections.isEmpty() || document.allSegments().isEmpty()) {
                            sessionCoordinator.onDocumentLoadFailed(loadToken, "No readable text found in selected PDF")
                            _pdfViewerState.value = PdfViewerState.Empty
                            sessionRuntime.clearSavedProgressState()
                            updatePdfSyncState()
                        } else {
                            sessionCoordinator.onDocumentLoaded(loadToken, document, displayName)
                            restoreProgressIfAvailable(document.id)
                            val mapper = PdfReadingPositionMapper.fromDocument(document)
                            pdfMapper = mapper
                            navigationCoordinator.setPdfDocument(mapper, isActive = true)
                            _pdfViewerState.value = PdfViewerState.Active(uri, displayName)
                            updatePdfSyncState()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: com.readme.app.reading.content.pdf.PdfPasswordRequiredException) {
                        if (!sessionCoordinator.isCurrentLoadToken(loadToken) || activeContentSource !== pdfSource) return@launch
                        sessionCoordinator.onDocumentLoadFailed(loadToken, "Password required for this PDF.")
                        _pdfViewerState.value = PdfViewerState.Empty
                        updatePdfSyncState()
                    } catch (e: com.readme.app.reading.content.pdf.ocr.PdfOcrModelUnavailableException) {
                        if (!sessionCoordinator.isCurrentLoadToken(loadToken) || activeContentSource !== pdfSource) return@launch
                        sessionCoordinator.onDocumentLoadFailed(loadToken, "OCR text recognition is not ready yet.")
                        _pdfViewerState.value = PdfViewerState.Empty
                        updatePdfSyncState()
                    } catch (e: com.readme.app.reading.content.pdf.ocr.PdfOcrUnavailableException) {
                        if (!sessionCoordinator.isCurrentLoadToken(loadToken) || activeContentSource !== pdfSource) return@launch
                        sessionCoordinator.onDocumentLoadFailed(loadToken, "OCR is currently unavailable on this device.")
                        _pdfViewerState.value = PdfViewerState.Empty
                        updatePdfSyncState()
                    } catch (e: com.readme.app.reading.content.pdf.ocr.PdfOcrInitializationException) {
                        if (!sessionCoordinator.isCurrentLoadToken(loadToken) || activeContentSource !== pdfSource) return@launch
                        sessionCoordinator.onDocumentLoadFailed(loadToken, "OCR is currently unavailable on this device.")
                        _pdfViewerState.value = PdfViewerState.Empty
                        updatePdfSyncState()
                    } catch (e: com.readme.app.reading.content.pdf.PdfNoSelectableTextException) {
                        if (!sessionCoordinator.isCurrentLoadToken(loadToken) || activeContentSource !== pdfSource) return@launch
                        sessionCoordinator.onDocumentLoadFailed(loadToken, "No selectable text was found in this PDF.")
                        _pdfViewerState.value = PdfViewerState.Empty
                        updatePdfSyncState()
                    } catch (e: Exception) {
                        if (!sessionCoordinator.isCurrentLoadToken(loadToken) || activeContentSource !== pdfSource) return@launch
                        sessionCoordinator.onDocumentLoadFailed(loadToken, "Unable to read selected PDF file")
                        _pdfViewerState.value = PdfViewerState.Empty
                        updatePdfSyncState()
                    }
                }
            }
            DetectedFormat.EPUB -> {
                val epubSource = EpubContentSource(getApplication(), uri, displayName)
                activeContentSource = epubSource

                documentLoadJob = viewModelScope.launch {
                    try {
                        val document = epubSource.load()
                        if (!sessionCoordinator.isCurrentLoadToken(loadToken) || activeContentSource !== epubSource) return@launch

                        if (document.sections.isEmpty() || document.allSegments().isEmpty()) {
                            sessionCoordinator.onDocumentLoadFailed(loadToken, "No readable text found in selected EPUB")
                            sessionRuntime.clearSavedProgressState()
                        } else {
                            sessionCoordinator.onDocumentLoaded(loadToken, document, displayName)
                            restoreProgressIfAvailable(document.id)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: com.readme.app.reading.content.epub.EpubDrmException) {
                        if (!sessionCoordinator.isCurrentLoadToken(loadToken) || activeContentSource !== epubSource) return@launch
                        sessionCoordinator.onDocumentLoadFailed(loadToken, "DRM-protected EPUB files are not supported")
                    } catch (e: Exception) {
                        if (!sessionCoordinator.isCurrentLoadToken(loadToken) || activeContentSource !== epubSource) return@launch
                        sessionCoordinator.onDocumentLoadFailed(loadToken, "Unable to read selected EPUB file")
                    }
                }
            }
            DetectedFormat.TXT -> {
                val txtSource = TxtContentSource(getApplication(), uri, displayName)
                activeContentSource = txtSource

                documentLoadJob = viewModelScope.launch {
                    try {
                        val document = txtSource.load()
                        if (!sessionCoordinator.isCurrentLoadToken(loadToken) || activeContentSource !== txtSource) return@launch

                        if (document.sections.isEmpty() || document.allSegments().isEmpty()) {
                            sessionCoordinator.onDocumentLoadFailed(loadToken, "No readable text found in selected text file")
                            sessionRuntime.clearSavedProgressState()
                        } else {
                            sessionCoordinator.onDocumentLoaded(loadToken, document, displayName)
                            restoreProgressIfAvailable(document.id)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!sessionCoordinator.isCurrentLoadToken(loadToken) || activeContentSource !== txtSource) return@launch
                        sessionCoordinator.onDocumentLoadFailed(loadToken, "Unable to read selected text file")
                    }
                }
            }
            DetectedFormat.UNKNOWN -> {
                sessionCoordinator.onDocumentLoadFailed(loadToken, "Unsupported document format")
                _pdfViewerState.value = PdfViewerState.Empty
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
        if (activeDocumentState.value.sourceType != ReadingDocumentSourceType.PDF) return
        val isPdfActive = _pdfViewerState.value is PdfViewerState.Active
        if (!isPdfActive || pdfMapper == null) return

        val position = com.readme.app.ui.pdf.PdfPositionReconciler.reconcile(
            document = readingEngine.currentDocument,
            mapper = pdfMapper,
            viewportState = _pdfViewportState.value,
            isPdfActive = true
        ) ?: return
        
        val wasReading = sessionCoordinator.readingSessionState.value.isReading
        
        if (wasReading) {
            // Stop speech safely
            stopReading()
            
            // Set the new position and resume reading
            sessionCoordinator.setPosition(position)
            sessionRuntime.saveCurrentProgress(isCompleted = false)
            startReading()
        } else {
            sessionCoordinator.setPosition(position)
            sessionRuntime.saveCurrentProgress(isCompleted = false)
        }
        updatePdfSyncState()
    }

    fun startReading() {
        if (!sessionCoordinator.hasActiveDocument) {
            Log.w("ReadMeViewModel", "startReading called but no active document is loaded")
            return
        }
        restartJob?.cancel()
        ReadMeReadingService.startReading(getApplication())
    }

    fun stopReading() {
        restartJob?.cancel()
        ReadMeReadingService.stopReading(getApplication())
        navigationCoordinator.onReadingStopped()
        updatePdfSyncState()
    }

    fun restartReadingFromBeginning() {
        if (!sessionCoordinator.hasActiveDocument) {
            Log.w("ReadMeViewModel", "restartReadingFromBeginning called but no active document is loaded")
            return
        }
        stopReading()
        sessionRuntime.restartReadingFromBeginning()
        ReadMeReadingService.startReading(getApplication())
    }

    private fun saveCurrentProgress(isCompleted: Boolean = false) {
        sessionRuntime.saveCurrentProgress(isCompleted)
    }

    private suspend fun restoreProgressIfAvailable(documentId: String) {
        sessionRuntime.restoreProgressIfAvailable(documentId)
        updatePdfSyncState()
    }

    override fun onCleared() {
        super.onCleared()
        documentLoadJob?.cancel()
        restartJob?.cancel()
        // Phase 9E: Do NOT stop reading or shutdown speechEngine here.
        // Active background reading is owned and sustained by ReadMeReadingService / ReadMeReadingSessionRuntime.
    }
}
