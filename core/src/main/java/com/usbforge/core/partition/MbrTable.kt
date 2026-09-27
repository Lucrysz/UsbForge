package com.usbforge.core.partition

/**
 * Master Boot Record (MBR) oluşturucu.
 *
 * ## Düzen
 * ```
 * 0x000  446 bayt   önyükleme kodu (genelde 0)
 * 0x1BE   16 bayt   1. bölüntü girdisi
 * 0x1CE   16 bayt   2. bölüntü girdisi
 * 0x1DE   16 bayt   3. bölüntü girdisi
 * 0x1EE   16 bayt   4. bölüntü girdisi
 * 0x1FE    2 bayt   imza 0x55 0xAA
 * ```
 *
 * ## CHS adresleme
 * Klasik `C/H/S` alanları 1034/16/63 ile sınırlıdır (~528 MiB). Bu sınırın
 * üzerindeki bölüntülerde alanlar `0xFE 0xFF 0xFF` yazılarak "LBA kullan"
 * sinyali verilir; Windows, Linux ve UEFI firmware'i bu durumda LBA alanını
 * kullanır. Bölüntü tipi ayrıca LBA-uyumlu olan 0x0C (FAT32 LBA) ya da
 * 0x83 (Linux) seçilir.
 */
object MbrTable {

    const val SECTOR = 512
    const val SIGNATURE = 0xAA55

    /** CHS'nin güvenilir azami LBA sınırı: 1024 silindir × 255 başlık × 63 sektör. */
    const val CHS_LIMIT = 1024L * 255L * 63L

    // ------------------------------------------------------- bölüntü tipleri

    const val PARTITION_FAT32_LBA = 0x0C
    const val PARTITION_FAT32_CHS = 0x1B
    const val PARTITION_EXFAT = 0x07
    const val PARTITION_NTFS = 0x07
    const val PARTITION_LINUX = 0x83
    const val PARTITION_EFI_SYSTEM = 0xEF
    const val PARTITION_PROTECTIVE = 0xEE

    data class Entry(
        val type: Int,
        val startLba: Long,
        val sectors: Long,
        val bootable: Boolean = false,
    )

    /**
     * Bölüntü girdilerinden 512 baytlık MBR üretir.
     *
     * @param entries en fazla 4 giriş
     * @param bootCode 446 baytlık önyükleme kodu (null ise boş)
     */
    fun build(
        entries: List<Entry>,
        diskSectors: Long,
        bootCode: ByteArray? = null,
    ): ByteArray {
        require(entries.size <= 4) { "MBR en fazla 4 bölüntü destekler, ${entries.size} verildi." }

        val mbr = ByteArray(SECTOR)

        if (bootCode != null) {
            require(bootCode.size <= 446) { "Önyükleme kodu 446 bayttan büyük olamaz." }
            System.arraycopy(bootCode, 0, mbr, 0, bootCode.size)
        }

        for (i in 0 until 4) {
            val off = 0x1BE + i * 16
            val e = entries.getOrNull(i)
            if (e == null || e.sectors <= 0) {
                mbr[off] = 0
                mbr[off + 4] = 0
                continue
            }
            val (head, cylSector, cyl) = chs(e.startLba)
            mbr[off] = if (e.bootable) 0x80.toByte() else 0
            mbr[off + 1] = head.toByte()
            mbr[off + 2] = cylSector.toByte()
            mbr[off + 3] = cyl.toByte()
            mbr[off + 4] = (e.type and 0xFF).toByte()
            mbr[off + 5] = cylSector.toByte()
            mbr[off + 6] = head.toByte()
            putLe32(mbr, off + 8, e.sectors)
            putLe32(mbr, off + 12, e.startLba)
        }

        mbr[510] = 0x55
        mbr[511] = 0xAA.toByte()
        return mbr
    }

    /**
     * UEFI'nin "koruyucu MBR"si. GPT diskinin 0. sektöründe bulunur ve tek
     * girişi 0xEE tipinde, diskin tamamını kapsayan bölüntüdür. Eski MBR
     * ayrıştırıcıları diskin yanlışlıkla silinmesini böyle önler.
     */
    fun protectiveMbr(diskSectors: Long): ByteArray {
        val size = minOf(diskSectors - 1, 0xFFFFFFFFL)
        return build(
            entries = listOf(Entry(type = 0xEE, startLba = 1, sectors = size)),
            diskSectors = diskSectors,
        )
    }

    /**
     * LBA değerini CHS üçlüsüne çevirir.
     * @return `head`, `(silindir & 0xFF) << 6 | sektör`, `silindir & 0xFF`
     */
    fun chs(lba: Long): Triple<Int, Int, Int> {
        if (lba >= CHS_LIMIT) {
            // LBA'yı doğrudan CHS alanına paketleyen "pseudo CHS"
            // (bkz. Wikipedia: CHS encoding, LBA in CHS)
            val head = ((lba shr 24) and 0xFF).toInt()
            val cyl = ((lba shr 16) and 0xFF).toInt()
            val sector = (0xE0 or ((lba shr 8) and 0xFF).toInt())
            return Triple(head, sector, cyl)
        }
        val sector = ((lba % 63) + 1).toInt()
        val temp = lba / 63
        val head = (temp % 255).toInt()
        val cyl = (temp / 255).toInt() and 0xFF
        return Triple(head, (cyl shl 6) or (sector and 0x3F), cyl)
    }

    private fun putLe32(dst: ByteArray, off: Int, v: Long) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((v ushr 16) and 0xFF).toByte()
        dst[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
