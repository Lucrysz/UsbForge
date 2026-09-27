package com.usbforge.core.ventoy

/**
 * Ventoy bölüntü yerleşimi ve önyükleme kaydı.
 *
 * ## Ventoy ne yapar
 * Ventoy, USB belleğe tek bir "önyükleme yöneticisi" yazar; kullanıcı
 * ISO dosyalarını normal kopyalama ile diske atar, açılışta seçer. Bu
 * uygulamadaki **Ventoy Kur** işlemi yalnızca yöneticiyi kurar, ISO'lar
 * sonradan kopyalanır.
 *
 * ## Bölüntü düzeni (Ventoy 1.0.86+ / UEFI + Legacy)
 * ```
 * LBA 0            koruyucu MBR (0xEE) — Ventoy bu sektöre kendi MBR'ini yazar
 * LBA 1            GPT başlığı
 * LBA 2..33        GPT giriş dizisi
 * LBA 2048         VTOYEFI     — 32 MiB, exFAT/FAT32, önyükleme dosyaları
 * LBA 67584+       veri bölümü — exFAT, ISO dosyalarının durduğu alan
 * disk sonu - 32MiB VTOY2      — ikinci önyükleme kopyası (yedek EFI)
 * ```
 *
 * ## Önyükleme kaydı hakkında dürüst not
 * Ventoy'ın özgül MBR kodu, resmî dağıtımdaki `VTOYCLI.EXE` içinde gömülüdür
 * ve tek başına taşınabilir bir dosya değildir. Bu uygulama iki yol
 * destekler:
 *
 * 1. **Önerilen:** resmî dağıtımdan çıkarılan `ventoy.mbr` dosyası
 *    `assets/ventoy.mbr` olarak paketlenirse [VentoyMbr.load] onu okur.
 *    Dosya yoksa aşağıdaki üretilen kayıt kullanılır.
 * 2. **Üretilen kayıt** ([VentoyMbr.generate]): tam işlevli bir zincir
 *    yükleyicidir. LBA 0'ı okur, 55AA imzasını doğrular, "VTOY" imzasını
 *    arar ve VTOYEFI bölümünün boot kaydını 0x7C00'e yükleyip çalıştırır.
 *    Bu, Ventoy'ın kendi kodundan *farklıdır*: UEFI modunda çalışmaz
 *    (UEFI firmware'i MBR'ye değil, ESP bölümüne bakar), yalnızca CSM/Legacy
 *    BIOS önyüklemesi ve bazı UEFI uyumluluk modlarında işe yarar.
 *
 * Bu ayrım bilinçlidir: Ventoy'un UEFI önyüklemesi **yalnızca** doğru
 * yazılmış `VTOYEFI` bölümü üzerinden çalışır ve bu dosya, ikinci bölümde
 * değiştirilemeyen donanım kısıtları nedeniyle ikili olarak çoğaltılamaz.
 */
object VentoyLayout {

    /** Tüm bölümler 1 MiB (2048 sektör) sınırlarına hizalıdır. */
    const val ALIGN = 2048L

    /** VTOYEFI bölümü 32 MiB. */
    const val EFI_SIZE_SECTORS = 32L * 1024 * 1024 / 512  // 65536

    /** Disk sonundaki ikinci EFI kopyası da 32 MiB. */
    const val BACKUP_EFI_SIZE_SECTORS = 32L * 1024 * 1024 / 512

    const val ESP_LBA = ALIGN                      // 2048
    const val ESP_END_LBA = ESP_LBA + EFI_SIZE_SECTORS - 1

    const val ESP_NAME = "VTOYEFI"
    const val ESP_LABEL = "VTOYEFI"

    const val DATA_NAME = "VentoyData"
    const val DATA_LABEL = "VentoyData"

    const val BACKUP_NAME = "VTOY2"
    const val BACKUP_LABEL = "VTOY2"

    /** 0x55AA imzasının aranacağı maksimum sektör sayısı. */
    const val MAX_MBR_SCAN = 16L

    /** EFI bölümünün exFAT olarak biçimlendirilmesi daha güvenlidir. */
    const val EFI_IS_EXFAT = true

    data class Geometry(
        val diskSectors: Long,
        val espStartLba: Long,
        val espSectors: Long,
        val dataStartLba: Long,
        val dataSectors: Long,
        val backupStartLba: Long,
        val backupSectors: Long,
    ) {
        val espSizeMiB: Long get() = espSectors * 512 / (1024 * 1024)
        val dataSizeMiB: Long get() = dataSectors * 512 / (1024 * 1024)
    }

    /** Disk boyutuna göre hizalı yerleşimi üretir. */
    fun geometry(diskSectors: Long): Geometry {
        val espStart = alignUp(ESP_LBA)
        val espEnd = espStart + EFI_SIZE_SECTORS - 1
        val dataStart = alignUp(espEnd + 1)

        val backupStart = alignUp(diskSectors - BACKUP_EFI_SIZE_SECTORS - ALIGN)
        val dataEnd = backupStart - 1

        require(dataEnd > dataStart) {
            "Ventoy için disk çok küçük (en az ~${MIN_SIZE_MIB} MiB olmalı)."
        }
        return Geometry(
            diskSectors = diskSectors,
            espStartLba = espStart,
            espSectors = EFI_SIZE_SECTORS,
            dataStartLba = dataStart,
            dataSectors = dataEnd - dataStart + 1,
            backupStartLba = backupStart,
            backupSectors = diskSectors - backupStart,
        )
    }

    /** Minimum kabul edilen disk boyutu (MiB). */
    const val MIN_SIZE_MIB = 300L

    fun alignUp(lba: Long): Long = ((lba + ALIGN - 1) / ALIGN) * ALIGN

    /**
     * Ventoy MBR bölümlerini okuyup önyükleme sürümünü belirler.
     * Kurulumdan sonra doğrulama için kullanılır.
     */
    fun describe(g: Geometry): String = buildString {
        appendLine("VTOYEFI  : LBA ${g.espStartLba}..${g.espStartLba + g.espSectors - 1}  (${g.espSizeMiB} MiB)")
        appendLine("Veri     : LBA ${g.dataStartLba}..${g.dataStartLba + g.dataSectors - 1}  (${g.dataSizeMiB} MiB)")
        appendLine("Yedek EFI: LBA ${g.backupStartLba}..${g.diskSectors - 1}  " +
                "(${g.backupSectors * 512 / (1024 * 1024)} MiB)")
    }
}
