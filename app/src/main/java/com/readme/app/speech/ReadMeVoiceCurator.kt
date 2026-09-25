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
}
