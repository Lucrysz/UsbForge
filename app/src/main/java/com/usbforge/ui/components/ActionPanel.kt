package com.usbforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.usbforge.core.partition.FileSystemKind
import com.usbforge.core.partition.PartitionScheme
import com.usbforge.core.util.Bytes
import com.usbforge.ui.theme.ForgeColors
import com.usbforge.ui.theme.Spacing
import com.usbforge.ui.theme.UsbForgeTheme
import com.usbforge.vm.OperationKind

/**
 * Orta kısım: işlem seçimi (Format / Ventoy / ISO), dosya seçici ve
 * "Başlat" düğmesi.
 *
 * Tüm bileşenler **durumsuz** (stateless) tutulmuştur: veri
 * `UiState`'ten gelir, olaylar geri çağırma ile yukarı iletilir. Bu sayede
 * ekran Robolectric altında tek başına test edilebilir.
 */
@Composable
fun ActionPanel(
    operation: OperationKind,
    fileSystem: FileSystemKind,
    scheme: PartitionScheme,
    label: String,
    isoName: String?,
    isoSize: Long,
    hasIso: Boolean,
    verify: Boolean,
    canStart: Boolean,
    enabled: Boolean,
    onOperation: (OperationKind) -> Unit,
    onFileSystem: (FileSystemKind) -> Unit,
    onScheme: (PartitionScheme) -> Unit,
    onLabel: (String) -> Unit,
    onPickIso: () -> Unit,
    onVerify: (Boolean) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionTitle("İŞLEM")
        SegmentedRow(
            options = OperationKind.entries.toList(),
            selected = operation,
            labelOf = { it.label },
            onSelect = onOperation,
            tagPrefix = "op",
        )

        when (operation) {
            OperationKind.FORMAT -> FormatOptions(
                fileSystem = fileSystem,
                scheme = scheme,
                label = label,
                onFileSystem = onFileSystem,
                onScheme = onScheme,
                onLabel = onLabel,
            )

            OperationKind.VENTOY -> VentoyNotice()

            OperationKind.ISO -> IsoOptions(
                isoName = isoName,
                isoSize = isoSize,
                hasIso = hasIso,
                verify = verify,
                onPickIso = onPickIso,
                onVerify = onVerify,
            )
        }

        Button(
            onClick = onStart,
            enabled = canStart && enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("start-button"),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ForgeColors.Accent,
                contentColor = Color(0xFF00201A),
                disabledContainerColor = ForgeColors.SurfaceHigh,
                disabledContentColor = ForgeColors.TextSecondary,
            ),
        ) {
            Text(
                text = if (canStart) operation.label else "BAŞLAT",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun FormatOptions(
    fileSystem: FileSystemKind,
    scheme: PartitionScheme,
    label: String,
    onFileSystem: (FileSystemKind) -> Unit,
    onScheme: (PartitionScheme) -> Unit,
    onLabel: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionTitle("DOSYA SİSTEMİ")
        SegmentedRow(
            options = FileSystemKind.entries.toList(),
            selected = fileSystem,
            labelOf = { it.label },
            onSelect = onFileSystem,
            tagPrefix = "fs",
        )

        SectionTitle("BÖLÜNTÜ TABLOSU")
        SegmentedRow(
            options = PartitionScheme.entries.toList(),
            selected = scheme,
            labelOf = { it.label },
            onSelect = onScheme,
            tagPrefix = "scheme",
        )

        SectionTitle("BİRİM ETİKETİ")
        OutlinedTextField(
            value = label,
            onValueChange = onLabel,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("label-field"),
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            supportingText = {
                Text(
                    text = "Maks. 11 karakter · ${label.length}/11",
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )

        if (fileSystem == FileSystemKind.NTFS) {
            Hint("USB belleklerde NTFS yerine exFAT önerilir: flash aşınma dengelemesini bozmaz.")
        }
    }
}

@Composable
private fun VentoyNotice() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionTitle("VENTOY")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(ForgeColors.Surface)
                .border(1.dp, ForgeColors.Outline, RoundedCornerShape(8.dp))
                .padding(Spacing.md),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    "Diske Ventoy önyükleme yöneticisi kurulacak.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeColors.TextPrimary,
                )
                Text(
                    "Kurulum sonrası ISO dosyalarını normal kopyalama ile ekleyebilirsiniz; " +
                            "her açılışta listeden seçilir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeColors.TextSecondary,
                )
                Text(
                    "İşlem diskin tamamını siler: VTOYEFI (32 MiB) + veri bölümü + yedek EFI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeColors.Warning,
                )
            }
        }
    }
}

@Composable
private fun IsoOptions(
    isoName: String?,
    isoSize: Long,
    hasIso: Boolean,
    verify: Boolean,
    onPickIso: () -> Unit,
    onVerify: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionTitle("ISO / IMG DOSYASI")
        OutlinedButton(
            onClick = onPickIso,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pick-iso"),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.padding(start = Spacing.sm)) {
                Text(
                    text = if (hasIso) isoName ?: "Seçildi" else "Dosya seç",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasIso) {
                    Text(
                        text = Bytes.human(isoSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = ForgeColors.TextSecondary,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Yazma sonrası doğrula", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Diske geri okuma yapar (süreyi ikiye katlar)",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeColors.TextSecondary,
                )
            }
            Switch(
                checked = verify,
                onCheckedChange = onVerify,
                modifier = Modifier.testTag("verify-switch"),
            )
        }
    }
}

// ------------------------------------------------------------------ parçalar

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = ForgeColors.TextSecondary,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = ForgeColors.Warning,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

/**
 * Yatay seçim şeridi. [tagPrefix] verilen test etiketlerini üretir
 * (`op-FORMAT`, `fs-EXFAT`, …) ve UI testlerinin güvenilir hedef bulmasını sağlar.
 */
@Composable
fun <T> SegmentedRow(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) ForgeColors.Accent.copy(alpha = 0.14f) else ForgeColors.Surface)
                    .border(
                        1.dp,
                        if (isSelected) ForgeColors.Accent else ForgeColors.Outline,
                        RoundedCornerShape(6.dp),
                    )
                    .clickable { onSelect(option) }
                    .semantics { contentDescription = labelOf(option) }
                    .testTag("$tagPrefix-${option}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = labelOf(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) ForgeColors.Accent else ForgeColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0F14, widthDp = 380)
@Composable
private fun ActionPanelPreview() {
    UsbForgeTheme {
        ActionPanel(
            operation = OperationKind.FORMAT,
            fileSystem = FileSystemKind.EXFAT,
            scheme = PartitionScheme.GPT,
            label = "USBFORGE",
            isoName = null,
            isoSize = 0,
            hasIso = false,
            verify = false,
            canStart = true,
            enabled = true,
            onOperation = {},
            onFileSystem = {},
            onScheme = {},
            onLabel = {},
            onPickIso = {},
            onVerify = {},
            onStart = {},
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}
