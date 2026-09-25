package com.readme.app.accessibility.autonav

import android.content.Context
import android.graphics.Rect
import com.readme.app.accessibility.AndroidAccessibleNode
import com.readme.app.accessibility.CrossAppOcrAcquisitionResult
import com.readme.app.accessibility.CrossAppOcrCoordinator
import com.readme.app.accessibility.CrossAppWindowTarget
import com.readme.app.accessibility.ReadMeAccessibilityService
import com.readme.app.reading.service.ReadMeReadingSessionRuntime
import com.readme.app.ui.overlay.ScreenHighlightOverlayController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordinates automatic screen advance according to Phase 9X Part B:
 * - One advance at a time (mutex guarded).
 * - Generates a new acquisition generation per cycle.
 * - Inquires target window root and dispatches accessible page/scroll action via [AutoNavigator].
 * - Waits for target content change event or settling delay without busy-looping.
 * - Performs OCR acquisition on equivalent reading region.
 * - Validates content change (ensures text actually changed, prevents looping at end of content).
 * - Immediately clears old highlight and initializes fresh highlight on new content.
 * - Cancels on app switch, window switch, pause, reselect, stop, or error.
 */
class AutoNavigationCoordinator(
    private val context: Context,
    private val sessionRuntime: ReadMeReadingSessionRuntime,
    private val getHighlightOverlayController: () -> ScreenHighlightOverlayController?
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

            // 3. Find target window root and inspect candidate node
            val rawRoot = service.getRootForTarget(target)
            if (rawRoot == null) {
                _navState.value = AutoNavigationState.Failed("Target window root unavailable")
                return AutoAdvanceCycleResult.Unavailable
            }

            val wrappedRoot = AndroidAccessibleNode(rawRoot)
            val actionResult = AutoNavigator.executeNavigation(wrappedRoot, lastSelectedRegion)

            when (actionResult) {
                is AutoAdvanceActionResult.NoCandidateNodeFound -> {
                    _navState.value = AutoNavigationState.Failed("No accessible page or scroll action available")
                    return AutoAdvanceCycleResult.Unavailable
                }
                is AutoAdvanceActionResult.ActionRejectedByNode -> {
                    _navState.value = AutoNavigationState.Idle
                    return AutoAdvanceCycleResult.EndOfAccessibleContent
                }
                is AutoAdvanceActionResult.Error -> {
                    _navState.value = AutoNavigationState.Failed(actionResult.message)
                    return AutoAdvanceCycleResult.Error(actionResult.message)
                }
                is AutoAdvanceActionResult.ServiceUnavailable -> {
                    _navState.value = AutoNavigationState.Failed("Service unavailable")
                    return AutoAdvanceCycleResult.Unavailable
                }
                is AutoAdvanceActionResult.Dispatched -> {
                    // Action successfully dispatched, wait for content change
                }
            }

            _navState.value = AutoNavigationState.WaitingForContentChange

            // 4. Wait for content change event or settling timeout
            try {
                withTimeoutOrNull(1500) {
                    service.contentChangeEventFlow.firstOrNull { event ->
                        val pkg = event.packageName?.toString()
                        pkg == target.packageName
                    }
                }
            } catch (_: Throwable) {}

            // Settle delay for visual page flip / scroll transition animation to finish
            delay(350)

            _navState.value = AutoNavigationState.Acquiring

            // 5. Post-navigation verification: verify package has not switched
            val postPkg = service.currentActivePackage
            if (postPkg != null && postPkg != target.packageName && postPkg != context.packageName) {
                _navState.value = AutoNavigationState.Idle
                return AutoAdvanceCycleResult.Cancelled
            }

            val newTarget = service.identifyTargetWindow()
            if (newTarget == null || newTarget.packageName != target.packageName) {
                _navState.value = AutoNavigationState.Idle
                return AutoAdvanceCycleResult.Cancelled
            }

            // 6. Acquire new screenshot and OCR using the preserved relative region
            val ocrEngine = com.readme.app.accessibility.OnDeviceCrossAppOcrEngine()
            val realBounds = com.readme.app.accessibility.ScreenGeometryMapper.getRealDisplayBounds(context)
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

            when (ocrResult) {
                is CrossAppOcrAcquisitionResult.Success -> {
                    val newDoc = ocrResult.document
                    val newFullText = newDoc.allSegments().joinToString(" ") { it.text }.trim()

                    // Content change validation:
                    // If text appears identical to previous screen, retry once after an additional delay
                    // in case a page turn animation (e.g. page curl, slide) was still completing.
                    var finalDoc = newDoc
                    var finalFullText = newFullText
                    if (finalFullText.isEmpty() || isContentPracticallyIdentical(finalFullText, lastExtractedText)) {
                        delay(400)
                        val retryEngine = com.readme.app.accessibility.OnDeviceCrossAppOcrEngine()
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
                            if (retryText.isNotEmpty() && !isContentPracticallyIdentical(retryText, lastExtractedText)) {
                                finalDoc = retryResult.document
                                finalFullText = retryText
                            }
                        }
                    }

                    if (finalFullText.isEmpty() || isContentPracticallyIdentical(finalFullText, lastExtractedText)) {
                        _navState.value = AutoNavigationState.Idle
                        return AutoAdvanceCycleResult.ContentUnchanged
                    }

                    // Fresh content successfully acquired!
                    lastExtractedText = finalFullText
                    currentTarget = newTarget

                    // Load new ephemeral document and start reading sentence 0
                    sessionRuntime.loadEphemeralDocument(finalDoc)
                    sessionRuntime.resumeReading()

                    _navState.value = AutoNavigationState.Reading
                    AutoAdvanceCycleResult.Success(finalDoc.allSegments().size, newTarget.generation)
                }
                is CrossAppOcrAcquisitionResult.OcrReturnedEmpty,
                is CrossAppOcrAcquisitionResult.SelectedAreaTooSmall -> {
                    _navState.value = AutoNavigationState.Idle
                    AutoAdvanceCycleResult.EndOfAccessibleContent
                }
                is CrossAppOcrAcquisitionResult.SensitiveContentBlocked -> {
                    _navState.value = AutoNavigationState.Failed("Sensitive content blocked")
                    AutoAdvanceCycleResult.Unavailable
                }
                is CrossAppOcrAcquisitionResult.StaleAppSwitch -> {
                    _navState.value = AutoNavigationState.Idle
                    AutoAdvanceCycleResult.Cancelled
                }
                else -> {
                    _navState.value = AutoNavigationState.Failed("OCR acquisition failed")
                    AutoAdvanceCycleResult.Unavailable
                }
            }
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

            // Check length ratio
            val minLen = minOf(norm1.length, norm2.length)
            val maxLen = maxOf(norm1.length, norm2.length)
            if (minLen.toFloat() / maxLen.toFloat() < 0.70f) {
                return false // Significant length difference -> new page
            }

            val set1 = words1.toSet()
            val set2 = words2.toSet()
            val intersection = set1.intersect(set2).size
            val union = set1.union(set2).size
            if (union == 0) return true
            val jaccard = intersection.toFloat() / union.toFloat()

            // If >= 78% of words match, it is the same page with slight OCR noise
            return jaccard >= 0.78f
        }
    }
}
