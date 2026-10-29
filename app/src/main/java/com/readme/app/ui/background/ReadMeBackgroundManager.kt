package com.readme.app.ui.background

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.random.Random

/**
 * Represents a discovered background image source (bundled in assets or imported into app files).
 */
sealed interface BackgroundImageSource {
    val displayName: String
    fun openStream(context: Context): InputStream?

    data class AssetSource(val assetPath: String, override val displayName: String) : BackgroundImageSource {
        override fun openStream(context: Context): InputStream? = try {
            context.assets.open(assetPath)
        } catch (e: Exception) {
            Log.e("ReadMeBackgroundManager", "Error opening asset $assetPath", e)
            null
        }
    }

    data class FileSource(val file: File, override val displayName: String) : BackgroundImageSource {
        override fun openStream(context: Context): InputStream? = try {
            file.inputStream()
        } catch (e: Exception) {
            Log.e("ReadMeBackgroundManager", "Error opening file ${file.absolutePath}", e)
            null
        }
    }
}

/**
 * Manages main screen background wallpapers:
 * - Scans `app/src/main/assets/backgrounds/` and internal `files/backgrounds/` for image files.
 * - Automatically rotates/shuffles through images at a 20-minute interval.
 * - Decodes images with downsampling to maintain low memory usage and avoid OOM.
 * - Provides manual shuffle trigger and gallery import support.
 */
class ReadMeBackgroundManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        const val ASSET_BACKGROUNDS_DIR = "backgrounds"
        const val INTERNAL_BACKGROUNDS_DIR = "backgrounds"
        const val DEFAULT_INTERVAL_MINUTES = 20

        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp")

        @Volatile
        private var instance: ReadMeBackgroundManager? = null

        fun getInstance(context: Context, scope: CoroutineScope): ReadMeBackgroundManager {
            return instance ?: synchronized(this) {
                instance ?: ReadMeBackgroundManager(context.applicationContext, scope).also {
                    instance = it
                }
            }
        }
    }

    private val _availableSources = MutableStateFlow<List<BackgroundImageSource>>(emptyList())
    val availableSources: StateFlow<List<BackgroundImageSource>> = _availableSources.asStateFlow()

    private val _currentBitmap = MutableStateFlow<ImageBitmap?>(null)
    val currentBitmap: StateFlow<ImageBitmap?> = _currentBitmap.asStateFlow()

    private val _currentImageName = MutableStateFlow<String?>(null)
    val currentImageName: StateFlow<String?> = _currentImageName.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private var manualOffset = 0
    private var tickerJob: Job? = null
    private var intervalMinutes = DEFAULT_INTERVAL_MINUTES
    private var isEnabled = true

    init {
        refreshBackgroundList()
        startTicker()
    }

    fun updateSettings(enabled: Boolean, intervalMin: Int) {
        val shouldRestart = (isEnabled != enabled) || (intervalMinutes != intervalMin)
        isEnabled = enabled
        intervalMinutes = intervalMin.coerceAtLeast(1)

        if (!enabled) {
            _currentBitmap.value = null
            _currentImageName.value = null
        } else if (shouldRestart) {
            startTicker()
        }
    }

    fun refreshBackgroundList() {
        coroutineScope.launch(Dispatchers.IO) {
            val list = mutableListOf<BackgroundImageSource>()

            // 1. Scan internal storage directory: files/backgrounds/
            try {
                val internalDir = File(context.filesDir, INTERNAL_BACKGROUNDS_DIR)
                if (internalDir.exists() && internalDir.isDirectory) {
                    internalDir.listFiles()?.forEach { file ->
                        if (file.isFile && isImageFile(file.name)) {
                            list.add(BackgroundImageSource.FileSource(file, file.name))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ReadMeBackgroundManager", "Error listing internal background files", e)
            }

            // 2. Scan bundled assets: assets/backgrounds/
            try {
                val assetFiles = context.assets.list(ASSET_BACKGROUNDS_DIR)
                assetFiles?.forEach { filename ->
                    if (isImageFile(filename)) {
                        list.add(
                            BackgroundImageSource.AssetSource(
                                "$ASSET_BACKGROUNDS_DIR/$filename",
                                filename
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ReadMeBackgroundManager", "Error listing asset backgrounds", e)
            }

            _availableSources.value = list
            updateCurrentBitmapForTime(System.currentTimeMillis())
        }
    }

    fun shuffleNext() {
        manualOffset++
        coroutineScope.launch(Dispatchers.IO) {
            updateCurrentBitmapForTime(System.currentTimeMillis())
        }
    }

    fun shufflePrevious() {
        manualOffset--
        coroutineScope.launch(Dispatchers.IO) {
            updateCurrentBitmapForTime(System.currentTimeMillis())
        }
    }

    fun importImages(uris: List<Uri>, contentResolver: ContentResolver, onComplete: (Int) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            var importedCount = 0
            val targetDir = File(context.filesDir, INTERNAL_BACKGROUNDS_DIR).apply { mkdirs() }

            uris.forEach { uri ->
                try {
                    val filename = "custom_bg_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}.jpg"
                    val targetFile = File(targetDir, filename)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (targetFile.exists() && targetFile.length() > 0) {
                        importedCount++
                    }
                } catch (e: Exception) {
                    Log.e("ReadMeBackgroundManager", "Failed to import image from uri $uri", e)
                }
            }

            if (importedCount > 0) {
                refreshBackgroundList()
            }
            withContext(Dispatchers.Main) {
                onComplete(importedCount)
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        if (!isEnabled) return

        tickerJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                val intervalMs = intervalMinutes * 60 * 1000L
                val now = System.currentTimeMillis()
                updateCurrentBitmapForTime(now)

                val delayToNextSlot = intervalMs - (now % intervalMs)
                delay(delayToNextSlot.coerceAtLeast(1000L))
            }
        }
    }

    private suspend fun updateCurrentBitmapForTime(timeMillis: Long) {
        if (!isEnabled) {
            _currentBitmap.value = null
            _currentImageName.value = null
            return
        }

        val sources = _availableSources.value
        if (sources.isEmpty()) {
            _currentBitmap.value = null
            _currentImageName.value = null
            return
        }

        val intervalMs = intervalMinutes * 60 * 1000L
        val timeSlot = timeMillis / intervalMs
        val calculatedIndex = ((timeSlot + manualOffset) % sources.size).toInt()
        val positiveIndex = if (calculatedIndex < 0) calculatedIndex + sources.size else calculatedIndex

        for (offset in 0 until sources.size) {
            val candidateIndex = (positiveIndex + offset) % sources.size
            val candidateSource = sources[candidateIndex]
            val decoded = decodeSampledBitmap(candidateSource)
            if (decoded != null) {
                _currentIndex.value = candidateIndex
                _currentImageName.value = candidateSource.displayName
                _currentBitmap.value = decoded
                return
            }
        }

        _currentBitmap.value = null
        _currentImageName.value = null
    }

    private fun decodeSampledBitmap(
        source: BackgroundImageSource,
        reqWidth: Int = 1080,
        reqHeight: Int = 1920
    ): ImageBitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            source.openStream(context)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return null
            }

            var inSampleSize = 1
            val height = options.outHeight
            val width = options.outWidth
            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while ((halfHeight / inSampleSize) >= reqHeight || (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = source.openStream(context)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
            bitmap?.asImageBitmap()
        } catch (e: Exception) {
            Log.e("ReadMeBackgroundManager", "Error decoding ${source.displayName}", e)
            null
        }
    }

    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_EXTENSIONS
    }
}
