package com.usbforge.usb

/**
 * Bir USB Mass Storage cihazının tespit edilmiş bilgileri.
 *
 * [sectorSize] her zaman 512'e normalize edilmiştir; cihaz 4096 baytlık
 * mantıksal blok bildirse bile tüm LBA hesapları 512 baytlık sektör
 * tabanlıdır (GPT, MBR, dosya sistemi matematiği böyle varsayar).
 */
data class UsbStorageDevice(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String,
    val product: String,
    val serial: String,
    val usbVersion: Float,
    val sectorSize: Int,
    val totalSectors: Long,
    val isReadOnly: Boolean = false,
    val removable: Boolean = true,
) {
    val sizeBytes: Long get() = totalSectors * sectorSize
    val sizeMiB: Long get() = sizeBytes / (1024L * 1024L)
    val sizeGiB: Double get() = sizeBytes / (1024.0 * 1024.0 * 1024.0)
    val isHuge: Boolean get() = totalSectors > 0x0FFFFFFF1L // 2 TiB sınırı (READ(10))

    /** Mantıksal blok boyutına normalize edilmiş LBA aralığı. */
    val lastLba: Long get() = totalSectors - 1

    fun summary(): String = buildString {
        append(product.trim().ifEmpty { "USB Storage" })
        if (serial.isNotBlank()) append("  ·  S/N ").append(serial)
    }
}

/** Denetleyici tarafından döndürülen kapasite bilgisi. */
data class DiskCapacity(
    val sectorSize: Int,
    val totalSectors: Long,
) {
    val sizeBytes: Long get() = totalSectors * sectorSize
}

/** Cihaz üzerinde yapılan her blok erişiminde çağrılan ilerleme geri çağırımı. */
fun interface ByteProgress {
    fun onBytes(bytes: Long)
}

/** Cihaz hazırlığı sırasında üretilen olaylar (UI log satırı olarak gösterilir). */
data class UsbLogLine(val level: LogLevel, val message: String)

enum class LogLevel { INFO, WARN, ERROR }
