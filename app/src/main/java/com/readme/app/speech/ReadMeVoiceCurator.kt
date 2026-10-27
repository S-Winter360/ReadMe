package com.readme.app.speech

import android.speech.tts.Voice
import java.util.Locale

/**
 * Metadata representation of a voice candidate, decoupling curation logic
 * from framework-specific Voice object instantiation during unit testing.
 */
data class VoiceMetadata(
    val name: String,
    val locale: Locale,
    val quality: Int = 300,
    val latency: Int = 300,
    val isNetworkConnectionRequired: Boolean = false,
    val features: Set<String> = emptySet()
)

/**
 * Curated Locale Families strictly supported by ReadMe (Phase 9AB).
 * Ordered in strict requested priority.
 */
enum class CuratedLocaleFamily(
    val familyKey: String,
    val familyDisplayName: String,
    val priority: Int
) {
    ENGLISH_GHANA("en-GH", "English (Ghana)", 1),
    ENGLISH_US("en-US", "English (United States)", 2),
    ENGLISH_UK("en-GB", "English (United Kingdom)", 3),
    ENGLISH_NIGERIA("en-NG", "English (Nigeria)", 4),
    ARABIC("ar", "Arabic", 5),
    FRENCH("fr", "French", 6),
    LATIN("la", "Latin", 7),
    SPANISH("es", "Spanish", 8);

    companion object {
        /**
         * Matches an Android [Locale] and optional [voiceName] against the curated families.
         *
         * Strict matching rules:
         * - English regional variants must match GH, US, GB, or NG. All other English locales are rejected.
         * - Non-English languages (ar, fr, la, es) match their language family regardless of region.
         * - All other languages are rejected.
         */
        fun match(locale: Locale, voiceName: String? = null): CuratedLocaleFamily? {
            val lang = locale.language.lowercase(Locale.ROOT)
            val country = locale.country.uppercase(Locale.ROOT)
            val tag = locale.toLanguageTag().lowercase(Locale.ROOT)
            val nameLower = voiceName?.lowercase(Locale.ROOT) ?: ""

            if (lang == "en" || tag.startsWith("en-") || tag == "en") {
                return when {
                    country == "GH" || tag == "en-gh" || tag.startsWith("en-gh-") ||
                            nameLower.startsWith("en-gh") || nameLower.startsWith("en_gh") -> ENGLISH_GHANA

                    country == "US" || tag == "en-us" || tag.startsWith("en-us-") ||
                            nameLower.startsWith("en-us") || nameLower.startsWith("en_us") -> ENGLISH_US

                    country == "GB" || tag == "en-gb" || tag.startsWith("en-gb-") ||
                            nameLower.startsWith("en-gb") || nameLower.startsWith("en_gb") -> ENGLISH_UK

                    country == "NG" || tag == "en-ng" || tag.startsWith("en-ng-") ||
                            nameLower.startsWith("en-ng") || nameLower.startsWith("en_ng") -> ENGLISH_NIGERIA

                    else -> null // Strictly reject unrelated English variants (e.g. en-AU, en-IN, en-CA, unassigned en)
                }
            }

            return when {
                lang == "ar" || tag == "ar" || tag.startsWith("ar-") || nameLower.startsWith("ar-") || nameLower.startsWith("ar_") -> ARABIC
                lang == "fr" || tag == "fr" || tag.startsWith("fr-") || nameLower.startsWith("fr-") || nameLower.startsWith("fr_") -> FRENCH
                lang == "la" || tag == "la" || tag.startsWith("la-") || nameLower.startsWith("la-") || nameLower.startsWith("la_") -> LATIN
                lang == "es" || tag == "es" || tag.startsWith("es-") || nameLower.startsWith("es-") || nameLower.startsWith("es_") -> SPANISH
                else -> null // Strictly reject all other languages
            }
        }
    }
}

