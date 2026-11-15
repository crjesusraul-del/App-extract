package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.crypto.KeyValidationSummary
import com.example.data.SetupConfig
import com.example.data.ThemeMode
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NxAmber
import com.example.ui.theme.NxCyan
import com.example.ui.theme.NxEmerald

@Composable
fun SettingsScreen(
    config: SetupConfig,
    keySummary: KeyValidationSummary,
    onPickProdKeys: () -> Unit,
    onPickTitleKeys: () -> Unit,
    onPickGameDir: () -> Unit,
    onPickOutputDir: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onOpenSetupWizard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "Settings & Keys",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "nxdump v1.2 • ExeFS, RomFS & Raw NCA Extractor",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Key diagnostics card
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Cryptographic Keys",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        DiagnosticRow(
                            label = "prod.keys",
                            value = config.prodKeysName ?: "Not set",
                            ok = keySummary.isUsableForExtraction
                        )
                        DiagnosticRow(
                            label = "header_key (AES-XTS)",
                            value = if (keySummary.hasHeaderKey) "Valid (32 bytes)" else "Missing",
                            ok = keySummary.hasHeaderKey
                        )
                        DiagnosticRow(
                            label = "Key Area App / Master",
                            value = "${keySummary.keyAreaAppCount} key area • ${keySummary.masterKeyCount} master",
                            ok = keySummary.keyAreaAppCount > 0
                        )
                        DiagnosticRow(
                            label = "title.keys",
                            value = if (config.titleKeysName != null) "${config.titleKeysName} (${keySummary.titleKeysCount})" else "Optional (${keySummary.titleKeysCount} loaded)",
                            ok = config.titleKeysUri != null
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onPickProdKeys,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_prod_keys_btn")
                            ) {
                                Text("Replace prod.keys")
                            }
                            OutlinedButton(
                                onClick = onPickTitleKeys,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_title_keys_btn")
                            ) {
                                Text("Select title.keys")
                            }
                        }
                    }
                }
            }

            // Storage paths card
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "SAF Folders",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        DiagnosticRow(
                            label = "Game Library",
                            value = config.gameDirName ?: "Not selected",
                            ok = config.gameDirUri != null
                        )
                        DiagnosticRow(
                            label = "Output Folder",
                            value = config.outputDirName ?: "Not selected",
                            ok = config.outputDirUri != null && config.outputDirRestrictedWarning == null
                        )
                        if (config.outputDirRestrictedWarning != null) {
                            Text(
                                text = config.outputDirRestrictedWarning,
                                style = MaterialTheme.typography.bodySmall,
                                color = NxAmber
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onPickGameDir,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_game_dir_btn")
                            ) {
                                Text("Game Folder")
                            }
                            FilledTonalButton(
                                onClick = onPickOutputDir,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_output_dir_btn")
                            ) {
                                Text("Output Folder")
                            }
                        }
                        OutlinedButton(
                            onClick = onOpenSetupWizard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_open_wizard_btn")
                        ) {
                            Text("Run Setup Wizard")
                        }
                    }
                }
            }

            // Appearance card
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Appearance",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            ThemeMode.entries.forEachIndexed { idx, mode ->
                                SegmentedButton(
                                    selected = config.themeMode == mode,
                                    onClick = { onThemeModeChange(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(idx, ThemeMode.entries.size),
                                    modifier = Modifier.testTag("theme_mode_${mode.name.lowercase()}")
                                ) {
                                    Text(mode.label)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Material You Dynamic Color",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Use system wallpaper palette on Android 12+",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = config.dynamicColor,
                                onCheckedChange = onDynamicColorChange,
                                modifier = Modifier.testTag("settings_dynamic_color_switch")
                            )
                        }

                        HorizontalDivider()

                        OutlinedButton(
                            onClick = onClearCache,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_clear_cache_btn")
                        ) {
                            Text("Clear Library Index Cache")
                        }
                    }
                }
            }

            // Format capabilities & limitations
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Supported Formats & Limitations",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "• Supported: Uncompressed .xci (HFS0), .nsp (PFS0), .nca (NCA2/NCA3), ExeFS & RomFS extraction, and BKTR update patching.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• Tickets: Common titlekey tickets inside NSPs are extracted automatically. Personalized tickets are not supported.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• Unsupported: Compressed NSZ/XCZ, NCA0, sparse/compressed NCAs, and DLC extraction.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• Storage: On Android 11+, choose a dedicated output subfolder rather than root storage or the top-level Download directory.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    ok: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
            color = if (ok) NxEmerald else NxCyan
        )
    }
}
