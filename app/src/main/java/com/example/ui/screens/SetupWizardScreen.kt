package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.crypto.KeyValidationSummary
import com.example.data.SetupConfig
import com.example.ui.components.GroupedSection
import com.example.ui.components.semanticSuccessColor
import com.example.ui.components.semanticWarningColor
import com.example.ui.theme.JetBrainsMonoFontFamily

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
    val step1Ready = keySummary.isUsableForExtraction
    val step2Ready = config.titleKeysUri != null
    val step3Ready = !config.gameDirUri.isNullOrBlank()
    val step4Ready = !config.outputDirUri.isNullOrBlank() && config.outputDirRestrictedWarning == null
    val completedCount = listOf(step1Ready, step2Ready, step3Ready, step4Ready).count { it }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "nx",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "$completedCount / 4 ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = stringResource(R.string.setup_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.setup_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 4-segment readiness meter
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(step1Ready, step2Ready, step3Ready, step4Ready).forEachIndexed { idx, ready ->
                            val isRequired = idx != 1
                            val barColor = when {
                                ready -> semanticSuccessColor()
                                !isRequired -> MaterialTheme.colorScheme.surfaceContainerHighest
                                else -> MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(barColor)
                            )
                        }
                    }
                }
            }

            item {
                GroupedSection {
                    SetupStepRow(
                        stepCode = "01",
                        title = stringResource(R.string.step_prod_keys),
                        subtitle = if (config.prodKeysName != null) {
                            "${config.prodKeysName} • ${keySummary.totalKeysCount} keys (${keySummary.masterKeyCount} master)"
                        } else {
                            "AES-XTS header & NCA key area decryption"
                        },
                        isCompleted = step1Ready,
                        buttonLabel = if (config.prodKeysUri == null) stringResource(R.string.action_select_file) else stringResource(R.string.action_replace_file),
                        testTag = "setup_prod_keys_btn",
                        onClick = onPickProdKeys,
                        warningText = if (config.prodKeysUri != null && keySummary.warnings.isNotEmpty()) {
                            keySummary.warnings.first()
                        } else null
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    SetupStepRow(
                        stepCode = "02",
                        title = stringResource(R.string.step_title_keys),
                        subtitle = if (config.titleKeysName != null) {
                            "${config.titleKeysName} • ${keySummary.titleKeysCount} entries loaded"
                        } else {
                            "Optional — NSP common tickets are parsed automatically"
                        },
                        isCompleted = step2Ready,
                        buttonLabel = if (config.titleKeysUri == null) stringResource(R.string.action_select_file) else stringResource(R.string.action_replace_file),
                        secondaryActionLabel = if (config.titleKeysUri != null) "Clear" else null,
                        onSecondaryClick = onClearTitleKeys,
                        testTag = "setup_title_keys_btn",
                        onClick = onPickTitleKeys
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    SetupStepRow(
                        stepCode = "03",
                        title = stringResource(R.string.step_game_dir),
                        subtitle = config.gameDirName ?: "Folder containing .xci, .nsp, or .nca dumps",
                        isCompleted = step3Ready,
                        buttonLabel = if (config.gameDirUri == null) stringResource(R.string.action_select_folder) else stringResource(R.string.action_change_folder),
                        testTag = "setup_game_dir_btn",
                        onClick = onPickGameDir
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    SetupStepRow(
                        stepCode = "04",
                        title = stringResource(R.string.step_output_dir),
                        subtitle = config.outputDirName ?: "Dedicated subfolder for extracted files",
                        isCompleted = step4Ready,
                        buttonLabel = if (config.outputDirUri == null) stringResource(R.string.action_select_folder) else stringResource(R.string.action_change_folder),
                        testTag = "setup_output_dir_btn",
                        onClick = onPickOutputDir,
                        warningText = config.outputDirRestrictedWarning
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onContinue,
                        enabled = config.isMinimumSetupComplete && keySummary.isUsableForExtraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
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
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (!config.isMinimumSetupComplete || !keySummary.isUsableForExtraction) {
                        Text(
                            text = "Select valid prod.keys, a game folder, and a dedicated output subfolder to continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SetupStepRow(
    stepCode: String,
    title: String,
    subtitle: String,
    isCompleted: Boolean,
    buttonLabel: String,
    testTag: String,
    onClick: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    warningText: String? = null
) {
    val successColor = semanticSuccessColor()
    val warnColor = semanticWarningColor()

    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isCompleted) successColor.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Configured",
                        tint = successColor,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(
                        text = stepCode,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
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
                    color = warnColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = "Warning",
                            tint = warnColor,
                            modifier = Modifier.size(16.dp)
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
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (secondaryActionLabel != null && onSecondaryClick != null) {
                TextButton(
                    onClick = onSecondaryClick,
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Text(secondaryActionLabel)
                }
            }
            FilledTonalButton(
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.testTag(testTag)
            ) {
                Text(
                    text = buttonLabel,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