/**
 * Phase 9AB Curated Voice Library Engine.
 *
 * Responsibilities:
 * 1. Filter raw voices to only the 8 supported curated locale families.
 * 2. Enforce offline-first: prioritize and curate offline voices. Exclude network-dependent voices.
 * 3. Enforce 1–3 voices per locale maximum.
 * 4. Generate clean, stable, friendly labels: e.g. "English (Ghana) • Voice 1".
 * 5. Absolutely NO fabricated human attributes (no Male, Female, Warm, Deep, etc.).
 * 6. Derive quality descriptors strictly from Voice.getQuality() (e.g. "High quality", "Standard quality").
 * 7. Rank deterministically: Locale Priority -> Offline -> Quality (desc) -> Latency (asc) -> Voice Name (asc).
 * 8. Never fabricate availability: omit unavailable locales.
 */
object ReadMeVoiceCurator {

    const val MAX_VOICES_PER_LOCALE = 3

    /**
     * Curates a collection of Android [Voice] objects into ReadMe's curated list.
     */
    fun curateVoices(
        rawVoices: Collection<Voice>?,
        allowNetworkFallback: Boolean = false
    ): List<ReadMeVoice> {
        if (rawVoices.isNullOrEmpty()) return emptyList()

        val metadataList = rawVoices.map { voice ->
            VoiceMetadata(
                name = voice.name,
                locale = voice.locale,
                quality = voice.quality,
                latency = voice.latency,
                isNetworkConnectionRequired = voice.isNetworkConnectionRequired,
                features = voice.features ?: emptySet()
            )
        }

        return curateFromMetadata(metadataList, allowNetworkFallback)
    }

    /**
     * Curates from [VoiceMetadata]. Decoupled from Android Voice runtime for pure unit testing.
     */
    fun curateFromMetadata(
        rawMetadata: Collection<VoiceMetadata>?,
        allowNetworkFallback: Boolean = false
    ): List<ReadMeVoice> {
        if (rawMetadata.isNullOrEmpty()) return emptyList()

        // 1. Deduplicate by unique stable voice name
        val distinctVoices = rawMetadata.distinctBy { it.name }

        // 2. Group candidate voices by CuratedLocaleFamily (excluding unsupported locales)
        val voicesByFamily = mutableMapOf<CuratedLocaleFamily, MutableList<VoiceMetadata>>()

        for (voice in distinctVoices) {
            val family = CuratedLocaleFamily.match(voice.locale, voice.name) ?: continue
            voicesByFamily.getOrPut(family) { mutableListOf() }.add(voice)
        }

        val result = mutableListOf<ReadMeVoice>()

        // 3. Process families in strict priority order
        val orderedFamilies = CuratedLocaleFamily.values().sortedBy { it.priority }

        for (family in orderedFamilies) {
            val familyCandidates = voicesByFamily[family] ?: continue
            if (familyCandidates.isEmpty()) continue

            // 4. Offline-first filtering
            val offlineCandidates = familyCandidates.filter { !it.isNetworkConnectionRequired }
            val pool = if (offlineCandidates.isNotEmpty()) {
                offlineCandidates
            } else if (allowNetworkFallback) {
                // Only if offline is completely absent AND caller explicitly permitted network fallback
                familyCandidates
            } else {
                // Exclude network voices from default curated list
                emptyList()
            }

            if (pool.isEmpty()) continue

            // 5. Rank candidates deterministically:
            //    - Offline availability (offline=0, network=1)
            //    - Quality descending (500 > 400 > 300 > 200 > 100)
            //    - Latency ascending (100 < 200 < 300 < 400 < 500)
            //    - Stable voice name ascending (alphabetical)
            val rankedCandidates = pool.sortedWith(
                compareBy<VoiceMetadata> { if (it.isNetworkConnectionRequired) 1 else 0 }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
                    .thenBy { it.name }
            )

            // 6. Limit to 1–3 voices per locale
            val topVoices = rankedCandidates.take(MAX_VOICES_PER_LOCALE)

            // 7. Assign stable, friendly names without fabricated human characteristics
            topVoices.forEachIndexed { index, candidate ->
                val voiceNumber = index + 1
                val displayName = "${family.familyDisplayName} • Voice $voiceNumber"
                val qualityDesc = qualityDescriptorFromQuality(candidate.quality)

                result.add(
                    ReadMeVoice(
                        id = candidate.name,
                        displayName = displayName,
                        locale = candidate.locale,
                        isNetworkConnectionRequired = candidate.isNetworkConnectionRequired,
                        quality = candidate.quality,
                        latency = candidate.latency,
                        qualityDescriptor = qualityDesc,
                        familyKey = family.familyKey,
                        familyDisplayName = family.familyDisplayName
                    )
                )
            }
        }

        return result
    }

