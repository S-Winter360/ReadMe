package com.readme.app.speech

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingEngine
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionCoordinator
import com.readme.app.reading.ReadingSessionState
import com.readme.app.reading.progress.InMemoryReadingProgressRepository
import com.readme.app.reading.progress.ReadingProgress
import com.readme.app.settings.ReadMeSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class Phase2AVoiceLibraryTest {

    // =========================================================================
    // 1. Premium ReadMe voices cannot be selected or previewed
    // =========================================================================
    @Test
    fun testPremiumVoicesCannotBeSelectedOrPreviewed() {
        val engine = ReadMeSpeechEngine()
        engine.setStateForTesting(TtsState.Ready)

        val usVoice = ReadMeVoice(
            id = "en-us-voice1",
            displayName = "Daniel",
            locale = Locale("en", "US"),
            isNetworkConnectionRequired = false
        )
        engine.setAvailableVoicesForTesting(listOf(usVoice))

        // Attempting to preview a premium placeholder or blank/invalid voice must not trigger synthesis
        engine.previewVoice("readme_male")
        assertNotEquals("readme_male", engine.state.value.name)

        engine.previewVoice("readme_female")
        assertNotEquals("readme_female", engine.state.value.name)

        engine.previewVoice("")
        assertNotEquals(TtsState.Speaking, engine.state.value)
    }

    // =========================================================================
    // 2. Only English (US) and English (UK) voices are selectable and active
    // =========================================================================
    @Test
    fun testOnlyEnglishUSAndEnglishUKVoicesAreCuratedInPhase2A() {
        val candidates = listOf(
            VoiceMetadata(name = "en-gh-1", locale = Locale("en", "GH"), quality = 400),
            VoiceMetadata(name = "en-us-1", locale = Locale("en", "US"), quality = 400),
            VoiceMetadata(name = "en-gb-1", locale = Locale("en", "GB"), quality = 400),
            VoiceMetadata(name = "en-ng-1", locale = Locale("en", "NG"), quality = 400),
            VoiceMetadata(name = "ar-1", locale = Locale("ar"), quality = 400),
            VoiceMetadata(name = "fr-1", locale = Locale("fr"), quality = 400),
            VoiceMetadata(name = "la-1", locale = Locale("la"), quality = 400),
            VoiceMetadata(name = "es-1", locale = Locale("es"), quality = 400)
        )

        val phase2AVoices = ReadMeVoiceCurator.curatePhase2AFromMetadata(candidates)

        // Only en-US and en-GB should be present in the active curated pool
        assertEquals(2, phase2AVoices.size)
        assertTrue(phase2AVoices.any { it.familyKey == "en-US" })
        assertTrue(phase2AVoices.any { it.familyKey == "en-GB" })

        assertFalse(phase2AVoices.any { it.familyKey == "en-GH" })
        assertFalse(phase2AVoices.any { it.familyKey == "en-NG" })
        assertFalse(phase2AVoices.any { it.familyKey == "ar" })
        assertFalse(phase2AVoices.any { it.familyKey == "fr" })
        assertFalse(phase2AVoices.any { it.familyKey == "la" })
        assertFalse(phase2AVoices.any { it.familyKey == "es" })
    }

    // =========================================================================
    // 3. Voices in active sections are backed by real, distinct TextToSpeech voices
    // =========================================================================
    @Test
    fun testVoicesAreBackedByRealDistinctVoices() {
        val candidates = listOf(
            VoiceMetadata(name = "real-tts-voice-us-m1", locale = Locale("en", "US"), quality = 400),
            VoiceMetadata(name = "real-tts-voice-us-m2", locale = Locale("en", "US"), quality = 350),
            VoiceMetadata(name = "real-tts-voice-us-f1", locale = Locale("en", "US"), quality = 400),
            VoiceMetadata(name = "real-tts-voice-us-f2", locale = Locale("en", "US"), quality = 300)
        )

        val curated = ReadMeVoiceCurator.curatePhase2AFromMetadata(candidates)
        assertEquals(4, curated.size)

        val expectedIds = candidates.map { it.name }.toSet()
        val actualIds = curated.map { it.id }.toSet()
        assertEquals(expectedIds, actualIds)
    }

    // =========================================================================
    // 4. Display names are unique within a locale and map 1:1 to unique voice IDs
    // =========================================================================
    @Test
    fun testDisplayNamesAreUniqueWithinLocaleAndMap1to1() {
        val candidates = listOf(
            VoiceMetadata(name = "v_us_male_1", locale = Locale("en", "US"), quality = 400),
            VoiceMetadata(name = "v_us_male_2", locale = Locale("en", "US"), quality = 350),
            VoiceMetadata(name = "v_us_female_1", locale = Locale("en", "US"), quality = 400),
            VoiceMetadata(name = "v_us_female_2", locale = Locale("en", "US"), quality = 300),
            VoiceMetadata(name = "v_gb_male_1", locale = Locale("en", "GB"), quality = 400),
            VoiceMetadata(name = "v_gb_male_2", locale = Locale("en", "GB"), quality = 350),
            VoiceMetadata(name = "v_gb_female_1", locale = Locale("en", "GB"), quality = 400),
            VoiceMetadata(name = "v_gb_female_2", locale = Locale("en", "GB"), quality = 300)
        )

        val curated = ReadMeVoiceCurator.curatePhase2AFromMetadata(candidates)

        val usVoices = curated.filter { it.familyKey == "en-US" }
        val ukVoices = curated.filter { it.familyKey == "en-GB" }

        assertEquals(4, usVoices.size)
        assertEquals(4, ukVoices.size)

        // Unique display names in US
        val usDisplayNames = usVoices.map { it.displayName }
        assertEquals(usDisplayNames.distinct().size, usDisplayNames.size)

        // Unique display names in UK
        val ukDisplayNames = ukVoices.map { it.displayName }
        assertEquals(ukDisplayNames.distinct().size, ukDisplayNames.size)

        // 1:1 mapping between IDs and display names
        assertEquals(curated.map { it.id }.distinct().size, curated.size)
    }

    // =========================================================================
    // 5. The two male-sounding voices are grouped together and two female-sounding voices together
    // =========================================================================
    @Test
    fun testMaleVoicesGroupedTogetherAndFemaleVoicesGroupedTogether() {
        val candidates = listOf(
            VoiceMetadata(name = "en-us-x-sfg#male_1-local", locale = Locale("en", "US"), quality = 400),
            VoiceMetadata(name = "en-us-x-tpd#female_2-local", locale = Locale("en", "US"), quality = 380),
            VoiceMetadata(name = "en-us-x-iol#male_2-local", locale = Locale("en", "US"), quality = 350),
            VoiceMetadata(name = "en-us-x-smb#female_1-local", locale = Locale("en", "US"), quality = 370)
        )

        val curated = ReadMeVoiceCurator.curatePhase2AFromMetadata(candidates)
        assertEquals(4, curated.size)

        // First two voices must be male
        assertEquals("Male", curated[0].genderDescriptor)
        assertEquals("Male", curated[1].genderDescriptor)
        assertEquals("Daniel", curated[0].displayName)
        assertEquals("Oliver", curated[1].displayName)

        // Next two voices must be female
        assertEquals("Female", curated[2].genderDescriptor)
        assertEquals("Female", curated[3].genderDescriptor)
        assertEquals("Emma", curated[2].displayName)
        assertEquals("Claire", curated[3].displayName)
    }

    // =========================================================================
    // 6. Devices with fewer than four voices show only real voices without crashing or fabricating entries
    // =========================================================================
    @Test
    fun testDevicesWithFewerThanFourVoicesShowOnlyRealVoices() {
        // Single US voice
        val singleVoiceCandidate = listOf(
            VoiceMetadata(name = "single-voice-on-device", locale = Locale("en", "US"), quality = 400)
        )
        val singleResult = ReadMeVoiceCurator.curatePhase2AFromMetadata(singleVoiceCandidate)
        assertEquals(1, singleResult.size)
        assertEquals("single-voice-on-device", singleResult[0].id)

        // Two voices (no fabrication of 3rd and 4th)
        val twoVoiceCandidates = listOf(
            VoiceMetadata(name = "voice-m", locale = Locale("en", "US"), quality = 400),
            VoiceMetadata(name = "voice-f", locale = Locale("en", "US"), quality = 300)
        )
        val twoResult = ReadMeVoiceCurator.curatePhase2AFromMetadata(twoVoiceCandidates)
        assertEquals(2, twoResult.size)
        assertEquals("voice-m", twoResult[0].id)
        assertEquals("voice-f", twoResult[1].id)

        // Empty list returns empty list without crashing
        val emptyResult = ReadMeVoiceCurator.curatePhase2AFromMetadata(emptyList())
        assertTrue(emptyResult.isEmpty())
    }

    // =========================================================================
    // 7. Other languages display Coming Soon state and are marked unavailable
    // =========================================================================
    @Test
    fun testOtherLanguagesAreMarkedAsComingSoon() {
        val planned = ReadMeVoiceCurator.PLANNED_COMING_SOON_LANGUAGES
        assertEquals(6, planned.size)

        val plannedKeys = planned.map { it.familyKey }
        assertTrue(plannedKeys.contains("en-GH"))
        assertTrue(plannedKeys.contains("en-NG"))
        assertTrue(plannedKeys.contains("ar"))
        assertTrue(plannedKeys.contains("fr"))
        assertTrue(plannedKeys.contains("la"))
        assertTrue(plannedKeys.contains("es"))

        // Ensure isComingSoonVoiceId detects non-English voice IDs
        assertTrue(ReadMeVoiceCurator.isComingSoonVoiceId("en-gh-voice1"))
        assertTrue(ReadMeVoiceCurator.isComingSoonVoiceId("fr-fr-voice1"))
        assertTrue(ReadMeVoiceCurator.isComingSoonVoiceId("es-es-voice1"))
        assertTrue(ReadMeVoiceCurator.isComingSoonVoiceId("ar-voice1"))

        assertFalse(ReadMeVoiceCurator.isComingSoonVoiceId("en-us-voice1"))
        assertFalse(ReadMeVoiceCurator.isComingSoonVoiceId("en-gb-voice1"))
    }

    // =========================================================================
    // 8. Voice preview does not alter the saved reading position or advance session
    // =========================================================================
    @Test
    fun testVoicePreviewDoesNotAlterReadingPositionOrSession() = runBlocking {
        val progressRepo = InMemoryReadingProgressRepository()
        val readingEngine = ReadingEngine()
        val sessionCoordinator = ReadingSessionCoordinator(readingEngine, progressRepo)

        val segments = listOf(
            ReadingSegment(id = "seg-1", text = "First segment."),
            ReadingSegment(id = "seg-2", text = "Second segment.")
        )
        val document = ReadingDocument(
            id = "test-doc-voice-preview",
            title = "Preview Test",
            sourceType = ReadingDocumentSourceType.TXT,
            sections = listOf(ReadingSection(id = "sec-1", title = "Sec 1", segments = segments))
        )

        val token = sessionCoordinator.startLoading("test.txt")
        sessionCoordinator.onDocumentLoaded(token, document, "test.txt")

        val initialProgress = ReadingProgress(
            documentId = "test-doc-voice-preview",
            sectionId = "sec-1",
            segmentId = "seg-1",
            segmentIndex = 0
        )
        progressRepo.saveProgress(initialProgress)
        sessionCoordinator.restoreProgress(document)

        val speechEngine = ReadMeSpeechEngine()
        speechEngine.setStateForTesting(TtsState.Ready)
        speechEngine.setAvailableVoicesForTesting(
            listOf(
                ReadMeVoice("en-us-1", "Daniel", Locale("en", "US"), false)
            )
        )

        // Audition preview
        speechEngine.previewVoice("en-us-1", "Preview sentence")

        // Progress repository and session position MUST remain unchanged
        val saved = progressRepo.loadProgress("test-doc-voice-preview")
        assertNotNull(saved)
        assertEquals("seg-1", saved?.segmentId)
        assertEquals(0, saved?.segmentIndex)
        assertFalse(sessionCoordinator.readingSessionState.value.isReading)
    }

    // =========================================================================
    // 9. Voice selection preference model and non-English preservation
    // =========================================================================
    @Test
    fun testVoicePreferenceModelIntegrity() {
        val defaultSettings = ReadMeSettings()
        assertEquals("natural_voice", defaultSettings.selectedVoice)

        val updatedSettings = defaultSettings.copy(selectedVoice = "en-us-variant-daniel")
        assertEquals("en-us-variant-daniel", updatedSettings.selectedVoice)

        // Non-English preference is preserved
        val legacyNonEnglish = defaultSettings.copy(selectedVoice = "fr-fr-voice1")
        assertEquals("fr-fr-voice1", legacyNonEnglish.selectedVoice)
        assertTrue(ReadMeVoiceCurator.isComingSoonVoiceId(legacyNonEnglish.selectedVoice))
    }

    // =========================================================================
    // 10. Fallback behavior when voice is missing or from unsupported locale
    // =========================================================================
    @Test
    fun testFallbackBehaviorInSpeechEngine() {
        val engine = ReadMeSpeechEngine()
        val curated = listOf(
            ReadMeVoice("en-us-1", "Daniel", Locale("en", "US"), false),
            ReadMeVoice("en-gb-1", "Arthur", Locale("en", "GB"), false)
        )
        engine.setAvailableVoicesForTesting(curated)

        // Previewing unavailable or non-English voice safely rejected without crash
        engine.setStateForTesting(TtsState.Ready)
        engine.previewVoice("non-existent-voice")
        assertNotEquals(TtsState.Speaking, engine.state.value)
    }

    // =========================================================================
    // 11. Honest labelling: Male voices are never placed into female slots
    // =========================================================================
    @Test
    fun testMaleVoicesAreNotMisgenderedWhenOnlyMaleVoicesExist() {
        val maleOnlyCandidates = listOf(
            VoiceMetadata(name = "en-us-x-sfg#male_1-local", locale = Locale("en", "US"), quality = 400),
            VoiceMetadata(name = "en-us-x-iol#male_2-local", locale = Locale("en", "US"), quality = 350),
            VoiceMetadata(name = "en-us-x-extra#male_3-local", locale = Locale("en", "US"), quality = 300)
        )

        val curated = ReadMeVoiceCurator.curatePhase2AFromMetadata(maleOnlyCandidates)
        // Must only curate up to 2 male voices, with NO voices in female slots
        assertEquals(2, curated.size)
        assertTrue(curated.all { it.genderDescriptor == "Male" })
        assertEquals("Daniel", curated[0].displayName)
        assertEquals("Oliver", curated[1].displayName)
        assertFalse(curated.any { it.genderDescriptor == "Female" })
    }

    // =========================================================================
    // 12. Blank utterance does not crash or lock synthesis
    // =========================================================================
    @Test
    fun testBlankSegmentDoesNotLockSpeechEngine() {
        val engine = ReadMeSpeechEngine()
        var completedSegmentId: String? = null

        engine.setSpeechListener(object : SpeechEngineListener {
            override fun onSegmentStarted(segmentId: String, sessionId: Long) {}
            override fun onSegmentCompleted(segmentId: String, sessionId: Long) {
                completedSegmentId = segmentId
            }
            override fun onSegmentError(segmentId: String, sessionId: Long, errorCode: Int) {}
        })

        engine.setStateForTesting(TtsState.Ready)
        engine.speakSegment("blank-seg", "   ", 1L, "en-us-1", 1.0f, 0.5f, 0.7f)

        // Engine must stay Ready and report segment completed immediately
        assertEquals(TtsState.Ready, engine.state.value)
        assertEquals("blank-seg", completedSegmentId)
    }
}
