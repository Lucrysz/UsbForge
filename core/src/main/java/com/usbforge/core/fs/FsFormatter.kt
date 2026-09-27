package com.usbforge.core.fs

import com.usbforge.core.block.BlockDevice
import com.usbforge.core.engine.ProgressReporter

/**
 * Bir bölüntüyü boş bir dosya sistemiyle biçimlendiren bileşen sözleşmesi.
 *
 * ## Sözleşme
 * - Tüm yazma işlemleri `Dispatchers.IO` üzerinde çalışır.
 * - [format] çağrıldığında bölüntünün tamamı geçerli bir dosya sistemi
 *   yapısıyla doldurulmuş olur (boş ama bağlanabilir).
 * - Biçimlendirme **yıkıcıdır**: bölüntüdeki tüm veriler yok olur.
 */
interface FsFormatter {
    /** Biçimlendirilecek bölüntünün diske göre başlangıç LBA'sı. */
    val startLba: Long

    /** Bölüntünün uzunluğu (sektör). */
    val sectors: Long

    /** Biçimlendirilecek bölüntünün toplam bayt boyutu. */
    val sizeBytes: Long get() = sectors * 512

    /** Biçimlendirme sonrasında kullanıcıya gösterilecek özet. */
    suspend fun format(device: BlockDevice, progress: ProgressReporter): FormatResult
}

data class FormatResult(
    val fileSystem: String,
    val clusterSizeBytes: Int,
    val totalClusters: Long,
    val label: String,
    val notes: List<String> = emptyList(),
)

/** Biçimleyicilerin ortak kullandığı matematik yardımcıları. */
object FsMath {

    const val SECTOR = 512

    /** Yukarı yuvarlar (bölüntülenebilir). */
    fun divUp(a: Long, b: Long): Long = (a + b - 1) / b

    /** Yukarı yuvarlar (512 bayta). */
    fun ceilSectors(bytes: Long): Long = divUp(bytes, SECTOR.toLong())

    /** 2'nin kuvveti mi? */
    fun isPowerOfTwo(v: Long): Boolean = v > 0 && (v and (v - 1)) == 0L

    /** 1, 2, 4, 8 ... 2^31 arası bir sektör/küme boyutu seçer. */
    fun pow2(limit: Int): Int {
        var v = 1
        while (v < limit) v = v shl 1
        return v
    }

    /**
     * exFAT / FAT32 tarafından kullanılan büyük ölçekli biçimlendirme
     * (Windows'un "Format Quick" seçeneği) varsayılanı: ilk bölümün
     * tamamını tek seferde tek sektör yazmalarıyla doldurmak yerine
     * FAT/dizin tablolarını sıfırlayıp veri alanına dokunmamak.
     *
     * Flash bellekte bu yaklaşım hem hızlı hem de ömür dostudur.
     */
    fun zeroFill(device: BlockDevice, startLba: Long, sectors: Long, progress: ProgressReporter) {
        val buf = ByteArray(1024 * 1024)
        var lba = startLba
        var remaining = sectors
        while (remaining > 0) {
            val n = minOf(remaining, buf.size / SECTOR.toLong()).toInt()
            val len = n * SECTOR
            device.write(lba, buf, 0, len) { progress.addWritten(len.toLong()) }
            lba += n
            remaining -= n
        }
    }
}
