package com.usbforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.usbforge.ui.theme.ForgeColors
import com.usbforge.ui.theme.Spacing
import com.usbforge.ui.theme.UsbForgeTheme
import com.usbforge.usb.UsbStorageDevice
import com.usbforge.vm.DeviceRow

/**
 * Üst kısımdaki cihaz listesi: takılı USB aygıtının adı, depolama
 * boyutu, seri numarası ve bağlantı durumu.
 */
@Composable
fun DeviceCardList(
    devices: List<DeviceRow>,
    selectedName: String?,
    scanning: Boolean,
    onSelect: (String) -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "USB AYGITLARI",
                style = MaterialTheme.typography.labelSmall,
                color = ForgeColors.TextSecondary,
            )
            IconButton(
                onClick = onRescan,
                modifier = Modifier.semantics { contentDescription = "Cihazları Tara" },
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = ForgeColors.TextSecondary,
                )
            }
        }

        if (devices.isEmpty() && !scanning) {
            EmptyDeviceCard()
        }

        if (scanning && devices.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.lg),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = ForgeColors.Accent,
                )
                Text("Taranıyor…", style = MaterialTheme.typography.bodySmall, color = ForgeColors.TextSecondary)
            }
        }

        // Düz Column, LazyColumn DEĞİL: bu liste ana ekranın
        // verticalScroll() sarmalayıcısının içindedir ve bir LazyList'e
        // sınırsız yükseklik kısıtı verilmesi Compose tarafından reddedilir
        // ("infinity maximum height constraints"). USB cihaz sayısı birkaç
        // düzeyde olduğu için tembel liste gereksizdir.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            devices.forEach { row ->
                DeviceCard(
                    row = row,
                    selected = row.device.deviceName == selectedName,
                    onClick = { onSelect(row.device.deviceName) },
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    row: DeviceRow,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = when {
        selected -> ForgeColors.Accent
        !row.connected -> ForgeColors.Outline
        else -> ForgeColors.Outline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ForgeColors.SurfaceHigh else ForgeColors.Surface)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(Spacing.md)
            .testTagCompat("device-${row.device.deviceName}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) ForgeColors.Accent.copy(alpha = 0.15f) else ForgeColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Usb,
                contentDescription = null,
                tint = if (selected) ForgeColors.Accent else ForgeColors.TextSecondary,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.device.titleLine(),
                style = MaterialTheme.typography.titleMedium,
                color = ForgeColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.device.subtitleLine(),
                style = MaterialTheme.typography.bodySmall,
                color = ForgeColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.error != null) {
                Text(
                    text = row.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeColors.Warning,
                )
            }
        }

        if (row.opening) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = ForgeColors.Accent,
            )
        } else {
            Text(
                text = if (row.device.totalSectors > 0) {
                    String.format(java.util.Locale.US, "%.1f GiB", row.device.sizeGiB)
                } else {
                    "—"
                },
                style = MaterialTheme.typography.labelSmall,
                color = ForgeColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun EmptyDeviceCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ForgeColors.Surface)
            .border(1.dp, ForgeColors.Outline, RoundedCornerShape(8.dp))
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text("USB bellek bulunamadı", style = MaterialTheme.typography.titleMedium, color = ForgeColors.TextPrimary)
        Text(
            text = "OTG destekli bir belleği bağlayın veya listeyi yenileyin. " +
                    "Cihaz başlığında 'belge' türünde görünüyor olmalıdır.",
            style = MaterialTheme.typography.bodySmall,
            color = ForgeColors.TextSecondary,
        )
    }
}

/** Testlerde kullanılan etiket kısayolu. */
private fun Modifier.testTagCompat(tag: String): Modifier = this.testTag(tag)

@Preview(showBackground = true, backgroundColor = 0xFF0B0F14, widthDp = 380)
@Composable
private fun DeviceCardListPreview() {
    UsbForgeTheme {
        DeviceCardList(
            devices = listOf(
                DeviceRow(
                    device = UsbStorageDevice(
                        deviceName = "/dev/bus/usb/001/004",
                        vendorId = 0x0781,
                        productId = 0x5567,
                        manufacturer = "SanDisk",
                        product = "Cruzer Blade",
                        serial = "4C530001120611110424",
                        usbVersion = 2.0f,
                        sectorSize = 512,
                        totalSectors = 30_645_248,
                    ),
                    connected = true,
                ),
                DeviceRow(
                    device = UsbStorageDevice(
                        deviceName = "/dev/bus/usb/001/007",
                        vendorId = 0x1234,
                        productId = 0x5678,
                        manufacturer = "Kingston",
                        product = "DataTraveler 3.0",
                        serial = "",
                        usbVersion = 3.0f,
                        sectorSize = 512,
                        totalSectors = 60_514_560,
                    ),
                    connected = true,
                ),
            ),
            selectedName = "/dev/bus/usb/001/004",
            scanning = false,
            onSelect = {},
            onRescan = {},
        )
    }
}
