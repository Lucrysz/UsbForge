package com.usbforge.usb

import com.usbforge.core.util.Bytes

/**
 * Bir USB Mass Storage cihazının tespit edilmiş bilgileri.
 *
 * [sectorSize] her zaman 512'e normalize edilmiştir; cihaz 4096 baytlık
 * mantıksal blok bildirse bile tüm LBA hesapları 512 baytlık sektör
 * tabanlıdır (GPT, MBR ve dosya sistemi matematiği böyle varsayar).
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
    val removable: Boolean = true,
) {
    val sizeBytes: Long get() = totalSectors * sectorSize
    val sizeGiB: Double get() = sizeBytes / (1024.0 * 1024.0 * 1024.0)

    /** READ(10) sınırını aşan cihaz (2 TiB üstü) — READ(16) gerekir. */
    val needs16ByteCommands: Boolean get() = totalSectors > 0x0FFFFFFF1L

    /** Kısa görünen ad (arayüzde). */
    val displayName: String
        get() = product.trim().ifEmpty { deviceName }

    /** Üst satır: ad + seri no. */
    fun titleLine(): String = buildString {
        append(displayName)
        if (serial.isNotBlank()) append("  ·  S/N ").append(serial)
    }

    /** Alt satır: boyut + bağlantı detayı. */
    fun subtitleLine(): String = buildString {
        append(Bytes.human(sizeBytes))
        if (needs16ByteCommands) append("  ·  2 TiB+ (READ16)")
        append("  ·  ").append(deviceName)
    }
}
