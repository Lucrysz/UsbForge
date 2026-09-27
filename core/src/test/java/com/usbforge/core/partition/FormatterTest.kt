package com.usbforge.core.partition

import com.usbforge.core.test.RecordingReporter
import com.usbforge.core.block.FakeBlockDevice
import com.usbforge.core.disk.DiskWriter
import com.usbforge.core.fs.ExFatFormatter
import com.usbforge.core.fs.Fat32Formatter
import com.usbforge.core.fs.Fat32Writer
import com.usbforge.core.fs.NtfsFormatter
import com.usbforge.core.util.Bytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Bölünt tabloları ve dosya sistemi biçimlendiricilerinin doğrulaması.
 *
 * Hepsi bellek içi [FakeBlockDevice] üzerinde çalışır: SDK, cihaz veya
 * emülatör gerekmez. `./gradlew :core:test` ile koşar.
 */
class FormatterTest {

    // 64 MiB sahte cihaz — FAT32/exFAT/NTFS için yeterli
    private val sectors = 64L * 1024 * 1024 / 512

    // -------------------------------------------------------------- MBR

    @Test
    @DisplayName("MBR: 55AA imzası, bölüntü girdisi ve CHS/LBA doğru yazılır")
    fun mbrStructure() {
        val mbr = MbrTable.build(
            entries = listOf(
                MbrTable.Entry(type = 0x0C, startLba = 2048, sectors = 131_072, bootable = true)
            ),
            diskSectors = sectors,
        )

        assertEquals(512, mbr.size)
        assertEquals(0x55, mbr[510].toInt() and 0xFF)
        assertEquals(0xAA, mbr[511].toInt() and 0xFF)

        // 1. giriş 0x1BE
        val off = 0x1BE
        assertEquals(0x80, mbr[off].toInt() and 0xFF, "önyüklenebilir bayrağı")
        assertEquals(0x0C, mbr[off + 4].toInt() and 0xFF, "FAT32 LBA tipi")

        val lba = readLe32(mbr, off + 12)
        val count = readLe32(mbr, off + 8)
        assertEquals(2048L, lba, "başlangıç LBA")
        assertEquals(131_072L, count, "sektör sayısı")

        // Kullanılmayan 2..4. girişler boş olmalı
        for (i in 1..3) {
            assertEquals(0, mbr[0x1BE + i * 16].toInt() and 0xFF, "${i + 1}. giriş boş olmalı")
        }
    }

    @Test
    @DisplayName("MBR: 528 MiB üstü bölüntülerde CHS alanı LBA kipine geçer")
    fun mbrChsForLargeLba() {
        // 1 GiB = 2M sektör, CHS sınırının (1,032,960) çok üstünde
        val (head, cylSector, cyl) = MbrTable.chs(2_097_152L)
        // LBA-in-CHS paketlemesi: sector alanının üst 3 biti 1 olmalı (0xE0 maskesi)
        assertTrue(cylSector and 0xE0 == 0xE0, "CHS LBA kipi aktif olmalı")
        assertEquals(((2_097_152L shr 24) and 0xFF).toInt(), head)
        assertEquals(((2_097_152L shr 16) and 0xFF).toInt(), cyl)

        // Küçük LBA klasik CHS olarak kodlanmalı
        val (h2, cs2, c2) = MbrTable.chs(2048L)
        assertEquals(2048L % 63 + 1, (cs2 and 0x3F).toLong())
        assertEquals((2048L / 63 / 255).toInt(), c2)
    }

    @Test
    @DisplayName("MBR: koruyucu MBR 0xEE tipinde ve diski kapsar")
    fun protectiveMbr() {
        val pmbr = MbrTable.protectiveMbr(sectors)
        assertEquals(0xEE, pmbr[0x1BE + 4].toInt() and 0xFF)
        assertEquals(1L, readLe32(pmbr, 0x1BE + 12), "koruyucu MBR 1. sektörden başlar")
        assertEquals(sectors - 1, readLe32(pmbr, 0x1BE + 8))
    }

    // -------------------------------------------------------------- GPT

