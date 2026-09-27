package com.usbforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import com.usbforge.core.engine.JobSnapshot
import com.usbforge.core.engine.JobStatus
import com.usbforge.core.partition.FileSystemKind
import com.usbforge.core.partition.PartitionScheme
import com.usbforge.ui.components.ActionPanel
import com.usbforge.ui.components.BannerSlot
import com.usbforge.ui.components.BottomBannerPair
import com.usbforge.ui.components.DeviceCardList
import com.usbforge.ui.components.ProgressPanel
import com.usbforge.ui.theme.ForgeColors
import com.usbforge.ui.theme.Spacing
import com.usbforge.ui.theme.UsbForgeTheme
import com.usbforge.usb.UsbStorageDevice
import com.usbforge.vm.DeviceRow
import com.usbforge.vm.OperationKind
import com.usbforge.vm.UiState

/**
 * Ana ekran.
 *
 * ## Düzen
 * ```
 * ┌─────────────────────────────┐
 * │  UsbForge başlık + root rozeti│
 * ├─────────────────────────────┤
 * │  [Reklam slotu — üst]       │  ← BannerSlot
 * ├─────────────────────────────┤
 * │  USB AYGITLARI              │
 * │  ┌ cihaz kartı ──────────┐  │
 * │  └───────────────────────┘  │
 * │  İŞLEM  [Format|Ventoy|ISO] │
 * │  ... ayarlar ...            │
 * │  [ BAŞLAT ]                │
 * ├─────────────────────────────┤
 * │  İlerleme çubuğu / hız      │  ← ProgressPanel
│ ├─────────────────────────────┤
 * │  [Reklam slotu — alt]       │  ← BottomBannerPair
 * └─────────────────────────────┘
 * ```
 *
 * ## Test edilebilirlik
 * Tüm bileşenler durumsuzdur; veri [UiState] ve [JobSnapshot] üzerinden
 * gelir. Bu sayede Robolectric altında Android cihaz olmadan tam ekran
 * akışları doğrulanabilir.
 */
@Composable
fun MainScreen(
    state: UiState,
    job: JobSnapshot,
    onSelectDevice: (String) -> Unit,
    onRescan: () -> Unit,
    onOperation: (OperationKind) -> Unit,
    onFileSystem: (FileSystemKind) -> Unit,
    onScheme: (PartitionScheme) -> Unit,
    onLabel: (String) -> Unit,
    onPickIso: () -> Unit,
    onVerify: (Boolean) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onClearJob: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onDismissMessage()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ForgeColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .testTag("main-screen"),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Header(rootAvailable = state.rootAvailable)

        // --- Üst reklam konteyneri ---------------------------------------
        BannerSlot(
            slot = "top",
            label = "Reklam Alanı (Üst Banner)",
            visible = state.showTopBanner,
        )

        // --- Cihaz listesi ------------------------------------------------
        DeviceCardList(
            devices = state.devices,
            selectedName = state.selected,
            scanning = state.scanning,
            onSelect = onSelectDevice,
            onRescan = onRescan,
        )

        // --- İşlem seçimi -------------------------------------------------
        ActionPanel(
            operation = state.operation,
            fileSystem = state.fileSystem,
            scheme = state.scheme,
            label = state.label,
            isoName = state.isoName,
            isoSize = state.isoSize,
            hasIso = state.hasIso,
            verify = state.verifyAfterWrite,
            canStart = state.canStart,
            enabled = job.status != JobStatus.RUNNING,
            onOperation = onOperation,
            onFileSystem = onFileSystem,
            onScheme = onScheme,
            onLabel = onLabel,
            onPickIso = onPickIso,
            onVerify = onVerify,
            onStart = onStart,
        )

        // --- Uyarı --------------------------------------------------------
        if (state.selected != null) {
            DestructiveWarning(operation = state.operation)
        }

        // --- İlerleme -----------------------------------------------------
        if (job.status != JobStatus.IDLE) {
            ProgressPanel(snapshot = job, onCancel = onCancel, onClear = onClearJob)
        }

        // --- Alt reklam konteyneri ---------------------------------------
        BottomBannerPair(visible = state.showBottomBanner, onToggle = {})

        // --- Alt bilgi ----------------------------------------------------
        Footer()

        SnackbarHost(hostState = snackbarHostState) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = ForgeColors.SurfaceHigh,
                contentColor = ForgeColors.TextPrimary,
            )
        }
    }
}

@Composable
private fun Header(rootAvailable: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("UsbForge", style = MaterialTheme.typography.titleLarge, color = ForgeColors.TextPrimary)
            Text(
                text = "USB biçimlendirme · ISO yazma · Ventoy",
                style = MaterialTheme.typography.labelSmall,
                color = ForgeColors.TextSecondary,
            )
        }
        if (rootAvailable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = null,
                    tint = ForgeColors.Warning,
                    modifier = Modifier.padding(end = Spacing.xs),
                )
                Text("root", style = MaterialTheme.typography.labelSmall, color = ForgeColors.Warning)
            }
        }
    }
}

@Composable
private fun DestructiveWarning(operation: OperationKind) {
    val text = when (operation) {
        OperationKind.FORMAT ->
            "Seçili aygıttaki tüm veriler silinecek. Yanlış cihaz seçtiyseniz işlem geri alınamaz."
        OperationKind.VENTOY ->
            "Seçili aygıtın tamamı Ventoy'a dönüştürülecek; mevcut bölümler ve dosyalar silinecek."
        OperationKind.ISO ->
            "Seçili aygıtın tamamının üzerine yazılacak. ISO'dan küçük bir görüntü kalan alanı sıfırlar."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("destructive-warning"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = ForgeColors.Warning,
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = ForgeColors.Warning)
    }
}

@Composable
private fun Footer() {
    Text(
        text = "Ham blok erişimi USB Mass Storage (BOT) ve SCSI komutları üzerinden yapılır. " +
                "Bazı flash denetleyicileri işletim sistemi dışından ham yazmaya izin vermeyebilir.",
        style = MaterialTheme.typography.labelSmall,
        color = ForgeColors.Outline,
        modifier = Modifier.padding(vertical = Spacing.sm),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0F14, heightDp = 1400)
@Composable
private fun MainScreenPreview() {
    UsbForgeTheme {
        MainScreen(
            state = UiState(
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
                ),
                selected = "/dev/bus/usb/001/004",
                rootAvailable = true,
            ),
            job = JobSnapshot(
                id = 1,
                title = "Cruzer Blade biçimlendiriliyor",
                phase = "exFAT biçimlendiriliyor",
                writtenBytes = 900_000_000,
                totalBytes = 15_689_166_976,
                bytesPerSecond = 21_474_836,
                status = JobStatus.RUNNING,
            ),
            onSelectDevice = {},
            onRescan = {},
            onOperation = {},
            onFileSystem = {},
            onScheme = {},
            onLabel = {},
            onPickIso = {},
            onVerify = {},
            onStart = {},
            onCancel = {},
            onClearJob = {},
            onDismissMessage = {},
        )
    }
}
