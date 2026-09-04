package com.readme.app.ui.pdf

import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.view.PdfView

/**
 * Concrete implementation of [PdfPageNavigator] that delegates to an AndroidX [PdfView].
 * Safe against calling from background threads and handles view detachment or navigation errors.
 */
@OptIn(ExperimentalPdfApi::class)
class PdfViewPageNavigator(
    private val pdfViewProvider: () -> PdfView?
) : PdfPageNavigator {

    override fun navigateToPage(pageIndex: Int) {
        if (pageIndex < 0) return
        val pdfView = pdfViewProvider() ?: return
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                pdfView.scrollToPage(pageIndex)
            } else {
                pdfView.post {
                    try {
                        pdfView.scrollToPage(pageIndex)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Visual navigation to page $pageIndex failed safely in post", t)
                    }
                }
            }
        } catch (t: Throwable) {
            // Visual navigation failure must NOT break or interrupt speech
            Log.e(TAG, "Visual navigation to page $pageIndex failed safely", t)
        }
    }

    companion object {
        private const val TAG = "PdfViewPageNavigator"
    }
}
