package com.readme.app.reading.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.readme.app.BuildConfig
import com.readme.app.MainActivity
import com.readme.app.R
import com.readme.app.accessibility.CrossAppAcquisitionMode
import com.readme.app.accessibility.ReadMeAccessibilityService
import com.readme.app.diagnostics.ReadMeCrashLogger
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.reading.ReadingSessionState
import com.readme.app.settings.ReadMeSettings
import com.readme.app.settings.ReadMeSettingsRepository
import com.readme.app.ui.overlay.BubbleLifecyclePolicy
import com.readme.app.ui.overlay.BubbleVisibilityState
import com.readme.app.ui.overlay.ScreenHighlightOverlayController
import com.readme.app.ui.overlay.ScreenRegionSelectionController
import com.readme.app.ui.overlay.SystemFloatingBubbleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ReadMeReadingService : Service() {

    val serviceInstanceId: Long = ReadMeCrashLogger.serviceInstanceCounter.incrementAndGet()

    private val sessionRuntime by lazy { ReadMeReadingSessionRuntime.getInstance(applicationContext) }
    private val settingsRepo by lazy { sessionRuntime.settingsRepository ?: ReadMeSettingsRepository(applicationContext) }
    private var bubbleController: SystemFloatingBubbleController? = null
    private var selectionController: ScreenRegionSelectionController? = null
    private var highlightOverlayController: ScreenHighlightOverlayController? = null
    private var autoNavigationCoordinator: com.readme.app.accessibility.autonav.AutoNavigationCoordinator? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastSettings: ReadMeSettings = ReadMeSettings()

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        ReadMeCrashLogger.currentServiceInstanceId = serviceInstanceId
        ReadMeCrashLogger.currentLifecycleState = "ServiceCreated"
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "ReadMeReadingService onCreate (ID: $serviceInstanceId)")
        }

        createNotificationChannel()
        bubbleController = SystemFloatingBubbleController(applicationContext)
        selectionController = ScreenRegionSelectionController(applicationContext)
        highlightOverlayController = ScreenHighlightOverlayController(applicationContext)
        autoNavigationCoordinator = com.readme.app.accessibility.autonav.AutoNavigationCoordinator(
            context = applicationContext,
            sessionRuntime = sessionRuntime,
            getHighlightOverlayController = { highlightOverlayController }
        )

        val foregroundAndPickerFlow = combine(
            sessionRuntime.appForegroundState,
            sessionRuntime.isDocumentPickerActive
        ) { fg, dp -> fg to dp }

        serviceScope.launch {
            combine(
                sessionRuntime.readingSessionState,
                sessionRuntime.activeDocumentState,
                foregroundAndPickerFlow,
                settingsRepo.settingsFlow,
                sessionRuntime.isBubbleClosedByUser
            ) { sessionState, docState, fgAndDp, settings, isClosedByUser ->
                lastSettings = settings
                BubbleAndNotificationState(
                    sessionState = sessionState,
                    docState = docState,
                    isForeground = fgAndDp.first,
                    isDocumentPickerActive = fgAndDp.second,
                    settings = settings,
                    isClosedByUser = isClosedByUser
                )
            }.collect { state ->
                updateNotificationAndBubble(state)
            }
        }

        serviceScope.launch {
            combine(
                sessionRuntime.readingSessionState,
                sessionRuntime.readingEngine.currentSegment,
                sessionRuntime.activeDocumentState
            ) { sessionState, currentSeg, docState ->
                Triple(sessionState, currentSeg, docState)
            }.collect { (sessionState, currentSeg, docState) ->
                val shouldHighlight = (sessionState.isReading || (sessionState.sessionState == ReadingSessionState.Stopped && !sessionState.isCompleted)) &&
                        currentSeg != null &&
                        currentSeg.boundingBoxes.isNotEmpty() &&
                        docState.isEphemeral
                if (shouldHighlight) {
                    highlightOverlayController?.showHighlight(currentSeg!!.boundingBoxes)
                } else {
                    highlightOverlayController?.clearHighlight()
                }
            }
        }

        serviceScope.launch {
            ReadMeAccessibilityService.activePackageFlow.collect { activePkg ->
                val docState = sessionRuntime.activeDocumentState.value
                if (docState.isEphemeral && !docState.sourcePackageName.isNullOrBlank()) {
                    if (!activePkg.isNullOrBlank() && activePkg != packageName && activePkg != docState.sourcePackageName) {
                        // User switched to another app: clear overlay highlight, cancel auto-advance, and stop ephemeral reading
                        autoNavigationCoordinator?.reset()
                        sessionRuntime.stopReading()
                        highlightOverlayController?.clearHighlight()
                        sessionRuntime.discardEphemeralContext()
                    }
                }
            }
        }

        serviceScope.launch {
            combine(
                sessionRuntime.readingSessionState,
                sessionRuntime.activeDocumentState,
                settingsRepo.settingsFlow
            ) { sessionState, docState, settings ->
                Triple(sessionState, docState, settings)
            }.collect { (sessionState, docState, settings) ->
                if (sessionState.isCompleted &&
                    docState.isEphemeral &&
                    settings.isAutoAdvanceScreenReadingEnabled &&
                    settings.isCrossAppReadingEnabled
                ) {
                    val target = autoNavigationCoordinator?.currentTarget ?: ReadMeAccessibilityService.instance?.identifyTargetWindow()
                    if (target != null) {
                        val cycleResult = autoNavigationCoordinator?.attemptAutoAdvance(target, serviceScope)
                        when (cycleResult) {
                            is com.readme.app.accessibility.autonav.AutoAdvanceCycleResult.EndOfAccessibleContent,
                            is com.readme.app.accessibility.autonav.AutoAdvanceCycleResult.ContentUnchanged -> {
                                Toast.makeText(this@ReadMeReadingService, "End of accessible content", Toast.LENGTH_SHORT).show()
                            }
                            is com.readme.app.accessibility.autonav.AutoAdvanceCycleResult.Unavailable -> {
                                Toast.makeText(this@ReadMeReadingService, "Automatic screen advance isn't available here.", Toast.LENGTH_SHORT).show()
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "ReadMeReadingService onStartCommand: action=${intent?.action} (ID: $serviceInstanceId)")
        }
        when (intent?.action) {
            ACTION_START_READING -> {
                val initialNotification = buildNotification(
                    sessionRuntime.readingSessionState.value,
                    sessionRuntime.activeDocumentState.value
                )
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            initialNotification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, initialNotification)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to startForeground in onStartCommand: ${e.message}")
                }
                sessionRuntime.startReading()
            }
            ACTION_STOP_READING -> {
                sessionRuntime.stopReading()
            }
            ACTION_SYNC_SERVICE -> {
                // State combination will update automatically
            }
        }
        return START_STICKY
    }

    private fun updateNotificationAndBubble(state: BubbleAndNotificationState) {
        val canDraw = Settings.canDrawOverlays(this)
        ReadMeCrashLogger.currentReadingState = if (state.sessionState.isReading) "Reading" else "Idle"
        ReadMeCrashLogger.isAppForeground = state.isForeground

        val visibilityState = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = state.settings.isFloatingReadmeEnabled,
            hasOverlayPermission = canDraw,
            isForeground = state.isForeground,
            isClosedByUser = state.isClosedByUser,
            isDocumentPickerActive = state.isDocumentPickerActive
        )

        // Notification management
        if (state.sessionState.isReading) {
            val notification = buildNotification(state.sessionState, state.docState)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to startForeground: ${e.message}")
            }
        } else {
            // Stopped or paused
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stopForeground: ${e.message}")
            }

            // Only stop service if floating bubble is disabled AND not reading
            if (!state.settings.isFloatingReadmeEnabled && !state.sessionState.isReading) {
                stopSelf()
            }
        }

        // System Floating Bubble management
        val hasAccessibility = ReadMeAccessibilityService.isConnected
        val canAcquire = hasAccessibility && state.settings.isCrossAppReadingEnabled && !state.docState.hasActiveDocument

        if (visibilityState == BubbleVisibilityState.Visible) {
            bubbleController?.show(
                sessionState = state.sessionState,
                activeDocumentState = state.docState,
                crossAppReadingEnabled = state.settings.isCrossAppReadingEnabled,
                canAcquireText = canAcquire,
                isAutoAdvanceEnabled = state.settings.isAutoAdvanceScreenReadingEnabled,
                onToggleReading = {
                    if (sessionRuntime.readingSessionState.value.isReading) {
                        autoNavigationCoordinator?.cancelPendingNavigation()
                        sessionRuntime.pauseReading()
                    } else if (state.docState.isEphemeral && state.sessionState.isCompleted && state.docState.hasSuspendedPrimary) {
                        autoNavigationCoordinator?.reset()
                        sessionRuntime.returnToPrimaryDocument()
                    } else if (state.docState.hasActiveDocument) {
                        sessionRuntime.resumeReading()
                    }
                },
                onPauseReading = {
                    autoNavigationCoordinator?.cancelPendingNavigation()
                    sessionRuntime.pauseReading()
                },
                onResumeReading = {
                    sessionRuntime.resumeReading()
                },
                onReselectArea = {
                    autoNavigationCoordinator?.reset()
                    handleReselectArea()
                },
                onStopReading = {
                    autoNavigationCoordinator?.reset()
                    sessionRuntime.stopReading()
                    highlightOverlayController?.clearHighlight()
                    sessionRuntime.returnToPrimaryDocument()
                },
                onCloseBubble = {
                    autoNavigationCoordinator?.reset()
                    sessionRuntime.closeBubbleByUser()
                },
                onAcquireMode = { mode ->
                    startAcquisition(mode)
                }
            )
        } else {
            bubbleController?.hide()
        }
    }

    private fun handleReselectArea() {
        val docState = sessionRuntime.activeDocumentState.value
        if (docState.isEphemeral) {
            sessionRuntime.pauseReading()
            startAcquisition(CrossAppAcquisitionMode.SCREEN_OCR)
        }
    }

    private fun startAcquisition(mode: CrossAppAcquisitionMode) {
        try {
            if (mode == CrossAppAcquisitionMode.SCREEN_OCR) {
                // Temporarily hide the floating bubble so it doesn't obstruct region selection
                bubbleController?.hide()
                val service = ReadMeAccessibilityService.instance
                val targetWindow = service?.identifyTargetWindow()
                selectionController?.show(
                    windowBounds = targetWindow?.windowBounds,
                    onRegionSelected = { selectedRegion ->
                        executeAcquisitionFlow(mode, selectedRegion)
                    },
                    onCancelled = {
                        // If cancelled, restore bubble visibility
                        val canDraw = Settings.canDrawOverlays(this@ReadMeReadingService)
                        val docState = sessionRuntime.activeDocumentState.value
                        val settings = lastSettings
                        if (BubbleLifecyclePolicy.computeVisibilityState(
                                isFloatingEnabled = settings.isFloatingReadmeEnabled,
                                hasOverlayPermission = canDraw,
                                isForeground = sessionRuntime.appForegroundState.value,
                                isClosedByUser = sessionRuntime.isBubbleClosedByUser.value,
                                isDocumentPickerActive = sessionRuntime.isDocumentPickerActive.value
                            ) == BubbleVisibilityState.Visible
                        ) {
                            val sessionState = sessionRuntime.readingSessionState.value
                            bubbleController?.show(
                                sessionState = sessionState,
                                activeDocumentState = docState,
                                crossAppReadingEnabled = settings.isCrossAppReadingEnabled,
                                canAcquireText = ReadMeAccessibilityService.isConnected && settings.isCrossAppReadingEnabled && !docState.hasActiveDocument
                            )
                        }
                    }
                )
            } else {
                executeAcquisitionFlow(mode, null)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error starting acquisition", t)
            Toast.makeText(this@ReadMeReadingService, "Unable to read screen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun executeAcquisitionFlow(
        mode: CrossAppAcquisitionMode,
        selectedRegion: android.graphics.Rect?
    ) {
        if (mode == CrossAppAcquisitionMode.SCREEN_OCR) {
            Toast.makeText(this@ReadMeReadingService, "Reading screen...", Toast.LENGTH_SHORT).show()
        }
        serviceScope.launch {
            try {
                val service = ReadMeAccessibilityService.instance
                val targetPkg = service?.currentActivePackage
                val targetWindow = service?.identifyTargetWindow()
                val appLabel = targetPkg?.let { getApplicationLabel(it) } ?: targetWindow?.let { getApplicationLabel(it.packageName) }
                val ocrEngine = if (mode == CrossAppAcquisitionMode.SCREEN_OCR) com.readme.app.accessibility.OnDeviceCrossAppOcrEngine() else null
                val displayMetrics = resources.displayMetrics

                val result = try {
                    com.readme.app.accessibility.CrossAppReadingCoordinator.acquire(
                        mode = mode,
                        textAcquirer = service,
                        screenshotCapturer = service,
                        ocrEngine = ocrEngine,
                        request = com.readme.app.accessibility.CrossAppAcquisitionRequest(targetPackageName = targetPkg),
                        target = targetWindow,
                        appLabel = appLabel,
                        selectedRegion = selectedRegion,
                        displayWidth = displayMetrics.widthPixels,
                        displayHeight = displayMetrics.heightPixels
                    )
                } finally {
                    ocrEngine?.close()
                }

                if (result is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.Success) {
                    autoNavigationCoordinator?.onInitialOcrCompleted(
                        documentText = result.document.allSegments().joinToString(" ") { it.text },
                        region = selectedRegion,
                        target = targetWindow
                    )
                    sessionRuntime.loadEphemeralDocument(
                        document = result.document,
                        displayName = result.document.metadata.title,
                        sourcePackageName = result.sourcePackageName,
                        sourceAppLabel = result.sourceAppLabel,
                        snapshotIdentity = System.currentTimeMillis()
                    )
                    sessionRuntime.startReading()
                } else {
                    val msg = when (result) {
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.NoTextAvailable -> "No readable text found in the selected area."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.OcrReturnedEmpty -> "No readable text found in the selected area."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.TextSegmentationEmpty -> "No sentences could be identified in the selected text."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.OcrProviderUnavailable -> "Text recognition is currently unavailable."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.CropOutsideScreenshot -> "Selected area is outside the active screen."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.SelectedAreaTooSmall -> "Please select a larger area."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.SelectedAreaOutsideWindow -> "Selected area is outside the active window."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.CaptureUnavailable -> "Screen capture is unavailable."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.ReadMeSelfIgnored -> "Switch to another app to read."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.ServiceUnavailable -> "Accessibility service is unavailable."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.AppSwitched -> "The selected app changed before reading could begin."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.RateLimited -> "Please wait a moment before trying again."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.SecureWindow -> "That screen cannot be read."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.SensitiveContentBlocked -> "That screen contains protected content."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.ApiNotSupported -> "Screen reading requires Android 14+."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.InvalidTarget -> "No active window found to read."
                        is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.UnknownError -> "Unable to read selected screen."
                        else -> "Unable to read text."
                    }
                    Toast.makeText(this@ReadMeReadingService, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in executeAcquisitionFlow coroutine", t)
                Toast.makeText(this@ReadMeReadingService, "Screen reading error: ${t.message ?: "unexpected error"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getApplicationLabel(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun buildNotification(
        sessionState: ActiveReadingSessionState,
        docState: ActiveDocumentState
    ): Notification {
        val title = when {
            docState.isEphemeral -> "Reading Screen: ${docState.sourceAppLabel ?: "App"}"
            docState.hasActiveDocument -> docState.displayName.ifBlank { docState.title }
            else -> "ReadMe"
        }
        val text = when {
            sessionState.isReading -> "Reading in progress..."
            sessionState.isCompleted -> "Reading completed"
            else -> "Ready to read"
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ReadMeReadingService::class.java).apply {
                action = ACTION_STOP_READING
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Stop", stopIntent)
            .setOngoing(sessionState.isReading)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ReadMe Reading Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls and status for background reading in ReadMe"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ReadMeCrashLogger.currentLifecycleState = "ServiceDestroyed"
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "ReadMeReadingService onDestroy (ID: $serviceInstanceId)")
        }
        bubbleController?.destroy()
        selectionController?.dismiss()
        highlightOverlayController?.destroy()
        bubbleController = null
        selectionController = null
        highlightOverlayController = null
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class BubbleAndNotificationState(
        val sessionState: ActiveReadingSessionState,
        val docState: ActiveDocumentState,
        val isForeground: Boolean,
        val isDocumentPickerActive: Boolean,
        val settings: ReadMeSettings,
        val isClosedByUser: Boolean
    )

    companion object {
        private const val TAG = "ReadMeReadingService"

        const val CHANNEL_ID = "readme_reading_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_READING = "com.readme.app.action.START_READING"
        const val ACTION_STOP_READING = "com.readme.app.action.STOP_READING"
        const val ACTION_SYNC_SERVICE = "com.readme.app.action.SYNC_SERVICE"

        fun startReading(context: Context) {
            val intent = Intent(context, ReadMeReadingService::class.java).apply {
                action = ACTION_START_READING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopReading(context: Context) {
            val intent = Intent(context, ReadMeReadingService::class.java).apply {
                action = ACTION_STOP_READING
            }
            context.startService(intent)
        }

        fun syncService(context: Context) {
            val intent = Intent(context, ReadMeReadingService::class.java).apply {
                action = ACTION_SYNC_SERVICE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Service sync from background postponed: ${e.message}")
            }
        }
    }
}
