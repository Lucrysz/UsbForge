package com.usbforge.core.fs

import com.usbforge.core.block.BlockDevice
import com.usbforge.core.engine.ProgressReporter

/**
 * exFAT biçimlendirici (Microsoft exFAT şartnamesi, sürüm 1.00).
 *
 * ## Bölge düzeni
 * exFAT diski üç bölgeden oluşur ve bunlar birbirine bitişik olmak
 * zorundadır:
 * ```
 * [ Main Boot Region ]  24 sektör (0..23)
 *   0        önyükleme kaydı
 *   1..8     8. sektörden itibaren 9 "Extended Boot Sectors" (toplam 12 sektör)
 *   9..11    yedek önyükleme kaydı + 2 boş sektör
 *   12..23   boş
 * [ Backup Boot Region ] son 12 sektör
 * [ Main FAT ]           fatOffset'ten itibaren fatLength sektör
 * [ Cluster Heap ]       clusterHeapOffset'ten itibaren
 * ```
 *
 * ## Önyükleme kaydı alanları
 * ```
 * 0x00  3 bayt   jump (EB 76 90)
 * 0x03  8 bayt   FileSystemName = "EXFAT   "
 * 0x0B 53 bayt   MustBeZero
 * 0x40  2 bayt   Part1Reserved
 * 0x42  8 bayt   VolumeLength          (sektör sayısı - 1)
 * 0x4A  4 bayt   FatOffset
 * 0x4E  4 bayt   FatLength
 * 0x52  4 bayt   ClusterHeapOffset
 * 0x56  4 bayt   ClusterCount
 * 0x5A  4 bayt   FirstClusterOfRootDirectory
 * 0x5E  4 bayt   VolumeSerialNumber
 * 0x62  2 bayt   FileSystemRevision    (0x0100)
 * 0x64  2 bayt   VolumeFlags
 * 0x66  1 bayt   BytesPerSectorShift   (9 = 512)
 * 0x67  1 bayt   SectorsPerClusterShift
 * 0x68  1 bayt   NumberOfFats
 * 0x69  1 bayt   DriveSelect           (0x80)
 * 0x6A  1 bayt   PercentInUse
 * 0x6B  7 bayt   Reserved
 * 0x72 390 bayt  BootCode
 * 0x1FE 2 bayt   0x55 0xAA
 * ```
 *
 * exFAT'in en büyük avantajı burada görülür: geçmiş dizin girişini
 * (defragmentation bitmap) ve bitmap'i **yazmamıza gerek yoktur**.
 * Yeni biçimlendirilmiş birimde bitmap boş olduğu için Windows/Linux
 * tüm küme havuzunu boş kabul eder. Bu, flash belleklerde yazma
 * döngüsünü ve biçimlendirme süresini en aza indirir.
 */
