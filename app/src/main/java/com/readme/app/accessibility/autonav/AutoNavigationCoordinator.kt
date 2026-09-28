package com.readme.app.accessibility.autonav

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import com.readme.app.accessibility.AndroidAccessibleNode
import com.readme.app.accessibility.CrossAppGestureDispatcher
import com.readme.app.accessibility.CrossAppOcrAcquisitionResult
import com.readme.app.accessibility.CrossAppOcrCoordinator
import com.readme.app.accessibility.CrossAppWindowTarget
import com.readme.app.accessibility.OnDeviceCrossAppOcrEngine
import com.readme.app.accessibility.ReadMeAccessibilityService
import com.readme.app.accessibility.ScreenGeometryMapper
import com.readme.app.reading.service.ReadMeReadingSessionRuntime
import com.readme.app.reading.ReadingDocument
import com.readme.app.settings.PagedReaderNavigationMode
import com.readme.app.settings.ReadMeSettings
import com.readme.app.settings.ReadMeSettingsRepository
import com.readme.app.ui.overlay.ScreenHighlightOverlayController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordinates automatic screen advance according to Phases 9X, 9AA, and 9AC:
 * - One advance at a time (mutex guarded).
 * - Identifies target reader type (paginated vs vertical).
 * - Conceptual Priority for paged readers:
 *   A. Verified accessibility page action (e.g. ACTION_PAGE_RIGHT).
 *   B. Accessible page-turn click.
 *   C. User-enabled screen-edge tap page turn.
 * - Enforces single tap per cycle (no repeated taps or long swipes).
 * - Transition settling delay (400–800ms) before post-navigation OCR verification.
 * - Compares pre- and post-navigation content fingerprints.
 * - Same-page protection & snap-back protection: Never re-reads the same page.
 * - Cancels on app switch, window switch, pause, stop, reselect, or orientation change.
 */
