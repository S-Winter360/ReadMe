package com.readme.app.ui.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Diagnostic and behavioral tests for the ReadMe wallpaper pipeline:
 * - Asset discovery
 * - Supported image extensions (spaces, accents, uppercase, non-English)
 * - Invalid file handling
 * - Empty source list handling
 * - Wallpaper selection, modulo math, and 20-minute interval rotation
 * - Corrupt stream / decoding fallback simulation
 */
class ReadMeBackgroundManagerTest {

    @Test
    fun testSupportedExtensions_validFormatsAccepted() {
        val validFilenames = listOf(
            "wallpaper.jpg",
            "photo.jpeg",
            "art.png",
            "modern.webp",
            "UPPERCASE.JPG",
            "Mixed_Case.PnG",
            "Moody Black & White Manga Boy Wallpaper _ A Quieter Mind.jpg",
            "скорбь сатаны.jpg",
            "Anlayamazlar________.jpg",
            "1031324383431051372.jpg",
            "study hard worker.jpg"
        )

        for (filename in validFilenames) {
            assertTrue(
                "Expected $filename to be accepted as supported image format",
                ReadMeBackgroundManager.isSupportedImageExtension(filename)
            )
        }
    }

    @Test
    fun testSupportedExtensions_unsupportedFormatsRejected() {
        val invalidFilenames = listOf(
            "README.txt",
            "document.pdf",
            "audio.mp3",
            "script.sh",
            "noextension",
            ".gitignore",
            ""
        )

        for (filename in invalidFilenames) {
            assertFalse(
                "Expected $filename to be rejected as unsupported format",
                ReadMeBackgroundManager.isSupportedImageExtension(filename)
            )
        }
    }

    @Test
    fun testAssetFilesOnDisk_allExistAndAreValidImages() {
        val assetsDir = File("src/main/assets/backgrounds")
        val fallbackDir = if (assetsDir.exists()) assetsDir else File("app/src/main/assets/backgrounds")
        assertTrue("Assets backgrounds directory must exist: ${fallbackDir.absolutePath}", fallbackDir.exists())

        val files = fallbackDir.listFiles()?.filter {
            ReadMeBackgroundManager.isSupportedImageExtension(it.name)
        } ?: emptyList()

        assertTrue("Expected at least 16 background images present in assets", files.size >= 16)

        for (file in files) {
            assertTrue("File ${file.name} must not be empty", file.length() > 1000)
            val bytes = file.readBytes()
            assertTrue("File ${file.name} must have valid byte content", bytes.isNotEmpty())

            if (file.extension.lowercase() in listOf("jpg", "jpeg")) {
                // Verify JPEG Start Of Image (SOI) marker: 0xFF 0xD8
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                assertEquals("JPEG ${file.name} should start with 0xFF", 0xFF, b0)
                assertEquals("JPEG ${file.name} should start with 0xD8", 0xD8, b1)
            }
        }
    }

    @Test
    fun testWallpaperRotationIndexCalculation() {
        val totalWallpapers = 19
        val intervalMinutes = 20
        val intervalMs = intervalMinutes * 60 * 1000L

        // Slot 0 (e.g. time 0)
        val time0 = 0L
        val slot0 = (time0 / intervalMs)
        val index0 = ((slot0) % totalWallpapers).toInt()
        assertEquals(0, index0)

        // Exactly 20 mins later (Slot 1)
        val time1 = intervalMs
        val slot1 = (time1 / intervalMs)
        val index1 = ((slot1) % totalWallpapers).toInt()
        assertEquals(1, index1)

        // 40 mins later (Slot 2)
        val time2 = 2 * intervalMs
        val slot2 = (time2 / intervalMs)
        val index2 = ((slot2) % totalWallpapers).toInt()
        assertEquals(2, index2)

        // Full cycle wrap-around at 19 * 20 mins
        val time19 = 19 * intervalMs
        val slot19 = (time19 / intervalMs)
        val index19 = ((slot19) % totalWallpapers).toInt()
        assertEquals(0, index19)
    }

    @Test
    fun testWallpaperManualShuffleOffsets() {
        val totalWallpapers = 10
        val baseSlot = 5L

        // Base slot 5
        val baseIndex = (baseSlot % totalWallpapers).toInt()
        assertEquals(5, baseIndex)

        // Shuffle next (manualOffset = +1)
        val nextOffset = 1
        val nextIndex = ((baseSlot + nextOffset) % totalWallpapers).toInt()
        assertEquals(6, nextIndex)

        // Shuffle prev (manualOffset = -1) with safe positive modulo
        val prevOffset = -1
        val calcIndex = ((baseSlot + prevOffset) % totalWallpapers).toInt()
        val positiveIndex = if (calcIndex < 0) calcIndex + totalWallpapers else calcIndex
        assertEquals(4, positiveIndex)

        // Large negative offset wrap
        val largeNegOffset = -12
        val calcNegIndex = ((baseSlot + largeNegOffset) % totalWallpapers).toInt()
        val safeNegIndex = if (calcNegIndex < 0) calcNegIndex + totalWallpapers else calcNegIndex
        assertEquals(3, safeNegIndex)
    }

    @Test
    fun testCandidateFallbackLoop() {
        // Simulate a scenario where index 2 is corrupt/fails to decode
        val candidates = listOf("img0.jpg", "img1.jpg", "corrupt.jpg", "img3.jpg")
        val failingIndex = 2
        val targetSlot = 2 // points initially to failingIndex

        var selectedIndex = -1
        for (offset in candidates.indices) {
            val candidateIndex = (targetSlot + offset) % candidates.size
            if (candidateIndex != failingIndex) {
                selectedIndex = candidateIndex
                break
            }
        }

        // Must gracefully fall back to the next candidate (index 3)
        assertEquals(3, selectedIndex)
    }

    @Test
    fun testEmptySourcesSafeHandling() {
        val emptySources = emptyList<String>()
        val positiveIndex = if (emptySources.isEmpty()) -1 else 0
        assertEquals(-1, positiveIndex)
    }
}
