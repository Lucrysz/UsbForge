package com.usbforge.block

import com.usbforge.core.block.BlockAccessException
import com.usbforge.core.block.BlockDevice
import com.usbforge.core.block.BlockWriteCancelled
import com.usbforge.usb.ScsiTransferCancelled
import com.usbforge.usb.UsbMassStorageController

/**
 * [BlockDevice] implementasyonu; arka planda USB Mass Storage (BOT/SCSI)
 * kontrolcüsü var.
 */
class ScsiBlockDevice(
    private val controller: UsbMassStorageController,
) : BlockDevice {

    override val displayName: String =
        controller.inquiry.product.trim().ifEmpty { controller.usbDevice.deviceName }

    override val totalSectors: Long get() = controller.totalBlocks

    override val writable: Boolean = true

    private var prepared = false

    override fun prepareForWrite() {
        if (prepared) return
        controller.prepareForWrite()
        prepared = true
    }

    override fun write(
        startLba: Long,
        src: ByteArray,
        srcOffset: Int,
        length: Int,
        isCancelled: () -> Boolean,
        onChunk: ((bytes: Long) -> Unit)?,
    ) {
        prepareForWrite()
        try {
            controller.write(startLba, src, srcOffset, length, isCancelled, onChunk)
        } catch (c: ScsiTransferCancelled) {
            throw BlockWriteCancelled(c.message ?: "Yazma kullanıcı tarafından durduruldu.")
        } catch (t: Throwable) {
            throw BlockAccessException(t.message ?: "SCSI yazma hatası", t)
        }
    }

    override fun read(
        startLba: Long,
        length: Int,
        dst: ByteArray,
        dstOffset: Int,
        isCancelled: () -> Boolean,
    ) {
        val sectors = length / SECTOR_SIZE
        require(sectors * SECTOR_SIZE == length) { "Okuma uzunluğu 512'nin katı olmalı, $length verildi." }
        try {
            controller.read(startLba, sectors, dst, dstOffset)
        } catch (t: Throwable) {
            throw BlockAccessException(t.message ?: "SCSI okuma hatası", t)
        }
    }

    override fun healthHint(): String? {
        val inq = controller.inquiry
        val parts = listOfNotNull(
            inq.vendor.trim().ifEmpty { null },
            inq.revision.trim().ifEmpty { null },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    companion object {
        /** Tüm LBA hesapları bu sabite göre yapılır. */
        const val SECTOR_SIZE = 512
    }

    override fun flush() {
        controller.flush()
    }

    override fun close() = controller.close()
}
