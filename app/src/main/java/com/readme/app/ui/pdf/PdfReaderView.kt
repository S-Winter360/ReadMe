package com.readme.app.ui.pdf

import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.view.PdfView
import com.readme.app.ui.theme.DarkSurface
import com.readme.app.ui.theme.TealAccent
import com.readme.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers

/**
 * Dedicated visual rendering boundary for PDF documents in ReadMe.
 *
 * Hosts [androidx.pdf.view.PdfView] in a Jetpack Compose [AndroidView]
 * and manages sandboxed document loading and viewport observation independently
 * from the reading/speech engine.
 */
@OptIn(ExperimentalPdfApi::class)
@Composable
fun PdfReaderView(
    uri: Uri,
    modifier: Modifier = Modifier,
    onViewportChanged: (PdfViewportState) -> Unit = {},
    onNavigatorReady: (PdfPageNavigator?) -> Unit = {},
    onError: (Throwable) -> Unit = {}
) {
    val context = LocalContext.current
    var pdfDocument by remember(uri) { mutableStateOf<PdfDocument?>(null) }
    var isLoading by remember(uri) { mutableStateOf(true) }
    var errorMessage by remember(uri) { mutableStateOf<String?>(null) }

    val currentOnViewportChanged by rememberUpdatedState(onViewportChanged)
    val currentOnNavigatorReady by rememberUpdatedState(onNavigatorReady)
    val currentOnError by rememberUpdatedState(onError)

    LaunchedEffect(uri) {
        isLoading = true
        errorMessage = null
        try {
            val loader = SandboxedPdfLoader(context, Dispatchers.IO)
            val doc = loader.openDocument(uri, "")
            pdfDocument = doc
            isLoading = false
        } catch (t: Throwable) {
            errorMessage = "Unable to render PDF visual preview"
            isLoading = false
            currentOnError(t)
        }
    }

    DisposableEffect(uri) {
        onDispose {
            currentOnNavigatorReady(null)
            try {
                pdfDocument?.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
            pdfDocument = null
        }
    }

    Box(
        modifier = modifier
            .testTag("pdf_reader_view")
            .background(DarkSurface),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    color = TealAccent,
                    modifier = Modifier.testTag("pdf_loading_indicator")
                )
            }
            errorMessage != null -> {
                Text(
                    text = errorMessage ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(16.dp)
                        .testTag("pdf_error_message")
                )
            }
            pdfDocument != null -> {
                val document = pdfDocument!!
                var listenerRef by remember { mutableStateOf<PdfView.OnViewportChangedListener?>(null) }

                AndroidView(
                    factory = { ctx ->
                        PdfView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            val listener = object : PdfView.OnViewportChangedListener {
                                override fun onViewportChanged(
                                    firstVisiblePage: Int,
                                    visiblePagesCount: Int,
                                    pageLocations: android.util.SparseArray<android.graphics.RectF>,
                                    zoom: Float
                                ) {
                                    val state = PdfViewportState(
                                        firstVisiblePage = firstVisiblePage,
                                        visiblePagesCount = visiblePagesCount,
                                        zoom = zoom
                                    )
                                    currentOnViewportChanged(state)
                                }
                            }
                            listenerRef = listener
                            addOnViewportChangedListener(listener)
                            this.pdfDocument = document
                            currentOnNavigatorReady(PdfViewPageNavigator { this })
                        }
                    },
                    update = { view ->
                        if (view.pdfDocument != document) {
                            view.pdfDocument = document
                        }
                        currentOnNavigatorReady(PdfViewPageNavigator { view })
                    },
                    onRelease = { view ->
                        currentOnNavigatorReady(null)
                        listenerRef?.let { listener ->
                            view.removeOnViewportChangedListener(listener)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("pdf_view_android")
                )
            }
        }
    }
}
