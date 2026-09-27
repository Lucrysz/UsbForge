package com.usbforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.usbforge.core.engine.JobSnapshot
import com.usbforge.core.engine.JobStatus
import com.usbforge.core.usb.LogLevel
import com.usbforge.core.util.Bytes
import com.usbforge.ui.theme.ForgeColors
import com.usbforge.ui.theme.MonoMedium
import com.usbforge.ui.theme.MonoSmall
import com.usbforge.ui.theme.Spacing
import com.usbforge.ui.theme.UsbForgeTheme

/**
 * İlerleme alanı: doğrusal ilerleme çubuğu, aktarılan miktar, yazma hızı,
 * kalan süre ve son satırlarda iş günlüğü.
 *
 * ## Hesaplanan değerler
 * - Yüzde: `writtenBytes / totalBytes`. `totalBytes == 0` ise belirsiz
 *   ilerleme gösterilir (çubuk dolu görünmez).
 * - Hız: kayan 3 saniyelik pencere ortalaması ([com.usbforge.engine.JobEngine]).
 * - Kalan süre: `(total - written) / speed`.
 */
@Composable
fun ProgressPanel(
    snapshot: JobSnapshot,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (snapshot.status) {
        JobStatus.RUNNING -> ForgeColors.Accent
        JobStatus.SUCCESS -> ForgeColors.Success
        JobStatus.FAILED -> ForgeColors.Danger
        JobStatus.CANCELLED -> ForgeColors.Warning
        JobStatus.IDLE -> ForgeColors.TextSecondary
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ForgeColors.Surface)
            .border(1.dp, ForgeColors.Outline, RoundedCornerShape(8.dp))
            .padding(Spacing.md)
            .testTag("progress-panel"),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = snapshot.title.ifEmpty { "Hazır" },
                style = MaterialTheme.typography.titleMedium,
                color = ForgeColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = snapshot.percentText,
                style = MonoMedium,
                color = statusColor,
                modifier = Modifier.testTag("percent-text"),
            )
        }

        if (snapshot.status == JobStatus.RUNNING || snapshot.writtenBytes > 0) {
            LinearProgressIndicator(
                progress = { snapshot.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .testTag("progress-bar"),
                color = statusColor,
                trackColor = ForgeColors.SurfaceHigh,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MetricColumn("FAZ", snapshot.phase.ifEmpty { "—" })
            MetricColumn("HIZ", Bytes.speed(snapshot.bytesPerSecond))
            MetricColumn("KALAN", Bytes.duration(snapshot.etaSeconds))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${Bytes.human(snapshot.writtenBytes)} / ${Bytes.human(snapshot.totalBytes)}" +
                        if (snapshot.totalBytes > 0) "" else "",
                style = MonoSmall,
                color = ForgeColors.TextSecondary,
                modifier = Modifier.testTag("transferred-text"),
            )
            when (snapshot.status) {
                JobStatus.RUNNING -> IconButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("cancel-button"),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Durdur",
                        tint = ForgeColors.Danger,
                    )
                }

                JobStatus.SUCCESS, JobStatus.FAILED, JobStatus.CANCELLED -> IconButton(
                    onClick = onClear,
                    modifier = Modifier.testTag("clear-button"),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Temizle",
                        tint = ForgeColors.TextSecondary,
                    )
                }

                JobStatus.IDLE -> Unit
            }
        }

        snapshot.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = ForgeColors.Danger,
                modifier = Modifier.testTag("error-text"),
            )
        }

        if (snapshot.log.isNotEmpty()) {
            JobLog(snapshot)
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = ForgeColors.TextSecondary)
        Text(value, style = MonoSmall, color = ForgeColors.TextPrimary)
    }
}

/** Son 6 log satırı. Uzun listelerde arayüzü şişirmemek için kırpılır. */
@Composable
private fun JobLog(snapshot: JobSnapshot) {
    val visible = snapshot.log.takeLast(6)
    Column(
        verticalArrangement = Arrangement.spacedBy(1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(ForgeColors.Slot)
            .padding(Spacing.sm),
    ) {
        visible.forEach { line ->
            Text(
                text = line.message,
                style = MonoSmall,
                color = when (line.level) {
                    LogLevel.INFO -> ForgeColors.TextSecondary
                    LogLevel.WARN -> ForgeColors.Warning
                    LogLevel.ERROR -> ForgeColors.Danger
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0F14, widthDp = 380)
@Composable
private fun ProgressPanelPreview() {
    UsbForgeTheme {
        ProgressPanel(
            snapshot = JobSnapshot(
                id = 1,
                title = "ubuntu-24.04.1-desktop-amd64.iso yazılıyor",
                phase = "ISO yazılıyor",
                writtenBytes = 1_073_741_824,
                totalBytes = 5_733_138_944,
                bytesPerSecond = 18_874_368,
                status = JobStatus.RUNNING,
            ),
            onCancel = {},
            onClear = {},
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}
