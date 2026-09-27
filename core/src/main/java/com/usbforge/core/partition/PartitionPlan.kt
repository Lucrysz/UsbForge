package com.usbforge.core.partition

/** Kullanıcının seçtiği bölüntü tablosu türü. */
enum class PartitionScheme(val label: String) {
    MBR("MBR (en uyumlu)"),
    GPT("GPT (UEFI + 2 TiB üstü)"),
}

/** Biçimlenecek bölüntünün dosya sistemi. */
enum class FileSystemKind(val label: String, val mbrType: Int, val gptType: String) {
    FAT32("FAT32", MbrTable.PARTITION_FAT32_LBA, Guid.Types.LINUX_FILESYSTEM),
    EXFAT("exFAT", MbrTable.PARTITION_EXFAT, Guid.Types.BASIC_DATA),
    NTFS("NTFS", MbrTable.PARTITION_NTFS, Guid.Types.BASIC_DATA),
    ;

    companion object {
        fun fromName(name: String): FileSystemKind =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: FAT32
    }
}

/**
 * Bölüntü yerleşim planı. Hem MBR hem GPT üreticileri tarafından
 * tüketilen ara temsil.
 */
data class PartitionPlan(
    val scheme: PartitionScheme,
    val fsKind: FileSystemKind,
    /** Bölüntünün diske göre başlangıç LBA'sı. */
    val startLba: Long,
    /** Bölüntünün uzunluğu (sektör). */
    val sectors: Long,
    /** GPT adı / MBR'da kullanılmayan etiket. */
    val label: String,
    /** NTFS için 16 MiB Microsoft Rezerve bölümü eklensin mi? */
    val withMsr: Boolean = false,
) {
    val sizeBytes: Long get() = sectors * MbrTable.SECTOR
    val sizeMiB: Long get() = sizeBytes / (1024 * 1024)

    /** Doğrulama: bölüntü cihaz sınırları içinde ve makul boyutlu mu? */
    fun validate(diskSectors: Long) {
        require(sectors > 0) { "Bölüntü boyutu sıfır olamaz." }
        require(startLba > 0) { "Bölüntü LBA 0'da başlayamaz (ön yükleme kaydı bulunur)." }
        require(startLba + sectors <= diskSectors) {
            "Bölüntü disk sınırını aşıyor (${startLba + sectors} > $diskSectors)."
        }
        // FAT32 için en az ~33 MiB gerekir; exFAT ~1 MiB; NTFS ~40 MiB.
        val minimum = when (fsKind) {
            FileSystemKind.FAT32 -> 34L * 1024 * 1024
            FileSystemKind.EXFAT -> 2L * 1024 * 1024
            FileSystemKind.NTFS -> 40L * 1024 * 1024
        }
        require(sizeBytes >= minimum) {
            "${fsKind.label} için en az ${minimum / (1024 * 1024)} MiB gerekiyor, " +
                    "bölüntü ${sizeMiB} MiB."
        }
    }

    companion object {
        /** 1 MiB hizalama sabiti (sektör). */
        const val ALIGN_SECTORS = 2048L

        fun alignUp(lba: Long, alignment: Long = ALIGN_SECTORS): Long =
            ((lba + alignment - 1) / alignment) * alignment

        /**
         * Tek bölüntülü varsayılan plan üretir.
         *
         * @param diskSectors cihazın toplam sektör sayısı
         * @param fsKind      dosya sistemi
         * @param scheme     MBR / GPT
         */
        fun singlePartition(
            diskSectors: Long,
            fsKind: FileSystemKind,
            scheme: PartitionScheme,
        ): PartitionPlan {
            val start = when (scheme) {
                // MBR'de bölüntü 1. sektörden (ön yükleme kaydı) hemen
                // sonra, 1 MiB sınırına hizalı başlar.
                PartitionScheme.MBR -> alignUp(1L)
                // GPT'de ilk kullanılabilir LBA 34'tür; yine 1 MiB'ye hizalanır.
                PartitionScheme.GPT -> alignUp(GptTable.FIRST_USABLE_LBA)
            }
            val end = when (scheme) {
                PartitionScheme.MBR -> diskSectors - 1
                PartitionScheme.GPT -> diskSectors - 1 - GptTable.ENTRY_ARRAY_SECTORS - 1
            }
            return PartitionPlan(
                scheme = scheme,
                fsKind = fsKind,
                startLba = start,
                sectors = end - start + 1,
                label = defaultLabel(fsKind),
            )
        }

        fun defaultLabel(fsKind: FileSystemKind): String = when (fsKind) {
            FileSystemKind.FAT32 -> "USBFAT32"
            FileSystemKind.EXFAT -> "USBEXFAT"
            FileSystemKind.NTFS -> "USBNTFS"
        }
    }
}
