package com.usbforge.core.fs

import com.usbforge.core.block.BlockDevice
import com.usbforge.core.engine.ProgressReporter
import java.util.Calendar
import java.util.TimeZone

/**
 * FAT32 biçimlendirici.
 *
 * ## Üretilen yapı
 * ```
 * +0        önyükleme kaydı (jump + BPB + Microsoft bootstrap kodu)
 * +1        FSInfo (boş küme sayacı)
 * +2..5     yedek önyükleme kaydı
 * +6..31    2. önyükleme kaydı yedeği (sector 6) + yedek FSInfo (7)
 * +32       FAT #1
 * +32+fatSz FAT #2
 * +data     veri bölümü: kök dizin (küme 2) ve dosya verisi
 * ```
 *
 * ## Biçimlendirme stratejisi
 * "Hızlı biçimlendirme" uygulanır: yalnızca önyükleme kaydı, FSInfo,
 * yedek önyükleme ve iki FAT tablosu sıfırlanır; veri alanına dokunulmaz.
 * Bu, 1) saniyeler yerine saniyeler mertebesinde sürme sağlar, 2) flash
 * bellekte aşınma yazma döngüsü üretmez, 3) eski veri blokları flash
 * denetleyicisinin kendi temizleme/wear-leveling mantığına bırakılır.
 *
 * ## Önyükleme kaydı kodu
 * 0x5A adresindeki bootstrap dizisi, önyükleme sırasında 15 sektörü
 * 0x7C00'e kopyalayıp oraya atlar (Microsoft'un genel amaçlı FAT32
 * önyükleme kaydıyla aynıdır). Biçimlendirilen bölüm veri bölümü
 * olduğundan bu kod normalde çalıştırılmaz.
 */
