package com.usbforge.core.fs

import com.usbforge.core.block.BlockDevice
import com.usbforge.core.engine.ProgressReporter
import java.io.ByteArrayOutputStream

/**
 * NTFS biçimlendirici (NTFS 1.2, 4 KiB küme, 1024 bayt MFT kaydı).
 *
 * ## Disk düzeni
 * ```
 * sektör 0        önyükleme kaydı
 * sektör 8        yedek önyükleme kaydı
 * sektör 16..23   MFT başlığı alanı ("NTFS    ")
 * LCN 4..7        $MFT      (4 küme = 16 KiB = 16 kayıt)
 * LCN 8..11       $MFTMirr  (ilk 4 kaydın yedeği)
 * ...             $Bitmap   (küme başına 1 bit, tüm havuzu kapsar)
 * ...             $UpCase   (128 KiB)
 * ...             $LogFile  (256 KiB)
 * ...             $AttrDef  (2560 bayt = 16 × 160)
 * ...             kök dizin $INDEX_ALLOCATION (1 küme INDX)
 * ...             kök dizin $INDEX_ALLOCATION $BITMAP (1 küme)
 * kalan           boş
 * ```
 *
 * ## Üretilen MFT kayıtları
 * ```
 *  0  $MFT          6  $Bitmap     12 $Extend (dizin)
 *  1-3  boş         7  $UpCase     13 $ObjId
 *  4  boş           8  $LogFile    14 $Quota
 *  5  kök dizin     9  $AttrDef    15 $Reparse
 * 10,11 boş        16-23 boş      24+ kullanıcı dosyaları
 * ```
 * Bu sıralama Windows'un `mkntfs` çıktısıyla birebir aynıdır; ilk kullanıcı
 * dosyasının numarası 24 olur.
 *
 * ## Dürüstlük notu
 * `$LogFile` sıfırlanmış olarak bırakılır. Geçerli bir RSTR (restart area)
 * başlığı üretmek, içerisinde geçerli bir RCRD (log record) sayfası
 * bulunmasını da gerektirir; bunu üretmek bu kapsamın dışındadır. Windows
 * ve `ntfs-3g` bu durumda birimi açar ve günlük dosyasını sıfırlar. Aynı
 * sebeple $Extend altındaki $ObjId/$Quota/$Reparse kayıtları boş
 * (0 baytlık) oluşturulur.
 *
 * ## Öneri
 * USB belleklerde NTFS yerine **exFAT** kullanılmalıdır: exFAT TRIM'i ve
 * flash aşınma dengelemesini engellemez, Windows tarafından sürücü yazma
 * önbelleği kullanmaz ve daha hızlıdır.
 */