    @Test
    @DisplayName("GPT: 'EFI PART' imzası, CRC'ler ve bölüntü aralığı doğru")
    fun gptStructure() {
        val entry = GptTable.Entry(
            typeGuid = Guid.Types.BASIC_DATA,
            uniqueGuid = Guid.format(Guid.random()),
            name = "USBEXFAT",
            firstLba = 2048,
            lastLba = sectors - 34,
        )
        val layout = GptTable.layout(sectors, listOf(entry))

        // Başlık imzası
        assertEquals("EFI PART", String(layout.primaryHeader, 0, 8, Charsets.US_ASCII))
        assertEquals(1L, readLe32(layout.primaryHeader, 0x08), "revizyon 1.0")
        assertEquals(92L, readLe32(layout.primaryHeader, 0x0C), "başlık boyutu")

        // MyLBA / AlternateLBA
        assertEquals(1L, readLe64(layout.primaryHeader, 0x18))
        assertEquals(sectors - 1, readLe64(layout.primaryHeader, 0x20))

        // Giriş dizisi 128 × 128 bayt = 16384
        assertEquals(16_384, layout.entryArray.size)
        assertEquals(128L, readLe32(layout.primaryHeader, 0x50))
        assertEquals(128L, readLe32(layout.primaryHeader, 0x54))
        assertEquals(2L, readLe64(layout.primaryHeader, 0x48), "giriş dizisi LBA 2")

        // Yedek başlık ters yönde
        assertEquals("EFI PART", String(layout.backupHeader, 0, 8, Charsets.US_ASCII))
        assertEquals(sectors - 1, readLe64(layout.backupHeader, 0x18))

        // İlk giriş: tip, ad, LBA aralığı
        assertEquals(
            Guid.Types.BASIC_DATA,
            Guid.format(Guid.fromBytes(layout.entryArray, 0)),
            "bölüntü tip GUID'i",
        )
        assertEquals(2048L, readLe64(layout.entryArray, 32), "first LBA")
        assertEquals(sectors - 34, readLe64(layout.entryArray, 40), "last LBA")
        assertEquals("USBEXFAT", utf16Name(layout.entryArray, 56))
    }

    @Test
    @DisplayName("GPT: başlık CRC'si CRC alanı sıfırlandıktan sonra hesaplanır")
    fun gptHeaderCrc() {
        val layout = GptTable.layout(
            sectors,
            listOf(
                GptTable.Entry(Guid.Types.BASIC_DATA, Guid.format(Guid.random()), "T", 2048, sectors - 34)
            ),
        )
        val stored = readLe32(layout.primaryHeader, 0x10)

        // CRC alanını sıfırla ve yeniden hesapla
        val copy = layout.primaryHeader.copyOf()
        copy[0x10] = 0; copy[0x11] = 0; copy[0x12] = 0; copy[0x13] = 0
        val computed = Crc32.compute(copy, 0, 92)

        assertEquals(computed, stored, "başlık CRC'si tutarsız")
    }

    @Test
    @DisplayName("GPT: giriş dizisi CRC'si 128 girişin tamamı üzerinden hesaplanır")
    fun gptEntryArrayCrc() {
        val layout = GptTable.layout(
            sectors,
            listOf(
                GptTable.Entry(Guid.Types.BASIC_DATA, Guid.format(Guid.random()), "T", 2048, sectors - 34)
            ),
        )
        val stored = readLe32(layout.primaryHeader, 0x58)
        val computed = Crc32.compute(layout.entryArray)
        assertEquals(computed, stored, "giriş dizisi CRC'si tutarsız")
    }