    /**
     * Maps Android Voice quality integer to an objective quality descriptor.
     * Guaranteed to use only real system metadata without inventing voice personality.
     */
    fun qualityDescriptorFromQuality(quality: Int): String {
        return when {
            quality >= 400 -> "High quality"
            quality >= 300 -> "Standard quality"
            else -> "Basic quality"
        }
    }

    // =========================================================================
    // Phase 2A Curated English Voice Library & Coming Soon Features
    // =========================================================================

    val US_MALE_NAMES = listOf("Daniel", "Oliver")
    val US_FEMALE_NAMES = listOf("Emma", "Claire")
    val UK_MALE_NAMES = listOf("Arthur", "George")
    val UK_FEMALE_NAMES = listOf("Charlotte", "Sophie")

    val PLANNED_COMING_SOON_LANGUAGES = listOf(
        PlannedLanguage("en-GH", "English (Ghana)"),
        PlannedLanguage("en-NG", "English (Nigeria)"),
        PlannedLanguage("ar", "Arabic"),
        PlannedLanguage("fr", "French"),
        PlannedLanguage("la", "Latin"),
        PlannedLanguage("es", "Spanish")
    )

    fun detectPerceivedGender(name: String, features: Set<String> = emptySet()): PerceivedVoiceGender {
        val lowerName = name.lowercase(Locale.ROOT)
        val lowerFeatures = features.map { it.lowercase(Locale.ROOT) }

        val cleanNameWithoutFemale = lowerName.replace("female", "").replace("woman", "")
        val cleanFeaturesWithoutFemale = lowerFeatures.map { it.replace("female", "").replace("woman", "") }

        val hasFemaleFeature = lowerFeatures.any { it.contains("female") || it.contains("woman") }
        val hasMaleFeature = cleanFeaturesWithoutFemale.any { it.contains("male") || it.contains("man") }

        if (hasFemaleFeature && !hasMaleFeature) return PerceivedVoiceGender.FEMALE
        if (hasMaleFeature && !hasFemaleFeature) return PerceivedVoiceGender.MALE

        val hasExplicitFemale = lowerName.contains("female") || lowerName.contains("woman")
        val hasExplicitMale = cleanNameWithoutFemale.contains("male") || cleanNameWithoutFemale.contains("man")

        if (hasExplicitFemale && !hasExplicitMale) return PerceivedVoiceGender.FEMALE
        if (hasExplicitMale && !hasExplicitFemale) return PerceivedVoiceGender.MALE

        // Check well-known Google TTS voice codes (Google Speech Services offline voices)
        val isKnownGoogleMale = lowerName.contains("-tpd") || lowerName.contains("-iol") ||
                lowerName.contains("-rjs") || lowerName.contains("-gkb") ||
                lowerName.contains("-wavenet-b") || lowerName.contains("-wavenet-d") ||
                lowerName.contains("-wavenet-i") || lowerName.contains("-wavenet-j")

        val isKnownGoogleFemale = lowerName.contains("-sfg") || lowerName.contains("-iob") ||
                lowerName.contains("-fis") || lowerName.contains("-gba") ||
                lowerName.contains("-wavenet-a") || lowerName.contains("-wavenet-c") ||
                lowerName.contains("-wavenet-e") || lowerName.contains("-wavenet-f")

        if (isKnownGoogleMale && !isKnownGoogleFemale) return PerceivedVoiceGender.MALE
        if (isKnownGoogleFemale && !isKnownGoogleMale) return PerceivedVoiceGender.FEMALE

        val isFemale = lowerName.contains("female") ||
                lowerName.contains("#female") ||
                lowerName.contains("_female") ||
                lowerName.contains("-female") ||
                lowerName.contains("woman") ||
                lowerName.contains("sf-") ||
                lowerName.contains("f0") || lowerName.contains("f1") || lowerName.contains("f2") || lowerName.contains("f3") ||
                lowerName.contains("-f-") || lowerName.contains("_f_")

        val isMale = lowerName.contains("male") ||
                lowerName.contains("#male") ||
                lowerName.contains("_male") ||
                lowerName.contains("-male") ||
                lowerName.contains("man") ||
                lowerName.contains("sm-") ||
                lowerName.contains("m0") || lowerName.contains("m1") || lowerName.contains("m2") || lowerName.contains("m3") ||
                lowerName.contains("-m-") || lowerName.contains("_m_")

        return when {
            isFemale && !isMale -> PerceivedVoiceGender.FEMALE
            isMale && !isFemale -> PerceivedVoiceGender.MALE
            else -> PerceivedVoiceGender.UNSPECIFIED
        }
    }

