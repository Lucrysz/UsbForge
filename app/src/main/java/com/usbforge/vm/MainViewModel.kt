package com.usbforge.vm

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.usbforge.core.block.BlockDevice
import com.usbforge.core.block.BlockWriteCancelled
import com.usbforge.core.block.RootBlockDevice
import com.usbforge.core.block.RootShellProvider
import com.usbforge.core.block.ScsiBlockDevice
import com.usbforge.core.disk.CancelledByUser
import com.usbforge.core.disk.DiskWriter
import com.usbforge.core.disk.IsoWriter
import com.usbforge.core.partition.FileSystemKind
import com.usbforge.core.partition.PartitionPlan
import com.usbforge.core.partition.PartitionScheme
import com.usbforge.core.util.Bytes
import com.usbforge.core.ventoy.VentoyInstaller
import com.usbforge.engine.JobEngine
import com.usbforge.engine.JobSnapshot
import com.usbforge.engine.JobStatus
import com.usbforge.usb.LogLevel
import com.usbforge.usb.UsbMassStorageController
import com.usbforge.usb.UsbStorageDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Kullanıcının seçtiği iş tipi. */
enum class OperationKind(val label: String) {
    FORMAT("USB Biçimlendir"),
    VENTOY("Ventoy Kur"),
    ISO("ISO / IMG Yaz"),
}

/** Cihaz listesindeki tek bir kayıt ve bağlantı durumu. */
data class DeviceRow(
    val device: UsbStorageDevice,
    val connected: Boolean,
    val opening: Boolean = false,
    val error: String? = null,
)

/** Ana ekranın tüm durumu. Tek kaynak (single source of truth). */
data class UiState(
    val scanning: Boolean = false,
    val devices: List<DeviceRow> = emptyList(),
    val selected: String? = null,
    val operation: OperationKind = OperationKind.FORMAT,
    val fileSystem: FileSystemKind = FileSystemKind.EXFAT,
    val scheme: PartitionScheme = PartitionScheme.GPT,
    val label: String = "USBFORGE",
    val isoUri: Uri? = null,
    val isoName: String? = null,
    val isoSize: Long = 0L,
    val verifyAfterWrite: Boolean = false,
    val showTopBanner: Boolean = true,
    val showBottomBanner: Boolean = true,
    val rootAvailable: Boolean = false,
    val message: String? = null,
) {
    val selectedRow: DeviceRow? get() = devices.firstOrNull { it.device.deviceName == selected }
    val canStart: Boolean get() = selected != null && !openingBusy
    val openingBusy: Boolean get() = devices.any { it.opening }
    val hasIso: Boolean get() = isoUri != null
}

