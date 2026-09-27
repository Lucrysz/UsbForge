package com.usbforge.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.usbforge.core.engine.JobSnapshot
import com.usbforge.core.engine.JobStatus
import com.usbforge.core.partition.FileSystemKind
import com.usbforge.core.partition.PartitionScheme
import com.usbforge.core.usb.LogLevel
import com.usbforge.ui.theme.UsbForgeTheme
import com.usbforge.usb.UsbStorageDevice
import com.usbforge.vm.DeviceRow
import com.usbforge.vm.OperationKind
import com.usbforge.vm.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Ana ekranın cihazsız UI testleri.
 *
 * ## Nasıl çalışır
 * Robolectric, Activity ve Compose ağacını **JVM içinde** çalıştırır.
 * Emülatör, cihaz veya `adb` gerekmez:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest
 * ```
 *
 * ## Neden bu değerli
 * Ekran düzeni, tıklama zincirleri, durum geçişleri ve biçimlendirme
 * mantığını her seferinde telefona APK yükleyerek doğrulamak yerine
 * saniyeler içinde çalışır. Tarayıcı/Compose ağacı gerçek ölçüm motoruyla
 * yerleştirildiği için düzen hataları (taşma, kırpılma) da yakalanır.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MainScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun device(
        name: String = "/dev/bus/usb/001/004",
        product: String = "Cruzer Blade",
        sectors: Long = 30_645_248,
    ) = DeviceRow(
        device = UsbStorageDevice(
            deviceName = name,
            vendorId = 0x0781,
            productId = 0x5567,
            manufacturer = "SanDisk",
            product = product,
            serial = "4C530001120611110424",
            usbVersion = 2.0f,
            sectorSize = 512,
            totalSectors = sectors,
        ),
        connected = true,
    )

    private fun baseState(
        devices: List<DeviceRow> = listOf(device()),
        selected: String? = "/dev/bus/usb/001/004",
        operation: OperationKind = OperationKind.FORMAT,
    ) = UiState(
        devices = devices,
        selected = selected,
        operation = operation,
        fileSystem = FileSystemKind.EXFAT,
        scheme = PartitionScheme.GPT,
        label = "USBFORGE",
    )

    /** Ekranı verilen durumla çizer ve tüm geri çağırmaları kaydeder. */
    private fun render(
        state: UiState,
        job: JobSnapshot = JobSnapshot(),
        onOperation: (OperationKind) -> Unit = {},
        onFileSystem: (FileSystemKind) -> Unit = {},
        onScheme: (PartitionScheme) -> Unit = {},
        onLabel: (String) -> Unit = {},
        onStart: () -> Unit = {},
        onSelectDevice: (String) -> Unit = {},
        onRescan: () -> Unit = {},
        onVerify: (Boolean) -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        compose.setContent {
            UsbForgeTheme {
                MainScreen(
                    state = state,
                    job = job,
                    onSelectDevice = onSelectDevice,
                    onRescan = onRescan,
                    onOperation = onOperation,
                    onFileSystem = onFileSystem,
                    onScheme = onScheme,
                    onLabel = onLabel,
                    onPickIso = {},
                    onVerify = onVerify,
                    onStart = onStart,
                    onCancel = onCancel,
                    onClearJob = {},
                    onDismissMessage = {},
                )
            }
        }
    }

    // ------------------------------------------------------------- temel yerleşim

    @Test
    fun `ekran basligi ve cihaz listesi gorunur`() {
        render(baseState())
        compose.onNodeWithText("UsbForge").assertIsDisplayed()
        compose.onNodeWithText("Cruzer Blade").assertIsDisplayed()
        compose.onNodeWithText("4C530001120611110424").assertIsDisplayed()
    }

    @Test
    fun `cihaz yoksa bilgilendirme gosterilir`() {
        render(baseState(devices = emptyList(), selected = null))
        compose.onNodeWithText("USB bellek bulunamadı").assertIsDisplayed()
    }

    @Test
    fun `iki reklam banner konteyneri gorunur`() {
        render(baseState())
        compose.onNodeWithText("Reklam Alanı (Üst Banner) · top").assertIsDisplayed()
        compose.onNodeWithText("Reklam Alanı (Alt Banner) · bottom-1").assertIsDisplayed()
        compose.onNodeWithText("Reklam Alanı (Alt Banner 2) · bottom-2").assertIsDisplayed()
    }

    @Test
    fun `banner gorunurlugu kapatilinca konteyner kaldirilir`() {
        render(baseState().copy(showTopBanner = false, showBottomBanner = false))
        compose.onAllNodesWithTextContains("Reklam Alanı (Üst Banner)").assertCountEquals(0)
        compose.onAllNodesWithTextContains("Reklam Alanı (Alt Banner)").assertCountEquals(0)
    }

    // ------------------------------------------------------------------ etkilesim

    @Test
    fun `islem secimi durumu degistirir`() {
        var selected: OperationKind? = null
        render(baseState(), onOperation = { selected = it })

        compose.onNodeWithTag("op-ISO").performClick()
        assertEquals(OperationKind.ISO, selected)

        compose.onNodeWithTag("op-VENTOY").performClick()
        assertEquals(OperationKind.VENTOY, selected)
    }

    @Test
    fun `dosya sistemi ve boluntu tablosu secilebilir`() {
        var fs: FileSystemKind? = null
        var scheme: PartitionScheme? = null
        render(baseState(), onFileSystem = { fs = it }, onScheme = { scheme = it })

        compose.onNodeWithTag("fs-NTFS").performClick()
        assertEquals(FileSystemKind.NTFS, fs)

        compose.onNodeWithTag("scheme-MBR").performClick()
        assertEquals(PartitionScheme.MBR, scheme)
    }

    @Test
    fun `etiket alanina yazilabilir`() {
        var label: String? = null
        render(baseState(), onLabel = { label = it })

        compose.onNodeWithTag("label-field").performTextInput("TESTUSB")
        assertEquals("TESTUSB", label)
    }

    @Test
    fun `cihaz secildiginde baslat butonu etkinlesir`() {
        var started = false
        render(baseState(), onStart = { started = true })

        compose.onNodeWithTag("start-button").assertIsEnabled().performClick()
        assertTrue("Başlat butonu işi tetiklemeli", started)
    }

    @Test
    fun `cihaz secili degilse baslat butonu pasif`() {
        render(baseState(selected = null))
        compose.onNodeWithTag("start-button").assertIsNotEnabled()
    }

    @Test
    fun `is calisirken baslat butonu pasif`() {
        render(
            baseState(),
            job = JobSnapshot(id = 1, title = "x", status = JobStatus.RUNNING),
        )
        compose.onNodeWithTag("start-button").assertIsNotEnabled()
    }

    @Test
    fun `cihaz kartina tiklamak secimi degistirir`() {
        var picked: String? = null
        render(baseState(), onSelectDevice = { picked = it })

        compose.onNodeWithTag("device-/dev/bus/usb/001/007").performClick()
        assertEquals("/dev/bus/usb/001/007", picked)
    }

    @Test
    fun `yikici uyari yalnizca cihaz seciliyken gorunur`() {
        render(baseState(selected = null))
        compose.onAllNodesWithTag("destructive-warning").assertCountEquals(0)

        compose.runOnIdle { }
        render(baseState())
        compose.onAllNodesWithTag("destructive-warning").assertCountEquals(1)
    }

    // ------------------------------------------------------------------ ilerleme

    @Test
    fun `ilerleme yuzdesi hesaplanir ve gosterilir`() {
        val job = JobSnapshot(
            id = 1,
            title = "ubuntu.iso yazılıyor",
            phase = "ISO yazılıyor",
            writtenBytes = 1_073_741_824,
            totalBytes = 5_733_138_944,
            bytesPerSecond = 20_971_520,
            status = JobStatus.RUNNING,
        )
        render(baseState(), job = job)

        // 1073741824 / 5733138944 = %18.72
        compose.onNodeWithTag("percent-text").assertTextEquals("18.7%")
        compose.onNodeWithText("1.0 GiB / 5.3 GiB").assertIsDisplayed()
        compose.onNodeWithText("20.0 MB/s").assertIsDisplayed()
        compose.onNodeWithTag("progress-bar").assertIsDisplayed()
    }

    @Test
    fun `toplam bilinmiyorsa yuzde gosterilmez`() {
        val job = JobSnapshot(
            id = 1,
            title = "temizlik",
            status = JobStatus.RUNNING,
            totalBytes = 0,
        )
        render(baseState(), job = job)
        compose.onNodeWithTag("percent-text").assertTextEquals("—")
    }

    @Test
    fun `calisan iste durdur butonu gorunur`() {
        var cancelled = false
        render(
            baseState(),
            job = JobSnapshot(id = 1, title = "x", status = JobStatus.RUNNING),
            onCancel = { cancelled = true },
        )
        compose.onNodeWithTag("cancel-button").assertIsDisplayed().performClick()
        assertTrue("İptal geri çağırması tetiklenmeli", cancelled)
    }

    @Test
    fun `hatali iste hata metni gorunur`() {
        val job = JobSnapshot(
            id = 1,
            title = "biçimlendirme",
            status = JobStatus.FAILED,
            error = "Cihaz yazmayı reddetti (write-protected).",
        )
        render(baseState(), job = job)
        compose.onNodeWithTag("error-text")
            .assertTextEquals("Cihaz yazmayı reddetti (write-protected).")
    }

    @Test
    fun `gunluk satirlari gorunur`() {
        val job = JobSnapshot(
            id = 1,
            title = "biçimlendirme",
            status = JobStatus.SUCCESS,
            log = listOf(
                LogLine(LogLevel.INFO, "Cihaz açıldı", 0),
                LogLine(LogLevel.WARN, "Yazma koruması kaldırıldı", 1),
            ),
        )
        render(baseState(), job = job)
        compose.onNodeWithText("Cihaz açıldı").assertIsDisplayed()
        compose.onNodeWithText("Yazma koruması kaldırıldı").assertIsDisplayed()
    }

    @Test
    fun `ISO modunda dosya secici ve dogrulama anahtari gorunur`() {
        var verify: Boolean? = null
        render(
            baseState(operation = OperationKind.ISO),
            onVerify = { verify = it },
        )
        compose.onNodeWithTag("pick-iso").assertIsDisplayed()
        compose.onNodeWithTag("verify-switch").assertIsDisplayed()
        compose.onNodeWithTag("verify-switch").performClick()
        assertEquals(true, verify)
    }

    @Test
    fun `Ventoy modunda bilgilendirme metni gorunur`() {
        render(baseState(operation = OperationKind.VENTOY))
        compose.onNodeWithText("Diske Ventoy önyükleme yöneticisi kurulacak.").assertIsDisplayed()
    }

    @Test
    fun `NTFS secildiginde uyari gosterilir`() {
        render(baseState().copy(fileSystem = FileSystemKind.NTFS))
        compose.onNodeWithText("USB belleklerde NTFS yerine exFAT önerilir: flash aşınma dengelemesini bozmaz.")
            .assertIsDisplayed()
    }
}