class Fat32Formatter(
    override val startLba: Long,
    override val sectors: Long,
    private val label: String = "USBFORGE",
    private val volumeSerial: Long = System.currentTimeMillis(),
) : FsFormatter {

    private lateinit var geo: Fat32Geometry

    override suspend fun format(device: BlockDevice, progress: ProgressReporter): FormatResult {
        require(sectors >= 33L * 1024) {
            "FAT32 için en az 33 MiB gerekir (verilen: ${sectors * 512 / (1024 * 1024)} MiB)."
        }

        val g = Fat32Geometry.compute(sectors)
        geo = g

        progress.setPhase("FAT32 önyükleme kaydı hazırlanıyor")
        val now = DosTime.now()

        val boot = buildBootSector(g, label, volumeSerial)
        val fsInfo = buildFsInfo(g)
        val backupBoot = buildBootSector(g, label, volumeSerial)
        val backupFsInfo = buildFsInfo(g)

        val firstSector = ByteArray(512 * 8) // 4 sektörlük metadata bloğu
        System.arraycopy(boot, 0, firstSector, 0, 512)
        System.arraycopy(fsInfo, 0, firstSector, 512, 512)
        System.arraycopy(boot, 0, firstSector, 4 * 512, 512) // 6. sektör: yedek önyükleme
        System.arraycopy(fsInfo, 0, firstSector, 5 * 512, 512) // 7. sektör: yedek FSInfo

        progress.setPhase("FAT32 önyükleme kaydı yazılıyor")
        device.write(startLba, firstSector) { progress.addWritten(it) }

        // FAT tablolarını sıfırla. 0xFFFFFFF8 (medya + EOC) ve 0xFFFFFFFF yazılır.
        progress.setPhase("FAT tabloları hazırlanıyor (${g.fatSectors * 512 / 1024} KiB × 2)")
        val fatSectors = g.fatSectors
        val fatBuffer = ByteArray(512 * 256) // 128 KiB'lik parçalar halinde
        val firstSectorOfFat = g.reservedSectors
        var offsetSectors = 0L
        while (offsetSectors < fatSectors) {
            val n = minOf(256L, fatSectors - offsetSectors).toInt()
            val len = n * 512
            java.util.Arrays.fill(fatBuffer, 0, len, 0.toByte())
            // 0. ve 1. küme özel değerler
            if (offsetSectors == 0L) {
                putLe32(fatBuffer, 0, 0xFFFFFFF8L) // küme 0: medya + EOC
                putLe32(fatBuffer, 4, 0xFFFFFFFFL) // küme 1: rezerve
            }
            device.write(startLba + firstSectorOfFat + offsetSectors, fatBuffer, 0, len) {
                progress.addWritten(it)
            }
            offsetSectors += n
        }

        // İkinci FAT'ı kopyala (yedek güvenlik).
        progress.setPhase("FAT #2 kopyalanıyor")
        var copied = 0L
        while (copied < fatSectors) {
            val n = minOf(256L, fatSectors - copied).toInt()
            val buf = ByteArray(n * 512)
            device.read(startLba + firstSectorOfFat + copied, buf.size, buf)
            device.write(startLba + firstSectorOfFat + fatSectors + copied, buf) { progress.addWritten(it) }
            copied += n
        }

        // Kök dizin kümesini işaretle (küme 2 sonu, işaretçisiz).
        val rootFatOffset = ((g.rootCluster - 2) * 4)
        val fatSectorInCopy = rootFatOffset / 512
        val fatSector = ByteArray(512)
        device.read(startLba + firstSectorOfFat + fatSectorInCopy, 512, fatSector)
        putLe32(fatSector, (rootFatOffset % 512).toInt(), 0xFFFFFFFFL)
        device.write(startLba + firstSectorOfFat + fatSectorInCopy, fatSector) { progress.addWritten(it) }
        device.write(startLba + firstSectorOfFat + fatSectors + fatSectorInCopy, fatSector) {
            progress.addWritten(it)
        }

        // Kök dizinin ilk sektörü: yalnızca birim etiketi girdisi.
        progress.setPhase("Kök dizin oluşturuluyor")
        val rootDir = ByteArray(512)
        writeShortEntry(
            dir = rootDir,
            offset = 0,
            name = label.toFatLabel(),
            attr = ATTR_VOLUME_ID,
            firstCluster = 0,
            size = 0,
        )
        device.write(startLba + g.dataSectorOf(g.rootCluster), rootDir) { progress.addWritten(it) }

        device.flush()

        return FormatResult(
            fileSystem = "FAT32",
            clusterSizeBytes = g.clusterSize,
            totalClusters = g.clusterCount.toLong(),
            label = label,
            notes = listOf(
                "Küme boyutu: ${g.clusterSize / 1024} KiB",
                "Kök dizin: ${g.rootDirSectors} sektör (küme ${g.rootCluster})",
                "Veri başlangıcı: LBA ${g.firstDataSector}",
                "Küme #1,2 (rezerve/metadata) doğrulanmamış olarak işaretlendi",
            ),
        )
    }

    /** Biçimlendirme sonrası dosya eklemek için yazıcı üretir. */
    fun newWriter(device: BlockDevice, progress: ProgressReporter): Fat32Writer {
        check(::geo.isInitialized) { "Önce format() çağrılmalı." }
        return Fat32Writer(device, geo, progress, startLba)
    }

    companion object {
        const val ATTR_READ_ONLY = 0x01
        const val ATTR_HIDDEN = 0x02
        const val ATTR_SYSTEM = 0x04
        const val ATTR_VOLUME_ID = 0x08
        const val ATTR_DIRECTORY = 0x10
        const val ATTR_ARCHIVE = 0x20
        const val ATTR_LFN = 0x0F

        /**
         * Önyükleme kaydı (VBR) üretir: 3 bayt jump + 8 bayt OEM + 79 bayt
         * BPB (0x0B..0x59) + Microsoft bootstrap kodu (0x5A..0x71).
         */
        fun buildBootSector(g: Fat32Geometry, label: String, serial: Long): ByteArray {
            val b = ByteArray(512)

            // --- 0x00: korunmuş alan (jump) + mesaj/hata işleyicisi ---
            // UEFI/BIOS, 0x55AA imzasını ve 0x00'daki "bootable" imzasını
            // kontrol eder; 0x00..0x02 alanı korunmuş bırakılır ve 0x03'e
            // "MSWIN4.1" yazılır (genel amaçlı, önyüklenebilir değil).
            b[0] = 0xEB.toByte()
            b[1] = 0x58.toByte()
            b[2] = 0x90.toByte()
            System.arraycopy("MSWIN4.1".toByteArray(Charsets.US_ASCII), 0, b, 3, 8)

            // --- BPB (0x0B) ---
            putLe16(b, 0x0B, 512)                        // bytes per sector
            b[0x0D] = g.sectorsPerCluster.toByte()       // sectors per cluster
            putLe16(b, 0x0E, g.reservedSectors)         // reserved sectors
            b[0x10] = g.numFats.toByte()                 // FAT count
            putLe16(b, 0x11, 0)                          // root entry count (FAT32'de 0)
            putLe16(b, 0x13, 0)                          // total sectors 16 (0 = kullanma)
            b[0x15] = 0xF8.toByte()                      // media descriptor
            putLe16(b, 0x16, 0)                          // FAT size 16 (0 = kullanma)
            putLe16(b, 0x18, 63)                         // sectors per track
            putLe16(b, 0x1A, 255)                        // heads
            putLe32(b, 0x1C, 0)                          // hidden sectors
            putLe32(b, 0x20, 0)                          // total sectors 32 (0 = kullanma)
            putLe32(b, 0x24, g.fatSectors)               // FAT size 32
            putLe16(b, 0x28, 0)                          // ext flags
            putLe16(b, 0x2A, 0)                          // fs version
            putLe32(b, 0x2C, g.rootCluster)              // root cluster
            putLe16(b, 0x30, 1)                          // FSInfo sector
            putLe16(b, 0x32, 6)                          // backup boot sector
            // 0x34..0x3F reserved (zaten 0)
            b[0x40] = 0x80.toByte()                      // drive number
            b[0x41] = 0
            b[0x42] = 0x29.toByte()                      // extended boot signature
            putLe32(b, 0x43, serial and 0xFFFFFFFFL)     // volume serial
            System.arraycopy(label.toFatLabel().toByteArray(Charsets.US_ASCII), 0, b, 0x47, 11)
            System.arraycopy("FAT32   ".toByteArray(Charsets.US_ASCII), 0, b, 0x52, 8)

            // --- 0x5A: Microsoft bootstrap dizisi ---
            // Diski 0x7C00'e yükler (ilk 15 sektör) ve oraya atlar.
            val boot = byteArrayOf(
                0x31.toByte(), 0xC0.toByte(),             // 5A  xor ax,ax
                0x8E.toByte(), 0xD0.toByte(),             // 5C  mov ss,ax
                0xBC.toByte(), 0x00.toByte(), 0x7C.toByte(), // 5E mov sp,0x7C00
                0xBE.toByte(), 0x05.toByte(), 0x7C.toByte(), // 61 mov si,0x7C05
                0xBF.toByte(), 0x00.toByte(), 0x7C.toByte(), // 64 mov di,0x7C00
                0xFC.toByte(),                             // 67 cld
                0xB9.toByte(), 0x00.toByte(), 0x1E.toByte(), // 68 mov cx,0x1E00
                0xF3.toByte(), 0xA4.toByte(),             // 6B rep movsb
                0xEA.toByte(), 0x00.toByte(), 0x7C.toByte(), 0x00, 0x00, // 6D jmp 0000:7C00
            )
            System.arraycopy(boot, 0, b, 0x5A, boot.size)

            // --- 0x72: "bootable değil" bilgilendirmesi ---
            // Önyükleme kodu çalıştırıldığında 0x0000:0x0000'a (boş vektör)
            // düşer. Bazı BIOS'lar buradaki metni gösterir; işlevsel
            // değildir ancak bölümün "veri diski" olduğunu belgeler.
            val msg = "This is not a bootable disk. Data volume only."
            System.arraycopy(msg.toByteArray(Charsets.US_ASCII), 0, b, 0x72, msg.length)

            b[510] = 0x55
            b[511] = 0xAA.toByte()
            return b
        }

        /** FSInfo sektörü: boş küme sayacı + sonraki boş küme. */
        fun buildFsInfo(g: Fat32Geometry): ByteArray {
            val b = ByteArray(512)
            putLe32(b, 0x00, 0x41615252L)      // lead signature
            putLe32(b, 0x04, 0x61417272L)      // structure signature
            putLe32(b, 0x08, (g.clusterCount - 2).toLong())  // free cluster count
            putLe32(b, 0x0C, g.rootCluster)     // next free cluster (root isimli)
            putLe32(b, 0x10, 0xAA550000L)      // trail signature
            return b
        }

        internal fun putLe16(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        internal fun putLe32(b: ByteArray, off: Int, v: Long) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
            b[off + 2] = ((v shr 16) and 0xFF).toByte()
            b[off + 3] = ((v shr 24) and 0xFF).toByte()
        }

        internal fun putLe16At(b: ByteArray, off: Int, v: Int) = putLe16(b, off, v)

        /** Küçük bayt sırasında 32 bit okur. */
        internal fun readLe32(b: ByteArray, off: Int): Long = (b[off].toLong() and 0xFF) or
                ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or
                ((b[off + 3].toLong() and 0xFF) shl 24)

        /** 11 baytlık FAT etiketine dönüştürür (boşlukla doldurulur). */
        fun String.toFatLabel(): String =
            uppercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                .take(11).padEnd(11, ' ')
    }
}

