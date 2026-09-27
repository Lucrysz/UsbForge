package com.usbforge.core.block

import com.usbforge.core.util.Bytes
import java.util.TreeMap

/**
 * Testler için bellek içi blok cihaz.
 *
 * Bu sınıf **üretim kodunda kullanılmaz**; yalnızca `:core` testlerinin
 * biçimlendiricileri gerçek bir cihaz olmadan çalıştırmasını sağlar.
 *
 * ## Seyrek depolama
 * Disk içeriği sektör sektör `[sektör no → 512 bayt]` olarak saklanır.
 * Böylece 512 MiB'lık bir sahte disk yalnızca **yazılan** sektörler için
 * bellek harcar (Ventoy testi ~2 MiB yazar), `java.io` ya da `ByteArray`
 * sınırına takılmaz. `Int.MAX_VALUE` bayt = 2 TiB'a kadar sahte disk
 * desteklenir.
 *
 * ## Arıza benzetimi
 * [failNextWrite] ile USB kablosu kopması gibi hatalar taklit edilir.
 */
class FakeBlockDevice(
    override val totalSectors: Long,
    override val displayName: String = "FAKE-USB",
) : BlockDevice {

    private val sectors = TreeMap<Long, ByteArray>()

    /** Yazılan toplam bayt. */
    var bytesWritten: Long = 0
        private set

    /** Toplam write() çağrısı sayısı. */
    var writeCalls: Int = 0
        private set

    /** Her write() çağrısının LBA'sı (yazma sırasını doğrulamak için). */
    val writeLog = mutableListOf<Long>()

    /** true ise bir sonraki write() çağrısı hata fırlatır. */
    var failNextWrite: Boolean = false

    /** Ayrılan bellek miktarı (test tanılama çıktısı için). */
    val allocatedSectors: Int get() = sectors.size

    var prepareCalled: Boolean = false
        private set

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
        require(length > 0) { "Yazma uzunluğu sıfır olamaz." }
        require(startLba >= 0) { "Geçersiz LBA: $startLba" }
        require(startLba * 512 + length <= totalSectors * 512) {
            "Yazma sahte diskin dışında: LBA $startLba + $length bayt > ${totalSectors * 512} bayt"
        }

        // Bayt bazlı yazma: sektör sınırlarıyla hizalanmamış uzunlukları da
        // doğru karşılar (BlockDevice sözleşmesi bayt cinsinden uzunluk ister).
        var done = 0
        while (done < length) {
            val absolute = startLba * 512 + done
            val lba = absolute / 512
            val within = (absolute % 512).toInt()
            val n = minOf(512 - within, length - done)
            val target = sectors.getOrPut(lba) { ByteArray(512) }
            System.arraycopy(src, srcOffset + done, target, within, n)
            done += n
        }
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
        require(startLba >= 0 && length >= 0) { "Geçersiz okuma aralığı." }
        require(startLba * 512 + length <= totalSectors * 512) {
            "Okuma sahte disk dışında: LBA $startLba + $length bayt"
        }
        require(dstOffset + length <= dst.size) { "Hedef tampon yetersiz." }

        // Bayt bazlı okuma (bkz. write).
        var done = 0
        while (done < length) {
            val absolute = startLba * 512 + done
            val lba = absolute / 512
            val within = (absolute % 512).toInt()
            val n = minOf(512 - within, length - done)
            val content = sectors[lba]
            if (content != null) {
                System.arraycopy(content, within, dst, dstOffset + done, n)
            } else {
                java.util.Arrays.fill(dst, dstOffset + done, dstOffset + done + n, 0.toByte())
            }
            done += n
        }
    }

    override fun healthHint(): String = "sahte · ${Bytes.human(totalSectors * 512)} · " +
            "${allocatedSectors} sektör ayrıldı"

    override fun flush() {
        flushCount++
    }

    override fun close() {}

    // ------------------------------------------------------------ test yardımcıları

    /** [lba] sektörünün 512 baytı (yoksa sıfırlar). */
    fun sector(lba: Long): ByteArray = sectors[lba] ?: ByteArray(512)

    /** Belirtilen ofsetten [length] bayt okur. */
    fun sectorAt(lba: Long, offsetInSector: Int, length: Int = 512 - offsetInSector): ByteArray {
        val b = ByteArray(length)
        read(lba * 512 + offsetInSector, length, b)
        return b
    }

    /** [fromLba, fromLba + sectorCount) aralığı tamamen sıfır mı? */
    fun isZeroed(fromLba: Long, sectorCount: Long): Boolean {
        for (lba in fromLba until fromLba + sectorCount) {
            val content = sectors[lba] ?: continue
            for (b in content) if (b.toInt() != 0) return false
        }
        return true
    }
}
