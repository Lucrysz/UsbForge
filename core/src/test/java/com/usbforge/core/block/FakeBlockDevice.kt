package com.usbforge.core.block

import com.usbforge.core.util.Bytes

/**
 * Testler için bellek içi blok cihaz.
 *
 * Bu sınıf **üretim kodunda kullanılmaz**; yalnızca `:core` testlerinin
 * biçimlendiricileri gerçek bir cihaz olmadan çalıştırmasını sağlar.
 * Rastgele hata benzetimi (fault injection) ile USB kablosu kopması gibi
 * arıza senaryoları da taklit edilebilir.
 *
 * @param sectors cihaz kapasitesi (512 bayt/sektör)
 */
class FakeBlockDevice(
    override val totalSectors: Long,
    override val displayName: String = "FAKE-USB",
) : BlockDevice {

    private val data = ByteArray(totalSectors * 512)

    /** Yazılan toplam bayt (ilerleme testleri için). */
    var bytesWritten: Long = 0
        private set

    /** Toplam yazma çağrısı sayısı. */
    var writeCalls: Int = 0
        private set

    /** Her write() çağrısının LBA'sı (yazma sırasını doğrulamak için). */
    val writeLog = mutableListOf<Long>()

    /** true ise bir sonraki write() çağrısı hata fırlatır. */
    var failNextWrite: Boolean = false

    /** Cihazın yazmaya hazır olup olmadığı. */
    var prepareCalled: Boolean = false
        private set

    /** flush() kaç kez çağrıldı. */
    var flushCount: Int = 0
        private set

    override val writable: Boolean = true

    override fun prepareForWrite() {
        prepareCalled = true
    }

    override fun write(
        startLba: Long,
        src: ByteArray,
        srcOffset: Int,
        length: Int,
        isCancelled: () -> Boolean,
        onChunk: ((bytes: Long) -> Unit)?,
    ) {
        if (isCancelled()) throw BlockWriteCancelled()
        if (failNextWrite) {
            failNextWrite = false
            throw BlockAccessException("Simüle edilmiş yazma hatası (kablo koptu).")
        }
        require(length % 512 == 0) { "Yazma uzunluğu 512'nin katı olmalı, $length verildi." }
        val startByte = startLba * 512
        require(startByte + length <= data.size) {
            "LBA aralığı sahte diskin dışında: ${startLba}+${length / 512} > $totalSectors"
        }
        System.arraycopy(src, srcOffset, data, startByte.toInt(), length)
        bytesWritten += length
        writeCalls++
        writeLog += startLba
        onChunk?.invoke(length.toLong())
    }

    override fun read(
        startLba: Long,
        length: Int,
        dst: ByteArray,
        dstOffset: Int,
        isCancelled: () -> Boolean,
    ) {
        val startByte = startLba * 512
        require(startByte + length <= data.size) { "Okuma sahte disk dışında." }
        System.arraycopy(data, startByte.toInt(), dst, dstOffset, length)
    }

    override fun healthHint(): String = "sahte · ${Bytes.human(totalSectors * 512)}"

    override fun flush() {
        flushCount++
    }

    override fun close() {}

    // ------------------------------------------------------------ test yardımcıları

    /** Belirtilen sektörden 512 bayt okur. */
    fun sector(lba: Long): ByteArray {
        val b = ByteArray(512)
        read(lba, 512, b)
        return b
    }

    /** Belirtilen ofsetten 512 bayt okur. */
    fun sectorAt(lba: Long, offsetInSector: Int, length: Int = 512 - offsetInSector): ByteArray {
        val b = ByteArray(length)
        read(lba * 512 + offsetInSector, length, b)
        return b
    }

    /** Disk içeriğinin tamamını döndürür (doğrulama için). */
    fun snapshot(): ByteArray = data.copyOf()

    /** Belirtilen LBA aralığının tamamı sıfır mı? */
    fun isZeroed(fromLba: Long, sectors: Long): Boolean {
        val from = (fromLba * 512).toInt()
        val to = ((fromLba + sectors) * 512).toInt()
        for (i in from until to) if (data[i].toInt() != 0) return false
        return true
    }
}