/** FAT32 hacminin geometrik parametreleri. */
data class Fat32Geometry(
    val totalSectors: Long,
    val sectorsPerCluster: Int,
    val reservedSectors: Int,
    val numFats: Int,
    val fatSectors: Int,
    val rootCluster: Int,
    val rootDirSectors: Int,
    val clusterCount: Int,
    val firstDataSector: Long,
) {
    val clusterSize: Int get() = sectorsPerCluster * 512
    val fatOffset: Long get() = reservedSectors.toLong()

    /** [cluster] numarasının 1. FAT içindeki sektör numarası. */
    fun fatSectorIndexOf(cluster: Int): Int = ((cluster - 2) * 4) / 512

    /** [cluster] numarasının sektör içindeki bayt ofseti. */
    fun fatByteOffsetOf(cluster: Int): Int = ((cluster - 2) * 4) % 512

    /** [cluster] numarasının disk üzerindeki ilk sektörü. */
    fun dataSectorOf(cluster: Int): Long =
        firstDataSector + (cluster - 2).toLong() * sectorsPerCluster

    /** Bir kümenin kaç sektör kapladığı. */
    fun sectorsOf(count: Int): Int = count * sectorsPerCluster

    companion object {
        const val RESERVED = 32
        const val NUM_FATS = 2

        /** Hacmin geometrisini hesaplar ve FAT boyutunu sabit noktaya iterasyonla bulur. */
        fun compute(totalSectors: Long): Fat32Geometry {
            val spc = chooseSectorsPerCluster(totalSectors)
            var fatSectors = -1L
            var clusterCount = 0
            var stable = false
            var iteration = 0
            // Her yineleme FAT boyutunu daha kesinleştirir; 32 yineleme
            // garantidir (her turda değişim en az 1 sektör düzeyindedir).
            while (!stable && iteration++ < 32) {
                val dataSectors = totalSectors - RESERVED - NUM_FATS * fatSectors
                clusterCount = (dataSectors / spc).toInt()
                val needed = ((clusterCount + 2L) * 4L + 511L) / 512L
                if (needed == fatSectors) stable = true else fatSectors = needed
            }
            require(fatSectors > 0) { "FAT32 FAT boyutu hesaplanamadı." }
            // Kök dizin için 64 KiB'lık küme rezervi
            val clusterSize = spc * 512
            val rootClusters = maxOf(1, (64 * 1024) / clusterSize)
            val rootDirSectors = rootClusters * spc
            val firstData = (RESERVED + NUM_FATS * fatSectors).toLong()
            return Fat32Geometry(
                totalSectors = totalSectors,
                sectorsPerCluster = spc,
                reservedSectors = RESERVED,
                numFats = NUM_FATS,
                fatSectors = fatSectors.toInt(),
                rootCluster = 2,
                rootDirSectors = rootDirSectors,
                clusterCount = clusterCount,
                firstDataSector = firstData,
            )
        }

        /**
         * Küme boyutunu hacme göre seçer.
         * Çok küçük küçeler FAT tablosunu şişirir, çok büyükler küçük
         * dosyalarda israf yaratır. Flash bellek için 4–64 KiB bandı uygundur.
         */
        fun chooseSectorsPerCluster(totalSectors: Long): Int = when {
            totalSectors < 260L * 2048 -> 1        // < 128 MiB
            totalSectors < 2L * 1024 * 1024 -> 8    // < 1 GiB  → 4 KiB
            totalSectors < 8L * 1024 * 1024 -> 16   // < 4 GiB  → 8 KiB
            totalSectors < 32L * 1024 * 1024 -> 32  // < 16 GiB → 16 KiB
            totalSectors < 128L * 1024 * 1024 -> 64 // < 64 GiB → 32 KiB
            else -> 128                              // ≥ 64 GiB  → 64 KiB
        }
    }
}

/** DOS tarih/zaman kodlaması. */
internal object DosTime {
    private fun calendar(): Calendar = Calendar.getInstance(TimeZone.getDefault())

    fun now(): Int = encode(calendar())

    fun encode(cal: Calendar): Int {
        val year = (cal.get(Calendar.YEAR) - 1980).coerceIn(0, 127)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND) / 2
        val date = (year shl 9) or (month shl 5) or day
        val time = (hour shl 11) or (minute shl 5) or second
        return (date shl 16) or time
    }

    fun decode(code: Int): Calendar {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        val date = (code shr 16) and 0xFFFF
        val time = code and 0xFFFF
        cal.set(
            ((date shr 9) and 0x7F) + 1980,
            ((date shr 5) and 0x0F) - 1,
            date and 0x1F,
            (time shr 11) and 0x1F,
            (time shr 5) and 0x3F,
            (time and 0x1F) * 2,
        )
        return cal
    }
}
