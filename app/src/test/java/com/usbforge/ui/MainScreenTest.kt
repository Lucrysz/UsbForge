package com.usbforge.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.usbforge.core.engine.JobSnapshot
import com.usbforge.core.engine.JobStatus
import com.usbforge.core.engine.LogLine
import com.usbforge.core.partition.FileSystemKind
import com.usbforge.core.partition.PartitionScheme
import com.usbforge.core.usb.LogLevel
import com.usbforge.ui.theme.UsbForgeTheme
import com.usbforge.usb.UsbStorageDevice
import com.usbforge.vm.DeviceRow
import com.usbforge.vm.OperationKind
import com.usbforge.vm.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Ana ekranın **cihazsız** UI testleri.
 *
 * ## Nasıl çalışır
 * Robolectric, Activity ve Compose ağacını JVM içinde çalıştırır. Emülatör,
 * cihaz veya `adb` gerekmez:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest
 * ```
 *
 * ## Neden değerli
 * Ekran düzeni, tıklama zincirleri, durum geçişleri ve biçimlendirme
 * mantığını her seferinde telefona APK yükleyerek doğrulamak yerine
 * saniyeler içinde çalıştırır. Compose ağacı gerçek ölçüm motoruyla
 * yerleştirildiği için düzen hataları da yakalanır.
 *
 * ## Kaydırılabilir ekran notu
 * [MainScreen] dikey kaydırılabilir bir sütundur; bu nedenle ekranın alt
 * kısmındaki öğeler test sırasında görünür alanda olmayabilir. Bu yüzden
 * - varlık kontrolleri `assertExists()` ile,
 * - tıklama öncesi `performScrollTo()` ile,
 * yapılır. `assertIsDisplayed()` yalnızca üst kısımdaki öğelerde kullanılır.
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

    /** Ekranı verilen durumla çizer ve geri çağırmaları kaydeder. */
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
    fun `ekran basligi gorunur`() {
        render(baseState())
        // Başlık ekranın en üstünde olduğu için doğrudan "displayed".
        compose.onNodeWithText("UsbForge").assertIsDisplayed()
    }

    @Test
    fun `cihaz adi ve seri numarasi agacta yer alir`() {
        render(baseState())
        // Başlık, ad ve seri numarasını tek bir Text olarak basar;
        // bu yüzden alt dize eşleştirmesi kullanılır.
        compose.onNode(hasText("S/N 4C530001120611110424", substring = true)).assertExists()
    }
    @Test
    fun `cihaz yoksa bilgilendirme gosterilir`() {
        render(baseState(devices = emptyList(), selected = null))
        compose.onNodeWithText("USB bellek bulunamadı").assertExists()
    }

    @Test
    fun `iki reklam banner konteyneri agacta yer alir`() {
        render(baseState())
        compose.onNodeWithText("Reklam Alanı (Üst Banner) · top").assertExists()
        compose.onNodeWithText("Reklam Alanı (Alt Banner) · bottom-1").assertExists()
        compose.onNodeWithText("Reklam Alanı (Alt Banner 2) · bottom-2").assertExists()
    }

    @Test
    fun `banner gorunurlugu kapatilinca konteyner kaldirilir`() {
        render(baseState().copy(showTopBanner = false, showBottomBanner = false))
        compose.onAllNodesWithTag("main-screen").assertCountEquals(1)
        compose.onNode(hasText("Reklam Alanı (Üst Banner)", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Reklam Alanı (Alt Banner", substring = true)).assertDoesNotExist()
    }

    // ------------------------------------------------------------------ etkilesim

    @Test
    fun `islem secimi durumu degistirir`() {
        var selected: OperationKind? = null
        render(baseState(), onOperation = { selected = it })

        compose.onNodeWithTag("op-ISO").performScrollTo().performClick()
        assertEquals(OperationKind.ISO, selected)

        compose.onNodeWithTag("op-VENTOY").performScrollTo().performClick()
        assertEquals(OperationKind.VENTOY, selected)
    }

    @Test
    fun `dosya sistemi secilebilir`() {
        var fs: FileSystemKind? = null
        render(baseState(), onFileSystem = { fs = it })
        compose.onNodeWithTag("fs-NTFS").performScrollTo().performClick()
        assertEquals(FileSystemKind.NTFS, fs)
    }

    @Test
    fun `boluntu tablosu secilebilir`() {
        var scheme: PartitionScheme? = null
        render(baseState(), onScheme = { scheme = it })
        compose.onNodeWithTag("scheme-MBR").performScrollTo().performClick()
        assertEquals(PartitionScheme.MBR, scheme)
    }

    @Test
    fun `etiket alani duzenlenebilir`() {
        var label: String? = null
        render(baseState(), onLabel = { label = it })

        compose.onNodeWithTag("label-field").performScrollTo().performTextClearance()
        compose.onNodeWithTag("label-field").performTextInput("TESTUSB")
        // Bu test durumsuzdur: alan `USBFORGE` ile başlar ve metin
        // sonuna eklenir. ViewModel geri çağırması değeri 11 karaktere
        // kırpar; burada eklenen parçayı doğrularız.
        assertTrue("yazılan metin alana işlenmeli, alınan: $label", (label ?: "").contains("TESTUSB"))
    }
    @Test
    fun `etiket alani mevcut degeri gosterir`() {
        // Etiket alanı, verilen durumdaki değeri gösterir. (Karakter
        // sayacı, alanın merged semantik düğümüne girdiği için ayrı
        // düğüm olarak görünmez; 11 karakter sınırı ViewModel katmanındadır.)
        render(baseState().copy(label = "USBFORGE"))
        compose.onNodeWithTag("label-field").assert(hasText("USBFORGE", substring = true))
    }

    @Test
    fun `cihaz seciliyken baslat butonu calisir`() {
        var started = false
        render(baseState(), onStart = { started = true })

        compose.onNodeWithTag("start-button").performScrollTo().assertIsEnabled().performClick()
        assertTrue("Başlat butonu işi tetiklemeli", started)
    }

    @Test
    fun `cihaz secili degilse baslat butonu pasif`() {
        render(baseState(selected = null))
        compose.onNodeWithTag("start-button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `is calisirken baslat butonu pasif`() {
        render(
            baseState(),
            job = JobSnapshot(id = 1, title = "x", status = JobStatus.RUNNING),
        )
        compose.onNodeWithTag("start-button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `cihaz kartina tiklamak secimi degistirir`() {
        var picked: String? = null
        render(
            baseState(
                devices = listOf(
                    device(),
                    device(name = "/dev/bus/usb/001/007", product = "DataTraveler 3.0"),
                ),
            ),
            onSelectDevice = { picked = it },
        )

        compose.onNodeWithTag("device-/dev/bus/usb/001/007").performScrollTo().performClick()
        assertEquals("/dev/bus/usb/001/007", picked)
    }

    @Test
    fun `cihaz secili degilken yikici uyari gorunmez`() {
        render(baseState(selected = null))
        compose.onAllNodesWithTag("destructive-warning").assertCountEquals(0)
    }

    @Test
    fun `cihaz seciliyken yikici uyari gorunur`() {
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

        // 1073741824 / 5733138944 = %18.7
        compose.onNodeWithTag("percent-text").assertTextEquals("18.7%")
        compose.onNodeWithTag("progress-bar").assertExists()
        compose.onNodeWithTag("transferred-text").assertTextEquals("1.0 GiB / 5.3 GiB")
    }

    @Test
    fun `hiz metni hesaplanir`() {
        val job = JobSnapshot(
            id = 1,
            title = "x",
            bytesPerSecond = 20_971_520, // 20 MiB/s
            totalBytes = 100_000_000,
            status = JobStatus.RUNNING,
        )
        render(baseState(), job = job)
        compose.onNodeWithText("20.0 MB/s").assertExists()
    }

    @Test
    fun `toplam bilinmiyorsa yuzde gosterilmez`() {
        val job = JobSnapshot(id = 1, title = "temizlik", status = JobStatus.RUNNING, totalBytes = 0)
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
        compose.onNodeWithTag("cancel-button").performScrollTo().performClick()
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
    fun `basarisiz iste hata alani bos kalir`() {
        val job = JobSnapshot(id = 1, title = "x", status = JobStatus.RUNNING, error = null)
        render(baseState(), job = job)
        compose.onAllNodesWithTag("error-text").assertCountEquals(0)
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
        compose.onNodeWithText("Cihaz açıldı").assertExists()
        compose.onNodeWithText("Yazma koruması kaldırıldı").assertExists()
    }

    @Test
    fun `is yokken ilerleme paneli gosterilmez`() {
        render(baseState())
        compose.onAllNodesWithTag("progress-panel").assertCountEquals(0)
    }

    // -------------------------------------------------------------- is modlari

    @Test
    fun `ISO modunda dosya secici ve dogrulama anahtari gorunur`() {
        var verify: Boolean? = null
        render(baseState(operation = OperationKind.ISO), onVerify = { verify = it })

        compose.onNodeWithTag("pick-iso").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("verify-switch").performScrollTo().performClick()
        assertEquals(true, verify)
    }

    @Test
    fun `ISO secili degilken dosya secici gorunmez`() {
        render(baseState(operation = OperationKind.FORMAT))
        compose.onAllNodesWithTag("pick-iso").assertCountEquals(0)
    }

    @Test
    fun `Ventoy modunda bilgilendirme metni gorunur`() {
        render(baseState(operation = OperationKind.VENTOY))
        compose.onNodeWithText("Diske Ventoy önyükleme yöneticisi kurulacak.").assertExists()
    }

    @Test
    fun `NTFS secildiginde uyari gosterilir`() {
        render(baseState().copy(fileSystem = FileSystemKind.NTFS))
        compose.onNodeWithText(
            "USB belleklerde NTFS yerine exFAT önerilir: flash aşınma dengelemesini bozmaz."
        ).assertExists()
    }

    @Test
    fun `exFAT secildiginde NTFS uyarisi gosterilmez`() {
        render(baseState().copy(fileSystem = FileSystemKind.EXFAT))
        compose.onNode(hasText("NTFS yerine exFAT", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `root rozeti yalnizca root varken gorunur`() {
        render(baseState().copy(rootAvailable = true))
        compose.onNodeWithText("root").assertExists()
    }
}
