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
import androidx.core.app.NotificationCompat
import com.readme.app.MainActivity
import com.readme.app.R
import com.readme.app.accessibility.CrossAppAcquisitionMode
import com.readme.app.accessibility.ReadMeAccessibilityService
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.settings.ReadMeSettings
import com.readme.app.settings.ReadMeSettingsRepository
import com.readme.app.ui.overlay.SystemFloatingBubbleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ReadMeReadingService : Service() {

    private val sessionRuntime by lazy { ReadMeReadingSessionRuntime.getInstance(applicationContext) }
    private val settingsRepo by lazy { sessionRuntime.settingsRepository ?: ReadMeSettingsRepository(applicationContext) }
    private var bubbleController: SystemFloatingBubbleController? = null
    private var selectionController: com.readme.app.ui.overlay.ScreenRegionSelectionController? = null
    private var highlightOverlayController: com.readme.app.ui.overlay.ScreenHighlightOverlayController? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bubbleController = SystemFloatingBubbleController(applicationContext)
        selectionController = com.readme.app.ui.overlay.ScreenRegionSelectionController(applicationContext)
        highlightOverlayController = com.readme.app.ui.overlay.ScreenHighlightOverlayController(applicationContext)

        serviceScope.launch {
            combine(
                sessionRuntime.readingSessionState,
                sessionRuntime.activeDocumentState,
                sessionRuntime.appForegroundState,
                settingsRepo.settingsFlow
            ) { sessionState, docState, isForeground, settings ->
                BubbleAndNotificationState(sessionState, docState, isForeground, settings)
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
                if (sessionState.isReading && currentSeg != null && currentSeg.boundingBoxes.isNotEmpty() && docState.isEphemeral) {
                    highlightOverlayController?.showHighlight(currentSeg.boundingBoxes)
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
                        // User switched to another app: clear overlay highlight immediately
                        highlightOverlayController?.clearHighlight()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_READING -> {
                val initialNotification = buildNotification(
                    sessionRuntime.readingSessionState.value,
                    sessionRuntime.activeDocumentState.value
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        initialNotification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, initialNotification)
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
        // Notification management
        if (state.sessionState.isReading) {
            val notification = buildNotification(state.sessionState, state.docState)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            // Stopped or paused
            val canDraw = Settings.canDrawOverlays(this)
            val hasAccessibility = ReadMeAccessibilityService.isConnected
            val shouldShowBubble = state.settings.isSystemBubbleEnabled &&
                    canDraw &&
                    !state.isForeground &&
                    (state.docState.hasActiveDocument || hasAccessibility)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }

            if (!shouldShowBubble && state.isForeground) {
                // If not reading, no bubble to show, and app is foreground, service can stop
                stopSelf()
            }
        }

        // System Floating Bubble management
        val canDraw = Settings.canDrawOverlays(this)
        val hasAccessibility = ReadMeAccessibilityService.isConnected
        val canAcquire = hasAccessibility && !state.docState.hasActiveDocument
        val shouldShowBubble = state.settings.isSystemBubbleEnabled &&
                canDraw &&
                !state.isForeground &&
                (state.docState.hasActiveDocument || hasAccessibility)

        if (shouldShowBubble) {
            bubbleController?.show(
                sessionState = state.sessionState,
                activeDocumentState = state.docState,
                canAcquireText = canAcquire,
                onToggleReading = {
                    if (sessionRuntime.readingSessionState.value.isReading) {
                        sessionRuntime.stopReading()
                    } else if (state.docState.isEphemeral && state.sessionState.isCompleted && state.docState.hasSuspendedPrimary) {
                        sessionRuntime.returnToPrimaryDocument()
                    } else if (state.docState.hasActiveDocument) {
                        sessionRuntime.startReading()
                    }
                },
                onAcquireMode = { mode ->
                    if (mode == CrossAppAcquisitionMode.SCREEN_OCR) {
                        val service = ReadMeAccessibilityService.instance
                        val targetWindow = service?.identifyTargetWindow()
                        selectionController?.show(
                            windowBounds = targetWindow?.windowBounds,
                            onRegionSelected = { selectedRegion ->
                                executeAcquisitionFlow(mode, selectedRegion)
                            },
                            onCancelled = {
                                // User cancelled selection - no-op
                            }
                        )
                    } else {
                        executeAcquisitionFlow(mode, null)
                    }
                }
            )
        } else {
            bubbleController?.hide()
        }
    }

    private fun executeAcquisitionFlow(
        mode: CrossAppAcquisitionMode,
        selectedRegion: android.graphics.Rect?
    ) {
        serviceScope.launch {
            val service = ReadMeAccessibilityService.instance
            val targetPkg = service?.currentActivePackage
            val targetWindow = service?.identifyTargetWindow()
            val appLabel = targetPkg?.let { getApplicationLabel(it) } ?: targetWindow?.let { getApplicationLabel(it.packageName) }
            val ocrEngine = if (mode == CrossAppAcquisitionMode.SCREEN_OCR) com.readme.app.accessibility.OnDeviceCrossAppOcrEngine() else null
            
            val result = try {
                com.readme.app.accessibility.CrossAppReadingCoordinator.acquire(
                    mode = mode,
                    textAcquirer = service,
                    screenshotCapturer = service,
                    ocrEngine = ocrEngine,
                    request = com.readme.app.accessibility.CrossAppAcquisitionRequest(targetPackageName = targetPkg),
                    target = targetWindow,
                    appLabel = appLabel,
                    selectedRegion = selectedRegion
                )
            } finally {
                ocrEngine?.close()
            }

            if (result is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.Success) {
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
                    is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.NoTextAvailable -> "No readable text found"
                    is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.ReadMeSelfIgnored -> "Switch to another app to read"
                    is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.ServiceUnavailable -> "Service unavailable"
                    is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.AppSwitched -> "Cancelled (app switched)"
                    is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.RateLimited -> "Please wait a moment before trying again"
                    is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.SecureWindow -> "No readable image is available from this screen"
                    is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.SensitiveContentBlocked -> "Screen contains sensitive fields"
                    is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.ApiNotSupported -> "Screen reading requires Android 14+"
                    is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.InvalidTarget -> "No active window found"
                    is com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult.UnknownError -> "Error: ${result.details}"
                    else -> "Unable to read text"
                }
                android.widget.Toast.makeText(this@ReadMeReadingService, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getApplicationLabel(packageName: String): String {
        return try {
            val pm = packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    private fun buildNotification(
        sessionState: ActiveReadingSessionState,
        activeDocState: ActiveDocumentState
    ): Notification {
        val title = if (activeDocState.isEphemeral) {
            val appLabel = activeDocState.sourceAppLabel ?: activeDocState.sourcePackageName?.substringAfterLast('.')?.replaceFirstChar { it.uppercase() }
            if (!appLabel.isNullOrBlank()) "Reading from $appLabel" else activeDocState.displayName
        } else {
            activeDocState.displayName.ifBlank { activeDocState.title }.ifBlank { "ReadMe" }
        }
        val content = if (sessionState.isReading) {
            val segment = sessionRuntime.readingEngine.currentSegment.value
            segment?.text?.take(80) ?: "Reading in progress..."
        } else {
            "Reading stopped"
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, ReadMeReadingService::class.java).apply {
            action = if (sessionState.isReading) ACTION_STOP_READING else ACTION_START_READING
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val actionTitle = if (sessionState.isReading) "Stop" else "Play"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_readme_notification)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                if (sessionState.isReading) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                actionTitle,
                togglePendingIntent
            )
            .setOngoing(sessionState.isReading)
            .setOnlyAlertOnce(true)
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
        val settings: ReadMeSettings
    )

    companion object {
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
            } catch (e: IllegalStateException) {
                // Background service restriction
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        context.startForegroundService(intent)
                    } catch (e2: Exception) {
                        // Ignore
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
