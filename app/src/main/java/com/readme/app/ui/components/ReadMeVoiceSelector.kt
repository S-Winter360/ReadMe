package com.readme.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.readme.app.speech.ReadMeVoice

@Composable
fun ReadMeVoiceSelector(
    selectedVoice: String,
    voices: List<ReadMeVoice>,
    onVoiceSelected: (ReadMeVoice) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val displayVoice = if (voices.isEmpty()) {
        "No voices available"
    } else {
        selectedVoice.ifBlank { "Select Voice" }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Voice",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                    .clickable(enabled = voices.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .semantics { contentDescription = "Voice Selector, currently $displayVoice" },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayVoice,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (voices.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "▼",
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (voices.isNotEmpty()) {
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    voices.forEach { voice ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = voice.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                onVoiceSelected(voice)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

