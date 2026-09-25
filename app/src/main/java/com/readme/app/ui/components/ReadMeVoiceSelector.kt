package com.readme.app.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.readme.app.speech.ReadMeVoice

/**
 * Phase 9AB Curated Voice Selector.
 *
 * Exposes only curated, offline-first voices with clean, stable, non-anthropomorphic labels
 * (e.g. "English (United States) • Voice 1"), real metadata badges ("High quality", "Offline"),
 * and full accessibility support with 48dp minimum touch targets.
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
        activeVoice?.displayName ?: selectedVoice.ifBlank { "Select Voice" }
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
        val groupedVoices = remember(voices) {
            voices.groupBy { it.familyDisplayName.ifBlank { "Other" } }
        }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Column {
                    Text(
                        text = "Select Voice",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Curated offline-first voice library",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .testTag("voice_dialog_content")
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        groupedVoices.forEach { (familyTitle, familyVoices) ->
                            item(key = "header_$familyTitle") {
                                Text(
                                    text = familyTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                                )
                            }

                            items(familyVoices, key = { it.id }) { voice ->
                                val isSelected = (voice.displayName == selectedVoice || voice.id == selectedVoice)

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
                                        .clickable {
                                            onVoiceSelected(voice)
                                            showDialog = false
                                        }
                                        .semantics {
                                            role = Role.RadioButton
                                            contentDescription = "${voice.displayName}, ${voice.qualityDescriptor ?: ""}"
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
                                            onClick = {
                                                onVoiceSelected(voice)
                                                showDialog = false
                                            },
                                            modifier = Modifier.size(24.dp)
                                        )

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = voice.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )

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

                                        if (onPreviewVoice != null) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            OutlinedButton(
                                                onClick = { onPreviewVoice(voice) },
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
