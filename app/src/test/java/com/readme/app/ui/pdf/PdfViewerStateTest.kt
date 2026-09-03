package com.readme.app.ui.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfViewerStateTest {

    private class TestDocumentSession {
        var visualState: PdfViewerState = PdfViewerState.Empty

        fun onPdfSelected(displayName: String, extractionSuccess: Boolean) {
            // Immediately clear/set to loading to avoid stale visual display
            visualState = PdfViewerState.Loading()
            if (extractionSuccess) {
                visualState = PdfViewerState.Active(displayName = displayName)
            } else {
                visualState = PdfViewerState.Empty
            }
        }

        fun onTxtSelected() {
            visualState = PdfViewerState.Empty
        }

        fun onEpubSelected() {
            visualState = PdfViewerState.Empty
        }
    }

    @Test
    fun pdfViewerState_startsEmptyWhenNoPdfSelected() {
        val initialState: PdfViewerState = PdfViewerState.Empty

        assertTrue(initialState.isEmpty)
        assertFalse(initialState.isActive)
        assertFalse(initialState.isLoading)
    }

    @Test
    fun pdfViewerState_becomesActiveWhenPdfSelectedSuccessfully() {
        val session = TestDocumentSession()

        assertEquals(PdfViewerState.Empty, session.visualState)

        session.onPdfSelected("sample.pdf", extractionSuccess = true)

        assertTrue(session.visualState.isActive)
        assertFalse(session.visualState.isEmpty)
        val active = session.visualState as PdfViewerState.Active
        assertEquals("sample.pdf", active.displayName)
    }

    @Test
    fun pdfViewerState_replacingPdfAWithPdfB_clearsAndReplacesOldVisualState() {
        val session = TestDocumentSession()

        // First select PDF A
        session.onPdfSelected("docA.pdf", extractionSuccess = true)
        assertTrue(session.visualState.isActive)
        assertEquals("docA.pdf", (session.visualState as PdfViewerState.Active).displayName)

        // Replace with PDF B
        session.onPdfSelected("docB.pdf", extractionSuccess = true)
        assertTrue(session.visualState.isActive)
        val activeB = session.visualState as PdfViewerState.Active
        assertEquals("docB.pdf", activeB.displayName)
    }

    @Test
    fun pdfViewerState_selectingTxt_clearsPdfVisualState() {
        val session = TestDocumentSession()

        session.onPdfSelected("doc.pdf", extractionSuccess = true)
        assertTrue(session.visualState.isActive)

        // Select TXT
        session.onTxtSelected()
        assertTrue(session.visualState.isEmpty)
        assertFalse(session.visualState.isActive)
    }

    @Test
    fun pdfViewerState_selectingEpub_clearsPdfVisualState() {
        val session = TestDocumentSession()

        session.onPdfSelected("doc.pdf", extractionSuccess = true)
        assertTrue(session.visualState.isActive)

        // Select EPUB
        session.onEpubSelected()
        assertTrue(session.visualState.isEmpty)
        assertFalse(session.visualState.isActive)
    }

    @Test
    fun pdfViewerState_extractionErrorsDoNotCreateViewerForInvalidDocuments() {
        val session = TestDocumentSession()

        session.onPdfSelected("invalid.pdf", extractionSuccess = false)

        // Must NOT be active
        assertFalse(session.visualState.isActive)
        assertTrue(session.visualState.isEmpty)
    }

    @Test
    fun pdfViewportState_capturesFirstVisiblePage_visibleCount_andZoom() {
        val state = PdfViewportState(
            firstVisiblePage = 3,
            visiblePagesCount = 2,
            zoom = 1.5f
        )

        assertEquals(3, state.firstVisiblePage)
        assertEquals(2, state.visiblePagesCount)
        assertEquals(1.5f, state.zoom, 0.001f)
    }

    @Test
    fun pdfViewportState_defaultValuesAreClean() {
        val state = PdfViewportState()

        assertEquals(0, state.firstVisiblePage)
        assertEquals(0, state.visiblePagesCount)
        assertEquals(1.0f, state.zoom, 0.001f)
    }

    @Test
    fun pdfViewerState_loadingStateProperties() {
        val state = PdfViewerState.Loading()

        assertTrue(state.isLoading)
        assertFalse(state.isActive)
        assertFalse(state.isEmpty)
    }

    @Test
    fun pdfViewerState_errorStateProperties() {
        val state = PdfViewerState.Error("Failed to open PDF")

        assertFalse(state.isLoading)
        assertFalse(state.isActive)
        assertFalse(state.isEmpty)
        assertEquals("Failed to open PDF", state.message)
    }
}
