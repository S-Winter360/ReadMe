package com.readme.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.readme.app.R
import com.readme.app.speech.CuratedLocaleFamily
import com.readme.app.speech.ReadMeVoice
import com.readme.app.speech.ReadMeVoiceCurator

/**
 * Phase 2A Curated Voice Selector.
 *
 * Implements:
 * 1. Premium ReadMe voices (Coming Soon) prominently positioned at the top.
 * 2. Curated English (US) and English (UK) active libraries with up to 4 distinct voices
 *    (2 male-sounding, 2 female-sounding) mapped to friendly human names.
 * 3. Planned other languages (Coming Soon) clearly marked and non-selectable.
 * 4. High-accessibility 48dp minimum touch targets and clear metadata badges.
 */
@Composable
fun ReadMeVoiceSelector(
    selectedVoice: String,
    voices: List<ReadMeVoice>,
    onVoiceSelected: (ReadMeVoice) -> Unit,
    modifier: Modifier = Modifier,
    onPreviewVoice: ((ReadMeVoice) -> Unit)? = null
) {
    var showDialog by remember { mutableStateOf(false) }

    val activeVoice = voices.find { it.displayName == selectedVoice || it.id == selectedVoice }
    val displayVoice = if (voices.isEmpty()) {
        "No curated voices available"
    } else {
        activeVoice?.let { "${it.displayName} • ${it.familyDisplayName}" }
            ?: voices.firstOrNull()?.let { "${it.displayName} • ${it.familyDisplayName}" }
            ?: "Select Voice"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_selector_container")
    ) {
        Text(
            text = "Voice",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Closed Selector Box / Trigger
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                .clickable(
                    enabled = voices.isNotEmpty(),
                    role = Role.DropdownList,
                    onClick = { showDialog = true }
                )
                .semantics {
                    contentDescription = "Voice Selector, currently $displayVoice"
                }
                .testTag("voice_selector_trigger"),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayVoice,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (voices.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )

                    if (activeVoice != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            activeVoice.qualityDescriptor?.let { desc ->
                                VoiceMetadataBadge(text = desc)
                            }
                            VoiceMetadataBadge(
                                text = if (activeVoice.isOffline) "Offline" else "Online"
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "▼",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Curated Voice Selection Dialog
    if (showDialog && voices.isNotEmpty()) {
        val usVoices = remember(voices) {
            voices.filter { it.familyKey == CuratedLocaleFamily.ENGLISH_US.familyKey }
        }
        val ukVoices = remember(voices) {
            voices.filter { it.familyKey == CuratedLocaleFamily.ENGLISH_UK.familyKey }
        }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Column {
                    Text(
                        text = "Voice Library",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Curated English library & upcoming voices",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .testTag("voice_dialog_content")
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // =====================================================
                        // SECTION B: Premium ReadMe Voices (Coming Soon)
                        // =====================================================
                        item(key = "header_premium_voices") {
                            Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                                Text(
                                    text = "Premium ReadMe Voices",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "High-expressiveness, human-like neural voices",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        item(key = "premium_male") {
                            LockedVoiceRow(
                                name = "ReadMe Male",
                                subtitle = "Natural, expressive human-like reading",
                                testTag = "premium_voice_male"
                            )
                        }

                        item(key = "premium_female") {
                            LockedVoiceRow(
                                name = "ReadMe Female",
                                subtitle = "Natural, expressive human-like reading",
                                testTag = "premium_voice_female"
                            )
                        }

                        item(key = "premium_explainer") {
                            Text(
                                text = "Coming Soon: Premium expressive human-like voices will be available in a future update.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier = Modifier
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                    .semantics {
                                        contentDescription = "Coming Soon: Premium expressive human-like voices will be available in a future update."
                                    }
                            )
                        }

                        // =====================================================
                        // SECTION C: Active English (US) Section
                        // =====================================================
                        item(key = "header_en_us") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "English (United States)",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )
                        }

                        if (usVoices.isEmpty()) {
                            item(key = "empty_en_us") {
                                Text(
                                    text = "No English (US) voices found on device. Install voices in Android Text-to-Speech settings.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        } else {
                            items(usVoices, key = { it.id }) { voice ->
                                ActiveVoiceRow(
                                    voice = voice,
                                    isSelected = (voice.displayName == selectedVoice || voice.id == selectedVoice),
                                    onSelect = {
                                        onVoiceSelected(voice)
                                        showDialog = false
                                    },
                                    onPreview = onPreviewVoice
                                )
                            }

                            if (usVoices.size < 4) {
                                item(key = "en_us_install_guidance") {
                                    Text(
                                        text = "Additional voices can be installed through Android Text-to-Speech settings.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        // =====================================================
                        // SECTION C: Active English (UK) Section
                        // =====================================================
                        item(key = "header_en_gb") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "English (United Kingdom)",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )
                        }

                        if (ukVoices.isEmpty()) {
                            item(key = "empty_en_gb") {
                                Text(
                                    text = "No English (UK) voices found on device. Install voices in Android Text-to-Speech settings.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        } else {
                            items(ukVoices, key = { it.id }) { voice ->
                                ActiveVoiceRow(
                                    voice = voice,
                                    isSelected = (voice.displayName == selectedVoice || voice.id == selectedVoice),
                                    onSelect = {
                                        onVoiceSelected(voice)
                                        showDialog = false
                                    },
                                    onPreview = onPreviewVoice
                                )
                            }

                            if (ukVoices.size < 4) {
                                item(key = "en_gb_install_guidance") {
                                    Text(
                                        text = "Additional voices can be installed through Android Text-to-Speech settings.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        // =====================================================
                        // SECTION D: Other Languages (Coming Soon)
                        // =====================================================
                        item(key = "header_planned_languages") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                                Text(
                                    text = "Other Languages (Coming Soon)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Planned for upcoming ReadMe releases",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }

                        items(ReadMeVoiceCurator.PLANNED_COMING_SOON_LANGUAGES, key = { it.familyKey }) { lang ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .semantics {
                                        contentDescription = "${lang.familyDisplayName}, Coming Soon, unavailable"
                                    }
                                    .testTag("coming_soon_lang_${lang.familyKey}"),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_lock),
                                        contentDescription = "Locked",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = lang.familyDisplayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.weight(1f)
                                    )
                                    ComingSoonBadge()
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showDialog = false },
                    modifier = Modifier.testTag("voice_dialog_close_button")
                ) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun ActiveVoiceRow(
    voice: ReadMeVoice,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPreview: ((ReadMeVoice) -> Unit)?
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(
                role = Role.RadioButton,
                onClick = onSelect
            )
            .semantics {
                role = Role.RadioButton
                contentDescription = "${voice.displayName}, ${voice.genderDescriptor ?: ""}, ${voice.qualityDescriptor ?: ""}"
            }
            .testTag("voice_item_${voice.id}"),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = voice.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    voice.genderDescriptor?.let { gender ->
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "($gender)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    voice.qualityDescriptor?.let { desc ->
                        VoiceMetadataBadge(text = desc)
                    }
                    VoiceMetadataBadge(
                        text = if (voice.isOffline) "Offline" else "Online"
                    )
                }
            }

            if (onPreview != null) {
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { onPreview(voice) },
                    modifier = Modifier
                        .height(36.dp)
                        .semantics {
                            contentDescription = "Test voice ${voice.displayName}"
                        }
                        .testTag("voice_preview_${voice.id}"),
                    contentPadding = ButtonDefaults.TextButtonContentPadding
                ) {
                    Text(
                        text = "Test",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun LockedVoiceRow(
    name: String,
    subtitle: String,
    testTag: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp)
            )
            .semantics {
                contentDescription = "$name, Coming Soon, unavailable"
            }
            .testTag(testTag),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_lock),
                contentDescription = "Locked voice",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            ComingSoonBadge()
        }
    }
}

@Composable
fun ComingSoonBadge(
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        )
    ) {
        Text(
            text = "Coming Soon",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun VoiceMetadataBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
