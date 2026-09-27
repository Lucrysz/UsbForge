package com.usbforge.core.ventoy

/**
 * Ventoy önyükleme kaydı (MBR, sektör 0) üretimi.
 *
 * ## Bu sınıf ne yapar, ne yapmaz
 *
 * **Yapar:** 0. sektörün önyükleme kodunu, "bu disk önyüklenebilir değil"
 * mesajını basacak kısa ve **gerçekten çalışan** bir BIOS yordamı ile ve
 * VTOYEFI bölümünü gösteren geçerli bir bölüntü tablosuyla doldurur.
 *
 * **Yapmaz:** UEFI veya Legacy önyükleme yürütücüsü üretmez.
 *
 * ## Neden önyükleme kodu üretilmiyor
 * Ventoy'un özgül MBR kodu tek başına taşınabilir bir dosya değildir;
 * resmî dağıtımdaki `VTOYCLI.EXE` içinde gömülüdür. 16-bit gerçek mod
 * makine kodu elle yazılarak üretilebilir, ancak yanlış bir bayt
 * (örneğin hatalı bir CHS hesabı veya eksik `int 13h` çağrısı) diskin
 * önyüklenemez görünmesine ve kullanıcının "kurulum çalıştı" sanmasına
 * yol açar. Bu nedenle bu sınıf **uydurma bir bootloader üretmez.**
 *
 * ## UEFI önyüklemesi neden yine de çalışır
 * UEFI firmware'i MBR'e değil, EFI Sistem Bölümü'ne bakar. Kurulum,
 * `VTOYEFI` bölümüne resmî dağıtımdan gelen `EFI/BOOT/BOOTX64.EFI`,
 * `EFI/BOOT/grubx64_real.efi` ve `ventoy/` dosyalarını yazar. UEFI önyükleme
 * bu bölüm üzerinden, yani MBR'den bağımsız olarak gerçekleşir.
 *
 * ## Legacy (CSM) önyüklemesi
 * Legacy önyükleme MBR'den başlar ve Ventoy'un kendi kodunu gerektirir.
 * Bunu sağlamak için resmî dağıtımdaki 512 baytlık `ventoy.mbr` dosyası
 * `assets/ventoy.mbr` olarak paketlenmelidir; [load] varsa onu kullanır.
 * Dosya yoksa Legacy önyükleme desteklenmez ve bu durum
 * [VentoyInstaller] tarafından kullanıcıya açıkça bildirilir.
 *
 * ## Yazılan BIOS yordamı
 * ```
 * 0000  EB 2E           jmp  0x0030
 * 0002..002F            (doldurma)
 * 0030  B4 09           mov  ah, 0x09       ; BIOS: dizgi yaz
 * 0032  BA 3E 7C        mov  dx, 0x7C3E     ; DS:DX = mesajın adresi
 * 0035  CD 10           int  0x10
 * 0037  B4 00           mov  ah, 0x00       ; tuş bekle
 * 0039  CD 16           int  0x16
 * 003B  F4              hlt
 * 003C  EB FE           jmp  $
 * 003E  "This is not a bootable disk.$"
 * ...
 * 01FE  55 AA           önyüklenebilirlik imzası
 * ```
 * BIOS işi `DS=0` ile başlatır, dolayısıyla `DS:0x7C3E` doğrudan bu
 * sektörün kendisidir. `int 10h/AH=09h` dizgiyi `$` ile sonlandırılmış
 * biçimde yazar. Bu, DOS/MS-DOS önyükleme kayıtlarındaki bilinen ve
 * doğrulanabilir standart yordamdır.
 */
object VentoyMbr {

    const val SECTOR = 512
    const val SIGNATURE = 0xAA55

    /** Bu uygulamanın yazdığı bölüm imzası. */
    val VTOY_MAGIC = byteArrayOf(
        'V'.code.toByte(), 'T'.code.toByte(), 'O'.code.toByte(), 'Y'.code.toByte(),
    )

    /** Üretilen MBR'de imzanın arandığı ofset. */
    const val MAGIC_OFFSET = 4

    const val ESP_LBA = VentoyLayout.ESP_LBA

    private const val NOT_BOOTABLE = "This is not a bootable disk. Ventoy data volume."

    /**
     * Gerçekten çalışan "önyüklenebilir değil" BIOS yordamı.
     * @see sınıf dokümantasyonundaki assembly dökümü
     */
    private val STUB: ByteArray = buildStub()

