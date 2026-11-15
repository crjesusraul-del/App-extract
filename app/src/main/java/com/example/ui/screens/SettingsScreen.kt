package com.example.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.crypto.KeyValidationSummary
import com.example.data.SetupConfig
import com.example.data.ThemeMode
import com.example.ui.components.GroupedSection
import com.example.ui.components.SectionLabel
import com.example.ui.components.semanticSuccessColor
import com.example.ui.components.semanticWarningColor
import com.example.ui.theme.JetBrainsMonoFontFamily

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
    val warnColor = semanticWarningColor()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 700.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "nxdump v1.2 • Local Switch Dump Utility",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Cryptographic Keys
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(text = "Cryptographic Keys")
                    GroupedSection {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            DiagnosticRow(
                                icon = Icons.Rounded.VpnKey,
                                label = "prod.keys",
                                value = config.prodKeysName ?: "Not set",
                                ok = keySummary.isUsableForExtraction
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DiagnosticRow(
                                icon = Icons.Rounded.Key,
                                label = "header_key (AES-XTS)",
                                value = if (keySummary.hasHeaderKey) "Valid (32 bytes)" else "Missing",
                                ok = keySummary.hasHeaderKey
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DiagnosticRow(
                                icon = Icons.Rounded.Key,
                                label = "Key Area / Master",
                                value = "${keySummary.keyAreaAppCount} app • ${keySummary.masterKeyCount} master",
                                ok = keySummary.keyAreaAppCount > 0
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DiagnosticRow(
                                icon = Icons.Rounded.Key,
                                label = "title.keys",
                                value = if (config.titleKeysName != null) "${config.titleKeysName} (${keySummary.titleKeysCount})" else "Optional (${keySummary.titleKeysCount} loaded)",
                                ok = config.titleKeysUri != null
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = onPickProdKeys,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_prod_keys_btn")
                                ) {
                                    Text("Replace prod.keys", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = onPickTitleKeys,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_title_keys_btn")
                                ) {
                                    Text("Select title.keys", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            // SAF Storage Folders
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(text = "Storage & Folders")
                    GroupedSection {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            DiagnosticRow(
                                icon = Icons.Rounded.FolderOpen,
                                label = "Game Library",
                                value = config.gameDirName ?: "Not selected",
                                ok = config.gameDirUri != null
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DiagnosticRow(
                                icon = Icons.Rounded.CreateNewFolder,
                                label = "Output Folder",
                                value = config.outputDirName ?: "Not selected",
                                ok = config.outputDirUri != null && config.outputDirRestrictedWarning == null
                            )

                            if (config.outputDirRestrictedWarning != null) {
                                Text(
                                    text = config.outputDirRestrictedWarning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = warnColor
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = onPickGameDir,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_game_dir_btn")
                                ) {
                                    Text("Game Folder", maxLines = 1)
                                }
                                FilledTonalButton(
                                    onClick = onPickOutputDir,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_output_dir_btn")
                                ) {
                                    Text("Output Folder", maxLines = 1)
                                }
                            }

                            OutlinedButton(
                                onClick = onOpenSetupWizard,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_open_wizard_btn")
                            ) {
                                Text("Run Setup Wizard")
                            }
                        }
                    }
                }
            }

            // Appearance & Cache
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(text = "Appearance & Cache")
                    GroupedSection {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
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
                                        text = "Dynamic Color",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Match Android 12+ system wallpaper palette",
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

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            OutlinedButton(
                                onClick = onClearCache,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_clear_cache_btn")
                            ) {
                                Text("Clear Library Index Cache")
                            }
                        }
                    }
                }
            }

            // Compatibility
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(text = "Engine & Compatibility")
                    GroupedSection {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "• Uncompressed .xci (HFS0), .nsp (PFS0), .nca (NCA2/NCA3), ExeFS, RomFS, raw NCA dumps, and BKTR update merges.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "• NSP common tickets are parsed automatically; personalized console tickets are unsupported.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "• NSZ/XCZ compressed dumps, NCA0, sparse NCAs, and DLC packs are skipped.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "• On Android 11+, select a dedicated output subfolder rather than root or top-level Download.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    icon: ImageVector,
    label: String,
    value: String,
    ok: Boolean
) {
    val successColor = semanticSuccessColor()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
            color = if (ok) successColor else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
