package com.readme.app.accessibility

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.content.CrossAppDocumentParser

data class CrossAppAcquisitionRequest(
    val requestId: Long = System.currentTimeMillis(),
    val targetPackageName: String? = null,
    val expectedGeneration: Long = 0L
)

sealed class AcquisitionResult {
    data class Success(val document: ReadingDocument, val snapshot: CrossAppTextSnapshot) : AcquisitionResult()
    object ServiceNotConnected : AcquisitionResult()
    object ReadMeSelfIgnored : AcquisitionResult()
    object NoTextAvailable : AcquisitionResult()
    data class StaleAppSwitch(val requestedPackage: String?, val actualPackage: String) : AcquisitionResult()
    data class StaleGeneration(val expected: Long, val actual: Long) : AcquisitionResult()
    data class Error(val message: String) : AcquisitionResult()
}

/**
 * Coordinates user-driven content acquisition, validates request identity,
 * guards against app-switch race conditions and stale generations, and converts
 * valid snapshots into [ReadingDocument]s.
 */
object CrossAppAcquisitionCoordinator {

    fun executeAcquisition(
        acquirer: CrossAppTextAcquirer?,
        request: CrossAppAcquisitionRequest,
        appLabel: String? = null
    ): AcquisitionResult {
        if (acquirer == null) {
            return AcquisitionResult.ServiceNotConnected
        }

        val snapshot = acquirer.acquireCurrentText() ?: return AcquisitionResult.NoTextAvailable

        if (snapshot.sourcePackageName == CrossAppTextExtractor.README_PACKAGE) {
            return AcquisitionResult.ReadMeSelfIgnored
        }

        if (request.targetPackageName != null &&
            !snapshot.sourcePackageName.equals(request.targetPackageName, ignoreCase = true)
        ) {
            return AcquisitionResult.StaleAppSwitch(request.targetPackageName, snapshot.sourcePackageName)
        }

        if (request.expectedGeneration > 0 && snapshot.generation < request.expectedGeneration) {
            return AcquisitionResult.StaleGeneration(request.expectedGeneration, snapshot.generation)
        }

        if (snapshot.isEmpty) {
            return AcquisitionResult.NoTextAvailable
        }

        val document = CrossAppDocumentParser.parse(snapshot, appLabel)
        if (document.sections.isEmpty() || document.allSegments().isEmpty()) {
            return AcquisitionResult.NoTextAvailable
        }

        return AcquisitionResult.Success(document, snapshot)
    }
}
