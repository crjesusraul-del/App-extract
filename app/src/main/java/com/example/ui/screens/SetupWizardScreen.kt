package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.crypto.KeyValidationSummary
import com.example.data.SetupConfig
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NxAmber
import com.example.ui.theme.NxCrimson
import com.example.ui.theme.NxCyan
import com.example.ui.theme.NxEmerald

@Composable
fun SetupWizardScreen(
    config: SetupConfig,
    keySummary: KeyValidationSummary,
    onPickProdKeys: () -> Unit,
    onPickTitleKeys: () -> Unit,
    onClearTitleKeys: () -> Unit,
    onPickGameDir: () -> Unit,
    onPickOutputDir: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                // Hero banner card with generated artwork
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(176.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_setup_hero_1794703306183),
                            contentDescription = "nxdump Switch cartridge extraction artwork",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color(0xD90E1116)
                                        )
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(18.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = NxCrimson
                                ) {
                                    Text(
                                        text = "NXDUMP",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "XCI • NSP • NCA • BKTR",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NxCyan
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.setup_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White
                            )
                            Text(
                                text = stringResource(R.string.setup_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1),
                                maxLines = 2
                            )
                        }
                    }
                }
            }

            item {
                SetupStepCard(
                    stepNumber = 1,
                    title = stringResource(R.string.step_prod_keys),
                    subtitle = if (config.prodKeysName != null) {
                        "${config.prodKeysName} • ${keySummary.totalKeysCount} keys (${keySummary.masterKeyCount} master)"
                    } else {
                        "Required for AES-XTS header & key area decryption"
                    },
                    icon = Icons.Rounded.VpnKey,
                    isCompleted = keySummary.isUsableForExtraction,
                    accentColor = NxCrimson,
                    buttonLabel = if (config.prodKeysUri == null) stringResource(R.string.action_select_file) else stringResource(R.string.action_replace_file),
                    testTag = "setup_prod_keys_btn",
                    onClick = onPickProdKeys,
                    warningText = if (config.prodKeysUri != null && keySummary.warnings.isNotEmpty()) {
                        keySummary.warnings.first()
                    } else null
                )
            }

            item {
                SetupStepCard(
                    stepNumber = 2,
                    title = stringResource(R.string.step_title_keys),
                    subtitle = if (config.titleKeysName != null) {
                        "${config.titleKeysName} • ${keySummary.titleKeysCount} titlekeys loaded"
                    } else {
                        "Optional — NSP common tickets are also read automatically"
                    },
                    icon = Icons.Rounded.Key,
                    isCompleted = config.titleKeysUri != null,
                    accentColor = NxCyan,
                    buttonLabel = if (config.titleKeysUri == null) stringResource(R.string.action_select_file) else stringResource(R.string.action_replace_file),
                    secondaryActionLabel = if (config.titleKeysUri != null) "Clear" else null,
                    onSecondaryClick = onClearTitleKeys,
                    testTag = "setup_title_keys_btn",
                    onClick = onPickTitleKeys
                )
            }

            item {
                SetupStepCard(
                    stepNumber = 3,
                    title = stringResource(R.string.step_game_dir),
                    subtitle = config.gameDirName ?: "Folder containing .xci, .nsp, or .nca dumps & updates",
                    icon = Icons.Rounded.FolderOpen,
                    isCompleted = !config.gameDirUri.isNullOrBlank(),
                    accentColor = NxCrimson,
                    buttonLabel = if (config.gameDirUri == null) stringResource(R.string.action_select_folder) else stringResource(R.string.action_change_folder),
                    testTag = "setup_game_dir_btn",
                    onClick = onPickGameDir
                )
            }

            item {
                SetupStepCard(
                    stepNumber = 4,
                    title = stringResource(R.string.step_output_dir),
                    subtitle = config.outputDirName ?: "Dedicated subfolder for extracted ExeFS & RomFS files",
                    icon = Icons.Rounded.CreateNewFolder,
                    isCompleted = !config.outputDirUri.isNullOrBlank() && config.outputDirRestrictedWarning == null,
                    accentColor = NxCyan,
                    buttonLabel = if (config.outputDirUri == null) stringResource(R.string.action_select_folder) else stringResource(R.string.action_change_folder),
                    testTag = "setup_output_dir_btn",
                    onClick = onPickOutputDir,
                    warningText = config.outputDirRestrictedWarning
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onContinue,
                    enabled = config.isMinimumSetupComplete && keySummary.isUsableForExtraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("setup_continue_btn"),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.action_continue_library),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null
                    )
                }
                if (!config.isMinimumSetupComplete || !keySummary.isUsableForExtraction) {
                    Text(
                        text = "Configure valid prod.keys, Game Library Folder, and Output Folder to unlock extraction.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SetupStepCard(
    stepNumber: Int,
    title: String,
    subtitle: String,
    icon: ImageVector,
    isCompleted: Boolean,
    accentColor: Color,
    buttonLabel: String,
    testTag: String,
    onClick: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    warningText: String? = null
) {
    val borderColor = when {
        warningText != null -> NxAmber.copy(alpha = 0.7f)
        isCompleted -> NxEmerald.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, MaterialTheme.shapes.medium)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isCompleted) NxEmerald.copy(alpha = 0.16f)
                            else accentColor.copy(alpha = 0.14f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isCompleted) Icons.Rounded.CheckCircle else icon,
                        contentDescription = null,
                        tint = if (isCompleted) NxEmerald else accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "STEP $stepNumber",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            AnimatedVisibility(visible = warningText != null) {
                if (warningText != null) {
                    Surface(
                        color = NxAmber.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = "Warning",
                                tint = NxAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = warningText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (secondaryActionLabel != null && onSecondaryClick != null) {
                    OutlinedButton(
                        onClick = onSecondaryClick,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(secondaryActionLabel)
                    }
                }
                FilledTonalButton(
                    onClick = onClick,
                    modifier = Modifier.testTag(testTag)
                ) {
                    Text(buttonLabel)
                }
            }
        }
    }
}