class AutoNavigationCoordinator(
    private val context: Context,
    private val sessionRuntime: ReadMeReadingSessionRuntime,
    private val getHighlightOverlayController: () -> ScreenHighlightOverlayController?,
    private val settingsRepository: ReadMeSettingsRepository? = null,
    private val gestureDispatcher: CrossAppGestureDispatcher? = null
) {
    private val _navState = MutableStateFlow<AutoNavigationState>(AutoNavigationState.Idle)
    val navState: StateFlow<AutoNavigationState> = _navState.asStateFlow()

    private val navMutex = Mutex()
    private var activeCycleJob: Job? = null

    @Volatile
    var lastExtractedText: String? = null
        private set

    @Volatile
    var lastSelectedRegion: Rect? = null

    @Volatile
    var currentTarget: CrossAppWindowTarget? = null

    // Testing overrides
    @Volatile
    var customGestureDispatcherForTesting: CrossAppGestureDispatcher? = null

    @Volatile
    var customSettingsForTesting: ReadMeSettings? = null

    fun reset() {
        activeCycleJob?.cancel()
        activeCycleJob = null
        lastExtractedText = null
        currentTarget = null
        _navState.value = AutoNavigationState.Idle
    }

    fun cancelPendingNavigation() {
        activeCycleJob?.cancel()
        activeCycleJob = null
        if (_navState.value !is AutoNavigationState.Idle) {
            _navState.value = AutoNavigationState.Idle
        }
    }

    fun onInitialOcrCompleted(documentText: String, region: Rect?, target: CrossAppWindowTarget?) {
        lastExtractedText = documentText.trim()
        lastSelectedRegion = region
        currentTarget = target
        _navState.value = AutoNavigationState.Reading
    }

    private suspend fun getEffectiveSettings(): ReadMeSettings {
        customSettingsForTesting?.let { return it }
        return try {
            (settingsRepository ?: ReadMeSettingsRepository(context)).settingsFlow.firstOrNull()
                ?: ReadMeSettings()
        } catch (_: Throwable) {
            ReadMeSettings()
        }
    }

    private fun getEffectiveGestureDispatcher(): CrossAppGestureDispatcher? {
        return customGestureDispatcherForTesting
            ?: gestureDispatcher
            ?: ReadMeAccessibilityService.gestureDispatcherForTesting
            ?: ReadMeAccessibilityService.instance
    }

    private data class SettleVerificationResult(
        val pageChanged: Boolean,
        val finalDoc: ReadingDocument?,
        val finalText: String,
        val newTarget: CrossAppWindowTarget?,
        val postFingerprint: ContentFingerprint,
        val eventsReceived: List<String>
    )

    /**
     * Waits for visual transition to settle and performs post-navigation OCR verification.
     */
    private suspend fun settleAndVerifyOcr(
        service: ReadMeAccessibilityService,
        target: CrossAppWindowTarget,
        preText: String?,
        realBounds: Rect,
        coroutineScope: CoroutineScope,
        settleMs: Long = 450L
    ): SettleVerificationResult {
        val eventsReceived = mutableListOf<String>()
        val eventJob = coroutineScope.launch {
            try {
                service.contentChangeEventFlow.collect { event ->
                    if (event.packageName?.toString() == target.packageName) {
                        eventsReceived.add(AccessibilityEvent.eventTypeToString(event.eventType))
                    }
                }
            } catch (_: Throwable) {}
        }

        // Wait for content change event or settling timeout
        try {
            withTimeoutOrNull(1000) {
                service.contentChangeEventFlow.firstOrNull { event ->
                    event.packageName?.toString() == target.packageName
                }
            }
        } catch (_: Throwable) {}

        // Settle delay for visual page flip transition animation to finish completely
        delay(settleMs)
        eventJob.cancel()

        _navState.value = AutoNavigationState.Acquiring

        val newTarget = service.identifyTargetWindow()
        if (newTarget == null || newTarget.packageName != target.packageName) {
            return SettleVerificationResult(
                pageChanged = false,
                finalDoc = null,
                finalText = "",
                newTarget = null,
                postFingerprint = ContentFingerprint.create(null),
                eventsReceived = eventsReceived.toList()
            )
        }

        // Acquire new screenshot and OCR using the preserved relative region
        val ocrEngine = OnDeviceCrossAppOcrEngine()
        val ocrResult = try {
            CrossAppOcrCoordinator.executeOcrAcquisition(
                capturer = service,
                ocrEngine = ocrEngine,
                target = newTarget,
                selectedRegion = lastSelectedRegion,
                displayWidth = realBounds.width(),
                displayHeight = realBounds.height()
            )
        } finally {
            ocrEngine.close()
        }

        if (ocrResult !is CrossAppOcrAcquisitionResult.Success) {
            return SettleVerificationResult(
                pageChanged = false,
                finalDoc = null,
                finalText = "",
                newTarget = newTarget,
                postFingerprint = ContentFingerprint.create(null),
                eventsReceived = eventsReceived.toList()
            )
        }

        var finalDoc = ocrResult.document
        var finalFullText = finalDoc.allSegments().joinToString(" ") { it.text }.trim()

        // If text appears identical to previous screen, retry once after an additional delay
        // in case a page turn animation (e.g. page curl, slide) was still completing.
        if (finalFullText.isEmpty() || isContentPracticallyIdentical(finalFullText, preText)) {
            delay(350)
            val retryEngine = OnDeviceCrossAppOcrEngine()
            val retryResult = try {
                CrossAppOcrCoordinator.executeOcrAcquisition(
                    capturer = service,
                    ocrEngine = retryEngine,
                    target = newTarget,
                    selectedRegion = lastSelectedRegion,
                    displayWidth = realBounds.width(),
                    displayHeight = realBounds.height()
                )
            } catch (_: Throwable) {
                null
            } finally {
                retryEngine.close()
            }

            if (retryResult is CrossAppOcrAcquisitionResult.Success) {
                val retryText = retryResult.document.allSegments().joinToString(" ") { it.text }.trim()
                if (retryText.isNotEmpty() && !isContentPracticallyIdentical(retryText, preText)) {
                    finalDoc = retryResult.document
                    finalFullText = retryText
                }
            }
        }

        val pageChanged = finalFullText.isNotEmpty() && !isContentPracticallyIdentical(finalFullText, preText)
        val postFingerprint = ContentFingerprint.create(finalFullText)

        return SettleVerificationResult(
            pageChanged = pageChanged,
            finalDoc = finalDoc,
            finalText = finalFullText,
            newTarget = newTarget,
            postFingerprint = postFingerprint,
            eventsReceived = eventsReceived.toList()
        )
    }

    suspend fun attemptAutoAdvance(
        target: CrossAppWindowTarget,
        coroutineScope: CoroutineScope
    ): AutoAdvanceCycleResult {
        if (!navMutex.tryLock()) {
            return AutoAdvanceCycleResult.Cancelled
        }

        return try {
            _navState.value = AutoNavigationState.Advancing

            // 1. Immediately remove old highlight
            getHighlightOverlayController()?.clearHighlight()

            val service = ReadMeAccessibilityService.instance
            if (service == null) {
                _navState.value = AutoNavigationState.Failed("Accessibility service disconnected")
                return AutoAdvanceCycleResult.Unavailable
            }

            // 2. Validate current package
            val currentPkg = service.currentActivePackage
            if (currentPkg != null && currentPkg != target.packageName && currentPkg != context.packageName) {
                _navState.value = AutoNavigationState.Idle
                return AutoAdvanceCycleResult.Cancelled
            }

            val initialDisplayBounds = ScreenGeometryMapper.getRealDisplayBounds(context)

            // 3. Find target window root and inspect candidate node
            val rawRoot = service.getRootForTarget(target)
            if (rawRoot == null) {
                _navState.value = AutoNavigationState.Failed("Target window root unavailable")
                return AutoAdvanceCycleResult.Unavailable
            }

            val wrappedRoot = AndroidAccessibleNode(rawRoot)
            val allCandidates = AutoNavigator.findCandidates(wrappedRoot, lastSelectedRegion)
            val validCandidates = allCandidates.filter { it.isGeometryValid }

            val preText = lastExtractedText
            val preFingerprint = ContentFingerprint.create(preText)

            // 4. Classify reader type (Phase 9AC Section 2)
            val pagedInfo = PaginatedReaderDetector.detect(validCandidates, lastSelectedRegion, initialDisplayBounds)
            val settings = getEffectiveSettings()
            val isEdgeTapConfigured = settings.pagedReaderNavigationMode == PagedReaderNavigationMode.TAP_SCREEN_EDGE

            if (pagedInfo.isPaginatedReader) {
                // ==========================================
                // PAGINATED NOVEL READER NAVIGATION STRATEGY
                // ==========================================

                // Priority A: Try verified accessibility page action (e.g. ACTION_PAGE_RIGHT) first if available
                var accessibilityActionAttempted = false
                if (pagedInfo.hasHorizontalPageAction || pagedInfo.primaryCandidate?.actionType == NavigationActionType.PAGE_RIGHT) {
                    val candidate = pagedInfo.primaryCandidate ?: validCandidates.firstOrNull()
                    if (candidate != null) {
                        val actionId = if (candidate.allActions.contains(AutoNavigator.ID_PAGE_RIGHT)) {
                            AutoNavigator.ID_PAGE_RIGHT
                        } else {
                            candidate.actionId
                        }

                        val timeBefore = System.currentTimeMillis()
                        val performed = try {
                            candidate.node.performAction(actionId)
                        } catch (_: Throwable) {
                            false
                        }
                        val timeAfter = System.currentTimeMillis()

                        if (performed) {
                            accessibilityActionAttempted = true
                            _navState.value = AutoNavigationState.WaitingForContentChange

                            val verification = settleAndVerifyOcr(
                                service = service,
                                target = target,
                                preText = preText,
                                realBounds = initialDisplayBounds,
                                coroutineScope = coroutineScope,
                                settleMs = 450L
                            )

                            AutoNavigationDiagnostics.record(
                                NavigationDiagnosticRecord(
                                    targetPackage = target.packageName,
                                    targetWindowId = target.windowId,
                                    navigationMethod = "ACCESSIBILITY_ACTION",
                                    readerBounds = pagedInfo.readerBounds,
                                    selectedNodeClass = candidate.className,
                                    selectedNodeBounds = candidate.bounds,
                                    selectedNodeResourceId = candidate.resourceId,
                                    selectedNodeContentDescription = candidate.contentDescription,
                                    selectedNodeTextSnippet = candidate.textSnippet,
                                    isScrollable = candidate.isScrollable,
                                    availableActions = candidate.allActions,
                                    selectedNavigationAction = "ACTION_PAGE_RIGHT",
                                    gestureEnabled = false,
                                    tapCoordinates = null,
                                    tapRelativeCoordinates = null,
                                    performActionReturnValue = true,
                                    timestampBeforeAction = timeBefore,
                                    timestampAfterAction = timeAfter,
                                    settlingDurationMs = 450L,
                                    accessibilityEventsReceived = verification.eventsReceived,
                                    preNavigationFingerprint = preFingerprint,
                                    postNavigationFingerprint = verification.postFingerprint,
                                    pageChangeDetected = verification.pageChanged,
                                    finalNavigationResult = if (verification.pageChanged) "SUCCESS" else "CONTENT_UNCHANGED"
                                )
                            )

                            if (verification.pageChanged && verification.finalDoc != null) {
                                lastExtractedText = verification.finalText
                                currentTarget = verification.newTarget ?: target
                                sessionRuntime.loadEphemeralDocument(verification.finalDoc)
                                sessionRuntime.resumeReading()
                                _navState.value = AutoNavigationState.Reading
                                return AutoAdvanceCycleResult.Success(verification.finalDoc.allSegments().size, target.generation)
                            }
                            // Action was performed but ineffective / snapped back. Proceed to edge-tap fallback if enabled!
                        }
                    }
                }

                // Priority C: User-enabled screen-edge tap page turn (Phase 9AC Sections 4, 5, 6, 7)
                if (isEdgeTapConfigured) {
                    val currentDisplayBounds = ScreenGeometryMapper.getRealDisplayBounds(context)
                    if (currentDisplayBounds.width() != initialDisplayBounds.width() ||
                        currentDisplayBounds.height() != initialDisplayBounds.height()
                    ) {
                        _navState.value = AutoNavigationState.Idle
                        return AutoAdvanceCycleResult.Cancelled
                    }

                    val dispatcher = getEffectiveGestureDispatcher()
                    if (dispatcher != null && dispatcher.canDispatchGestures) {
                        val (tapX, tapY) = EdgeTapCoordinatesCalculator.calculateNextPageTap(
                            readerBounds = pagedInfo.readerBounds,
                            readingRegion = lastSelectedRegion
                        )

                        val timeBefore = System.currentTimeMillis()
                        val tapSuccess = dispatcher.performTap(tapX, tapY, durationMs = 80L)
                        val timeAfter = System.currentTimeMillis()

                        if (tapSuccess) {
                            _navState.value = AutoNavigationState.WaitingForContentChange

                            val verification = settleAndVerifyOcr(
                                service = service,
                                target = target,
                                preText = preText,
                                realBounds = currentDisplayBounds,
                                coroutineScope = coroutineScope,
                                settleMs = 500L
                            )

                            AutoNavigationDiagnostics.record(
                                NavigationDiagnosticRecord(
                                    targetPackage = target.packageName,
                                    targetWindowId = target.windowId,
                                    navigationMethod = "EDGE_TAP",
                                    readerBounds = pagedInfo.readerBounds,
                                    selectedNodeClass = pagedInfo.primaryCandidate?.className ?: "PaginatedReaderSurface",
                                    selectedNodeBounds = pagedInfo.readerBounds,
                                    selectedNodeResourceId = pagedInfo.primaryCandidate?.resourceId,
                                    selectedNodeContentDescription = pagedInfo.primaryCandidate?.contentDescription,
                                    selectedNodeTextSnippet = pagedInfo.primaryCandidate?.textSnippet,
                                    isScrollable = true,
                                    availableActions = pagedInfo.primaryCandidate?.allActions ?: emptyList(),
                                    selectedNavigationAction = "EDGE_TAP_RIGHT",
                                    gestureEnabled = true,
                                    tapCoordinates = "(${tapX.toInt()}, ${tapY.toInt()})",
                                    tapRelativeCoordinates = "(${String.format(java.util.Locale.US, "%.2f", (tapX - pagedInfo.readerBounds.left) / pagedInfo.readerBounds.width().toFloat())}, ${String.format(java.util.Locale.US, "%.2f", (tapY - pagedInfo.readerBounds.top) / pagedInfo.readerBounds.height().toFloat())})",
                                    performActionReturnValue = true,
                                    timestampBeforeAction = timeBefore,
                                    timestampAfterAction = timeAfter,
                                    settlingDurationMs = 500L,
                                    accessibilityEventsReceived = verification.eventsReceived,
                                    preNavigationFingerprint = preFingerprint,
                                    postNavigationFingerprint = verification.postFingerprint,
                                    pageChangeDetected = verification.pageChanged,
                                    finalNavigationResult = if (verification.pageChanged) "SUCCESS" else "NO_VISUAL_CHANGE"
                                )
                            )

                            if (verification.pageChanged && verification.finalDoc != null) {
                                lastExtractedText = verification.finalText
                                currentTarget = verification.newTarget ?: target
                                sessionRuntime.loadEphemeralDocument(verification.finalDoc)
                                sessionRuntime.resumeReading()
                                _navState.value = AutoNavigationState.Reading
                                return AutoAdvanceCycleResult.Success(verification.finalDoc.allSegments().size, target.generation)
                            } else {
                                // Content unchanged or partial movement snapped back.
                                // Maximum ONE tap. DO NOT retry! DO NOT fall back to SCROLL_FORWARD!
                                _navState.value = AutoNavigationState.Idle
                                return AutoAdvanceCycleResult.ContentUnchanged
                            }
                        } else {
                            _navState.value = AutoNavigationState.Failed("Gesture tap rejected by system")
                            return AutoAdvanceCycleResult.Unavailable
                        }
                    } else {
                        _navState.value = AutoNavigationState.Failed("Gesture capability unavailable")
                        return AutoAdvanceCycleResult.Unavailable
                    }
                }

                // If edge tap is not enabled and accessibility page action was ineffective or missing:
                // Halt cleanly. DO NOT use generic vertical scrolling on a paginated reader!
                _navState.value = AutoNavigationState.Idle
                return AutoAdvanceCycleResult.ContentUnchanged
            }

            // ==========================================
            // VERTICAL READER / STANDARD SCROLL FALLBACK
            // ==========================================
            if (validCandidates.isEmpty()) {
                AutoNavigationDiagnostics.record(
                    NavigationDiagnosticRecord(
                        targetPackage = target.packageName,
                        targetWindowId = target.windowId,
                        navigationMethod = "NONE",
                        readerBounds = Rect(),
                        selectedNodeClass = "None",
                        selectedNodeBounds = Rect(),
                        selectedNodeResourceId = null,
                        selectedNodeContentDescription = null,
                        selectedNodeTextSnippet = null,
                        isScrollable = false,
                        availableActions = emptyList(),
                        selectedNavigationAction = "NONE",
                        gestureEnabled = false,
                        tapCoordinates = null,
                        tapRelativeCoordinates = null,
                        performActionReturnValue = false,
                        timestampBeforeAction = System.currentTimeMillis(),
                        timestampAfterAction = System.currentTimeMillis(),
                        accessibilityEventsReceived = emptyList(),
                        preNavigationFingerprint = preFingerprint,
                        postNavigationFingerprint = ContentFingerprint.create(null),
                        pageChangeDetected = false,
                        finalNavigationResult = "NO_CANDIDATE_FOUND"
                    )
                )
                _navState.value = AutoNavigationState.Failed("No accessible page or scroll action available")
                return AutoAdvanceCycleResult.Unavailable
            }

            for (candidate in validCandidates.take(3)) {
                val actionsToTry = listOf(candidate.actionType to candidate.actionId) + candidate.fallbackActions
                for ((actionType, actionId) in actionsToTry) {
                    val timeBefore = System.currentTimeMillis()
                    val performedSuccessfully = try {
                        candidate.node.performAction(actionId)
                    } catch (_: Throwable) {
                        false
                    }
                    val timeAfter = System.currentTimeMillis()

                    if (!performedSuccessfully) {
                        AutoNavigationDiagnostics.record(
                            NavigationDiagnosticRecord(
                                targetPackage = target.packageName,
                                targetWindowId = target.windowId,
                                navigationMethod = "SCROLL_FORWARD",
                                readerBounds = candidate.bounds,
                                selectedNodeClass = candidate.className,
                                selectedNodeBounds = candidate.bounds,
                                selectedNodeResourceId = candidate.resourceId,
                                selectedNodeContentDescription = candidate.contentDescription,
                                selectedNodeTextSnippet = candidate.textSnippet,
                                isScrollable = candidate.isScrollable,
                                availableActions = candidate.allActions,
                                selectedNavigationAction = actionType.name,
                                gestureEnabled = false,
                                tapCoordinates = null,
                                tapRelativeCoordinates = null,
                                performActionReturnValue = false,
                                timestampBeforeAction = timeBefore,
                                timestampAfterAction = timeAfter,
                                accessibilityEventsReceived = emptyList(),
                                preNavigationFingerprint = preFingerprint,
                                postNavigationFingerprint = ContentFingerprint.create(null),
                                pageChangeDetected = false,
                                finalNavigationResult = "ACTION_REJECTED"
                            )
                        )
                        continue
                    }

                    _navState.value = AutoNavigationState.WaitingForContentChange

                    val verification = settleAndVerifyOcr(
                        service = service,
                        target = target,
                        preText = preText,
                        realBounds = initialDisplayBounds,
                        coroutineScope = coroutineScope,
                        settleMs = 450L
                    )

                    AutoNavigationDiagnostics.record(
                        NavigationDiagnosticRecord(
                            targetPackage = target.packageName,
                            targetWindowId = target.windowId,
                            navigationMethod = "SCROLL_FORWARD",
                            readerBounds = candidate.bounds,
                            selectedNodeClass = candidate.className,
                            selectedNodeBounds = candidate.bounds,
                            selectedNodeResourceId = candidate.resourceId,
                            selectedNodeContentDescription = candidate.contentDescription,
                            selectedNodeTextSnippet = candidate.textSnippet,
                            isScrollable = candidate.isScrollable,
                            availableActions = candidate.allActions,
                            selectedNavigationAction = actionType.name,
                            gestureEnabled = false,
                            tapCoordinates = null,
                            tapRelativeCoordinates = null,
                            performActionReturnValue = true,
                            timestampBeforeAction = timeBefore,
                            timestampAfterAction = timeAfter,
                            settlingDurationMs = 450L,
                            accessibilityEventsReceived = verification.eventsReceived,
                            preNavigationFingerprint = preFingerprint,
                            postNavigationFingerprint = verification.postFingerprint,
                            pageChangeDetected = verification.pageChanged,
                            finalNavigationResult = if (verification.pageChanged) "SUCCESS" else "CONTENT_UNCHANGED"
                        )
                    )

                    if (verification.pageChanged && verification.finalDoc != null) {
                        lastExtractedText = verification.finalText
                        currentTarget = verification.newTarget ?: target
                        sessionRuntime.loadEphemeralDocument(verification.finalDoc)
                        sessionRuntime.resumeReading()
                        _navState.value = AutoNavigationState.Reading
                        return AutoAdvanceCycleResult.Success(verification.finalDoc.allSegments().size, target.generation)
                    }
                }
            }

            _navState.value = AutoNavigationState.Idle
            return AutoAdvanceCycleResult.ContentUnchanged
        } catch (e: CancellationException) {
            _navState.value = AutoNavigationState.Idle
            AutoAdvanceCycleResult.Cancelled
        } catch (e: Throwable) {
            _navState.value = AutoNavigationState.Failed(e.message ?: "Unknown navigation error")
            AutoAdvanceCycleResult.Error(e.message ?: "Error")
        } finally {
            navMutex.unlock()
        }
    }

    /**
     * Supports manual previous-page turn navigation using LEFT edge tap or ACTION_PAGE_LEFT (Phase 9AC Section 25).
     */
    suspend fun attemptPreviousPage(
        target: CrossAppWindowTarget,
        coroutineScope: CoroutineScope
    ): AutoAdvanceCycleResult {
        if (!navMutex.tryLock()) {
            return AutoAdvanceCycleResult.Cancelled
        }

        return try {
            val service = ReadMeAccessibilityService.instance
            if (service == null) return AutoAdvanceCycleResult.Unavailable

            val initialDisplayBounds = ScreenGeometryMapper.getRealDisplayBounds(context)
            val rawRoot = service.getRootForTarget(target) ?: return AutoAdvanceCycleResult.Unavailable
            val wrappedRoot = AndroidAccessibleNode(rawRoot)
            val allCandidates = AutoNavigator.findCandidates(wrappedRoot, lastSelectedRegion)
            val validCandidates = allCandidates.filter { it.isGeometryValid }

            val pagedInfo = PaginatedReaderDetector.detect(validCandidates, lastSelectedRegion, initialDisplayBounds)
            val preText = lastExtractedText

            // Try ACTION_PAGE_LEFT first if available
            val leftActionCandidate = validCandidates.firstOrNull { it.allActions.contains(AutoNavigator.ID_PAGE_LEFT) }
            if (leftActionCandidate != null) {
                val performed = try {
                    leftActionCandidate.node.performAction(AutoNavigator.ID_PAGE_LEFT)
                } catch (_: Throwable) { false }

                if (performed) {
                    val verification = settleAndVerifyOcr(service, target, preText, initialDisplayBounds, coroutineScope, 450L)
                    if (verification.pageChanged && verification.finalDoc != null) {
                        lastExtractedText = verification.finalText
                        currentTarget = verification.newTarget ?: target
                        sessionRuntime.loadEphemeralDocument(verification.finalDoc)
                        sessionRuntime.resumeReading()
                        return AutoAdvanceCycleResult.Success(verification.finalDoc.allSegments().size, target.generation)
                    }
                }
            }

            // Fallback to left-edge tap if dispatcher available
            val dispatcher = getEffectiveGestureDispatcher()
            if (dispatcher != null && dispatcher.canDispatchGestures) {
                val (tapX, tapY) = EdgeTapCoordinatesCalculator.calculatePreviousPageTap(
                    readerBounds = pagedInfo.readerBounds,
                    readingRegion = lastSelectedRegion
                )
                val tapSuccess = dispatcher.performTap(tapX, tapY, 80L)
                if (tapSuccess) {
                    val verification = settleAndVerifyOcr(service, target, preText, initialDisplayBounds, coroutineScope, 500L)
                    if (verification.pageChanged && verification.finalDoc != null) {
                        lastExtractedText = verification.finalText
                        currentTarget = verification.newTarget ?: target
                        sessionRuntime.loadEphemeralDocument(verification.finalDoc)
                        sessionRuntime.resumeReading()
                        return AutoAdvanceCycleResult.Success(verification.finalDoc.allSegments().size, target.generation)
                    }
                }
            }

            AutoAdvanceCycleResult.ContentUnchanged
        } catch (_: CancellationException) {
            AutoAdvanceCycleResult.Cancelled
        } catch (e: Throwable) {
            AutoAdvanceCycleResult.Error(e.message ?: "Error")
        } finally {
            navMutex.unlock()
        }
    }

    companion object {
        /**
         * Determines whether two OCR extraction results are practically identical.
         * Prevents re-reading the same page if OCR produces minor character-level noise or punctuation differences.
         */
        fun isContentPracticallyIdentical(text1: String?, text2: String?): Boolean {
            if (text1 == null || text2 == null) return false
            val norm1 = text1.lowercase().replace("[^a-z0-9 ]".toRegex(), " ").trim()
            val norm2 = text2.lowercase().replace("[^a-z0-9 ]".toRegex(), " ").trim()
            if (norm1 == norm2) return true
            if (norm1.isEmpty() || norm2.isEmpty()) return false

            val words1 = norm1.split("\\s+".toRegex()).filter { it.isNotBlank() }
            val words2 = norm2.split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (words1.isEmpty() || words2.isEmpty()) return false

            val minLen = minOf(norm1.length, norm2.length)
            val maxLen = maxOf(norm1.length, norm2.length)
            if (minLen.toFloat() / maxLen.toFloat() < 0.70f) {
                return false
            }

            val set1 = words1.toSet()
            val set2 = words2.toSet()
            val intersection = set1.intersect(set2).size
            val union = set1.union(set2).size
            if (union == 0) return true
            val jaccard = intersection.toFloat() / union.toFloat()

            return jaccard >= 0.78f
        }
    }
}
