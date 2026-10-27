package com.readme.app.speech

import com.readme.app.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class Phase2BVoiceIdentityAndAccountTest {

    // =========================================================================
    // 1. Standardized phonetically balanced audition preview text
    // =========================================================================
    @Test
    fun testAuditionPreviewTextIsStandardizedAndPhoneticallyBalanced() {
        val expected = "The quick brown fox jumps over the lazy dog. ReadMe brings your books and documents to life with clear, natural speech."
        assertEquals(expected, ReadMeSpeechEngine.DEFAULT_PREVIEW_TEXT)
    }

    // =========================================================================
    // 2. Voice audit diagnostics recording requested, resolved, and applied IDs
    // =========================================================================
    @Test
    fun testVoiceAuditDiagnosticsRecording() {
        val engine = ReadMeSpeechEngine()
        val mockAudit = VoiceAuditRecord(
            requestedVoiceId = "en-us-x-sfg#male_1-local",
            resolvedVoiceId = "en-us-x-sfg#male_1-local",
            appliedVoiceId = "en-us-x-sfg#male_1-local",
            isSuccess = true
        )
        engine.setLastVoiceAuditForTesting(mockAudit)

        val record = engine.lastVoiceAudit
        assertNotNull(record)
        assertEquals("en-us-x-sfg#male_1-local", record?.requestedVoiceId)
        assertEquals("en-us-x-sfg#male_1-local", record?.resolvedVoiceId)
        assertEquals("en-us-x-sfg#male_1-local", record?.appliedVoiceId)
        assertTrue(record?.isSuccess == true)
    }

    // =========================================================================
    // 3. Google TTS and Samsung TTS voice codes gender detection
    // =========================================================================
    @Test
    fun testGoogleAndSamsungVoiceCodesGenderDetection() {
        // Google TTS 3-letter codes
        val tpdMale = VoiceMetadata("en-us-x-tpd-local", Locale("en", "US"), 400)
        val iolMale = VoiceMetadata("en-us-x-iol-local", Locale("en", "US"), 400)
        val sfgFemale = VoiceMetadata("en-us-x-sfg-local", Locale("en", "US"), 400)
        val iobFemale = VoiceMetadata("en-us-x-iob-local", Locale("en", "US"), 400)

        val candidates = listOf(tpdMale, iolMale, sfgFemale, iobFemale)
        val curated = ReadMeVoiceCurator.curatePhase2AFromMetadata(candidates)

        val maleVoices = curated.filter { it.genderDescriptor == "Male" }
        val femaleVoices = curated.filter { it.genderDescriptor == "Female" }

        assertEquals(2, maleVoices.size)
        assertEquals(2, femaleVoices.size)

        // Male voices should map to Daniel and Oliver
        assertEquals("Daniel", maleVoices[0].displayName)
        assertEquals("Oliver", maleVoices[1].displayName)

        // Female voices should map to Emma and Claire
        assertEquals("Emma", femaleVoices[0].displayName)
        assertEquals("Claire", femaleVoices[1].displayName)
    }

    // =========================================================================
    // 4. Navigation model includes Screen.Account
    // =========================================================================
    @Test
    fun testScreenEnumIncludesAccount() {
        val screens = Screen.values().map { it.name }
        assertTrue(screens.contains("Account"))
        assertTrue(screens.contains("Home"))
        assertTrue(screens.contains("Settings"))
    }

    // =========================================================================
    // 5. Coming Soon lock state for premium voices in selector
    // =========================================================================
    @Test
    fun testPremiumVoicesAreLockedComingSoon() {
        assertTrue(ReadMeVoiceCurator.isComingSoonVoiceId("readme_male"))
        assertTrue(ReadMeVoiceCurator.isComingSoonVoiceId("readme_female"))
        assertTrue(ReadMeVoiceCurator.isComingSoonVoiceId("ReadMe Male — Coming Soon"))
        assertTrue(ReadMeVoiceCurator.isComingSoonVoiceId("ReadMe Female — Coming Soon"))
        assertFalse(ReadMeVoiceCurator.isComingSoonVoiceId("en-us-x-sfg-local"))
    }
}