    /**
     * Phase 2A: Curates voices specifically for the English (US) and English (UK) library,
     * assigning up to 4 real distinct voices per locale with friendly human-style names
     * grouped consistently by perceived gender.
     */
    fun curatePhase2AVoices(
        rawVoices: Collection<Voice>?,
        allowNetworkFallback: Boolean = false
    ): List<ReadMeVoice> {
        if (rawVoices.isNullOrEmpty()) return emptyList()

        val metadataList = rawVoices.map { voice ->
            VoiceMetadata(
                name = voice.name,
                locale = voice.locale,
                quality = voice.quality,
                latency = voice.latency,
                isNetworkConnectionRequired = voice.isNetworkConnectionRequired,
                features = voice.features ?: emptySet()
            )
        }

        return curatePhase2AFromMetadata(metadataList, allowNetworkFallback)
    }

    /**
     * Pure metadata version of Phase 2A curation for unit testing.
     */
    fun curatePhase2AFromMetadata(
        rawMetadata: Collection<VoiceMetadata>?,
        allowNetworkFallback: Boolean = false
    ): List<ReadMeVoice> {
        if (rawMetadata.isNullOrEmpty()) return emptyList()

        val distinctVoices = rawMetadata.distinctBy { it.name }
        val targetFamilies = listOf(CuratedLocaleFamily.ENGLISH_US, CuratedLocaleFamily.ENGLISH_UK)
        val result = mutableListOf<ReadMeVoice>()

        for (family in targetFamilies) {
            val familyCandidates = distinctVoices.filter { voice ->
                CuratedLocaleFamily.match(voice.locale, voice.name) == family
            }
            if (familyCandidates.isEmpty()) continue

            val offlineCandidates = familyCandidates.filter { !it.isNetworkConnectionRequired }
            val pool = if (offlineCandidates.isNotEmpty()) {
                offlineCandidates
            } else if (allowNetworkFallback) {
                familyCandidates
            } else {
                emptyList()
            }
            if (pool.isEmpty()) continue

            val sortedCandidates = pool.sortedWith(
                compareByDescending<VoiceMetadata> { it.quality }
                    .thenBy { it.latency }
                    .thenBy { it.name }
            )

            val maleList = mutableListOf<VoiceMetadata>()
            val femaleList = mutableListOf<VoiceMetadata>()
            val unspecifiedList = mutableListOf<VoiceMetadata>()

            for (candidate in sortedCandidates) {
                when (detectPerceivedGender(candidate.name, candidate.features)) {
                    PerceivedVoiceGender.MALE -> maleList.add(candidate)
                    PerceivedVoiceGender.FEMALE -> femaleList.add(candidate)
                    PerceivedVoiceGender.UNSPECIFIED -> unspecifiedList.add(candidate)
                }
            }

            val selectedMale = mutableListOf<VoiceMetadata>()
            val selectedFemale = mutableListOf<VoiceMetadata>()

            // 1. Fill male pool up to 2
            for (c in maleList) {
                if (selectedMale.size < 2) selectedMale.add(c)
            }
            // 2. Fill female pool up to 2
            for (c in femaleList) {
                if (selectedFemale.size < 2) selectedFemale.add(c)
            }
            // 3. Fill remaining slots from unspecified candidates (male pool up to 2, then female pool up to 2)
            while (selectedMale.size < 2 && unspecifiedList.isNotEmpty()) {
                selectedMale.add(unspecifiedList.removeAt(0))
            }
            while (selectedFemale.size < 2 && unspecifiedList.isNotEmpty()) {
                selectedFemale.add(unspecifiedList.removeAt(0))
            }

            val maleNames = if (family == CuratedLocaleFamily.ENGLISH_US) US_MALE_NAMES else UK_MALE_NAMES
            val femaleNames = if (family == CuratedLocaleFamily.ENGLISH_US) US_FEMALE_NAMES else UK_FEMALE_NAMES

            // Grouping: Male voices grouped together, female voices grouped together
            selectedMale.forEachIndexed { index, candidate ->
                val name = maleNames.getOrElse(index) { "Male Voice ${index + 1}" }
                result.add(
                    ReadMeVoice(
                        id = candidate.name,
                        displayName = name,
                        locale = candidate.locale,
                        isNetworkConnectionRequired = candidate.isNetworkConnectionRequired,
                        quality = candidate.quality,
                        latency = candidate.latency,
                        qualityDescriptor = qualityDescriptorFromQuality(candidate.quality),
                        familyKey = family.familyKey,
                        familyDisplayName = family.familyDisplayName,
                        genderDescriptor = "Male"
                    )
                )
            }

            selectedFemale.forEachIndexed { index, candidate ->
                val name = femaleNames.getOrElse(index) { "Female Voice ${index + 1}" }
                result.add(
                    ReadMeVoice(
                        id = candidate.name,
                        displayName = name,
                        locale = candidate.locale,
                        isNetworkConnectionRequired = candidate.isNetworkConnectionRequired,
                        quality = candidate.quality,
                        latency = candidate.latency,
                        qualityDescriptor = qualityDescriptorFromQuality(candidate.quality),
                        familyKey = family.familyKey,
                        familyDisplayName = family.familyDisplayName,
                        genderDescriptor = "Female"
                    )
                )
            }
        }

        return result
    }

    fun isComingSoonLocale(locale: Locale): Boolean {
        val family = CuratedLocaleFamily.match(locale) ?: return false
        return family != CuratedLocaleFamily.ENGLISH_US && family != CuratedLocaleFamily.ENGLISH_UK
    }

    fun isComingSoonVoiceId(voiceId: String): Boolean {
        val lower = voiceId.lowercase(Locale.ROOT)
        return lower.startsWith("readme_") || lower.startsWith("readme ") ||
                lower.startsWith("en-gh") || lower.startsWith("en_gh") ||
                lower.startsWith("en-ng") || lower.startsWith("en_ng") ||
                lower.startsWith("ar-") || lower.startsWith("ar_") || lower.startsWith("ar") ||
                lower.startsWith("fr-") || lower.startsWith("fr_") || lower.startsWith("fr") ||
                lower.startsWith("la-") || lower.startsWith("la_") || lower.startsWith("la") ||
                lower.startsWith("es-") || lower.startsWith("es_") || lower.startsWith("es")
    }
}

enum class PerceivedVoiceGender {
    MALE,
    FEMALE,
    UNSPECIFIED
}

data class PlannedLanguage(
    val familyKey: String,
    val familyDisplayName: String
)

