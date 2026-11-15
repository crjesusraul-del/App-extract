package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.io.BinaryUtils
import com.example.data.AttachedUpdate
import com.example.data.ExtractionTarget
import com.example.data.IndexedGame
import com.example.ui.components.FormatBadge
import com.example.ui.components.GameIconBox
import com.example.ui.components.PillTag
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NxCyan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailSheet(
    game: IndexedGame,
    onDismiss: () -> Unit,
    onStartExtraction: (ExtractionTarget, AttachedUpdate?, Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val defaultTarget = when {
        game.hasRomFs && game.hasExeFs -> ExtractionTarget.BOTH
        game.hasRomFs -> ExtractionTarget.ROMFS
        else -> ExtractionTarget.EXEFS
    }
    var selectedTarget by remember(game.id) { mutableStateOf(defaultTarget) }
    var selectedUpdate by remember(game.id) { mutableStateOf(game.matchedUpdates.firstOrNull()) }
    var mergeUpdate by remember(game.id) { mutableStateOf(game.matchedUpdates.isNotEmpty()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GameIconBox(
                    iconCachePath = game.iconCachePath,
                    titleName = game.titleName,
                    size = 72.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FormatBadge(format = game.format)
                        PillTag(
                            text = "v${game.displayVersion}",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = game.titleName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${game.publisher} • ${game.titleIdHex}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (game.isUpdateOnlyWithoutBase) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "This is a standalone Update/Patch dump (BKTR). Add the matching base game (${game.titleIdHex}) to merge ExeFS/RomFS, or extract raw .nca files below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Button(
                    onClick = {
                        onStartExtraction(ExtractionTarget.NCA, null, false)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("extract_nca_only_btn"),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Extract Raw NCAs",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            } else {
                // Partition metrics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricChip(
                        label = "ExeFS",
                        value = if (game.hasExeFs) BinaryUtils.formatBytes(game.exeFsSizeBytes) else "N/A",
                        modifier = Modifier.weight(1f)
                    )
                    MetricChip(
                        label = "RomFS",
                        value = if (game.hasRomFs) BinaryUtils.formatBytes(game.romFsSizeBytes) else "N/A",
                        modifier = Modifier.weight(1f)
                    )
                    MetricChip(
                        label = "Container",
                        value = BinaryUtils.formatBytes(game.fileSizeBytes),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Update merging section
                if (game.matchedUpdates.isNotEmpty()) {
                    Surface(
                        color = NxCyan.copy(alpha = 0.08f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, NxCyan.copy(alpha = 0.35f), MaterialTheme.shapes.medium)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Layers,
                                        contentDescription = null,
                                        tint = NxCyan
                                    )
                                    Column {
                                        Text(
                                            text = "Merge Update (BKTR Patch)",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Overlay patched RomFS & updated ExeFS on extraction",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Switch(
                                    checked = mergeUpdate,
                                    onCheckedChange = { mergeUpdate = it },
                                    modifier = Modifier.testTag("detail_merge_update_switch")
                                )
                            }

                            if (mergeUpdate) {
                                game.matchedUpdates.forEach { upd ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable { selectedUpdate = upd }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = selectedUpdate?.uriString == upd.uriString,
                                            onClick = { selectedUpdate = upd }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "v${upd.displayVersion} • ${upd.fileName}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "${upd.format.name} • ${BinaryUtils.formatBytes(upd.fileSizeBytes)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Target selector
                Text(
                    text = "Extraction Target",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExtractionTarget.entries.forEach { target ->
                        val enabled = when (target) {
                            ExtractionTarget.EXEFS -> game.hasExeFs
                            ExtractionTarget.ROMFS -> game.hasRomFs
                            ExtractionTarget.BOTH -> game.hasExeFs || game.hasRomFs
                            ExtractionTarget.NCA -> true
                            ExtractionTarget.ALL -> true
                        }
                        val isSelected = selectedTarget == target
                        Surface(
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = MaterialTheme.shapes.medium
                                )
                                .clip(MaterialTheme.shapes.medium)
                                .clickable(enabled = enabled) { selectedTarget = target }
                                .testTag("target_option_${target.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { if (enabled) selectedTarget = target },
                                    enabled = enabled
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = target.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = target.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                if (game.ncaSummary.isNotEmpty()) {
                    Text(
                        text = "Detected NCAs",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            game.ncaSummary.take(6).forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        onStartExtraction(selectedTarget, selectedUpdate, mergeUpdate && selectedUpdate != null)
                    },
                    enabled = game.hasExeFs || game.hasRomFs || selectedTarget == ExtractionTarget.NCA || selectedTarget == ExtractionTarget.ALL,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("extract_start_btn"),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_extract),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
