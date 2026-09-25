package com.readme.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class Phase9ABCuratedVoiceLibraryTest {

    @Test
    fun testExactLocaleMatchingAndUnrelatedLocaleExclusion() {
        // Supported locales
        assertEquals(CuratedLocaleFamily.ENGLISH_GHANA, CuratedLocaleFamily.match(Locale("en", "GH")))
        assertEquals(CuratedLocaleFamily.ENGLISH_US, CuratedLocaleFamily.match(Locale("en", "US")))
        assertEquals(CuratedLocaleFamily.ENGLISH_UK, CuratedLocaleFamily.match(Locale("en", "GB")))
        assertEquals(CuratedLocaleFamily.ENGLISH_NIGERIA, CuratedLocaleFamily.match(Locale("en", "NG")))
        assertEquals(CuratedLocaleFamily.ARABIC, CuratedLocaleFamily.match(Locale("ar", "SA")))
        assertEquals(CuratedLocaleFamily.ARABIC, CuratedLocaleFamily.match(Locale("ar")))
        assertEquals(CuratedLocaleFamily.FRENCH, CuratedLocaleFamily.match(Locale("fr", "FR")))
        assertEquals(CuratedLocaleFamily.FRENCH, CuratedLocaleFamily.match(Locale("fr", "CA")))
        assertEquals(CuratedLocaleFamily.LATIN, CuratedLocaleFamily.match(Locale("la")))
        assertEquals(CuratedLocaleFamily.SPANISH, CuratedLocaleFamily.match(Locale("es", "ES")))
        assertEquals(CuratedLocaleFamily.SPANISH, CuratedLocaleFamily.match(Locale("es", "MX")))

        // Language tag matching
        assertEquals(CuratedLocaleFamily.ENGLISH_GHANA, CuratedLocaleFamily.match(Locale.forLanguageTag("en-GH")))
        assertEquals(CuratedLocaleFamily.ENGLISH_US, CuratedLocaleFamily.match(Locale.forLanguageTag("en-US")))
        assertEquals(CuratedLocaleFamily.ENGLISH_UK, CuratedLocaleFamily.match(Locale.forLanguageTag("en-GB")))
        assertEquals(CuratedLocaleFamily.ENGLISH_NIGERIA, CuratedLocaleFamily.match(Locale.forLanguageTag("en-NG")))

        // Unrelated languages strictly excluded
        assertNull(CuratedLocaleFamily.match(Locale("de", "DE")))
        assertNull(CuratedLocaleFamily.match(Locale("ja", "JP")))
        assertNull(CuratedLocaleFamily.match(Locale("zh", "CN")))
        assertNull(CuratedLocaleFamily.match(Locale("it", "IT")))
        assertNull(CuratedLocaleFamily.match(Locale("ru", "RU")))
        assertNull(CuratedLocaleFamily.match(Locale("hi", "IN")))

        // Unrelated English variants strictly excluded
        assertNull(CuratedLocaleFamily.match(Locale("en", "IN")))
        assertNull(CuratedLocaleFamily.match(Locale("en", "AU")))
        assertNull(CuratedLocaleFamily.match(Locale("en", "CA")))
        assertNull(CuratedLocaleFamily.match(Locale("en", "NZ")))
        assertNull(CuratedLocaleFamily.match(Locale("en", "ZA")))
        assertNull(CuratedLocaleFamily.match(Locale("en"))) // Generic English without country
    }

    @Test
    fun testUnavailableLocalesAreOmittedNotFabricatedOrSubstituted() {
        val rawVoices = listOf(
            VoiceMetadata(name = "en-us-voice1", locale = Locale("en", "US"), quality = 400),
            VoiceMetadata(name = "fr-fr-voice1", locale = Locale("fr", "FR"), quality = 300)
        )

        val curated = ReadMeVoiceCurator.curateFromMetadata(rawVoices)

        // Only en-US and fr should be present
        assertEquals(2, curated.size)
        assertEquals("English (United States) • Voice 1", curated[0].displayName)
        assertEquals("French • Voice 1", curated[1].displayName)

        // English (Ghana), UK, Nigeria, Arabic, Latin, Spanish must NOT be present
        assertFalse(curated.any { it.familyKey == "en-GH" })
        assertFalse(curated.any { it.familyKey == "en-GB" })
        assertFalse(curated.any { it.familyKey == "en-NG" })
        assertFalse(curated.any { it.familyKey == "ar" })
        assertFalse(curated.any { it.familyKey == "la" })
        assertFalse(curated.any { it.familyKey == "es" })

        // Neither was en-US substituted as Ghana
        assertFalse(curated.any { it.displayName.contains("Ghana") })
    }

    @Test
    fun testOfflineFirstExcludesNetworkVoices() {
        val rawVoices = listOf(
            VoiceMetadata(
                name = "en-us-offline-1",
                locale = Locale("en", "US"),
                quality = 400,
                latency = 200,
                isNetworkConnectionRequired = false
            ),
            VoiceMetadata(
                name = "en-us-online-super",
                locale = Locale("en", "US"),
                quality = 500, // Higher quality but requires network
                latency = 100,
                isNetworkConnectionRequired = true
            ),
            VoiceMetadata(
                name = "es-online-only",
                locale = Locale("es", "ES"),
                quality = 400,
                isNetworkConnectionRequired = true
            )
        )

        val curated = ReadMeVoiceCurator.curateFromMetadata(rawVoices, allowNetworkFallback = false)

        // en-us-online-super must be excluded because offline voice exists
        assertEquals(1, curated.size)
        assertEquals("en-us-offline-1", curated[0].id)
        assertTrue(curated[0].isOffline)
        assertFalse(curated[0].isNetworkConnectionRequired)

        // es-online-only must be omitted by default because offline voices are required
        assertFalse(curated.any { it.familyKey == "es" })
    }

    @Test
    fun testLimitToOneToThreeVoicesPerLocale() {
        // Provide 6 voices for US English
        val rawVoices = (1..6).map { i ->
            VoiceMetadata(
                name = "en-us-variant-$i",
                locale = Locale("en", "US"),
                quality = 300 + (i * 10),
                latency = 200,
                isNetworkConnectionRequired = false
            )
        }

        val curated = ReadMeVoiceCurator.curateFromMetadata(rawVoices)

        // Must cap at MAX_VOICES_PER_LOCALE (3)
        assertEquals(3, curated.size)
        assertEquals("English (United States) • Voice 1", curated[0].displayName)
        assertEquals("English (United States) • Voice 2", curated[1].displayName)
        assertEquals("English (United States) • Voice 3", curated[2].displayName)

        // Voice 1 must be the highest quality variant (variant 6: quality 360)
        assertEquals("en-us-variant-6", curated[0].id)
        assertEquals("en-us-variant-5", curated[1].id)
        assertEquals("en-us-variant-4", curated[2].id)
    }

    @Test
    fun testDeduplicationByVoiceName() {
        val rawVoices = listOf(
            VoiceMetadata(name = "en-us-v1", locale = Locale("en", "US"), quality = 400),
            VoiceMetadata(name = "en-us-v1", locale = Locale("en", "US"), quality = 400), // Duplicate
            VoiceMetadata(name = "en-us-v2", locale = Locale("en", "US"), quality = 300)
        )

        val curated = ReadMeVoiceCurator.curateFromMetadata(rawVoices)
        assertEquals(2, curated.size)
        assertEquals("en-us-v1", curated[0].id)
        assertEquals("en-us-v2", curated[1].id)
    }

    @Test
    fun testUserFacingNamesAndAbsenceOfHumanAttributes() {
        val rawVoices = listOf(
            VoiceMetadata(name = "en-gh-1", locale = Locale("en", "GH"), quality = 400),
            VoiceMetadata(name = "ar-1", locale = Locale("ar"), quality = 300),
            VoiceMetadata(name = "la-1", locale = Locale("la"), quality = 300),
            VoiceMetadata(name = "es-1", locale = Locale("es", "ES"), quality = 400)
        )

        val curated = ReadMeVoiceCurator.curateFromMetadata(rawVoices)

        assertEquals("English (Ghana) • Voice 1", curated[0].displayName)
        assertEquals("Arabic • Voice 1", curated[1].displayName)
        assertEquals("Latin • Voice 1", curated[2].displayName)
        assertEquals("Spanish • Voice 1", curated[3].displayName)

        // Strict verification: No human or gender attributes claimed
        val forbiddenAttributes = listOf(
            "Male", "Female", "Deep", "Soft", "Warm",
            "Professional", "Natural", "Expressive", "Man", "Woman"
        )

        for (voice in curated) {
            for (attr in forbiddenAttributes) {
                assertFalse(
                    "Voice label '${voice.displayName}' must not claim human attribute '$attr'",
                    voice.displayName.contains(attr, ignoreCase = true)
                )
                assertFalse(
                    "Voice descriptor '${voice.qualityDescriptor}' must not claim human attribute '$attr'",
                    voice.qualityDescriptor?.contains(attr, ignoreCase = true) == true
                )
            }
        }
    }

    @Test
    fun testObjectiveQualityDescriptors() {
        assertEquals("High quality", ReadMeVoiceCurator.qualityDescriptorFromQuality(500))
        assertEquals("High quality", ReadMeVoiceCurator.qualityDescriptorFromQuality(400))
        assertEquals("Standard quality", ReadMeVoiceCurator.qualityDescriptorFromQuality(300))
        assertEquals("Basic quality", ReadMeVoiceCurator.qualityDescriptorFromQuality(200))
        assertEquals("Basic quality", ReadMeVoiceCurator.qualityDescriptorFromQuality(100))
    }

    @Test
    fun testDeterministicPrioritySorting() {
        // Provide 1 voice from each family in random order
        val rawVoices = listOf(
            VoiceMetadata(name = "es-v", locale = Locale("es"), quality = 400),
            VoiceMetadata(name = "fr-v", locale = Locale("fr"), quality = 400),
            VoiceMetadata(name = "en-us-v", locale = Locale("en", "US"), quality = 400),
            VoiceMetadata(name = "ar-v", locale = Locale("ar"), quality = 400),
            VoiceMetadata(name = "en-gh-v", locale = Locale("en", "GH"), quality = 400),
            VoiceMetadata(name = "la-v", locale = Locale("la"), quality = 400),
            VoiceMetadata(name = "en-ng-v", locale = Locale("en", "NG"), quality = 400),
            VoiceMetadata(name = "en-gb-v", locale = Locale("en", "GB"), quality = 400)
        )

        val curated = ReadMeVoiceCurator.curateFromMetadata(rawVoices)

        // Exact strict requested priority order:
        // 1. en-GH
        // 2. en-US
        // 3. en-GB
        // 4. en-NG
        // 5. ar
        // 6. fr
        // 7. la
        // 8. es
        assertEquals(8, curated.size)
        assertEquals("English (Ghana) • Voice 1", curated[0].displayName)
        assertEquals("English (United States) • Voice 1", curated[1].displayName)
        assertEquals("English (United Kingdom) • Voice 1", curated[2].displayName)
        assertEquals("English (Nigeria) • Voice 1", curated[3].displayName)
        assertEquals("Arabic • Voice 1", curated[4].displayName)
        assertEquals("French • Voice 1", curated[5].displayName)
        assertEquals("Latin • Voice 1", curated[6].displayName)
        assertEquals("Spanish • Voice 1", curated[7].displayName)
    }

    @Test
    fun testLatencyFilterRanksLowerLatencyHigherWhenQualityEqual() {
        val rawVoices = listOf(
            VoiceMetadata(name = "en-us-high-latency", locale = Locale("en", "US"), quality = 400, latency = 400),
            VoiceMetadata(name = "en-us-low-latency", locale = Locale("en", "US"), quality = 400, latency = 150)
        )

        val curated = ReadMeVoiceCurator.curateFromMetadata(rawVoices)

        assertEquals(2, curated.size)
        assertEquals("en-us-low-latency", curated[0].id)
        assertEquals("English (United States) • Voice 1", curated[0].displayName)
        assertEquals("en-us-high-latency", curated[1].id)
        assertEquals("English (United States) • Voice 2", curated[1].displayName)
    }

    @Test
    fun testReadMeSpeechEngineCuratedVoicesAndFallback() {
        val engine = ReadMeSpeechEngine()
        assertEquals(0, engine.availableVoices.value.size)

        val sampleCurated = listOf(
            ReadMeVoice(
                id = "en-gh-1",
                displayName = "English (Ghana) • Voice 1",
                locale = Locale("en", "GH"),
                isNetworkConnectionRequired = false,
                quality = 400,
                qualityDescriptor = "High quality"
            ),
            ReadMeVoice(
                id = "en-us-1",
                displayName = "English (United States) • Voice 1",
                locale = Locale("en", "US"),
                isNetworkConnectionRequired = false,
                quality = 400,
                qualityDescriptor = "High quality"
            )
        )

        engine.setAvailableVoicesForTesting(sampleCurated)
        assertEquals(2, engine.availableVoices.value.size)
        assertEquals("en-gh-1", engine.availableVoices.value[0].id)
        assertEquals("en-us-1", engine.availableVoices.value[1].id)
    }
}