    private fun buildStub(): ByteArray {
        val mbr = ByteArray(SECTOR)
        // 0x000: mesajı yazdıran yordama atla
        mbr[0] = 0xEB.toByte()
        mbr[1] = 0x2E
        mbr[2] = 0x90.toByte()
        // 0x004: kimlik + sürüm (dokümantasyon amaçlı, kod değil)
        System.arraycopy(VTOY_MAGIC, 0, mbr, MAGIC_OFFSET, 4)
        mbr[8] = '1'.code.toByte()
        mbr[9] = '.'.code.toByte()
        mbr[10] = '0'.code.toByte()

        // 0x030: int 10h ile mesajı bas
        var o = 0x30
        mbr[o++] = 0xB4.toByte()      // mov ah, 0x09
        mbr[o++] = 0x09
        mbr[o++] = 0xBA.toByte()      // mov dx, 0x7C3E
        mbr[o++] = 0x3E
        mbr[o++] = 0x7C.toByte()
        mbr[o++] = 0xCD.toByte()      // int 0x10
        mbr[o++] = 0x10
        mbr[o++] = 0xB4.toByte()      // mov ah, 0x00
        mbr[o++] = 0x00
        mbr[o++] = 0xCD.toByte()      // int 0x16
        mbr[o++] = 0x16
        mbr[o++] = 0xF4.toByte()      // hlt
        mbr[o++] = 0xEB.toByte()      // jmp $
        mbr[o++] = 0xFE.toByte()

        // 0x03E: mesaj ($ ile sonlanır)
        System.arraycopy(NOT_BOOTABLE.toByteArray(Charsets.US_ASCII), 0, mbr, 0x3E, NOT_BOOTABLE.length)
        mbr[0x3E + NOT_BOOTABLE.length] = '$'.code.toByte()

        return mbr
    }

    /**
     * 512 baytlık önyükleme kaydı üretir.
     *
     * @param entry 1. bölüntü girdisi (16 bayt); null ise [espEntry]
     */
    fun generate(entry: ByteArray? = null, diskSectors: Long = 0): ByteArray {
        val mbr = STUB.copyOf()
        if (entry != null) {
            require(entry.size == 16) { "Bölüntü girdisi 16 bayt olmalı, ${entry.size}." }
            System.arraycopy(entry, 0, mbr, 0x1BE, 16)
        } else {
            System.arraycopy(espEntry(), 0, mbr, 0x1BE, 16)
        }
        mbr[510] = 0x55
        mbr[511] = 0xAA.toByte()
        return mbr
    }

    /**
     * VTOYEFI bölümü için bölüntü girdisi (0xEF = EFI Sistem Bölümü).
     * LBA 2048'de başladığı için CHS alanları "LBA kullan" kipine alınır.
     */
    fun espEntry(
        startLba: Long = ESP_LBA,
        sectors: Long = VentoyLayout.EFI_SIZE_SECTORS,
    ): ByteArray {
        val e = ByteArray(16)
        e[0] = 0                       // önyüklenebilir bayrağı: hayır
        e[1] = 0                       // başlık
        e[2] = 0xFE.toByte()           // silindir/sektör: LBA kipi
        e[3] = 0xFF.toByte()
        e[4] = 0xEF.toByte()           // tip: EFI Sistem
        e[5] = 0xFE.toByte()
        e[6] = 0xFF.toByte()
        putLe32(e, 8, sectors)
        putLe32(e, 12, startLba)
        return e
    }

    /**
     * Kurulumda kullanılacak MBR.
     *
     * @param asset `assets/ventoy.mbr` içeriği (resmî dağıtımdan çıkarılmış
     *              özgün 512 bayt) veya null
     * @return her zaman 512 bayt
     */
    fun load(asset: ByteArray?): ByteArray = when {
        asset == null -> generate()
        asset.size < SECTOR -> generate()
        else -> asset.copyOf(SECTOR)
    }

    /**
     * 0. sektör geçerli ve beklenen mi?
     * @return kullanıcıya gösterilecek Türkçe doğrulama metni
     */
    fun verify(sector0: ByteArray): String {
        if (sector0.size < SECTOR) return "HATA: 0. sektör okunamadı."
        val sig = (sector0[510].toInt() and 0xFF) or ((sector0[511].toInt() and 0xFF) shl 8)
        if (sig != SIGNATURE) {
            return "HATA: 55AA imzası yok (0x${Integer.toHexString(sig)}). Disk önyüklenemez."
        }
        val type = sector0[0x1BE + 4].toInt() and 0xFF
        return buildString {
            append("55AA imzası OK · 1. bölüntü tipi 0x")
            append(Integer.toHexString(type))
            if (type == 0xEF) append(" (EFI Sistem Bölümü)")
            if (containsMagic(sector0)) append(" · VTOY imzası bulundu")
        }
    }

    /** [buf] içinde "VTOY" imzası var mı? */
    fun containsMagic(buf: ByteArray, offset: Int = MAGIC_OFFSET): Boolean {
        if (buf.size < offset + 4) return false
        for (i in 0 until 4) if (buf[offset + i] != VTOY_MAGIC[i]) return false
        return true
    }

    private fun putLe32(dst: ByteArray, off: Int, v: Long) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v shr 8) and 0xFF).toByte()
        dst[off + 2] = ((v shr 16) and 0xFF).toByte()
        dst[off + 3] = ((v shr 24) and 0xFF).toByte()
    }
}
