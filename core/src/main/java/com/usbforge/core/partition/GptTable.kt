package com.usbforge.core.partition

import com.usbforge.core.partition.MbrTable.SECTOR

/**
 * GUID Partition Table (GPT) oluşturucu.
 *
 * ## Disk düzeni
 * ```
 * LBA 0        koruyucu MBR (0xEE)
 * LBA 1        birincil GPT başlığı
 * LBA 2..33    128 adet 128 baytlık bölüntü girişi (16 KiB)
 * LBA 34       ilk kullanılabilir LBA (1 MiB hizalı)
 * LBA N-33     yedek GPT başlığı
 * LBA N-1      yedek koruyucu MBR
 * ```
 *
 * ## CRC kuralları
 * Başlık CRC'si, CRC alanı sıfırlanmış başlığın üzerinden hesaplanır
 * (92 bayt). Giriş dizisi CRC'si 128 girişin tamamının üzerinden
 * hesaplanır. Her iki CRC de küçük bayt sırasındadır.
 */
object GptTable {

    const val HEADER_SECTOR = 1
    const val ENTRY_ARRAY_SECTOR = 2
    const val ENTRY_COUNT = 128
    const val ENTRY_SIZE = 128
    const val ENTRY_ARRAY_SECTORS = ENTRY_COUNT * ENTRY_SIZE / SECTOR // 32
    const val FIRST_USABLE_LBA = ENTRY_ARRAY_SECTOR + ENTRY_ARRAY_SECTORS // 34
    const val BACKUP_ENTRY_ARRAY_SECTORS = 33

    data class Entry(
        val typeGuid: String,
        val uniqueGuid: String,
        val name: String,
        val firstLba: Long,
        val lastLba: Long,
        val attributes: Long = 0L,
    )

    data class Layout(
        val protectiveMbr: ByteArray,
        val primaryHeader: ByteArray,
        val entryArray: ByteArray,
        val backupEntryArray: ByteArray,
        val backupHeader: ByteArray,
        val firstUsableLba: Long,
        val lastUsableLba: Long,
    )

    /**
     * Disk için gerekli tüm yapıları üretir.
     *
     * @param diskSectors toplam sektör sayısı
     * @param entries     bölüntü tanımları (azami 128)
     * @param diskGuid    disk GUID'i (null ise rastgele üretilir)
     */
    fun layout(
        diskSectors: Long,
        entries: List<Entry>,
        diskGuid: String? = null,
    ): Layout {
        require(entries.size <= ENTRY_COUNT) { "GPT azami $ENTRY_COUNT bölüntü destekler." }
        require(diskSectors > FIRST_USABLE_LBA + 40) { "Disk GPT için çok küçük ($diskSectors sektör)." }

        val lastUsable = diskSectors - 1 - BACKUP_ENTRY_ARRAY_SECTORS - 1
        require(entries.all { it.firstLba >= FIRST_USABLE_LBA && it.lastLba <= lastUsable }) {
            "Bölüntü aralıkları kullanılabilir LBA sınırları dışında ($FIRST_USABLE_LBA..$lastUsable)."
        }

        val guid = diskGuid ?: Guid.format(Guid.random())
        val entryArray = buildEntryArray(entries)
        val backupEntryArray = entryArray.copyOf()

        val primary = buildHeader(
            diskSectors = diskSectors,
            currentLba = HEADER_SECTOR,
            backupLba = diskSectors - 1,
            firstUsable = FIRST_USABLE_LBA,
            lastUsable = lastUsable,
            diskGuid = guid,
            entryArrayLba = ENTRY_ARRAY_SECTOR,
        )

        val backup = buildHeader(
            diskSectors = diskSectors,
            currentLba = diskSectors - 1,
            backupLba = HEADER_SECTOR,
            firstUsable = FIRST_USABLE_LBA,
            lastUsable = lastUsable,
            diskGuid = guid,
            entryArrayLba = diskSectors - 1 - BACKUP_ENTRY_ARRAY_RESERVED_SECTORS,
        )

        return Layout(
            protectiveMbr = MbrTable.protectiveMbr(diskSectors),
            primaryHeader = primary,
            entryArray = entryArray,
            backupEntryArray = backupEntryArray,
            backupHeader = backup,
            firstUsableLba = FIRST_USABLE_LBA,
            lastUsableLba = lastUsable,
        )
    }