class ExFatFormatter(
    override val startLba: Long,
    override val sectors: Long,
    private val label: String = "USBFORGE",
    private val volumeSerial: Long = DEFAULT_VOLUME_SERIAL,
) : FsFormatter {

    private lateinit var geo: ExFatGeometry

    override suspend fun format(device: BlockDevice, progress: ProgressReporter): FormatResult {
        require(sectors >= 64L * 1024) { "exFAT için en az 32 MiB gerekir." }

        val g = ExFatGeometry.compute(sectors)
        geo = g

        progress.setPhase("exFAT önyükleme kaydı hazırlanıyor")

        // Ana önyükleme bölgesi: 24 sektör, 0x55AA imzası sadece 0. sektörde.
        val bootRegion = ByteArray(24 * 512)
        val main = buildBootSector(g, isMain = true)
        System.arraycopy(main, 0, bootRegion, 0, 512)

        // 8..15: Extended Boot Sectors 1..8 ("EXFAT   " imzasıyla)
        for (i in 1..8) {
            val off = i * 512
            bootRegion[off] = 0xEB.toByte()
            bootRegion[off + 1] = 0x76.toByte()
            bootRegion[off + 2] = 0x90.toByte()
            System.arraycopy("EXFAT   ".toByteArray(Charsets.US_ASCII), 0, bootRegion, off + 3, 8)
        }
        // 16: yedek önyükleme kaydı (Main Boot Region içinde, 9. sektör)
        System.arraycopy(main, 0, bootRegion, 16 * 512, 512)

        device.write(startLba, bootRegion) { progress.addWritten(it) }

        // Yedek önyükleme bölgesi: disk sonundaki 12 sektör
        progress.setPhase("exFAT yedek önyükleme bölgesi yazılıyor")
        val backup = ByteArray(12 * 512)
        val backupMain = buildBootSector(g, isMain = false)
        System.arraycopy(backupMain, 0, backup, 0, 512)
        for (i in 1..8) {
            val off = i * 512
            backup[off] = 0xEB.toByte()
            backup[off + 1] = 0x76.toByte()
            backup[off + 2] = 0x90.toByte()
            System.arraycopy("EXFAT   ".toByteArray(Charsets.US_ASCII), 0, backup, off + 3, 8)
        }
        System.arraycopy(backupMain, 0, backup, 8 * 512, 512)
        device.write(startLba + sectors - 12, backup) { progress.addWritten(it) }

        // FAT: giriş 0 ve 1 özel, kök dizin kümesi işaretlenir.
        progress.setPhase("exFAT FAT tablosu hazırlanıyor (${g.fatLength * 512 / 1024} KiB)")
        val fatBuffer = ByteArray(512 * 256)
        val fatSectorsToWrite = minOf(g.fatLength, 256L)
        java.util.Arrays.fill(fatBuffer, 0, fatSectorsToWrite.toInt() * 512, 0.toByte())
        // Giriş 0: 0xFFFFFFF8 (medya + EOC), giriş 1: 0xFFFFFFFF
        putLe32(fatBuffer, 0, 0xFFFFFFF8L)
        putLe32(fatBuffer, 4, 0xFFFFFFFFL)
        // Kök dizin kümesi (2) son kümeye işaretlenir: 0xFFFFFFF9
        val rootOffset = (g.rootCluster * 4).toInt()
        if (rootOffset < fatSectorsToWrite * 512) {
            putLe32(fatBuffer, rootOffset, 0xFFFFFFF9L)
        }
        device.write(startLba + g.fatOffset, fatBuffer, 0, fatSectorsToWrite.toInt() * 512) {
            progress.addWritten(it)
        }

        // Kalan FAT bölümünü sıfırla
        var done = fatSectorsToWrite
        while (done < g.fatLength) {
            val n = minOf(256L, g.fatLength - done).toInt()
            java.util.Arrays.fill(fatBuffer, 0, n * 512, 0.toByte())
            device.write(startLba + g.fatOffset + done, fatBuffer, 0, n * 512) { progress.addWritten(it) }
            done += n
        }

        device.flush()

        return FormatResult(
            fileSystem = "exFAT",
            clusterSizeBytes = g.clusterSize,
            totalClusters = g.clusterCount.toLong(),
            label = label,
            notes = listOf(
                "Küme boyutu: ${g.clusterSize / 1024} KiB",
                "FAT: LBA ${g.fatOffset}..${g.fatOffset + g.fatLength - 1}",
                "Cluster Heap: LBA ${g.clusterHeapOffset} (${g.clusterCount} küme)",
                "Kök dizin kümesi: ${g.rootCluster}",
            ),
        )
    }

    companion object {
        const val MAIN_BOOT_SECTORS = 24
        const val BACKUP_BOOT_SECTORS = 12
        const val DEFAULT_VOLUME_SERIAL = 0x20240000L

        /** exFAT önyükleme kaydı (boot sector) üretir. */
        fun buildBootSector(g: ExFatGeometry, isMain: Boolean): ByteArray {
            val b = ByteArray(512)

            b[0] = 0xEB.toByte()
            b[1] = 0x76.toByte()
            b[2] = 0x90.toByte()
            System.arraycopy("EXFAT   ".toByteArray(Charsets.US_ASCII), 0, b, 3, 8)
            // 0x0B..0x3F MustBeZero (zaten 0)

            putLe16(b, 0x40, 0)                                        // Part1Reserved
            putLe64(b, 0x42, g.totalSectors - 1)                       // VolumeLength
            putLe32(b, 0x4A, g.fatOffset)                              // FatOffset
            putLe32(b, 0x4E, g.fatLength)                             // FatLength
            putLe32(b, 0x52, g.clusterHeapOffset)                     // ClusterHeapOffset
            putLe32(b, 0x56, g.clusterCount)                          // ClusterCount
            putLe32(b, 0x5A, g.rootCluster)                            // FirstClusterOfRootDirectory
            putLe32(b, 0x5E, g.volumeSerial)                          // VolumeSerialNumber
            putLe16(b, 0x62, 0x0100)                                  // FileSystemRevision 1.00
            putLe16(b, 0x64, 0)                                        // VolumeFlags
            b[0x66] = 9                                                // BytesPerSectorShift (2^9=512)
            b[0x67] = g.sectorsPerClusterShift.toByte()               // SectorsPerClusterShift
            b[0x68] = 1                                                // NumberOfFats
            b[0x69] = 0x80.toByte()                                    // DriveSelect
            b[0x6A] = 0x50                                             // PercentInUse (%50 = belirsiz)
            // 0x6B..0x71 Reserved (0), 0x72..0x1FD BootCode (0)

            b[510] = 0x55
            b[511] = 0xAA.toByte()
            return b
        }

        private fun putLe16(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        private fun putLe32(b: ByteArray, off: Int, v: Long) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
            b[off + 2] = ((v shr 16) and 0xFF).toByte()
            b[off + 3] = ((v shr 24) and 0xFF).toByte()
        }

        private fun putLe64(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
    }
}

/** exFAT hacminin geometrik parametreleri. */
data class ExFatGeometry(
    val totalSectors: Long,
    val sectorsPerClusterShift: Int,
    val fatOffset: Long,
    val fatLength: Long,
    val clusterHeapOffset: Long,
    val clusterCount: Int,
    val rootCluster: Int,
    val volumeSerial: Long,
) {
    val clusterSize: Int get() = (1 shl sectorsPerClusterShift) * 512

    companion object {
        /** exFAT kök dizini için ayrılan küme sayısı. */
        const val ROOT_CLUSTER_COUNT = 16

        /**
         * Geometriyi hesaplar.
         *
         * exFAT'te FAT uzunluğu küme sayısına bağlıdır; 16 yineleme ile
         * sabit noktaya ulaşılır.
         */
        fun compute(totalSectors: Long): ExFatGeometry {
            val fatOffset = (ExFatFormatter.MAIN_BOOT_SECTORS + ExFatFormatter.BACKUP_BOOT_SECTORS).toLong()
            val clusterHeapOffset = fatOffset

            // exFAT küme boyutu 2'nin kuvveti olmalı ve en az 1 sektör.
            // Flash belleklerde 64 KiB dengeleyicisidir.
            var shift = 7
            while (shift < 25 && (1 shl shift) * 512 < 64 * 1024) shift++

            // FAT uzunluğu küme sayısına bağlıdır; sabit noktaya iterasyonla
            // ulaşılır. 32 yineleme en kötü durumda fazlasıyla yeterlidir.
            var clusterCount = 0L
            var fatLength = -1L
            var stable = false
            var iteration = 0
            while (!stable && iteration++ < 32) {
                val available = totalSectors - clusterHeapOffset
                clusterCount = available shr shift
                val needed = ((clusterCount + 2L) * 4L + 511L) / 512L
                // FAT 8 sektöre yuvarlanır: exFAT, FAT tablosunun belirli
                // sınırda başlamasını (MediaReadSize gibi ipuçları için)
                // önermez ama hizalama SSD denetleyicilerinde veri hizasını
                // iyileştirir.
                val rounded = ((needed + 7L) / 8L) * 8L
                if (rounded == fatLength) stable = true else fatLength = rounded
            }
            require(fatLength > 0) { "exFAT FAT boyutu hesaplanamadı." }

            val heapOffset = fatOffset + fatLength
            val usable = totalSectors - heapOffset
            val finalCount = (usable shr shift).toInt()
            require(finalCount > ROOT_CLUSTER_COUNT) {
                "exFAT için yeterli alan yok ($totalSectors sektör)."
            }

            return ExFatGeometry(
                totalSectors = totalSectors,
                sectorsPerClusterShift = shift,
                fatOffset = fatOffset,
                fatLength = fatLength,
                clusterHeapOffset = heapOffset,
                clusterCount = finalCount,
                rootCluster = 2,
                volumeSerial = ExFatFormatter.DEFAULT_VOLUME_SERIAL,
            )
        }
    }
}
