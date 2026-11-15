package com.example.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.DumpFormat
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NxAmber
import com.example.ui.theme.NxAmberLight
import com.example.ui.theme.NxCoral
import com.example.ui.theme.NxCoralLight
import com.example.ui.theme.NxEmerald
import com.example.ui.theme.NxEmeraldLight
import com.example.ui.theme.NxSteelBlue
import com.example.ui.theme.NxSteelBlueLight
import java.io.File

@Composable
fun semanticSuccessColor(): Color =
    if (MaterialTheme.colorScheme.background.luminanceCompat() < 0.5f) NxEmerald else NxEmeraldLight

@Composable
fun semanticWarningColor(): Color =
    if (MaterialTheme.colorScheme.background.luminanceCompat() < 0.5f) NxAmber else NxAmberLight

private fun Color.luminanceCompat(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
fun FormatBadge(format: DumpFormat, modifier: Modifier = Modifier) {
    val isDark = MaterialTheme.colorScheme.background.luminanceCompat() < 0.5f
    val (bg, fg) = when (format) {
        DumpFormat.XCI -> {
            val fg = if (isDark) NxCoral else NxCoralLight
            fg.copy(alpha = 0.14f) to fg
        }
        DumpFormat.NSP -> {
            val fg = if (isDark) NxSteelBlue else NxSteelBlueLight
            fg.copy(alpha = 0.14f) to fg
        }
        DumpFormat.NCA -> {
            val fg = if (isDark) NxAmber else NxAmberLight
            fg.copy(alpha = 0.15f) to fg
        }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = bg
    ) {
        Text(
            text = format.name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Medium
            ),
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun PillTag(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = containerColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun GameIconBox(
    iconCachePath: String?,
    titleName: String,
    size: Dp = 60.dp,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(iconCachePath) {
        if (!iconCachePath.isNullOrBlank()) {
            val f = File(iconCachePath)
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        } else null
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "$titleName icon",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.SdStorage,
                contentDescription = "$titleName icon",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(size * 0.44f)
            )
        }
    }
}

@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

/**
 * Clean native Compose geometric vector artwork depicting a Switch game cartridge
 * and partition schematic, replacing AI-generated raster illustrations.
 */
@Composable
fun GameCardSchematicGraphic(
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val outline = MaterialTheme.colorScheme.outline
    val labelBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val accentLine = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    Canvas(modifier = modifier.size(112.dp, 132.dp)) {
        val w = size.width
        val h = size.height
        val notch = w * 0.14f
        val r = 10.dp.toPx()

        // Outer cartridge silhouette with top-right chamfered notch
        val bodyPath = Path().apply {
            moveTo(r, 0f)
            lineTo(w - notch, 0f)
            lineTo(w, notch)
            lineTo(w, h - r)
            quadraticTo(w, h, w - r, h)
            lineTo(r, h)
            quadraticTo(0f, h, 0f, h - r)
            lineTo(0f, r)
            quadraticTo(0f, 0f, r, 0f)
            close()
        }

        drawPath(path = bodyPath, color = surfaceHigh)
        drawPath(
            path = bodyPath,
            color = outline.copy(alpha = 0.65f),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Recessed label area
        val padX = w * 0.12f
        val labelTop = h * 0.16f
        val labelW = w - padX * 2
        val labelH = h * 0.56f
        drawRoundRect(
            color = labelBg,
            topLeft = Offset(padX, labelTop),
            size = Size(labelW, labelH),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        )

        // Minimalist crimson bar + partition data bars inside label
        drawRoundRect(
            color = primary,
            topLeft = Offset(padX + 8.dp.toPx(), labelTop + 10.dp.toPx()),
            size = Size(labelW * 0.38f, 4.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )

        drawLine(
            color = accentLine,
            start = Offset(padX + 8.dp.toPx(), labelTop + 26.dp.toPx()),
            end = Offset(padX + labelW - 10.dp.toPx(), labelTop + 26.dp.toPx()),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = accentLine.copy(alpha = 0.65f),
            start = Offset(padX + 8.dp.toPx(), labelTop + 36.dp.toPx()),
            end = Offset(padX + labelW * 0.72f, labelTop + 36.dp.toPx()),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = accentLine.copy(alpha = 0.4f),
            start = Offset(padX + 8.dp.toPx(), labelTop + 46.dp.toPx()),
            end = Offset(padX + labelW * 0.54f, labelTop + 46.dp.toPx()),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Bottom contact ridges
        val pinCount = 6
        val pinAreaW = w * 0.56f
        val startX = (w - pinAreaW) / 2f
        val step = pinAreaW / (pinCount - 1)
        val pinTop = h * 0.80f
        val pinBottom = h * 0.90f
        for (i in 0 until pinCount) {
            val x = startX + i * step
            drawLine(
                color = outline.copy(alpha = 0.55f),
                start = Offset(x, pinTop),
                end = Offset(x, pinBottom),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) trailing()
    }
}