    /**
     * Disk sonundaki yedek alanın büyüklüğü: 1 sektör yedek başlık +
     * 32 sektör yedek giriş dizisi = 33 sektör.
     */
    private const val BACKUP_ENTRY_ARRAY_RESERVED_SECTORS = 33

    private fun buildEntryArray(entries: List<Entry>): ByteArray {
        val buf = ByteArray(ENTRY_COUNT * ENTRY_SIZE)
        entries.forEachIndexed { index, e ->
            val off = index * ENTRY_SIZE
            System.arraycopy(Guid.toBytes(e.typeGuid), 0, buf, off, 16)
            System.arraycopy(Guid.toBytes(e.uniqueGuid), 0, buf, off + 16, 16)
            putLe64(buf, off + 32, e.firstLba)
            putLe64(buf, off + 40, e.lastLba)
            putLe64(buf, off + 48, e.attributes)
            writeUtf16Name(buf, off + 56, e.name)
        }
        return buf
    }

    private fun buildHeader(
        diskSectors: Long,
        currentLba: Long,
        backupLba: Long,
        firstUsable: Long,
        lastUsable: Long,
        diskGuid: String,
        entryArrayLba: Long,
    ): ByteArray {
        val h = ByteArray(SECTOR)

        // 0x00: imza "EFI PART" (0x5452415020494645, küçük bayt = 'E','F','I',' ','P','A','R','T')
        val sig = "EFI PART".toByteArray(Charsets.US_ASCII)
        System.arraycopy(sig, 0, h, 0, 8)

        putLe32(h, 0x08, 0x00010000)      // Revision 1.0
        putLe32(h, 0x0C, HEADER_SIZE)    // HeaderSize
        putLe32(h, 0x10, 0)               // CRC32 — sonra hesaplanacak
        putLe32(h, 0x14, 0)               // Reserved
        putLe64(h, 0x18, currentLba)      // MyLBA
        putLe64(h, 0x20, backupLba)       // AlternateLBA
        putLe64(h, 0x28, firstUsable)     // FirstUsableLBA
        putLe64(h, 0x30, lastUsable)      // LastUsableLBA
        System.arraycopy(Guid.toBytes(diskGuid), 0, h, 0x38, 16) // DiskGUID
        putLe64(h, 0x48, entryArrayLba)   // PartitionEntryLBA
        putLe32(h, 0x50, ENTRY_COUNT)     // NumberOfPartitionEntries
        putLe32(h, 0x54, ENTRY_SIZE)      // SizeOfPartitionEntry
        putLe32(h, 0x58, 0)               // PartitionEntryArrayCRC32 — sonra hesaplanacak

        // CRC alanları sıfırken hesapla.
        putLe32(h, 0x58, 0)
        putLe32(h, 0x10, Crc32.compute(h, 0, HEADER_SIZE))
        return h
    }

    /**
     * Bölüntü adını UTF-16LE olarak yazar.
     * UEFI şartnamesi: ad alanı 36 UTF-16 kod birimi uzunluğundadır ve
     * kullanılmayan karakterler **sıfır** olmalıdır (0x0000).
     */
    private fun writeUtf16Name(dst: ByteArray, offset: Int, name: String) {
        val units = name.take(36)
        for (i in 0 until 36) {
            val code = if (i < units.length) units[i].code else 0
            dst[offset + i * 2] = (code and 0xFF).toByte()
            dst[offset + i * 2 + 1] = ((code shr 8) and 0xFF).toByte()
        }
    }

    private fun putLe32(dst: ByteArray, off: Int, v: Long) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((v ushr 16) and 0xFF).toByte()
        dst[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun putLe64(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) dst[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    /** Başlık boyutu: 92 bayt, ardından 420 bayt ayrılmış alan. */
    const val HEADER_SIZE = 92
}