/**
 * Ekran durumunu ve tüm I/O işlerini yönetir.
 *
 * ## Sorumluluk sınırı
 * - Cihaz taraması ve USB izni burada yapılır.
 * - Uzun süren yazma işleri [JobEngine] içinde `Dispatchers.IO`'da çalışır;
 *  ekran döngüsü hiçbir zaman bloklanmaz.
 * - [BlockDevice] açılması `openDevice` ile yapılır ve iş boyunca açık
 *  tutulur; her iş sonunda kapatılır.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val jobEngine = JobEngine(viewModelScope)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val jobState: StateFlow<JobSnapshot> = jobEngine.state

    init {
        checkRoot()
    }

    // ------------------------------------------------------------- cihaz yönetimi

    /** USB cihazlarını tarar; yoksa root altındaki blok cihazları listeler. */
    fun scanDevices() {
        _state.update { it.copy(scanning = true, message = null) }
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { enumerate() }
            _state.update { current ->
                val selected = current.selected
                    ?.takeIf { name -> rows.any { it.device.deviceName == name } }
                    ?: rows.firstOrNull()?.device?.deviceName
                current.copy(
                    scanning = false,
                    devices = rows,
                    selected = selected,
                    rootAvailable = current.rootAvailable,
                )
            }
        }
    }

    private fun enumerate(): List<DeviceRow> {
        val context = getApplication<Application>()
        val usb = runCatching {
            UsbMassStorageController.enumerate(context).map { usbDevice ->
                val manager = context.getSystemService(Context.USB_SERVICE)
                        as? android.hardware.usb.UsbManager
                val hasPermission = manager?.hasPermission(usbDevice) == true
                DeviceRow(
                    device = describe(context, usbDevice),
                    connected = true,
                    error = if (hasPermission) null else "Erişim izni bekleniyor",
                )
            }
        }.getOrDefault(emptyList())

        if (usb.isNotEmpty()) return usb

        // Root varsa /dev/block listesini göster
        if (RootShellProvider.isRooted) {
            return runCatching {
                RootBlockDevice.listBlockDevices().map { path ->
                    DeviceRow(
                        device = UsbStorageDevice(
                            deviceName = File(path).name,
                            vendorId = 0,
                            productId = 0,
                            manufacturer = "root",
                            product = File(path).name,
                            serial = "",
                            usbVersion = 0f,
                            sectorSize = 512,
                            totalSectors = 0,
                        ),
                        connected = File(path).exists(),
                        error = null,
                    )
                }
            }.getOrDefault(emptyList())
        }
        return emptyList()
    }

    /** USB cihazı açar ve kapasitesini okur. */
    private fun describe(context: Context, usbDevice: android.hardware.usb.UsbDevice): UsbStorageDevice {
        val controller = runBlockingQuiet { UsbMassStorageController.open(context, usbDevice) }
        if (controller == null) {
            return UsbStorageDevice(
                deviceName = usbDevice.deviceName,
                vendorId = usbDevice.vendorId,
                productId = usbDevice.productId,
                manufacturer = "",
                product = usbDevice.productName ?: usbDevice.deviceName,
                serial = usbDevice.serialNumber ?: "",
                usbVersion = usbDevice.version / 1000f,
                sectorSize = 512,
                totalSectors = 0,
            )
        }
        return controller.let {
            val result = UsbStorageDevice(
                deviceName = usbDevice.deviceName,
                vendorId = usbDevice.vendorId,
                productId = usbDevice.productId,
                manufacturer = it.inquiry.vendor,
                product = it.inquiry.product.ifBlank { usbDevice.productName ?: usbDevice.deviceName },
                serial = it.inquiry.serial.ifBlank { usbDevice.serialNumber ?: "" },
                usbVersion = usbDevice.version / 1000f,
                sectorSize = it.capacity.sectorSize,
                totalSectors = it.capacity.totalSectors,
            )
            it.close()
            result
        }
    }

    private suspend fun <T> runBlockingQuiet(block: suspend () -> T): T? =
        runCatching { block() }.getOrNull()

    fun checkRoot() {
        viewModelScope.launch {
            val rooted = withContext(Dispatchers.IO) { RootShellProvider.isRooted }
            _state.update { it.copy(rootAvailable = rooted) }
        }
    }

    fun selectDevice(name: String) {
        _state.update { it.copy(selected = name, message = null) }
    }

    // ------------------------------------------------------------- ayar değişimleri

    fun setOperation(kind: OperationKind) = _state.update { it.copy(operation = kind, message = null) }
    fun setFileSystem(kind: FileSystemKind) = _state.update { it.copy(fileSystem = kind) }
    fun setScheme(scheme: PartitionScheme) = _state.update { it.copy(scheme = scheme) }
    fun setLabel(label: String) = _state.update { it.copy(label = label.uppercase().take(11)) }
    fun setVerify(enabled: Boolean) = _state.update { it.copy(verifyAfterWrite = enabled) }
    fun setTopBannerVisible(visible: Boolean) = _state.update { it.copy(showTopBanner = visible) }
    fun setBottomBannerVisible(visible: Boolean) = _state.update { it.copy(showBottomBanner = visible) }
    fun dismissMessage() = _state.update { it.copy(message = null) }
    fun resetJob() = jobEngine.reset()

    /** Kullanıcının seçtiği ISO/IMG dosyasını uygulama önbelleğine kopyalar. */
    fun selectIso(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(message = "ISO önbelleğe alınıyor…") }
            val result = withContext(Dispatchers.IO) { copyToCache(uri) }
            result.onSuccess { (file, size) ->
                _state.update {
                    it.copy(
                        isoUri = uri,
                        isoName = file.name,
                        isoSize = size,
                        message = "Hazır: ${file.name} (${Bytes.human(size)})",
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(message = "Dosya okunamadı: ${e.message}") }
            }
        }
    }

    /** `content://` URI'sini uygulama önbelleğine kopyalar (sabit blok akışı). */
    private fun copyToCache(uri: Uri): Result<Pair<File, Long>> = runCatching {
        val context = getApplication<Application>()
        val name = queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "image.iso"
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(context.cacheDir, safe)

        val resolver = context.contentResolver
        var total = 0L
        val buf = ByteArray(1024 * 1024)
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Dosya açılamadı." }
            target.outputStream().use { out ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                out.flush()
            }
        }
        target to total
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    // ---------------------------------------------------------------- iş başlatma

    /** Seçili işi başlatır. Zaten bir iş sürüyorsa yok sayılır. */
    fun start() {
        val snapshot = _state.value
        val row = snapshot.selectedRow ?: run {
            _state.update { it.copy(message = "Önce bir cihaz seçin.") }
            return
        }
        if (jobEngine.isBusy) return

        when (snapshot.operation) {
            OperationKind.FORMAT -> startFormat(row)
            OperationKind.VENTOY -> startVentoy(row)
            OperationKind.ISO -> startIso(row)
        }
    }

    private fun startFormat(row: DeviceRow) {
        jobEngine.start("${row.device.displayName} biçimlendiriliyor") { progress ->
            progress.log("Cihaz: ${row.device.deviceName} · ${Bytes.human(row.device.sizeBytes)}")
            withBlockDevice(row) { device ->
                val plan = PartitionPlan.singlePartition(
                    diskSectors = device.totalSectors,
                    fsKind = snapshot().fileSystem,
                    scheme = snapshot().scheme,
                )
                val summary = DiskWriter().writeAndFormat(
                    device = device,
                    plan = plan,
                    progress = progress,
                    label = snapshot().label,
                )
                progress.log(summary, LogLevel.INFO)
                progress.setPhase("Tamamlandı")
            }
        }
    }

    private fun startVentoy(row: DeviceRow) {
        jobEngine.start("Ventoy kuruluyor — ${row.device.displayName}") { progress ->
            withBlockDevice(row) { device ->
                val installer = VentoyInstaller(progress)
                val summary = installer.install(device)
                progress.log(summary, LogLevel.INFO)
                progress.setPhase("Tamamlandı")
            }
        }
    }

    private fun startIso(row: DeviceRow) {
        val isoName = snapshot().isoName
        val size = snapshot().isoSize
        val verify = snapshot().verifyAfterWrite
        jobEngine.start("ISO yazılıyor — $isoName") { progress ->
            val context = getApplication<Application>()
            val file = isoName?.let { File(context.cacheDir, it) }
                ?: throw IllegalStateException("ISO dosyası seçilmedi.")
            // Cihaz, yazma tamamlanana kadar açık kalmalı; bu yüzden işin
            // tamamı withBlockDevice bloğunun içinde yürütülür.
            withBlockDevice(row) { device ->
                progress.log("Kaynak: ${file.name} (${Bytes.human(size)})")
                IsoWriter(
                    sectorsOverride = -1,
                    zeroRemainder = true,
                    verify = verify,
                ).write(
                    device = device,
                    openSource = { file.inputStream().buffered(1024 * 1024) },
                    totalBytes = size,
                    progress = progress,
                )
            }
        }
    }

    /**
     * Seçili cihazı açar, işi `Dispatchers.IO` üzerinde çalıştırır ve
     * cihazı kapatır.
     */
    private suspend fun withBlockDevice(row: DeviceRow, block: suspend (BlockDevice) -> Unit) {
        val context = getApplication<Application>()
        markOpening(row.device.deviceName, true)
        var device: BlockDevice? = null
        try {
            device = withContext(Dispatchers.IO) { openFor(context, row) }
            withContext(Dispatchers.IO) { block(device) }
        } finally {
            runCatching { withContext(Dispatchers.IO) { device?.close() } }
            markOpening(row.device.deviceName, false)
        }
    }

    private fun openFor(context: Context, row: DeviceRow): BlockDevice {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val usbDevice = usbManager.deviceList[row.device.deviceName]
        if (usbDevice != null) {
            val controller = UsbMassStorageController.openBlocking(context, usbDevice)
            return ScsiBlockDevice(controller)
        }
        return RootBlockDevice.open("/dev/block/${row.device.deviceName}")
    }

    private fun markOpening(name: String, opening: Boolean) {
        _state.update { s ->
            s.copy(devices = s.devices.map { if (it.device.deviceName == name) it.copy(opening = opening) else it })
        }
    }

    private fun snapshot(): UiState = _state.value

    /** İş iptal edildiğinde çağrılır. */
    fun cancel() = jobEngine.cancel()

    /** USB cihaz takılma intent'ini işler. */
    fun onUsbAttached(intent: Intent) {
        if (intent.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
        }
        if (device != null) {
            _state.update { it.copy(selected = device.deviceName) }
            scanDevices()
        }
    }

    /** Kısa bilgi mesajı gösterir (test ve hata durumları için). */
    fun notify(message: String) = _state.update { it.copy(message = message) }

    /** İş durumu yardımcıları. */
    val isBusy: Boolean get() = jobEngine.state.value.status == JobStatus.RUNNING
}