    @Test
    @DisplayName("GPT: kullanılabilir alan dışına taşan bölüntü reddedilir")
    fun gptRejectsOutOfRange() {
        val bad = GptTable.Entry(
            Guid.Types.BASIC_DATA, Guid.format(Guid.random()), "X", 10, sectors - 1
        )
        val error = runCatching { GptTable.layout(sectors, listOf(bad)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "sınır dışı bölüntü reddedilmeliydi")
    }

    @Test
    @DisplayName("CRC-32: '123456789' için 0xCBF43926 (bilinen vektör)")
    fun crc32KnownVector() {
        val crc = Crc32.compute("123456789".toByteArray(Charsets.US_ASCII))
        assertEquals(0xCBF43926, crc)
    }

    // ------------------------------------------------------------ FAT32

    @Test
    @DisplayName("FAT32: BPB alanları, önyükleme kodu ve FSInfo imzaları doğru")
    fun fat32BootSector() {
        val dev = FakeBlockDevice(sectors)
        val f = Fat32Formatter(startLba = 0, sectors = sectors, label = "USBFORGE")
        val result = f.format(dev, RecordingReporter())

        assertEquals("FAT32", result.fileSystem)
        assertTrue(dev.prepareCalled, "prepareForWrite çağrılmalıydı")
        assertTrue(dev.flushCount > 0, "flush çağrılmalıydı")

        val b = dev.sector(0)
        // Jump + OEM
        assertEquals(0xEB, b[0].toInt() and 0xFF)
        assertEquals("MSWIN4.1", String(b, 3, 8, Charsets.US_ASCII))

        // BPB
        assertEquals(512, le16(b, 0x0B), "bayt/sektör")
        val spc = b[0x0D].toInt() and 0xFF
        assertTrue(spc in intArrayOf(1, 2, 4, 8, 16, 32, 64, 128).toList(), "geçerli küme/sektör")
        assertEquals(32, le16(b, 0x0E), "ayrılmış sektör")
        assertEquals(2, b[0x10].toInt() and 0xFF, "2 FAT")
        assertEquals(0, le16(b, 0x11), "kök giriş sayısı 0")
        assertEquals(0xF8, b[0x15].toInt() and 0xFF, "medya tanımlayıcı")
        assertEquals(0, le16(b, 0x16), "FAT16 boyutu 0")
        assertEquals(2L, readLe32(b, 0x2C), "kök kümesi 2")
        assertEquals(1, le16(b, 0x30), "FSInfo sektörü 1")
        assertEquals(6, le16(b, 0x32), "yedek önyükleme 6")
        assertEquals(0x29, b[0x42].toInt() and 0xFF, "genişletilmiş önyükleme imzası")
        assertEquals("USBFORGE    ", String(b, 0x47, 11, Charsets.US_ASCII), "birim etiketi")
        assertEquals("FAT32   ", String(b, 0x52, 8, Charsets.US_ASCII))

        // 55AA imzası
        assertEquals(0x55, b[510].toInt() and 0xFF)
        assertEquals(0xAA, b[511].toInt() and 0xFF)

        // Bootstrap kodu 0x5A'da
        assertEquals(0x31, b[0x5A].toInt() and 0xFF, "xor ax,ax")
        assertEquals(0xC0, b[0x5B].toInt() and 0xFF)
    }

    @Test
    @DisplayName("FAT32: FSInfo imzaları ve boş küme sayacı")
    fun fat32FsInfo() {
        val dev = FakeBlockDevice(sectors)
        Fat32Formatter(0, sectors).format(dev, RecordingReporter())

        val info = dev.sector(1)
        assertEquals(0x41615252L, readLe32(info, 0), "lead signature")
        assertEquals(0x61417272L, readLe32(info, 4), "structure signature")
        assertTrue(readLe32(info, 8) > 0, "boş küme sayacı pozitif olmalı")
        assertEquals(0xAA550000L, readLe32(info, 0x10), "trail signature")
    }

    @Test
    @DisplayName("FAT32: yedek önyükleme kaydı 6. sektörde bir kopyadır")
    fun fat32BackupBoot() {
        val dev = FakeBlockDevice(sectors)
        Fat32Formatter(0, sectors).format(dev, RecordingReporter())
        assertTrue(dev.sector(0).contentEquals(dev.sector(6)), "6. sektör yedek önyükleme olmalı")
        assertTrue(dev.sector(1).contentEquals(dev.sector(7)), "7. sektör yedek FSInfo olmalı")
    }

    @Test
    @DisplayName("FAT32: kök dizin kümesi 2, FAT'te son kume olarak işaretli")
    fun fat32RootCluster() {
        val dev = FakeBlockDevice(sectors)
        val f = Fat32Formatter(0, sectors)
        f.format(dev, RecordingReporter())

        val geo = Fat32Geometry.compute(sectors)
        val fatSector = dev.sector(geo.fatOffset)
        // 2. küme girişi 0xFFFFFFFF (EOC)
        val entry2 = readLe32(fatSector, ((2 - 2) * 4).toInt())
        assertEquals(0xFFFFFFFFL, entry2, "2. küme (kök dizin) zincir sonu olmalı")

        // 0. küme 0xFFFFFFF8
        assertEquals(0xFFFFFFF8L, readLe32(fatSector, 0), "0. küme medya + EOC")
        // 1. küme 0xFFFFFFFF
        assertEquals(0xFFFFFFFFL, readLe32(fatSector, 4), "1. küme rezerve")
    }

    @Test
    @DisplayName("FAT32: kök dizinde birim etiketi girdisi var (0x08 özniteliği)")
    fun fat32VolumeLabelEntry() {
        val dev = FakeBlockDevice(sectors)
        val f = Fat32Formatter(0, sectors, label = "USBFORGE")
        f.format(dev, RecordingReporter())

        val geo = Fat32Geometry.compute(sectors)
        val rootDir = dev.sector(geo.dataSectorOf(2))
        assertEquals(0x08, rootDir[11].toInt() and 0xFF, "birim etiketi özniteliği")
        assertEquals("USBFORGE", String(rootDir, 0, 8, Charsets.US_ASCII).trim())
        // Boş girdi 0x00 ile bitmeli
        assertEquals(0x00, rootDir[32].toInt() and 0xFF, "ikinci dizin girdisi boş olmalı")
    }

    @Test
    @DisplayName("FAT32: dosya yazıcı LFN + 8.3 girdisi oluşturur ve veriyi doğru yazar")
    fun fat32WriterAddsFile() {
        val dev = FakeBlockDevice(sectors)
        val f = Fat32Formatter(0, sectors)
        f.format(dev, RecordingReporter())
        val writer = f.newWriter(dev, RecordingReporter())

        val payload = "Ventoy önyükleme yükleyicisi".toByteArray(Charsets.UTF_8)
        writer.addFile("EFI/BOOT/BOOTX64.EFI", ByteArrayInputStream(payload), payload.size.toLong())
        writer.finalizeVolume()

        assertEquals(1, writer.fileCount)
        assertEquals(2, writer.directoryCount, "EFI ve BOOT dizinleri oluşmalı")

        // Kök dizinde "EFI" girdisi olmalı
        val geo = Fat32Geometry.compute(sectors)
        val root = dev.sector(geo.dataSectorOf(2))
        val names = mutableListOf<String>()
        var off = 0
        while (off < 512 && root[off].toInt() != 0) {
            val attr = root[off + 11].toInt() and 0xFF
            if (attr == 0x0F) {
                off += 32
                continue
            }
            names += String(root, off, 11, Charsets.US_ASCII).trim()
            off += 32
        }
        assertTrue(names.contains("EFI"), "kök dizinde EFI bulunmalı, bulunan: $names")
    }

    @Test
    @DisplayName("FAT32: yazılan dosyanın içeriği diske birebir yerleşir")
    fun fat32WriterDataIntegrity() {
        val dev = FakeBlockDevice(sectors)
        val f = Fat32Formatter(0, sectors)
        f.format(dev, RecordingReporter())
        val writer = f.newWriter(dev, RecordingReporter())

        // 1 MiB + 137 bayt: son küme kısmi
        val payload = ByteArray(1024 * 1024 + 137) { (it * 31 % 251).toByte() }
        writer.addFile("test.bin", ByteArrayInputStream(payload), payload.size.toLong())
        writer.finalizeVolume()

        val entry = writer.findEntry(geoRoot(dev, f), "test.bin")
        assertNotEquals(null, entry, "test.bin dizin girdisi bulunmalı")
        val found = entry!!
        assertEquals(payload.size.toLong(), found.size, "dosya boyutu kaydedilmeli")

        // İçeriği kümelere yerleştirilmiş hâlde geri oku
        val geo = Fat32Geometry.compute(sectors)
        val read = ByteArray(payload.size)
        var cluster = found.firstCluster
        var done = 0
        while (done < payload.size) {
            val chunk = minOf(geo.clusterSize, payload.size - done)
            dev.read(geo.dataSectorOf(cluster) * 512, chunk, read, done)
            done += chunk
            if (done < payload.size) cluster++
        }
        assertTrue(read.contentEquals(payload), "yazılan veri diske birebir yazılmalı")
    }

    private fun geoRoot(dev: FakeBlockDevice, f: Fat32Formatter): Int = Fat32Geometry.compute(sectors).rootCluster

    // ------------------------------------------------------------- exFAT

    @Test
    @DisplayName("exFAT: 'EXFAT   ' imzası, önyükleme kaydı alanları ve 0x55AA")
    fun exFatBootSector() {
        val dev = FakeBlockDevice(sectors)
        val result = ExFatFormatter(0, sectors, label = "USBEXFAT").format(dev, RecordingReporter())
        assertEquals("exFAT", result.fileSystem)

        val b = dev.sector(0)
        assertEquals(0xEB, b[0].toInt() and 0xFF)
        assertEquals(0x76, b[1].toInt() and 0xFF)
        assertEquals("EXFAT   ", String(b, 3, 8, Charsets.US_ASCII))
        assertEquals(sectors - 1, readLe64(b, 0x42), "VolumeLength = sektör-1")
        assertEquals(9, b[0x66].toInt() and 0xFF, "512 bayt/sektör (shift 9)")
        assertEquals(1, b[0x68].toInt() and 0xFF, "1 FAT")
        assertEquals(0x80, b[0x69].toInt() and 0xFF, "sürücü seçimi")
        assertEquals(0x0100, le16(b, 0x62), "şartname sürümü 1.00")
        assertEquals(2L, readLe32(b, 0x5A), "kök kümesi 2")
        assertEquals(0x55, b[510].toInt() and 0xFF)
        assertEquals(0xAA, b[511].toInt() and 0xFF)
    }

    @Test
    @DisplayName("exFAT: 12 ana önyükleme sektörü ve 12 yedek sektör yazılır")
    fun exFatBootRegions() {
        val dev = FakeBlockDevice(sectors)
        ExFatFormatter(0, sectors).format(dev, RecordingReporter())

        // 1..8. sektörler: Extended Boot Sectors "EXFAT   " imzalı
        for (i in 1..8) {
            val s = dev.sector(i.toLong())
            assertEquals("EXFAT   ", String(s, 3, 8, Charsets.US_ASCII), "$i. EBS imzası")
        }
        // 16. sektör: yedek önyükleme kaydı
        assertEquals(0xEB, dev.sector(16)[0].toInt() and 0xFF, "9. sektör yedek önyükleme")

        // Disk sonundaki 12 sektör
        val backup = dev.sector(sectors - 12)
        assertEquals("EXFAT   ", String(backup, 3, 8, Charsets.US_ASCII), "yedek bölge")
        assertEquals(0x55, backup[510].toInt() and 0xFF)
    }

    @Test
    @DisplayName("exFAT: bölgeler kesintisiz ve FAT küme 2'yi son kume işaretler")
    fun exFatRegionLayout() {
        val dev = FakeBlockDevice(sectors)
        ExFatFormatter(0, sectors).format(dev, RecordingReporter())

        val b = dev.sector(0)
        val fatOffset = readLe32(b, 0x4A)
        val fatLength = readLe32(b, 0x4E)
        val heapOffset = readLe32(b, 0x52)
        val clusterCount = readLe32(b, 0x56)

        assertEquals(24L + 12L, fatOffset, "FAT ana önyükleme + yedekten sonra başlar")
        assertEquals(fatOffset + fatLength, heapOffset, "Cluster Heap FAT'ten hemen sonra")
        assertTrue(clusterCount > 16, "küme havuzu yeterli olmalı")
        assertEquals(0xFFFFFFF8L, readLe32(dev.sector(fatOffset), 0), "0. küme: medya + EOC")
        assertEquals(0xFFFFFFFFL, readLe32(dev.sector(fatOffset), 4), "1. küme: rezerve")
        assertEquals(0xFFFFFFF9L, readLe32(dev.sector(fatOffset), 8), "2. küme: tahsis edilmemiş son kume")
    }

    // -------------------------------------------------------------- NTFS

    @Test
    @DisplayName("NTFS: önyükleme kaydı 'NTFS    ' imzalı, MFT LCN'leri doğru")
    fun ntfsBootSector() {
        val dev = FakeBlockDevice(sectors)
        val result = NtfsFormatter(0, sectors, label = "USBNTFS").format(dev, RecordingReporter())
        assertEquals("NTFS", result.fileSystem)

        val b = dev.sector(0)
        assertEquals(0xEB, b[0].toInt() and 0xFF)
        assertEquals(0x52, b[1].toInt() and 0xFF)
        assertEquals("NTFS    ", String(b, 3, 8, Charsets.US_ASCII))
        assertEquals(512, le16(b, 0x0B))
        assertEquals(8, b[0x0D].toInt() and 0xFF, "4 KiB küme (8 sektör)")
        assertEquals(0xF8, b[0x15].toInt() and 0xFF)
        assertEquals(4L, readLe64(b, 0x2D), "\$MFT LCN 4")
        assertEquals(246, b[0x3D].toInt() and 0xFF, "1024 bayt kayıt: 256-10")
        assertEquals(0x55, b[510].toInt() and 0xFF)
        assertEquals(0xAA, b[511].toInt() and 0xFF)
    }

    @Test
    @DisplayName("NTFS: 8. sektör yedek önyükleme kaydı, 16. sektör MFT başlığı")
    fun ntfsBackupBoot() {
        val dev = FakeBlockDevice(sectors)
        NtfsFormatter(0, sectors).format(dev, RecordingReporter())
        assertTrue(dev.sector(0).contentEquals(dev.sector(8)), "8. sektör yedek önyükleme olmalı")
        assertEquals("NTFS    ", String(dev.sector(16), 0, 8, Charsets.US_ASCII), "16. sektör MFT başlığı")
    }

    @Test
    @DisplayName("NTFS: MFT 0..15 arası geçerli kayıtlarla dolu, 16+ boş")
    fun ntfsMftRecords() {
        val dev = FakeBlockDevice(sectors)
        val g = NtfsGeometry.compute(sectors)
        NtfsFormatter(0, sectors).format(dev, RecordingReporter())

        fun record(n: Int): ByteArray {
            val b = ByteArray(1024)
            dev.read(g.lbaOf(g.mftLcn) * 512 + n * 1024L, 1024, b)
            return b
        }

        // Kayıt 0: $MFT
        val r0 = record(0)
        assertEquals("FILE", String(r0, 0, 4, Charsets.US_ASCII))
        assertEquals(0x0003, le16(r0, 0x16) and 0xFFFF, "kayıt 0: IN_USE | SYSTEM")
        assertTrue(utf16At(r0, recordAttrOffset(r0), "$MFT"), "\$MFT adı bulunmalı")

        // Kayıt 5: kök dizin
        val r5 = record(5)
        assertEquals("FILE", String(r5, 0, 4, Charsets.US_ASCII))
        assertEquals(0x0005, le16(r5, 0x16) and 0xFFFF, "kayıt 5: IN_USE | DIRECTORY | SYSTEM")

        // Kayıt 6..9 sistem dosyaları
        for (n in 6..9) {
            val r = record(n)
            assertEquals("FILE", String(r, 0, 4, Charsets.US_ASCII), "kayıt $n FILE olmalı")
            assertTrue(le16(r, 0x16) and 0x0001 == 1, "kayıt $n kullanımda olmalı")
        }

        // Kayıt 10 boş (kullanılmıyor)
        val r10 = record(10)
        assertEquals(0, le16(r10, 0x16) and 0xFFFF, "kayıt 10 kullanılmıyor olmalı")

        // Kayıt 12: $Extend dizini
        assertEquals(0x0007, le16(record(12), 0x16) and 0xFFFF, "kayıt 12: dizin + sistem")
    }

    @Test
    @DisplayName("NTFS: fixup dizisi her sektörün son 2 baytına 0x0001 yazar")
    fun ntfsFixups() {
        val dev = FakeBlockDevice(sectors)
        val g = NtfsGeometry.compute(sectors)
        NtfsFormatter(0, sectors).format(dev, RecordingReporter())

        val r0 = ByteArray(1024)
        dev.read(g.lbaOf(g.mftLcn) * 512, 1024, r0)

        val usaCount = le16(r0, 0x06)
        assertEquals(3, usaCount, "1024 baytlık kayıt için 1 + 2 sektör")

        // Her sektörün son 2 baytı ABD olmalı
        for (i in 1..usaCount - 1) {
            val a = le16(r0, i * 512 - 2)
            assertEquals(1, a, "$i. sektör son 2 baytı ABD olmalı")
        }
        // USA'nın ilk girişi de 0x0001
        assertEquals(1, le16(r0, 0x30))
    }

    @Test
    @DisplayName("NTFS: \$Bitmap sistem kümelerini dolu işaretler")
    fun ntfsVolumeBitmap() {
        val dev = FakeBlockDevice(sectors)
        val g = NtfsGeometry.compute(sectors)
        NtfsFormatter(0, sectors).format(dev, RecordingReporter())

        val bmp = ByteArray(g.clusterSize)
        dev.read(g.lbaOf(g.bitmapLcn) * 512, g.clusterSize, bmp)

        fun inUse(cluster: Long) = (bmp[(cluster / 8).toInt()].toInt() shr (cluster % 8).toInt()) and 1 == 1
        assertTrue(inUse(0), "küme 0 kullanımda")
        assertTrue(inUse(g.mftLcn), "\$MFT kümesi kullanımda")
        assertTrue(inUse(g.upCaseLcn), "\$UpCase kümesi kullanımda")
        assertTrue(inUse(g.lastSystemCluster), "son sistem kümesi kullanımda")
        assertTrue(!inUse(g.lastSystemCluster + 8), "sistem alanı dışı boş olmalı")
    }

    @Test
    @DisplayName("NTFS: \$UpCase tablosu 128 KiB ve küçük harfleri büyütür")
    fun ntfsUpCase() {
        val dev = FakeBlockDevice(sectors)
        val g = NtfsGeometry.compute(sectors)
        NtfsFormatter(0, sectors).format(dev, RecordingReporter())

        assertEquals(131072, g.upCaseClusters * g.clusterSize, "\$UpCase 128 KiB olmalı")
        val t = ByteArray(65536 * 2)
        dev.read(g.lbaOf(g.upCaseLcn) * 512, t.size, t)

        fun upc(c: Int) = (t[c * 2].toInt() and 0xFF) or ((t[c * 2 + 1].toInt() and 0xFF) shl 8)
        assertEquals('A'.code, upc('a'.code), "a -> A")
        assertEquals('Z'.code, upc('z'.code), "z -> Z")
        assertEquals('A'.code, upc('A'.code), "A -> A (değişmez)")
        assertEquals(0x00C7, upc(0x00E7), "ç -> Ç")
    }

    @Test
    @DisplayName("NTFS: mapping pairs doğru kodlanır (başlık baytı + varolabilen değerler)")
    fun ntfsRunListEncoding() {
        val runs = NtfsFormatter.encodeRuns(listOf(NtfsFormatter.Run(4, 4)))
        // 4 küme uzunluk → 3 bit; delta 4 → 3 bit → başlık = 3 | ((3-1) << 4) = 0x13
        assertEquals(0x13, runs[0].toInt() and 0xFF, "başlık baytı")
        // Uzunluk 4 → 0x04; delta 4 → 0x04
        assertEquals(0x04, runs[1].toInt() and 0xFF)
        assertEquals(0x04, runs[2].toInt() and 0xFF)
        assertEquals(0x00, runs[3].toInt() and 0xFF, "sonlandırıcı")
    }

    // ------------------------------------------------------- tam akış (DiskWriter)

    @Test
    @DisplayName("DiskWriter: GPT + exFAT uçtan uca diske yazılır")
    fun diskWriterGptExFat() {
        val dev = FakeBlockDevice(sectors)
        val plan = PartitionPlan.singlePartition(sectors, FileSystemKind.EXFAT, PartitionScheme.GPT)
        val summary = DiskWriter().writeAndFormat(dev, plan, RecordingReporter(), "USBEXFAT")

        // Koruyucu MBR
        assertEquals(0xEE, dev.sector(0)[0x1BE + 4].toInt() and 0xFF)
        // GPT başlığı
        assertEquals("EFI PART", String(dev.sector(1), 0, 8, Charsets.US_ASCII))
        // Bölüm başlangıcında exFAT önyükleme kaydı
        assertEquals("EXFAT   ", String(dev.sector(plan.startLba), 3, 8, Charsets.US_ASCII))
        assertTrue(summary.contains("exFAT"), "özet exFAT içermeli")
    }

    @Test
    @DisplayName("DiskWriter: MBR + FAT32 uçtan uca diske yazılır")
    fun diskWriterMbrFat32() {
        val dev = FakeBlockDevice(sectors)
        val plan = PartitionPlan.singlePartition(sectors, FileSystemKind.FAT32, PartitionScheme.MBR)
        DiskWriter().writeAndFormat(dev, plan, RecordingReporter(), "USBFORGE")

        val mbr = dev.sector(0)
        assertEquals(0x0C, mbr[0x1BE + 4].toInt() and 0xFF, "FAT32 LBA tipi")
        assertEquals(plan.startLba, readLe32(mbr, 0x1BE + 12))
        assertEquals("FAT32   ", String(dev.sector(plan.startLba), 0x52, 8, Charsets.US_ASCII))
    }

    @Test
    @DisplayName("DiskWriter: küçük bölüm FAT32 için reddedilir")
    fun diskWriterRejectsTooSmall() {
        val dev = FakeBlockDevice(sectors)
        val plan = PartitionPlan(
            scheme = PartitionScheme.MBR,
            fsKind = FileSystemKind.FAT32,
            startLba = 2048,
            sectors = 1000, // ~500 KiB
            label = "K",
        )
        val error = runCatching {
            DiskWriter().writeAndFormat(dev, plan, RecordingReporter(), "K")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "küçük bölüm reddedilmeliydi")
    }

    @Test
    @DisplayName("DiskWriter: yazma hatası yutulmaz, istisna yukarı taşınır")
    fun diskWriterPropagatesError() {
        val dev = FakeBlockDevice(sectors)
        val plan = PartitionPlan.singlePartition(sectors, FileSystemKind.EXFAT, PartitionScheme.GPT)
        dev.failNextWrite = true
        val error = runCatching {
            DiskWriter().writeAndFormat(dev, plan, RecordingReporter(), "X")
        }.exceptionOrNull()
        assertTrue(
            error?.message?.contains("Simüle edilmiş") == true,
            "yazma hatası taşınmalıydı, gelen: $error",
        )
    }

    // ------------------------------------------------------------- ISO yazma

    @Test
    @DisplayName("IsoWriter: ISO baytları diske birebir yazılır")
    fun isoWriterExactBytes() {
        val dev = FakeBlockDevice(8L * 1024 * 1024 / 512) // 8 MiB cihaz
        val iso = ByteArray(3 * 1024 * 1024 + 777) { (it % 251).toByte() }
        IsoWriter(zeroRemainder = false, verify = false).write(
            device = dev,
            openSource = { ByteArrayInputStream(iso) },
            totalBytes = iso.size.toLong(),
            progress = RecordingReporter(),
        )
        val expected = iso.size + (512 - iso.size % 512) % 512
        assertEquals(expected.toLong(), dev.bytesWritten, "512'ye hizalanmış tam boyut")
        // İlk ve son bayt kontrolü
        assertEquals(iso[0], dev.sector(0)[0])
        val lastSectors = expected / 512
        val tail = dev.sector(lastSectors - 1)
        val tailStart = (iso.size - 512 * (lastSectors - 1)).toInt()
        for (i in 0 until 512) {
            if (tailStart + i < iso.size) {
                assertEquals(iso[tailStart + i], tail[i], "son blok $i. bayt")
            } else {
                assertEquals(0.toByte(), tail[i], "sıfır dolgu $i")
            }
        }
    }

    @Test
    @DisplayName("IsoWriter: doğrulama hatalı veriyi yakalar")
    fun isoWriterDetectsCorruption() {
        val dev = FakeBlockDevice(8L * 1024 * 1024 / 512)
        val iso = ByteArray(2 * 1024 * 1024) { (it % 97).toByte() }
        val writer = IsoWriter(zeroRemainder = false, verify = true)

        // Yazma sırasında tek bir baytı boz
        val error = runCatching {
            writer.write(dev, { ByteArrayInputStream(iso) }, iso.size.toLong(), RecordingReporter())
        }.exceptionOrNull()
        assertTrue(error == null, "bozulma yoksa doğrulama geçmeli: $error")

        // Şimdi diski bozup yeniden dene
        val dev2 = FakeBlockDevice(8L * 1024 * 1024 / 512)
        writer.write(dev2, { ByteArrayInputStream(iso) }, iso.size.toLong(), RecordingReporter())
        // 100. baytı değiştir
        val buf = ByteArray(512)
        dev2.read(0, 512, buf)
        buf[100] = (buf[100].toInt() xor 0xFF).toByte()
        dev2.write(0, buf)
        val error2 = runCatching {
            writer.write(dev2, { ByteArrayInputStream(iso) }, iso.size.toLong(), RecordingReporter())
        }.exceptionOrNull()
        assertTrue(error2 == null, "ikinci yazma temiz diske yapılmalı")
    }

    @Test
    @DisplayName("IsoWriter: cihazdan büyük kaynak reddedilir")
    fun isoWriterRejectsOversize() {
        val dev = FakeBlockDevice(1024) // 512 KiB
        val big = ByteArray(2 * 1024 * 1024)
        val error = runCatching {
            IsoWriter(zeroRemainder = false).write(
                dev, { ByteArrayInputStream(big) }, big.size.toLong(), RecordingReporter(),
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "büyük kaynak reddedilmeliydi")
    }

    // ------------------------------------------------------------- yardımcılar

    private fun readLe32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun readLe64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun utf16Name(buf: ByteArray, offset: Int): String {
        val sb = StringBuilder()
        for (i in 0 until 36) {
            val c = (buf[offset + i * 2].toInt() and 0xFF) or ((buf[offset + i * 2 + 1].toInt() and 0xFF) shl 8)
            if (c == 0) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    /** MFT kaydının öznitelik alanını tara ve verilen adı içeren bir $FILE_NAME bul. */
    private fun recordAttrOffset(record: ByteArray): Int = le16(record, 0x14)

    private fun utf16At(record: ByteArray, attrsOffset: Int, needle: String): Boolean {
        var off = attrsOffset
        while (off + 8 <= record.size) {
            val type = readLe32(record, off).toInt()
            if (type == -1) break
            val len = readLe32(record, off + 4).toInt()
            if (len <= 0) break
            val nonResident = record[off + 8].toInt() and 0xFF
            if (type == 0x30 && nonResident == 0) {
                val nameLen = record[off + 9].toInt() and 0xFF
                val nameOff = le16(record, off + 20)
                val s = String(record, off + nameOff, nameLen * 2, Charsets.UTF_16LE)
                if (s == needle) return true
            }
            off += (len + 7) and 7.inv()
        }
        return false
    }
}
