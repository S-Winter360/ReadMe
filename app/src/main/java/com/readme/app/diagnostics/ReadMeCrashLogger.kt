package com.readme.app.diagnostics

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.Log
import com.readme.app.BuildConfig
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Diagnostic crash logging mechanism for ReadMe.
 *
 * Captures uncaught exceptions on any thread, formats full diagnosis details
 * (exception class, message, stack trace, thread details, memory metrics, OS version,
 * service/controller instance IDs, overlay counts, lifecycle state),
 * logs them to Logcat under the tag [TAG], and retains the last crash record in-memory.
 *
 * STRICTLY EXCLUDES:
 * - screenshot data
 * - OCR text
 * - document contents
 * - passwords
 * - sensitive screen contents
 */
object ReadMeCrashLogger {

    const val TAG = "ReadMeCrashLogger"

    // Diagnostics and Audit Counters (Phase 9W Section 12 & 13)
    val serviceInstanceCounter = AtomicLong(0L)
    val controllerInstanceCounter = AtomicLong(0L)

    val bubbleAddCount = AtomicInteger(0)
    val bubbleRemoveCount = AtomicInteger(0)
    val selectionAddCount = AtomicInteger(0)
    val selectionRemoveCount = AtomicInteger(0)

    @Volatile var currentServiceInstanceId: Long = 0L
    @Volatile var currentControllerInstanceId: Long = 0L
    @Volatile var currentLifecycleState: String = "Initialized"
    @Volatile var bubbleControllerState: String = "Hidden"
    @Volatile var overlayPermissionGranted: Boolean = false
    @Volatile var isBubbleViewAttached: Boolean = false
    @Volatile var isSelectionAttached: Boolean = false
    @Volatile var isAppForeground: Boolean = false
    @Volatile var currentReadingState: String = "Stopped"

    data class CrashInfo(
        val timestamp: Long,
        val threadName: String,
        val threadId: Long,
        val exceptionClass: String,
        val message: String?,
        val stackTrace: String,
        val freeMemoryBytes: Long,
        val totalMemoryBytes: Long,
        val maxMemoryBytes: Long,
        val nativeHeapAllocatedBytes: Long,
        val serviceInstanceId: Long = 0L,
        val controllerInstanceId: Long = 0L,
        val lifecycleState: String = "",
        val bubbleState: String = "",
        val overlayPermission: Boolean = false,
        val bubbleAttached: Boolean = false,
        val selectionAttached: Boolean = false,
        val appForeground: Boolean = false,
        val readingState: String = "",
        val bubbleAddTotal: Int = 0,
        val bubbleRemoveTotal: Int = 0,
        val selectionAddTotal: Int = 0,
        val selectionRemoveTotal: Int = 0
    )

    private val lastCrashRef = AtomicReference<CrashInfo?>(null)
    val lastCrash: CrashInfo?
        get() = lastCrashRef.get()

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var isInstalled = false

    @Synchronized
    fun install(context: Context? = null) {
        if (isInstalled) return
        isInstalled = true
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(thread, throwable)
            } catch (loggingError: Throwable) {
                Log.e(TAG, "Failed while logging crash: ${loggingError.message}", loggingError)
            } finally {
                // Delegate to original system handler if available to preserve standard Android OS behavior
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        if (BuildConfig.DEBUG) {
            Log.i(TAG, "ReadMeCrashLogger installed successfully (PID: ${Process.myPid()})")
        }
    }

    fun recordCrash(thread: Thread, throwable: Throwable): CrashInfo {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        val fullStackTrace = sw.toString()

        val runtime = Runtime.getRuntime()
        val crashInfo = CrashInfo(
            timestamp = System.currentTimeMillis(),
            threadName = thread.name,
            threadId = thread.id,
            exceptionClass = throwable.javaClass.name,
            message = throwable.message,
            stackTrace = fullStackTrace,
            freeMemoryBytes = runtime.freeMemory(),
            totalMemoryBytes = runtime.totalMemory(),
            maxMemoryBytes = runtime.maxMemory(),
            nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            serviceInstanceId = currentServiceInstanceId,
            controllerInstanceId = currentControllerInstanceId,
            lifecycleState = currentLifecycleState,
            bubbleState = bubbleControllerState,
            overlayPermission = overlayPermissionGranted,
            bubbleAttached = isBubbleViewAttached,
            selectionAttached = isSelectionAttached,
            appForeground = isAppForeground,
            readingState = currentReadingState,
            bubbleAddTotal = bubbleAddCount.get(),
            bubbleRemoveTotal = bubbleRemoveCount.get(),
            selectionAddTotal = selectionAddCount.get(),
            selectionRemoveTotal = selectionRemoveCount.get()
        )

        lastCrashRef.set(crashInfo)

        val logOutput = buildString {
            appendLine("================ FATAL EXCEPTION CAUGHT BY README CRASH LOGGER ================")
            appendLine("Thread: ${thread.name} (id: ${thread.id}, isDaemon: ${thread.isDaemon})")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
            appendLine("Android SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Lifecycle State: ${crashInfo.lifecycleState}, Foreground: ${crashInfo.appForeground}")
            appendLine("Service Instance ID: ${crashInfo.serviceInstanceId}, Controller Instance ID: ${crashInfo.controllerInstanceId}")
            appendLine("Bubble State: ${crashInfo.bubbleState}, Overlay Permission: ${crashInfo.overlayPermission}")
            appendLine("Bubble Attached: ${crashInfo.bubbleAttached} (Adds: ${crashInfo.bubbleAddTotal}, Removes: ${crashInfo.bubbleRemoveTotal})")
            appendLine("Selection Attached: ${crashInfo.selectionAttached} (Adds: ${crashInfo.selectionAddTotal}, Removes: ${crashInfo.selectionRemoveTotal})")
            appendLine("Reading State: ${crashInfo.readingState}")
            appendLine("Memory (Free/Total/Max MB): ${crashInfo.freeMemoryBytes / (1024 * 1024)} / ${crashInfo.totalMemoryBytes / (1024 * 1024)} / ${crashInfo.maxMemoryBytes / (1024 * 1024)}")
            appendLine("Native Heap Allocated MB: ${crashInfo.nativeHeapAllocatedBytes / (1024 * 1024)}")
            appendLine("Stack trace:")
            appendLine(fullStackTrace)
            appendLine("================================================================================")
        }

        Log.e(TAG, logOutput, throwable)
        return crashInfo
    }

    fun clearLastCrash() {
        lastCrashRef.set(null)
    }

    fun resetAuditCounters() {
        bubbleAddCount.set(0)
        bubbleRemoveCount.set(0)
        selectionAddCount.set(0)
        selectionRemoveCount.set(0)
    }
}
