package com.readme.app.ui.background

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.readme.app.BuildConfig
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
            if (BuildConfig.DEBUG) {
                Log.e("ReadMeWallpaper", "Error opening asset $assetPath: ${e.message}", e)
            }
            null
        }
    }

    data class FileSource(val file: File, override val displayName: String) : BackgroundImageSource {
        override fun openStream(context: Context): InputStream? = try {
            file.inputStream()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e("ReadMeWallpaper", "Error opening file ${file.absolutePath}: ${e.message}", e)
            }
            null
        }
    }
}

/**
 * Manages main screen background wallpapers:
 * - Scans `app/src/main/assets/backgrounds/` and internal `files/backgrounds/` for image files.
 * - Automatically rotates through wallpapers at a 20-minute interval.
 * - Decodes images using memory-efficient inSampleSize downsampling.
 * - Robust against progressive JPEGs, spaces, and non-ASCII filenames using byte-buffer decoding.
 * - Controlled diagnostic logging enabled for debug builds only.
 */
class ReadMeBackgroundManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        const val ASSET_BACKGROUNDS_DIR = "backgrounds"
        const val INTERNAL_BACKGROUNDS_DIR = "backgrounds"
        const val DEFAULT_INTERVAL_MINUTES = 20

        val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        @Volatile
        private var instance: ReadMeBackgroundManager? = null

        fun getInstance(context: Context, scope: CoroutineScope): ReadMeBackgroundManager {
            return instance ?: synchronized(this) {
                instance ?: ReadMeBackgroundManager(context.applicationContext, scope).also {
                    instance = it
                }
            }
        }

        fun isSupportedImageExtension(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in SUPPORTED_EXTENSIONS
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

    fun updateSettings(enabled: Boolean = true, intervalMin: Int = DEFAULT_INTERVAL_MINUTES) {
        val shouldRestart = (intervalMinutes != intervalMin)
        isEnabled = true // Always active as built-in automatic wallpaper feature
        intervalMinutes = intervalMin.coerceAtLeast(1)

        if (shouldRestart) {
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
                        if (file.isFile && isSupportedImageExtension(file.name)) {
                            list.add(BackgroundImageSource.FileSource(file, file.name))
                        }
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e("ReadMeWallpaper", "Error listing internal background files", e)
                }
            }

            // 2. Scan bundled assets: assets/backgrounds/
            try {
                val assetFiles = context.assets.list(ASSET_BACKGROUNDS_DIR)
                if (BuildConfig.DEBUG) {
                    Log.d("ReadMeWallpaper", "Asset scan found ${assetFiles?.size ?: 0} raw entries in $ASSET_BACKGROUNDS_DIR")
                }
                assetFiles?.forEach { filename ->
                    if (isSupportedImageExtension(filename)) {
                        list.add(
                            BackgroundImageSource.AssetSource(
                                "$ASSET_BACKGROUNDS_DIR/$filename",
                                filename
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e("ReadMeWallpaper", "Error listing asset backgrounds", e)
                }
            }

            _availableSources.value = list
            if (BuildConfig.DEBUG) {
                Log.d("ReadMeWallpaper", "Total discovered wallpaper candidates: ${list.size}")
                list.forEachIndexed { i, s ->
                    Log.d("ReadMeWallpaper", "  [$i] ${s.displayName}")
                }
            }

            updateCurrentBitmapForTime(System.currentTimeMillis())
        }
    }

    fun shuffleNext() {
        manualOffset++
        if (BuildConfig.DEBUG) {
            Log.d("ReadMeWallpaper", "Manual shuffle next invoked. Offset is now $manualOffset")
        }
        coroutineScope.launch(Dispatchers.IO) {
            updateCurrentBitmapForTime(System.currentTimeMillis())
        }
    }

    fun shufflePrevious() {
        manualOffset--
        if (BuildConfig.DEBUG) {
            Log.d("ReadMeWallpaper", "Manual shuffle previous invoked. Offset is now $manualOffset")
        }
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
                    if (BuildConfig.DEBUG) {
                        Log.e("ReadMeWallpaper", "Failed to import image from uri $uri", e)
                    }
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
                val delayToNextSlot = intervalMs - (now % intervalMs)
                if (BuildConfig.DEBUG) {
                    Log.d("ReadMeWallpaper", "Next automatic wallpaper rotation in ${delayToNextSlot / 1000}s (${delayToNextSlot / 60000} mins)")
                }
                delay(delayToNextSlot.coerceAtLeast(1000L))
                if (isActive) {
                    if (BuildConfig.DEBUG) {
                        Log.d("ReadMeWallpaper", "Automatic rotation timer fired at $now")
                    }
                    updateCurrentBitmapForTime(System.currentTimeMillis())
                }
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
            if (BuildConfig.DEBUG) {
                Log.d("ReadMeWallpaper", "No wallpaper sources available. Defaulting to solid background.")
            }
            _currentBitmap.value = null
            _currentImageName.value = null
            return
        }

        val intervalMs = intervalMinutes * 60 * 1000L
        val timeSlot = timeMillis / intervalMs
        val calculatedIndex = ((timeSlot + manualOffset) % sources.size).toInt()
        val positiveIndex = if (calculatedIndex < 0) calculatedIndex + sources.size else calculatedIndex

        if (BuildConfig.DEBUG) {
            Log.d("ReadMeWallpaper", "Evaluating wallpaper slot $timeSlot (baseIndex=$positiveIndex, total=${sources.size})")
        }

        // Try current candidate, or fall back to subsequent candidates if decode fails
        for (offset in 0 until sources.size) {
            val candidateIndex = (positiveIndex + offset) % sources.size
            val candidateSource = sources[candidateIndex]
            val decoded = decodeSampledBitmap(candidateSource)
            if (decoded != null) {
                _currentIndex.value = candidateIndex
                _currentImageName.value = candidateSource.displayName
                _currentBitmap.value = decoded
                if (BuildConfig.DEBUG) {
                    Log.d("ReadMeWallpaper", "Successfully activated wallpaper [$candidateIndex]: ${candidateSource.displayName}")
                }
                return
            } else {
                if (BuildConfig.DEBUG) {
                    Log.w("ReadMeWallpaper", "Candidate [$candidateIndex] ${candidateSource.displayName} failed to decode. Trying next...")
                }
            }
        }

        _currentBitmap.value = null
        _currentImageName.value = null
        if (BuildConfig.DEBUG) {
            Log.e("ReadMeWallpaper", "All ${sources.size} wallpaper candidates failed to decode.")
        }
    }

    private fun getTargetDisplayDimensions(): Pair<Int, Int> {
        return try {
            val dm = context.resources.displayMetrics
            Pair(
                dm.widthPixels.coerceAtLeast(720),
                dm.heightPixels.coerceAtLeast(1280)
            )
        } catch (_: Exception) {
            Pair(1080, 1920)
        }
    }

    private fun decodeSampledBitmap(source: BackgroundImageSource): ImageBitmap? {
        val (reqWidth, reqHeight) = getTargetDisplayDimensions()
        return try {
            // Read all bytes once into a byte array.
            // This is essential for progressive JPEGs and AssetInputStream where mark/reset is unsupported.
            val bytes = source.openStream(context)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                if (BuildConfig.DEBUG) {
                    Log.w("ReadMeWallpaper", "Empty stream for ${source.displayName}")
                }
                return null
            }

            // 1. Measure dimensions without allocating full bitmap
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight

            if (origWidth <= 0 || origHeight <= 0) {
                if (BuildConfig.DEBUG) {
                    Log.w("ReadMeWallpaper", "Invalid image bounds for ${source.displayName}: ${origWidth}x${origHeight}")
                }
                return null
            }

            // 2. Compute power-of-2 inSampleSize
            var inSampleSize = 1
            if (origHeight > reqHeight || origWidth > reqWidth) {
                val halfHeight = origHeight / 2
                val halfWidth = origWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight || (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            // 3. Decode bitmap with calculated sample size
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            if (bitmap != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "ReadMeWallpaper",
                        "Decoded ${source.displayName}: ${origWidth}x${origHeight} -> sampled ${bitmap.width}x${bitmap.height} (inSampleSize=$inSampleSize)"
                    )
                }
                bitmap.asImageBitmap()
            } else {
                if (BuildConfig.DEBUG) {
                    Log.w("ReadMeWallpaper", "BitmapFactory returned null for ${source.displayName}")
                }
                null
            }
        } catch (e: Throwable) {
            if (BuildConfig.DEBUG) {
                Log.e("ReadMeWallpaper", "Exception decoding wallpaper ${source.displayName}", e)
            }
            null
        }
    }
}
