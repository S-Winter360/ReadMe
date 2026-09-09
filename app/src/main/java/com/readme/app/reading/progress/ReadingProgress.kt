package com.readme.app.reading.progress

import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingPosition

/**
 * Immutable Data Transfer Object representing persistent reading progress.
 *
 * Persisted fields:
 * - [documentId]: Unique identifier of the document.
 * - [sectionId]: Section identifier where reading stopped.
 * - [segmentId]: Segment identifier where reading stopped.
 * - [segmentIndex]: Deterministic sequential index of the segment.
 * - [sourceType]: Document format (TXT, EPUB, PDF).
 * - [isCompleted]: Whether the document reading was finished.
 * - [lastUpdated]: Epoch timestamp of when progress was recorded.
 * - [version]: Schema version for future migration safety.
 */
data class ReadingProgress(
    val documentId: String,
    val sectionId: String,
    val segmentId: String,
    val segmentIndex: Int,
    val sourceType: ReadingDocumentSourceType = ReadingDocumentSourceType.TXT,
    val isCompleted: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis(),
    val version: Int = 1
) {
    /**
     * Converts this progress DTO into an in-memory [ReadingPosition].
     */
    fun toReadingPosition(): ReadingPosition {
        return ReadingPosition(
            documentId = documentId,
            sectionId = sectionId,
            segmentId = segmentId,
            segmentIndex = segmentIndex
        )
    }

    /**
     * Serializes this progress model to a compact JSON string without external library dependencies.
     */
    fun toJson(): String {
        fun escape(s: String): String = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        return "{" +
            "\"documentId\":\"${escape(documentId)}\"," +
            "\"sectionId\":\"${escape(sectionId)}\"," +
            "\"segmentId\":\"${escape(segmentId)}\"," +
            "\"segmentIndex\":$segmentIndex," +
            "\"sourceType\":\"${sourceType.name}\"," +
            "\"isCompleted\":$isCompleted," +
            "\"lastUpdated\":$lastUpdated," +
            "\"version\":$version" +
            "}"
    }

    companion object {
        /**
         * Creates a [ReadingProgress] instance from an active in-memory [ReadingPosition].
         */
        fun fromPosition(
            position: ReadingPosition,
            sourceType: ReadingDocumentSourceType = ReadingDocumentSourceType.TXT,
            isCompleted: Boolean = false,
            lastUpdated: Long = System.currentTimeMillis(),
            version: Int = 1
        ): ReadingProgress {
            return ReadingProgress(
                documentId = position.documentId,
                sectionId = position.sectionId,
                segmentId = position.segmentId,
                segmentIndex = position.segmentIndex,
                sourceType = sourceType,
                isCompleted = isCompleted,
                lastUpdated = lastUpdated,
                version = version
            )
        }

        /**
         * Safely parses a JSON string into a [ReadingProgress] object.
         * Returns null if parsing fails or required fields are missing.
         */
        fun fromJson(json: String): ReadingProgress? {
            try {
                fun extractString(key: String): String? {
                    val pattern = Regex("\"$key\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")
                    val match = pattern.find(json) ?: return null
                    return match.groupValues[1]
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                }

                fun extractInt(key: String): Int? {
                    val pattern = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
                    return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
                }

                fun extractLong(key: String): Long? {
                    val pattern = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
                    return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull()
                }

                fun extractBoolean(key: String): Boolean? {
                    val pattern = Regex("\"$key\"\\s*:\\s*(true|false)")
                    return pattern.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
                }

                val docId = extractString("documentId") ?: return null
                val secId = extractString("sectionId") ?: return null
                val segId = extractString("segmentId") ?: return null
                val segIdx = extractInt("segmentIndex") ?: 0
                val srcTypeName = extractString("sourceType")
                val srcType = srcTypeName?.let {
                    try {
                        ReadingDocumentSourceType.valueOf(it)
                    } catch (e: Exception) {
                        ReadingDocumentSourceType.TXT
                    }
                } ?: ReadingDocumentSourceType.TXT
                val completed = extractBoolean("isCompleted") ?: false
                val updated = extractLong("lastUpdated") ?: 0L
                val ver = extractInt("version") ?: 1

                return ReadingProgress(
                    documentId = docId,
                    sectionId = secId,
                    segmentId = segId,
                    segmentIndex = segIdx,
                    sourceType = srcType,
                    isCompleted = completed,
                    lastUpdated = updated,
                    version = ver
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}

/**
 * UI / coordinator state representing the current restored progress for the active document.
 */
sealed class SavedProgressState {
    object None : SavedProgressState()
    data class Resumable(val progress: ReadingProgress) : SavedProgressState()
    data class Completed(val progress: ReadingProgress) : SavedProgressState()

    val isResumable: Boolean
        get() = this is Resumable

    val resumeSentenceIndex: Int?
        get() = (this as? Resumable)?.progress?.segmentIndex
}