class NtfsFormatter(
    override val startLba: Long,
    override val sectors: Long,
    private val label: String = "USBFORGE",
    private val volumeSerial: Long = 0x20240000L,
) : FsFormatter {

    private var geo: NtfsGeometry? = null

    override suspend fun format(device: BlockDevice, progress: ProgressReporter): FormatResult {
        require(sectors >= 64L * 1024) { "NTFS için en az 32 MiB gerekir." }

        val g = NtfsGeometry.compute(sectors, volumeSerial)
        geo = g

        // --- 1. Önyükleme bölgesi -------------------------------------------
        progress.setPhase("NTFS önyükleme kaydı hazırlanıyor")
        device.prepareForWrite()
        val head = ByteArray(64 * 512)
        val boot = buildBootSector(g)
        System.arraycopy(boot, 0, head, 0, 512)                  // sektör 0
        System.arraycopy(boot, 0, head, 8 * 512, 512)           // sektör 8: yedek
        System.arraycopy("NTFS    ".toByteArray(Charsets.US_ASCII), 0, head, 16 * 512, 8)
        device.write(startLba, head) { progress.addWritten(it) }

        // --- 2. $MFT ---------------------------------------------------------
        progress.setPhase("NTFS \$MFT yazılıyor (16 sistem kaydı)")
        val mft = ByteArray((g.mftClusters * g.clusterSize).toInt())
        var o = 0
        for (record in buildSystemRecords(g)) {
            System.arraycopy(record, 0, mft, o, g.recordSize)
            o += g.recordSize
        }
        device.write(startLba + g.lbaOf(g.mftLcn), mft) { progress.addWritten(it) }

        // --- 3. $MFTMirr -----------------------------------------------------
        progress.setPhase("NTFS \$MFTMirr yazılıyor")
        val mirror = ByteArray((g.mftMirrorClusters * g.clusterSize).toInt())
        System.arraycopy(mft, 0, mirror, 0, 4 * g.recordSize)
        device.write(startLba + g.lbaOf(g.mftMirrorLcn), mirror) { progress.addWritten(it) }

        // --- 4. $Bitmap ------------------------------------------------------
        progress.setPhase("NTFS küme bitmap'i yazılıyor (${g.bitmapClusters} küme)")
        val bitmap = ByteArray((g.bitmapClusters * g.clusterSize).toInt())
        // 0..sonSistemKumesi arası **her** küme "dolu" işaretlenir
        // (1 bit = 1 küme). NTFS küme 0'ı da kullanılıyor sayar; önyükleme
        // alanı 0-3 küme aralığında olduğu için bu doğrudur.
        for (cluster in 0..g.lastSystemCluster) {
            val byteIndex = (cluster / 8).toInt()
            if (byteIndex >= bitmap.size) break
            bitmap[byteIndex] = (bitmap[byteIndex].toInt() or (1 shl (cluster % 8).toInt())).toByte()
        }
        device.write(startLba + g.lbaOf(g.bitmapLcn), bitmap) { progress.addWritten(it) }

        // --- 5. $UpCase ------------------------------------------------------
        progress.setPhase("NTFS \$UpCase tablosu yazılıyor (128 KiB)")
        val upcase = ByteArray((g.upCaseClusters * g.clusterSize).toInt())
        buildUpCase(upcase)
        device.write(startLba + g.lbaOf(g.upCaseLcn), upcase) { progress.addWritten(it) }

        // --- 6. $LogFile (sıfırlanmış) --------------------------------------
        progress.setPhase("NTFS \$LogFile hazırlanıyor (${g.logFileClusters * g.clusterSize / 1024} KiB)")
        val log = ByteArray((g.logFileClusters * g.clusterSize).toInt())
        device.write(startLba + g.lbaOf(g.logFileLcn), log) { progress.addWritten(it) }

        // --- 7. $AttrDef -----------------------------------------------------
        progress.setPhase("NTFS \$AttrDef yazılıyor")
        val attrDef = ByteArray((g.attrDefClusters * g.clusterSize).toInt())
        buildAttrDef(attrDef)
        device.write(startLba + g.lbaOf(g.attrDefLcn), attrDef) { progress.addWritten(it) }

        // --- 8. Kök dizin $INDEX_ALLOCATION + bitmap ------------------------
        progress.setPhase("NTFS kök dizini yazılıyor")
        val indexAlloc = ByteArray((g.indexClusters * g.clusterSize).toInt())
        buildIndxRecord(indexAlloc, 0, g.indexRecordSize)
        device.write(startLba + g.lbaOf(g.indexAllocLcn), indexAlloc) { progress.addWritten(it) }

        val indexBitmap = ByteArray((g.indexBitmapClusters * g.clusterSize).toInt())
        device.write(startLba + g.lbaOf(g.indexBitmapLcn), indexBitmap) { progress.addWritten(it) }

        device.flush()

        return FormatResult(
            fileSystem = "NTFS",
            clusterSizeBytes = g.clusterSize,
            totalClusters = g.totalClusters,
            label = label,
            notes = listOf(
                "Küme boyutu: ${g.clusterSize / 1024} KiB",
                "\$MFT: LCN ${g.mftLcn} · kayıt boyutu ${g.recordSize} bayt",
                "\$Bitmap: LCN ${g.bitmapLcn} · ${g.bitmapClusters} küme",
                "Toplam küme: ${g.totalClusters}",
                "İlk kullanıcı dosyası MFT kaydı 24 olacak şekilde yerleştirildi",
                "USB'de exFAT daha verimlidir (TRIM + aşınma dengeleme)",
            ),
        )
    }

    // ------------------------------------------------------ sistem MFT kayıtları

    /**
     * 0..15 arasındaki kayıtları üretir.
     *
     * 16. kayıttan sonrası boş bırakılır; ilk kullanıcı dosyasının numarası
     * 24 olur (17..23 de Windows tarafından rezerve edilir).
     */
    private fun buildSystemRecords(g: NtfsGeometry): List<ByteArray> {
        val records = MutableList(SYSTEM_RECORD_COUNT) { ByteArray(g.recordSize) } // boş (kullanılmıyor)
        records[0] = mftRecord(g)
        records[5] = rootRecord(g)
        records[6] = fileRecord(g, 6, "\$Bitmap", g.bitmapLcn, g.bitmapClusters, g.bitmapClusters * g.clusterSize)
        records[7] = fileRecord(g, 7, "\$UpCase", g.upCaseLcn, g.upCaseClusters, g.upCaseClusters * g.clusterSize)
        records[8] = fileRecord(g, 8, "\$LogFile", g.logFileLcn, g.logFileClusters, g.logFileClusters * g.clusterSize)
        records[9] = fileRecord(g, 9, "\$AttrDef", g.attrDefLcn, g.attrDefClusters, g.attrDefClusters * g.clusterSize)
        records[12] = emptyDirectoryRecord(g, 12, "\$Extend", ROOT_REF)
        records[13] = fileRecord(g, 13, "\$ObjId", EXTEND_REF, 0, 0)
        records[14] = fileRecord(g, 14, "\$Quota", EXTEND_REF, 0, 0)
        records[15] = fileRecord(g, 15, "\$Reparse", EXTEND_REF, 0, 0)
        return records
    }

    /** Kayıt 0: $MFT'in kendisi. */
    private fun mftRecord(g: NtfsGeometry): ByteArray {
        val b = MftRecord(g.recordSize, 0, sequence = 1, flags = FLAG_IN_USE)
        b.addResident(AT_STANDARD_INFORMATION, null, standardInformation(), ID_SI)
        b.addResident(AT_FILE_NAME, null, fileName("\$MFT", ROOT_REF, isDirectory = false), ID_FN)
        b.addResident(AT_BITMAP, null, ByteArray(g.clusterSize), ID_MFT_BITMAP)
        b.addNonResident(
            type = AT_DATA,
            name = null,
            runs = listOf(Run(g.mftClusters, g.mftLcn)),
            allocSize = g.mftClusters * g.clusterSize,
            dataSize = g.mftClusters * g.clusterSize,
            initSize = g.mftClusters * g.clusterSize,
            idHint = ID_DATA,
        )
        return b.build()
    }

    /** Kayıt 5: kök dizin. */
    private fun rootRecord(g: NtfsGeometry): ByteArray {
        val b = MftRecord(g.recordSize, 5, sequence = 5, flags = FLAG_IN_USE or FLAG_DIRECTORY)
        b.addResident(AT_STANDARD_INFORMATION, null, standardInformation(), ID_SI)
        b.addResident(
            AT_FILE_NAME, null,
            fileName(".", ROOT_REF, isDirectory = true),
            ID_FN,
        )
        b.addResident(AT_INDEX_ROOT, null, indexRootValue(g, isExtend = false), ID_INDEX_ROOT)
        b.addNonResident(
            type = AT_INDEX_ALLOCATION,
            name = "\$I30",
            runs = listOf(Run(g.indexClusters, g.indexAllocLcn)),
            allocSize = g.indexClusters * g.clusterSize,
            dataSize = g.indexClusters * g.clusterSize,
            initSize = g.indexClusters * g.clusterSize,
            idHint = ID_INDEX_ALLOC,
        )
        b.addResident(AT_BITMAP, "\$I30", ByteArray((g.indexBitmapClusters * g.clusterSize).toInt()), ID_INDEX_BITMAP)
        return b.build()
    }

    /** Sistem dosyası (boş olmayan $DATA). */
    private fun fileRecord(
        g: NtfsGeometry,
        number: Int,
        name: String,
        lcn: Long,
        clusters: Long,
        size: Long,
    ): ByteArray {
        val b = MftRecord(g.recordSize, number, sequence = 1, flags = FLAG_IN_USE)
        b.addResident(AT_STANDARD_INFORMATION, null, standardInformation(), ID_SI)
        b.addResident(AT_FILE_NAME, null, fileName(name, ROOT_REF, isDirectory = false), ID_FN)
        b.addNonResident(
            type = AT_DATA,
            name = null,
            runs = listOf(Run(clusters, lcn)),
            allocSize = size,
            dataSize = size,
            initSize = size,
            idHint = ID_DATA,
        )
        return b.build()
    }

    /** Hiçbir şey içermeyen sistem dizini ($Extend). */
    private fun emptyDirectoryRecord(
        g: NtfsGeometry,
        number: Int,
        name: String,
        parent: Long,
    ): ByteArray {
        val b = MftRecord(g.recordSize, number, sequence = 1, flags = FLAG_IN_USE or FLAG_DIRECTORY)
        b.addResident(AT_STANDARD_INFORMATION, null, standardInformation(), ID_SI)
        b.addResident(AT_FILE_NAME, null, fileName(name, parent, isDirectory = true), ID_FN)
        b.addResident(AT_INDEX_ROOT, null, indexRootValue(g, isExtend = true), ID_INDEX_ROOT)
        return b.build()
    }

    companion object {
        /** Üretilen sistem kaydı sayısı (0..15). */
        const val SYSTEM_RECORD_COUNT = 16

        /**
         * MFT kayıt bayrakları (NTFS 3.1, bkz. $FILE_RECORD.flags).
         *
         * NTFS'te "sistem dosyası" diye bir bayrak **yoktur**; sistem
         * dosyaları yalnızca $MFT'te 24'ten küçük numarayla yer alır.
         * 0x0004 ise `IS_4` bayrağıdır ve 1024 bayttan küçük kayıtlar için
         * kullanılır — bizim kayıtlarımız 1024 bayt olduğundan bu bayrak
         * doğru olarak 0 bırakılır.
         */
        const val FLAG_IN_USE = 0x0001
        const val FLAG_DIRECTORY = 0x0002
        const val FLAG_IS_4 = 0x0004
        const val FLAG_IS_16 = 0x0008
        const val FLAG_IS_32 = 0x0010

        // Öznitelik türleri
        const val AT_STANDARD_INFORMATION = 0x10
        const val AT_ATTRIBUTE_LIST = 0x20
        const val AT_FILE_NAME = 0x30
        const val AT_OBJECT_ID = 0x40
        const val AT_SECURITY_DESCRIPTOR = 0x50
        const val AT_VOLUME_NAME = 0x60
        const val AT_VOLUME_INFORMATION = 0x70
        const val AT_DATA = 0x80
        const val AT_INDEX_ROOT = 0x90
        const val AT_INDEX_ALLOCATION = 0xA0
        const val AT_BITMAP = 0xB0
        const val AT_REPARSE_POINT = 0xC0
        const val AT_EA_INFORMATION = 0xD0
        const val AT_EA = 0xE0
        const val AT_LOGGED_UTILITY_STREAM = 0x100

        // Öznitelik kimlikleri
        private const val ID_SI = 0
        private const val ID_FN = 1
        private const val ID_DATA = 2
        private const val ID_INDEX_ROOT = 3
        private const val ID_INDEX_ALLOC = 4
        private const val ID_INDEX_BITMAP = 5
        private const val ID_MFT_BITMAP = 6

        /** Kök dizine işaret eden MFT referansı (kayıt 5, sequence 5). */
        const val ROOT_REF = 0x0005000000000005L

        /** $Extend dizinine işaret eden referans (kayıt 12, sequence 1). */
        const val EXTEND_REF = 0x000100000000000CL

        /** NTFS zaman damgaları için sabit taban değer (2001-01-01 UTC). */
        private const val NT_EPOCH = 100_000_000_000_000_000L

        // ------------------------------------------------------------- veri yapıları

        // ---------------------------------------------------------- önyükleme kaydı

        /** NTFS önyükleme kaydı (sktör 0). */
        fun buildBootSector(g: NtfsGeometry): ByteArray {
            val b = ByteArray(512)
            b[0] = 0xEB.toByte()
            b[1] = 0x52.toByte()
            b[2] = 0x90.toByte()
            System.arraycopy("NTFS    ".toByteArray(Charsets.US_ASCII), 0, b, 3, 8)

            putLe16(b, 0x0B, 512)                      // bytes per sector
            b[0x0D] = g.sectorsPerCluster.toByte()     // sectors per cluster
            putLe16(b, 0x0E, 0)                        // reserved sectors
            b[0x10] = 0                                // number of FATs
            putLe16(b, 0x11, 0)                        // root entries
            putLe16(b, 0x13, 0)                        // total sectors (16)
            b[0x15] = 0xF8.toByte()                    // media descriptor
            putLe16(b, 0x16, 0)                        // FAT size (16)
            putLe16(b, 0x18, 63)                       // sectors per track
            putLe16(b, 0x1A, 255)                      // heads
            putLe32(b, 0x1C, 0)                        // hidden sectors
            putLe32(b, 0x20, 0)                        // total sectors (32)
            putLe32(b, 0x24, 0)                        // reserved
            putLe16(b, 0x28, 0x0080)                   // reserved
            b[0x2A] = 0x80.toByte()                    // boot signature
            putLe16(b, 0x2B, 0)                        // reserved
            putLe64(b, 0x2D, g.mftLcn)                 // MFT cluster
            putLe64(b, 0x35, g.mftMirrorLcn)           // MFTMirr cluster
            // Kayıt boyutu ≥ 1024 ise 256 - log2(boyut) olarak saklanır.
            b[0x3D] = (256 - log2(g.recordSize)).toByte()
            b[0x3E] = 0
            b[0x3F] = (256 - log2(g.indexRecordSize)).toByte()
            putLe64(b, 0x40, g.volumeSerial)           // volume serial number
            putLe32(b, 0x48, 0)                        // checksum (yok sayılır)

            b[510] = 0x55
            b[511] = 0xAA.toByte()
            return b
        }

        private fun log2(v: Int): Int {
            var n = 0
            var x = v
            while (x > 1) { x = x shr 1; n++ }
            return n
        }

        // ---------------------------------------------------------------- $UpCase

        /**
         * $UpCase tablosunu doldurur: her UTF-16 kod birimi için büyük harf
         * eşlemesi (128 KiB = 65536 kod birimi).
         *
         * Windows'ın özgün tablosuyla birebir aynı olmasa da, ad sıralaması
         * ve arama dışındaki tüm NTFS işlemleri için geçerlidir.
         */
        fun buildUpCase(dest: ByteArray) {
            require(dest.size >= 131072) { "\$UpCase 128 KiB olmalı, ${dest.size} verildi." }
            for (unit in 0 until 65536) {
                val mapped = when {
                    unit in 0x61..0x7A -> unit - 32                 // a-z
                    unit in 0xE0..0xFE && unit != 0xF7 -> unit - 32 // à-þ
                    unit == 0xFF -> 0x0178                          // ÿ
                    unit == 0xB5 -> 0x039C                          // µ
                    unit in 0x3B1..0x3C9 -> unit - 32              // α-ω
                    unit in 0x430..0x44F -> unit - 32              // А-Я
                    unit in 0x450..0x45F -> unit - 80              // ѐ-џ
                    unit in 0x100..0x137 && unit != 0x131 -> unit  // Latin Ext-A: zaten büyük
                    else -> unit
                }
                dest[unit * 2] = (mapped and 0xFF).toByte()
                dest[unit * 2 + 1] = ((mapped shr 8) and 0xFF).toByte()
            }
        }

        // ---------------------------------------------------------------- $AttrDef

        /**
         * $AttrDef tablosu: 16 adet 160 baytlık tanım.
         *
         * `ntfs-3g`, adlandırılmış dizin indekslerini (`$I30` gibi)
         * çözebilmek için bu tabloyu okur; eksik olursa bazı dağıtımlar
         * birimi bağlamayı reddeder.
         */
        fun buildAttrDef(dest: ByteArray) {
            data class Def(
                val name: String, val type: Int, val display: Int,
                val collation: Int, val flags: Int, val min: Long, val max: Long,
            )
            val defs = listOf(
                Def("\$STANDARD_INFORMATION", AT_STANDARD_INFORMATION, 0x40, 0, 0, 48, 48),
                Def("\$ATTRIBUTE_LIST", AT_ATTRIBUTE_LIST, 0x80, 0, 0, 0, 0),
                Def("\$FILE_NAME", AT_FILE_NAME, 0x42, 1, 0, 68, 578),
                Def("\$OBJECT_ID", AT_OBJECT_ID, 0x40, 0, 0, 0, 256),
                Def("\$SECURITY_DESCRIPTOR", AT_SECURITY_DESCRIPTOR, 0x80, 0, 0, 0, 0),
                Def("\$VOLUME_NAME", AT_VOLUME_NAME, 0x40, 0, 0, 0, 256),
                Def("\$VOLUME_INFORMATION", AT_VOLUME_INFORMATION, 0x40, 0, 0, 12, 12),
                Def("\$DATA", AT_DATA, 0x00, 0, 0, 0, 0),
                Def("\$INDEX_ROOT", AT_INDEX_ROOT, 0x40, 0, 0, 0, 0),
                Def("\$INDEX_ALLOCATION", AT_INDEX_ALLOCATION, 0x80, 0, 0, 0, 0),
                Def("\$BITMAP", AT_BITMAP, 0x80, 0, 0, 0, 0),
                Def("\$REPARSE_POINT", AT_REPARSE_POINT, 0x40, 0, 0, 0, 16384),
                Def("\$EA_INFORMATION", AT_EA_INFORMATION, 0x40, 0, 0, 8, 8),
                Def("\$EA", AT_EA, 0x00, 0, 0, 0, 65536),
                Def("\$LOGGED_UTILITY_STREAM", AT_LOGGED_UTILITY_STREAM, 0x40, 0, 0, 0, 65536),
                Def("\$INDEX_ROOT\$I30", AT_INDEX_ROOT, 0x40, 1, 0, 0, 0),
            )
            defs.forEachIndexed { i, d ->
                val o = i * 160
                if (o + 160 > dest.size) return@forEachIndexed
                val nameBytes = d.name.toByteArray(Charsets.UTF_16LE)
                System.arraycopy(nameBytes, 0, dest, o, minOf(nameBytes.size, 128))
                putLe32(dest, o + 128, d.type.toLong())
                putLe32(dest, o + 132, d.display.toLong())
                putLe32(dest, o + 136, d.collation.toLong())
                putLe32(dest, o + 140, d.flags.toLong())
                putLe32(dest, o + 144, d.min)
                putLe32(dest, o + 152, (d.max shr 32))
                putLe32(dest, o + 156, d.max and 0xFFFFFFFFL)
            }
        }

        // ------------------------------------------------------------------ INDX

        /**
         * Boş bir INDX kaydı oluşturur (kök dizin $INDEX_ALLOCATION'ın ilk
         * kümesi).
         *
         * @param recordSize INDX kaydının bayt cinsinden boyutu (genelde 4096)
         */
        fun buildIndxRecord(dest: ByteArray, offset: Int, recordSize: Int) {
            System.arraycopy("INDX".toByteArray(Charsets.US_ASCII), 0, dest, offset, 4)
            putLe16(dest, offset + 0x04, 0x0028)            // uzama dizisi ofseti
            putLe16(dest, offset + 0x06, 0)                 // (build sonunda doldurulur)
            putLe32(dest, offset + 0x10, 0)                 // VCN

            // INDEX_HEADER (kaydın 0x18 ofsetinde)
            putLe32(dest, offset + 0x18, 0x00000028)        // girdi ofseti
            putLe32(dest, offset + 0x1C, 0x00000028)        // indeks uzunluğu
            putLe32(dest, offset + 0x20, 1)                 // ayrılan boyut (küme)
            putLe32(dest, offset + 0x24, 0)                 // bayraklar

            // 16 baytlık sonlandırıcı giriş (uzunlık alanı 0x10)
            putLe16(dest, offset + 0x28, 0x0010)
            putLe16(dest, offset + 0x2A, 0x0000)
            putLe32(dest, offset + 0x2C, 0x00000000)

            applyFixups(dest, offset, recordSize, 0x0028, 0x0001)
        }

        /**
         * NTFS "fixup" (Update Sequence Array) uygular.
         *
         * Her 512 baytlık sektörün son 2 baytı, ABD (Update Sequence Block
         * Descriptor) değeriyle değiştirilmiştir. Disk okunurken bu sektör
         * geri yüklenir; böylece kaba güç kesintisi sırasında yarım yazılmış
         * bir sektör "bozuk" olarak algılanır.
         *
         * @param usaOffset uzama dizisinin kayıt içindeki ofseti
         * @param abd        ABD değeri (genelde 0x0001)
         */
        fun applyFixups(buf: ByteArray, offset: Int, recordSize: Int, usaOffset: Int, abd: Int) {
            val sectorCount = recordSize / 512
            require(usaOffset + (sectorCount + 1) * 2 <= recordSize) { "Uzama dizisi kayda sığmıyor." }

            // 1) Orijinal değerleri sakla
            val saved = IntArray(sectorCount + 1)
            saved[0] = abd
            for (i in 1..sectorCount) {
                saved[i] = ((buf[offset + i * 512 - 2].toInt() and 0xFF) shl 8) or
                        (buf[offset + i * 512 - 1].toInt() and 0xFF)
            }
            // 2) ABD'yi yerleştir. NTFS, her sektörün **son 2 baytına**
            //    ABD'nin *küçük bayt* sırasıyla yazılmasını ister.
            for (i in 1..sectorCount) {
                buf[offset + i * 512 - 2] = (abd and 0xFF).toByte()
                buf[offset + i * 512 - 1] = ((abd shr 8) and 0xFF).toByte()
            }
            // 3) Uzama dizisini yaz
            putLe16(buf, offset + 0x04, usaOffset)
            putLe16(buf, offset + 0x06, sectorCount + 1)
            for (i in 0..sectorCount) {
                putLe16(buf, offset + usaOffset + i * 2, saved[i])
            }
        }

        // -------------------------------------------------------- mapping pairs

        /**
         * Run list (mapping pairs) kodlaması.
         *
         * Her çalıştırma için 1 başlık baytı:
         * `bits(kümeSayısı) | ((bits(delta) - 1) << 4)`
         * ardından küme sayısı ve LBA farkı, 7'bit gruplar hâlinde küçük
         * bayt sırasıyla yazılır. Diziyi `0x00` sonlandırır.
         *
         * @param runs her biri için `lbaDelta` **mutlak** LBA'dır; bu metot
         *             farkı kendisi hesaplar.
         */
        fun encodeRuns(runs: List<Run>): ByteArray {
            val out = ByteArrayOutputStream()
            var previousStart = 0L
            for ((index, run) in runs.withIndex()) {
                val delta = if (index == 0) run.lbaDelta else run.lbaDelta - previousStart
                require(delta > 0) { "Run listesi artan sırada olmalı." }
                val lengthBits = bitLength(run.lengthClusters)
                val deltaBits = bitLength(delta)
                val header = (lengthBits and 0x0F) or (((deltaBits - 1) and 0x0F) shl 4)
                out.write(header)
                writeVariable(out, run.lengthClusters)
                writeVariable(out, delta)
                previousStart = run.lbaDelta
            }
            out.write(0x00)
            return out.toByteArray()
        }

        private fun bitLength(v: Long): Int {
            var n = 0
            var x = v
            while (x > 0) { n++; x = x shr 1 }
            return n
        }

        private fun writeVariable(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while (v > 0) {
                out.write((v and 0x7F).toInt())
                v = v shr 7
            }
        }

        // ------------------------------------------------------- öznitelik değerleri

        /** $STANDARD_INFORMATION (48 bayt). */
        fun standardInformation(
            now: Long = NT_EPOCH,
            flags: Long = 0x00000020L,
        ): ByteArray {
            val b = ByteArray(48)
            putLe64(b, 0, now)   // oluşturma
            putLe64(b, 8, now)   // veri değişikliği
            putLe64(b, 16, now)  // MFT değişikliği
            putLe64(b, 24, now)  // erişim
            putLe32(b, 32, flags) // dosya bayrakları
            putLe32(b, 36, 0)    // max versions
            putLe32(b, 40, 0)    // version number
            putLe32(b, 44, 0)    // class id
            return b
        }

        /** $FILE_NAME değeri (ad + zamanlar + boyutlar). */
        fun fileName(
            name: String,
            parent: Long,
            isDirectory: Boolean,
            allocSize: Long = 0,
            dataSize: Long = 0,
            now: Long = NT_EPOCH,
        ): ByteArray {
            val nameBytes = name.toByteArray(Charsets.UTF_16LE)
            val b = ByteArray(66 + nameBytes.size)
            putLe64(b, 0, parent)
            putLe64(b, 8, now)
            putLe64(b, 16, now)
            putLe64(b, 24, now)
            putLe64(b, 32, now)
            putLe64(b, 40, allocSize)
            putLe64(b, 48, dataSize)
            putLe32(b, 56, if (isDirectory) 0x10000000L else 0x00000020L)
            putLe32(b, 60, 0)
            b[64] = name.length.toByte()
            b[65] = 1 // UTF-16LE (Win32 & DOS)
            System.arraycopy(nameBytes, 0, b, 66, nameBytes.size)
            return b
        }

        /**
         * $INDEX_ROOT değeri (boş dizin için).
         *
         * Dizin indekslemesi `COLLATION_FILE_NAME` (1) ile sıralanır ve
         * girdiler `$FILE_NAME` özniteliklerinin karşılaştırılmasıyla
         * üretilir.
         */
        fun indexRootValue(g: NtfsGeometry, isExtend: Boolean): ByteArray {
            val b = ByteArray(16 + 16 + 16)
            putLe32(b, 0, 0x00000020)             // öznitelikler: INDEXED
            putLe32(b, 4, 1)                      // harmanlama kuralı: FILE_NAME
            putLe32(b, 8, g.indexRecordSize.toLong()) // indeks kaydı başına bayt
            b[12] = (g.indexRecordSize / g.clusterSize).toByte()
            b[13] = 0
            b[14] = 0
            b[15] = 0
            // INDEX_HEADER (ofset 0x10)
            putLe32(b, 0x10, 0x00000020)          // girdi ofseti
            putLe32(b, 0x14, 0x00000020)          // indeks uzunluğu
            putLe32(b, 0x18, if (isExtend) 0 else g.indexClusters) // ayrılan boyut
            putLe32(b, 0x1C, 0)                   // bayraklar
            // Sonlandırıcı giriş
            putLe16(b, 0x20, 0x0010)
            putLe16(b, 0x22, 0x0000)
            putLe32(b, 0x24, 0x00000000)
            return b
        }

        // ---------------------------------------------------------- endian yardımı

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

/**
 * Bir dosyanın fiziksel yerleşimi (NTFS "run").
 *
 * @param lengthClusters bu çalıştırmadaki küme sayısı
 * @param lbaDelta       çalıştırmanın mutlak başlangıç LBA'sı
 *                       ([encodeRuns] ilk çalıştırmada mutlak, sonrakilerde
 *                       fark olarak yorumlar)
 */
data class Run(val lengthClusters: Long, val lbaDelta: Long)
/**
 * Tek bir MFT kaydı (FILE kaydı) oluşturan ve fixup uygulayan yapıcı.
 *
 * Kayıt düzeni (NTFS 3.1, bkz. [MFT_RECORD]):
 * ```
 * 0x00 4   "FILE"
 * 0x04 2   usa_ofs
 * 0x06 2   usa_count
 * 0x08 8   $LogFile LSN
 * 0x10 2   sequence number
 * 0x12 2   hard link count
 * 0x14 2   attrs offset
 * 0x16 2   flags
 * 0x18 4   bytes in use
 * 0x1C 4   bytes allocated
 * 0x20 8   base MFT record
 * 0x28 2   next attribute id
 * 0x2A 2   reserved
 * 0x2C 4   MFT record number
 * 0x30 ..  uzama dizisi (usa_count × 2 bayt)
 * 0x38 ..  öznitelikler, 0xFFFFFFFF ile sonlanır
 * ```
 */
class MftRecord(
    private val recordSize: Int,
    recordNumber: Int,
    sequence: Int,
    flags: Int,
) {
    private val buf = ByteArray(recordSize)
    private var pos = ATTRS_OFFSET

    /** 8 bayta hizalı sonraki öznitelik ofseti. */
    private var nextAttrId = 0x0000

    init {
        System.arraycopy("FILE".toByteArray(Charsets.US_ASCII), 0, buf, 0, 4)
        // 0x04 / 0x06 (usa) build() sırasında doldurulur
        putLe16(buf, 0x10, sequence)                 // sequence number
        putLe16(buf, 0x12, 1)                        // hard link count
        putLe16(buf, 0x14, ATTRS_OFFSET)             // attributes offset
        putLe16(buf, 0x16, flags)                    // flags
        putLe32(buf, 0x1C, recordSize.toLong())               // bytes allocated
        putLe64(buf, 0x20, 0)                        // base record
        putLe32(buf, 0x2C, recordNumber.toLong())    // record number
    }

    /** Yerleşik (resident) öznitelik ekler. */
    fun addResident(type: Int, name: String?, value: ByteArray, idHint: Int): MftRecord {
        val nameBytes = name?.toByteArray(Charsets.UTF_16LE) ?: ByteArray(0)
        val valueOffset = 0x18 + nameBytes.size
        val length = align8(valueOffset + value.size)
        if (pos + length + 8 > recordSize) return this // sığmıyorsa kayıt bozulmasın

        val o = pos
        putLe32(buf, o, type.toLong())
        putLe32(buf, o + 4, length.toLong())
        buf[o + 8] = 0                                     // non-resident = 0
        buf[o + 9] = (nameBytes.size / 2).toByte()
        putLe16(buf, o + 10, if (nameBytes.isEmpty()) 0 else 0x18)
        putLe16(buf, o + 12, 0)                            // flags
        putLe16(buf, o + 14, (idHint or nextAttrId).toShort().toInt())
        putLe32(buf, o + 16, value.size.toLong())          // value length
        putLe16(buf, o + 20, valueOffset)                  // value offset
        buf[o + 22] = 0                                    // indexed flag
        buf[o + 23] = 0                                    // padding
        System.arraycopy(nameBytes, 0, buf, o + 0x18, nameBytes.size)
        System.arraycopy(value, 0, buf, o + valueOffset, value.size)

        pos = o + length
        nextAttrId += 0x01
        return this
    }

    /**
     * Yerleşik olmayan (non-resident) öznitelik ekler.
     *
     * @param runs dosyanın fiziksel çalıştırmaları (mutlak LBA)
     */
    fun addNonResident(
        type: Int,
        name: String?,
        runs: List<Run>,
        allocSize: Long,
        dataSize: Long,
        initSize: Long,
        idHint: Int,
    ): MftRecord {
        val nameBytes = name?.toByteArray(Charsets.UTF_16LE) ?: ByteArray(0)
        val pairs = NtfsFormatter.encodeRuns(runs)
        val pairsOffset = 0x40 + nameBytes.size
        val length = align8(pairsOffset + pairs.size)
        if (pos + length + 8 > recordSize) return this

        val totalClusters = runs.sumOf { it.lengthClusters }
        val highestVcn = (totalClusters - 1).coerceAtLeast(0)

        val o = pos
        putLe32(buf, o, type.toLong())
        putLe32(buf, o + 4, length.toLong())
        buf[o + 8] = 1                                     // non-resident = 1
        buf[o + 9] = (nameBytes.size / 2).toByte()
        putLe16(buf, o + 10, if (nameBytes.isEmpty()) 0 else 0x40)
        putLe16(buf, o + 12, 0)                            // flags
        putLe16(buf, o + 14, (idHint or nextAttrId).toShort().toInt())
        putLe64(buf, o + 16, 0)                            // lowest VCN
        putLe64(buf, o + 24, highestVcn.toLong())                   // highest VCN
        putLe16(buf, o + 32, pairsOffset)                  // mapping pairs offset
        putLe16(buf, o + 34, 0)                            // compression unit
        putLe32(buf, o + 36, 0)                            // reserved
        putLe64(buf, o + 40, allocSize)
        putLe64(buf, o + 48, dataSize)
        putLe64(buf, o + 56, initSize)
        System.arraycopy(nameBytes, 0, buf, o + 0x40, nameBytes.size)
        System.arraycopy(pairs, 0, buf, o + pairsOffset, pairs.size)

        pos = o + length
        nextAttrId += 0x01
        return this
    }

    /** Kaydı tamamlar: end marker, bytes_in_use ve fixup uygulanır. */
    fun build(): ByteArray {
        putLe32(buf, pos, 0xFFFFFFFFL)          // öznitelik sonlandırıcısı
        putLe32(buf, 0x18, (pos + 4).toLong()) // bytes in use
        putLe16(buf, 0x28, (nextAttrId + 1).toShort().toInt()) // next attribute id

        val recordNumber = Fat32Formatter.readLe32(buf, 0x2C).toInt()
        val abd = recordNumber and 0xFFFF
        NtfsFormatter.applyFixups(buf, 0, recordSize, USA_OFFSET, if (abd == 0) 1 else abd)
        return buf
    }

    private fun align8(v: Int) = (v + 7) and 7.inv()

    private companion object {
        const val USA_OFFSET = 0x30
        const val ATTRS_OFFSET = 0x38

        fun putLe16(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        fun putLe32(b: ByteArray, off: Int, v: Long) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
            b[off + 2] = ((v shr 16) and 0xFF).toByte()
            b[off + 3] = ((v shr 24) and 0xFF).toByte()
        }

        fun putLe64(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
    }
}

/** NTFS hacminin geometrik parametreleri. */
data class NtfsGeometry(
    val totalSectors: Long,
    val sectorsPerCluster: Int,
    val recordSize: Int,
    val indexRecordSize: Int,
    val totalClusters: Long,
    val mftLcn: Long,
    val mftClusters: Long,
    val mftMirrorLcn: Long,
    val mftMirrorClusters: Long,
    val bitmapLcn: Long,
    val bitmapClusters: Long,
    val upCaseLcn: Long,
    val upCaseClusters: Long,
    val logFileLcn: Long,
    val logFileClusters: Long,
    val attrDefLcn: Long,
    val attrDefClusters: Long,
    val indexAllocLcn: Long,
    val indexClusters: Long,
    val indexBitmapLcn: Long,
    val indexBitmapClusters: Long,
    val lastSystemCluster: Long,
    val volumeSerial: Long,
) {
    val clusterSize: Int get() = sectorsPerCluster * 512

    /** Küme numarasını diske göre mutlak LBA'ya çevirir. */
    fun lbaOf(cluster: Long): Long = RESERVED_SECTORS + cluster * sectorsPerCluster

    companion object {
        /** NTFS ilk 8 sektörü ayırır. */
        const val RESERVED_SECTORS = 8L

        /** $MFT sabit olarak 4. kümeden başlar (Windows `mkntfs` ile uyumlu). */
        const val MFT_START_LCN = 4L

        const val UPCASE_SIZE = 131072L      // 128 KiB
        const val LOGFILE_CLUSTERS = 64L    // 4 KiB kümeyle 256 KiB
        const val ATTRDEF_SIZE = 2560L      // 16 × 160 bayt

        /**
         * Geometriyi hesaplar.
         *
         * $Bitmap tüm küme havuzunu kapsamak zorundadır; boyutu bu yüzden
         * küme sayısına bağlıdır ve 2 iterasyonda kesinleşir.
         */
        fun compute(totalSectors: Long, volumeSerial: Long = 0x20240000L): NtfsGeometry {
            val spc = 8                 // 4 KiB küme — Windows'ın varsayılanı
            val clusterSize = spc * 512
            val recordSize = 1024
            val indexRecordSize = 4096

            val totalClusters = (totalSectors - RESERVED_SECTORS) / spc
            val mftClusters = 4L      // 16 KiB = 16 kayıt (0..15)
            val mftMirrorClusters = 4L

            var bitmapClusters = -1L
            var stable = false
            var iteration = 0
            while (!stable && iteration++ < 8) {
                val bytesNeeded = (totalClusters + 7) / 8
                val clusters = (bytesNeeded + clusterSize - 1) / clusterSize
                if (clusters == bitmapClusters) stable = true else bitmapClusters = clusters
            }
            require(bitmapClusters > 0) { "\$Bitmap boyutu hesaplanamadı." }

            val mftLcn = MFT_START_LCN
            val mftMirrorLcn = mftLcn + mftClusters
            val bitmapLcn = mftMirrorLcn + mftMirrorClusters
            val upCaseLcn = bitmapLcn + bitmapClusters
            val upCaseClusters = UPCASE_SIZE / clusterSize
            val logFileLcn = upCaseLcn + upCaseClusters
            val logFileClusters = LOGFILE_CLUSTERS
            val attrDefLcn = logFileLcn + logFileClusters
            val attrDefClusters = (ATTRDEF_SIZE + clusterSize - 1) / clusterSize
            val indexAllocLcn = attrDefLcn + attrDefClusters
            val indexClusters = 1L
            val indexBitmapLcn = indexAllocLcn + indexClusters
            val indexBitmapClusters = 1L
            val lastSystemCluster = indexBitmapLcn + indexBitmapClusters - 1

            require(lastSystemCluster + 64 < totalClusters) {
                "NTFS için yeterli alan yok ($totalSectors sektör)."
            }

            return NtfsGeometry(
                totalSectors = totalSectors,
                sectorsPerCluster = spc,
                recordSize = recordSize,
                indexRecordSize = indexRecordSize,
                totalClusters = totalClusters,
                mftLcn = mftLcn,
                mftClusters = mftClusters,
                mftMirrorLcn = mftMirrorLcn,
                mftMirrorClusters = mftMirrorClusters,
                bitmapLcn = bitmapLcn,
                bitmapClusters = bitmapClusters,
                upCaseLcn = upCaseLcn,
                upCaseClusters = upCaseClusters,
                logFileLcn = logFileLcn,
                logFileClusters = logFileClusters,
                attrDefLcn = attrDefLcn,
                attrDefClusters = attrDefClusters,
                indexAllocLcn = indexAllocLcn,
                indexClusters = indexClusters,
                indexBitmapLcn = indexBitmapLcn,
                indexBitmapClusters = indexBitmapClusters,
                lastSystemCluster = lastSystemCluster,
                volumeSerial = volumeSerial,
            )
        }
    }
}
