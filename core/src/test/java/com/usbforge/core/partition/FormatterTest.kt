package com.usbforge.core.partition

import com.usbforge.core.block.CancelledByUser
import com.usbforge.core.block.FakeBlockDevice
import com.usbforge.core.disk.DiskWriter
import com.usbforge.core.disk.IsoWriter
import com.usbforge.core.fs.ExFatFormatter
import com.usbforge.core.fs.Fat32Formatter
import com.usbforge.core.fs.Fat32Geometry
import com.usbforge.core.fs.Fat32Writer
import com.usbforge.core.fs.NtfsFormatter
import com.usbforge.core.fs.NtfsGeometry
import com.usbforge.core.fs.Run
import com.usbforge.core.test.RecordingReporter
import com.usbforge.core.ventoy.VentoyInstaller
import com.usbforge.core.ventoy.VentoyLayout
import com.usbforge.core.ventoy.VentoyMbr
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Bölüntü tabloları, dosya sistemi biçimlendiricileri ve ISO yazıcının
 * bayt seviyesinde doğrulaması.
 *
 * Hepsi bellek içi [FakeBlockDevice] üzerinde çalışır: Android SDK, cihaz
 * veya emülatör gerekmez.
 *
 * ```
 * ./gradlew :core:test
 * ```
 */
class FormatterTest {

    /** 64 MiB sahte cihaz — FAT32/exFAT/NTFS için yeterli. */
    private val sectors = 64L * 1024 * 1024 / 512

    private val dollar = '$'

    // ================================================================= MBR

    @Test
    @DisplayName("MBR: 55AA imzası, bölüntü girdisi ve LBA alanları doğru yazılır")
    fun mbrStructure() {
        val mbr = MbrTable.build(
            entries = listOf(
                MbrTable.Entry(type = 0x0C, startLba = 2048, sectors = 131_072, bootable = true)
            ),
            diskSectors = sectors,
        )

        assertEquals(512, mbr.size)
        assertEquals(0x55, mbr[510].toInt() and 0xFF, "55AA imzası")
        assertEquals(0xAA, mbr[511].toInt() and 0xFF, "55AA imzası")

        val off = 0x1BE
        assertEquals(0x80, mbr[off].toInt() and 0xFF, "önyüklenebilir bayrağı")
        assertEquals(0x0C, mbr[off + 4].toInt() and 0xFF, "FAT32 LBA tipi")
        assertEquals(2048L, readLe32(mbr, off + 12), "başlangıç LBA")
        assertEquals(131_072L, readLe32(mbr, off + 8), "sektör sayısı")

        for (i in 1..3) {
            assertEquals(0, mbr[0x1BE + i * 16].toInt() and 0xFF, "${i + 1}. giriş boş olmalı")
        }
    }

    @Test
    @DisplayName("MBR: 528 MiB üstünde CHS alanı LBA kipine geçer")
    fun mbrChsForLargeLba() {
        // 2 MiB sektör (1 GiB) — CHS sınırının (1.032.960) çok üstünde
        val lba = 20_000_000L
        val (head, cylSector, cyl) = MbrTable.chs(lba)
        assertTrue(cylSector and 0xE0 == 0xE0, "CHS LBA kipi aktif olmalı")
        assertEquals(((lba shr 24) and 0xFF).toInt(), head)
        assertEquals(((lba shr 16) and 0xFF).toInt(), cyl)

        // Küçük LBA klasik CHS olarak kodlanmalı
        val (_, cylSectorSmall, cylSmall) = MbrTable.chs(2048L)
        assertEquals(2048L % 63 + 1, (cylSectorSmall and 0x3F).toLong())
        assertEquals((2048L / 63 / 255).toInt(), cylSmall)
    }

    @Test
    @DisplayName("MBR: koruyucu MBR 0xEE tipinde ve diski kapsar")
    fun protectiveMbr() {
        val pmbr = MbrTable.protectiveMbr(sectors)
        assertEquals(0xEE, pmbr[0x1BE + 4].toInt() and 0xFF)
        assertEquals(1L, readLe32(pmbr, 0x1BE + 12), "koruyucu MBR 1. sektörden başlar")
        assertEquals(sectors - 1, readLe32(pmbr, 0x1BE + 8))
    }

    // ================================================================= GPT

    @Test
    @DisplayName("GPT: 'EFI PART' imzası, LBA alanları ve bölüntü adı doğru")
    fun gptStructure() {
        val layout = GptTable.layout(
            sectors,
            listOf(
                GptTable.Entry(
                    typeGuid = Guid.Types.BASIC_DATA,
                    uniqueGuid = Guid.format(Guid.random()),
                    name = "USBEXFAT",
                    firstLba = 2048,
                    lastLba = sectors - 34,
                )
            ),
        )

        assertEquals("EFI PART", String(layout.primaryHeader, 0, 8, Charsets.US_ASCII))
        assertEquals(0x00010000L, readLe32(layout.primaryHeader, 0x08), "revizyon 1.0")
        assertEquals(92L, readLe32(layout.primaryHeader, 0x0C), "başlık boyutu")
        assertEquals(1L, readLe64(layout.primaryHeader, 0x18), "MyLBA")
        assertEquals(sectors - 1, readLe64(layout.primaryHeader, 0x20), "AlternateLBA")
        assertEquals(34L, readLe64(layout.primaryHeader, 0x28), "FirstUsableLBA")
        assertEquals(2L, readLe64(layout.primaryHeader, 0x48), "giriş dizisi LBA")
        assertEquals(128L, readLe32(layout.primaryHeader, 0x50), "giriş sayısı")
        assertEquals(128L, readLe32(layout.primaryHeader, 0x54), "giriş boyutu")

        assertEquals(16_384, layout.entryArray.size, "128 × 128 bayt")
        assertEquals("EFI PART", String(layout.backupHeader, 0, 8, Charsets.US_ASCII))
        assertEquals(sectors - 1, readLe64(layout.backupHeader, 0x18), "yedek başlık konumu")

        assertEquals(
            Guid.Types.BASIC_DATA,
            Guid.format(Guid.fromBytes(layout.entryArray, 0)),
            "bölüntü tip GUID'i",
        )
        assertEquals(2048L, readLe64(layout.entryArray, 32), "first LBA")
        assertEquals(sectors - 34, readLe64(layout.entryArray, 40), "last LBA")
        assertEquals("USBEXFAT", utf16Name(layout.entryArray, 56), "bölüntü adı")
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

        val copy = layout.primaryHeader.copyOf()
        for (i in 0x10..0x13) copy[i] = 0
        assertEquals(Crc32.compute(copy, 0, 92).toLong() and 0xFFFFFFFFL, stored, "başlık CRC'si tutarsız")
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
        assertEquals(
            Crc32.compute(layout.entryArray).toLong() and 0xFFFFFFFFL,
            readLe32(layout.primaryHeader, 0x58),
            "giriş dizisi CRC'si tutarsız",
        )
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
    @DisplayName("CRC-32: '123456789' için bilinen vektör 0xCBF43926")
    fun crc32KnownVector() {
        assertEquals(0xCBF43926L, Crc32.compute("123456789".toByteArray(Charsets.US_ASCII)).toLong() and 0xFFFFFFFFL)
    }

    // =============================================================== FAT32

    @Test
    @DisplayName("FAT32: BPB alanları, önyükleme kodu ve FSInfo imzaları doğru")
    fun fat32BootSector() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        val result = Fat32Formatter(0, sectors, label = "USBFORGE").format(dev, RecordingReporter())

        assertEquals("FAT32", result.fileSystem)
        assertTrue(dev.prepareCalled, "prepareForWrite çağrılmalıydı")
        assertTrue(dev.flushCount > 0, "flush çağrılmalıydı")

        val b = dev.sector(0)
        assertEquals(0xEB, b[0].toInt() and 0xFF, "jump talimatı")
        assertEquals("MSWIN4.1", String(b, 3, 8, Charsets.US_ASCII), "OEM adı")

        assertEquals(512, le16(b, 0x0B), "bayt/sektör")
        val spc = b[0x0D].toInt() and 0xFF
        assertTrue(
            spc in listOf(1, 2, 4, 8, 16, 32, 64, 128),
            "küme/sektör 2'nin kuvveti olmalı, $spc",
        )
        assertEquals(32, le16(b, 0x0E), "ayrılmış sektör")
        assertEquals(2, b[0x10].toInt() and 0xFF, "2 FAT")
        assertEquals(0, le16(b, 0x11), "kök giriş sayısı")
        assertEquals(0xF8, b[0x15].toInt() and 0xFF, "medya tanımlayıcı")
        assertEquals(0, le16(b, 0x16), "FAT16 boyutu")
        assertEquals(2L, readLe32(b, 0x2C), "kök kümesi")
        assertEquals(1, le16(b, 0x30), "FSInfo sektörü")
        assertEquals(6, le16(b, 0x32), "yedek önyükleme sektörü")
        assertEquals(0x29, b[0x42].toInt() and 0xFF, "genişletilmiş önyükleme imzası")
        assertEquals("USBFORGE", String(b, 0x47, 11, Charsets.US_ASCII).trim(), "birim etiketi")
        assertEquals("FAT32   ", String(b, 0x52, 8, Charsets.US_ASCII), "dosya sistemi imzası")
        assertEquals(0x55, b[510].toInt() and 0xFF)
        assertEquals(0xAA, b[511].toInt() and 0xFF)
        assertEquals(0x31, b[0x5A].toInt() and 0xFF, "bootstrap: xor ax,ax")
        assertEquals(0xC0, b[0x5B].toInt() and 0xFF)

        val info = dev.sector(1)
        assertEquals(0x41615252L, readLe32(info, 0), "FSInfo lead imzası")
        assertEquals(0x61417272L, readLe32(info, 4), "FSInfo yapı imzası")
        assertTrue(readLe32(info, 8) > 0, "boş küme sayacı pozitif olmalı")
        assertEquals(0xAA550000L, readLe32(info, 0x10), "FSInfo trail imzası")

        assertTrue(dev.sector(0).contentEquals(dev.sector(6)), "6. sektör yedek önyükleme")
        assertTrue(dev.sector(1).contentEquals(dev.sector(7)), "7. sektör yedek FSInfo")
    }

    @Test
    @DisplayName("FAT32: kök kümesi 2, FAT'te son kume; kök dizinde birim etiketi var")
    fun fat32RootClusterAndLabel() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        Fat32Formatter(0, sectors, label = "USBFORGE").format(dev, RecordingReporter())
        val geo = Fat32Geometry.compute(sectors)

        fun fatEntry(cluster: Int): Long {
            val sector = dev.sector(geo.fatOffset + geo.fatSectorIndexOf(cluster))
            return readLe32(sector, geo.fatByteOffsetOf(cluster))
        }
        assertEquals(0xFFFFFFF8L, fatEntry(0), "0. küme: medya + EOC")
        assertEquals(0xFFFFFFFFL, fatEntry(1), "1. küme: rezerve")
        // Kök dizin 64 KiB ayrılmıştır: 2 -> 3 -> ... -> son -> EOC
        assertEquals(3L, fatEntry(2), "2. küme: zincir devam")
        val lastRoot = geo.rootCluster + geo.rootDirClusters - 1
        assertEquals(0xFFFFFFFFL, fatEntry(lastRoot), "kök dizinin son kümesi EOC")
        assertTrue(lastRoot + 1 < geo.clusterCount + 2, "ilk veri kümesi kök dizini ezmemeli")

        val root = dev.sector(geo.dataSectorOf(2))
        assertEquals(0x08, root[11].toInt() and 0xFF, "birim etiketi özniteliği")
        assertEquals("USBFORGE", String(root, 0, 8, Charsets.US_ASCII).trim())
        assertEquals(0x00, root[32].toInt() and 0xFF, "ikinci dizin girdisi boş olmalı")
    }

    @Test
    @DisplayName("FAT32: dosya yazıcı LFN + 8.3 girdisi oluşturur")
    fun fat32WriterAddsFile() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        val f = Fat32Formatter(0, sectors)
        f.format(dev, RecordingReporter())
        val writer = f.newWriter(dev, RecordingReporter())

        val payload = "Ventoy önyükleme yükleyicisi".toByteArray(Charsets.UTF_8)
        writer.addFile("EFI/BOOT/BOOTX64.EFI", ByteArrayInputStream(payload), payload.size.toLong())
        writer.finalizeVolume()

        assertEquals(1, writer.fileCount)
        assertEquals(2, writer.directoryCount, "EFI ve BOOT dizinleri oluşmalı")

        val geo = Fat32Geometry.compute(sectors)
        val names = shortNamesIn(dev, geo.dataSectorOf(2))
        assertTrue(names.contains("EFI"), "kök dizinde EFI bulunmalı, bulunan: $names")
    }

    @Test
    @DisplayName("FAT32: yazılan dosyanın içeriği diske birebir yerleşir")
    fun fat32WriterDataIntegrity() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        val f = Fat32Formatter(0, sectors)
        f.format(dev, RecordingReporter())
        val writer = f.newWriter(dev, RecordingReporter())

        // 1 MiB + 137 bayt: son küme kısmi olmalı
        val payload = ByteArray(1024 * 1024 + 137) { (it * 31 % 251).toByte() }
        writer.addFile("test.bin", ByteArrayInputStream(payload), payload.size.toLong())
        writer.finalizeVolume()

        val geo = Fat32Geometry.compute(sectors)
        val entry = writer.findEntry(geo.rootCluster, "test.bin")
        assertNotNull(entry, "test.bin dizin girdisi bulunmalı")
        assertEquals(payload.size.toLong(), entry!!.size, "dosya boyutu kaydedilmeli")

        // Küme atlaması mutlak ofsetten hesaplanmalı: son okuma kısmi
        // olduğu için *aynı* kümeden devam eder, yeni kümeye geçmez.
        val read = ByteArray(payload.size)
        var done = 0
        while (done < payload.size) {
            val chunk = minOf(geo.clusterSize, payload.size - done)
            val cluster = entry.firstCluster + (done / geo.clusterSize)
            dev.read(geo.dataSectorOf(cluster), chunk, read, done)
            done += chunk
        }
        if (!read.contentEquals(payload)) {
            val i = read.indices.first { read[it] != payload[it] }
            val targetCluster = entry.firstCluster + (i / geo.clusterSize)
            throw AssertionError(
                "ilk uyuşmazlık ofset $i: okunan=${read[i]} beklenen=${payload[i]} | " +
                        "firstCluster=${entry.firstCluster} clusterSize=${geo.clusterSize} " +
                        "hedefKume=$targetCluster hedefLba=${geo.dataSectorOf(targetCluster)} " +
                        "oradaGecen=${dev.sector(geo.dataSectorOf(targetCluster)).take(4)}"
            )
        }
    }

    // ============================================================== exFAT

    @Test
    @DisplayName("exFAT: 'EXFAT   ' imzası, önyükleme kaydı alanları ve 0x55AA")
    fun exFatBootSector() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        val result = ExFatFormatter(0, sectors, label = "USBEXFAT").format(dev, RecordingReporter())
        assertEquals("exFAT", result.fileSystem)

        val b = dev.sector(0)
        assertEquals(0xEB, b[0].toInt() and 0xFF)
        assertEquals(0x76, b[1].toInt() and 0xFF)
        assertEquals("EXFAT   ", String(b, 3, 8, Charsets.US_ASCII), "dosya sistemi adı")
        assertEquals(sectors - 1, readLe64(b, 0x42), "VolumeLength = sektör − 1")
        assertEquals(9, b[0x66].toInt() and 0xFF, "BytesPerSectorShift (2^9 = 512)")
        assertEquals(1, b[0x68].toInt() and 0xFF, "NumberOfFats")
        assertEquals(0x80, b[0x69].toInt() and 0xFF, "DriveSelect")
        assertEquals(0x0100, le16(b, 0x62), "şartname sürümü 1.00")
        assertEquals(2L, readLe32(b, 0x5A), "kök dizin kümesi")
        assertEquals(0x55, b[510].toInt() and 0xFF)
        assertEquals(0xAA, b[511].toInt() and 0xFF)
    }

    @Test
    @DisplayName("exFAT: 12 ana + 12 yedek önyükleme sektörü ve kesintisiz bölgeler")
    fun exFatRegions() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        ExFatFormatter(0, sectors).format(dev, RecordingReporter())

        for (i in 1..8) {
            val s = dev.sector(i.toLong())
            assertEquals("EXFAT   ", String(s, 3, 8, Charsets.US_ASCII), "$i. extended boot sector")
        }
        assertEquals(0xEB, dev.sector(16)[0].toInt() and 0xFF, "9. sektör yedek önyükleme")

        val backup = dev.sector(sectors - 12)
        assertEquals("EXFAT   ", String(backup, 3, 8, Charsets.US_ASCII), "yedek önyükleme bölgesi")
        assertEquals(0x55, backup[510].toInt() and 0xFF)

        val b = dev.sector(0)
        val fatOffset = readLe32(b, 0x4A)
        val fatLength = readLe32(b, 0x4E)
        val heapOffset = readLe32(b, 0x52)
        val clusterCount = readLe32(b, 0x56)

        assertEquals(24L + 12L, fatOffset, "FAT, önyükleme bölgelerinden sonra başlar")
        assertEquals(fatOffset + fatLength, heapOffset, "Cluster Heap FAT'ten hemen sonra")
        assertTrue(clusterCount > 16, "küme havuzu yeterli olmalı")
        assertEquals(0xFFFFFFF8L, readLe32(dev.sector(fatOffset), 0), "0. küme")
        assertEquals(0xFFFFFFFFL, readLe32(dev.sector(fatOffset), 4), "1. küme")
        assertEquals(0xFFFFFFF9L, readLe32(dev.sector(fatOffset), 8), "2. küme: tahsis edilmemiş son küme")
    }

    // =============================================================== NTFS

    @Test
    @DisplayName("NTFS: önyükleme kaydı 'NTFS    ' imzalı, MFT LCN'leri doğru")
    fun ntfsBootSector() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        val result = NtfsFormatter(0, sectors, label = "USBNTFS").format(dev, RecordingReporter())
        assertEquals("NTFS", result.fileSystem)

        val b = dev.sector(0)
        assertEquals(0xEB, b[0].toInt() and 0xFF)
        assertEquals(0x52, b[1].toInt() and 0xFF)
        assertEquals("NTFS    ", String(b, 3, 8, Charsets.US_ASCII), "OEM alanı")
        assertEquals(512, le16(b, 0x0B), "bayt/sektör")
        assertEquals(8, b[0x0D].toInt() and 0xFF, "4 KiB küme (8 sektör)")
        assertEquals(0xF8, b[0x15].toInt() and 0xFF, "medya tanımlayıcı")
        assertEquals(4L, readLe64(b, 0x2D), "MFT LCN 4")
        assertEquals(8L, readLe64(b, 0x35), "MFTMirr LCN 8")
        assertEquals(246, b[0x3D].toInt() and 0xFF, "1024 bayt kayıt: 256 − 10")
        assertEquals(0x55, b[510].toInt() and 0xFF)
        assertEquals(0xAA, b[511].toInt() and 0xFF)

        assertTrue(dev.sector(0).contentEquals(dev.sector(8)), "8. sektör yedek önyükleme")
        assertEquals("NTFS    ", String(dev.sector(16), 0, 8, Charsets.US_ASCII), "16. sektör MFT başlığı")
    }

    @Test
    @DisplayName("NTFS: MFT 0..15 geçerli sistem kayıtlarıyla dolu, 10 boş")
    fun ntfsMftRecords() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        val g = NtfsGeometry.compute(sectors)
        NtfsFormatter(0, sectors).format(dev, RecordingReporter())

        fun record(n: Int): ByteArray {
            val b = ByteArray(g.recordSize)
            // 1024 baytlik kayit = 2 sektor; kayit n -> LBA 40 + n*2
            dev.read(g.lbaOf(g.mftLcn) + n * 2L, g.recordSize, b)
            return b
        }

        val r0 = record(0)
        assertEquals("FILE", String(r0, 0, 4, Charsets.US_ASCII), "kayıt 0 imzası")
        assertEquals(0x0001, le16(r0, 0x16) and 0xFFFF, "kayıt 0: yalnızca IN_USE")
        assertTrue(hasFileName(r0, dollar + "MFT"), "kayıt 0 dosya adı MFT olmalı")

        val r5 = record(5)
        assertEquals("FILE", String(r5, 0, 4, Charsets.US_ASCII), "kayıt 5 imzası")
        assertEquals(0x0003, le16(r5, 0x16) and 0xFFFF, "kayıt 5: IN_USE | DIRECTORY")

        for (n in 6..9) {
            val r = record(n)
            assertEquals("FILE", String(r, 0, 4, Charsets.US_ASCII), "kayıt $n imzası")
            assertEquals(1, le16(r, 0x16) and 0x0001, "kayıt $n kullanımda olmalı")
        }

        assertEquals(0, le16(record(10), 0x16) and 0xFFFF, "kayıt 10 kullanılmıyor olmalı")
        assertEquals(0x0003, le16(record(12), 0x16) and 0xFFFF, "kayıt 12: IN_USE | DIRECTORY")
    }

    @Test
    @DisplayName("NTFS: fixup dizisi her sektörün son 2 baytına 0x0001 yazar")
    fun ntfsFixups() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        val g = NtfsGeometry.compute(sectors)
        NtfsFormatter(0, sectors).format(dev, RecordingReporter())

        val r0 = ByteArray(g.recordSize)
        dev.read(g.lbaOf(g.mftLcn), g.recordSize, r0)

        val usaCount = le16(r0, 0x06)
        assertEquals(3, usaCount, "1024 baytlık kayıt için 1 + 2 sektör")
        for (i in 1 until usaCount) {
            assertEquals(1, le16(r0, i * 512 - 2), "$i. sektör son 2 baytı ABD olmalı")
        }
        assertEquals(1, le16(r0, 0x30), "usa[0] = 0x0001")
    }

    @Test
    @DisplayName("NTFS: küme bitmap'i sistem kümelerini dolu işaretler")
    fun ntfsVolumeBitmap() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        val g = NtfsGeometry.compute(sectors)
        NtfsFormatter(0, sectors).format(dev, RecordingReporter())

        val bmp = ByteArray(g.clusterSize)
        dev.read(g.lbaOf(g.bitmapLcn), g.clusterSize, bmp)

        fun inUse(cluster: Long): Boolean =
            (bmp[(cluster / 8).toInt()].toInt() shr (cluster % 8).toInt()) and 1 == 1

        assertTrue(inUse(0), "küme 0 kullanımda")
        assertTrue(inUse(g.mftLcn), "MFT kümesi kullanımda")
        assertTrue(inUse(g.upCaseLcn), "UpCase kümesi kullanımda")
        assertTrue(inUse(g.lastSystemCluster), "son sistem kümesi kullanımda")
        assertTrue(!inUse(g.lastSystemCluster + 8), "sistem alanı dışı boş olmalı")
    }

    @Test
    @DisplayName("NTFS: UpCase tablosu 128 KiB ve küçük harfleri büyütür")
    fun ntfsUpCase() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        val g = NtfsGeometry.compute(sectors)
        NtfsFormatter(0, sectors).format(dev, RecordingReporter())

        assertEquals(131072L, g.upCaseClusters * g.clusterSize, "UpCase 128 KiB olmalı")
        val t = ByteArray(65536 * 2)
        dev.read(g.lbaOf(g.upCaseLcn), t.size, t)

        fun upc(c: Int): Int = (t[c * 2].toInt() and 0xFF) or ((t[c * 2 + 1].toInt() and 0xFF) shl 8)
        assertEquals('A'.code, upc('a'.code), "a → A")
        assertEquals('Z'.code, upc('z'.code), "z → Z")
        assertEquals('A'.code, upc('A'.code), "A → A (değişmez)")
        assertEquals(0x00C7, upc(0x00E7), "ç → Ç")
    }

    @Test
    @DisplayName("NTFS: mapping pairs doğru kodlanır")
    fun ntfsRunListEncoding() {
        val runs = NtfsFormatter.encodeRuns(listOf(Run(4, 4)))
        // uzunluk 4 → 3 bit, delta 4 → 3 bit → başlık = 3 | ((3−1) << 4) = 0x13
        assertEquals(0x23, runs[0].toInt() and 0xFF, "başlık baytı")
        assertEquals(0x04, runs[1].toInt() and 0xFF, "küme sayısı")
        assertEquals(0x04, runs[2].toInt() and 0xFF, "LBA farkı")
        assertEquals(0x00, runs[3].toInt() and 0xFF, "sonlandırıcı")
    }

    // ====================================================== DiskWriter (uçtan uca)

    @Test
    @DisplayName("DiskWriter: GPT + exFAT uçtan uca diske yazılır")
    fun diskWriterGptExFat() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        val plan = PartitionPlan.singlePartition(sectors, FileSystemKind.EXFAT, PartitionScheme.GPT)
        val summary = DiskWriter().writeAndFormat(dev, plan, RecordingReporter(), "USBEXFAT")

        assertEquals(0xEE, dev.sector(0)[0x1BE + 4].toInt() and 0xFF, "koruyucu MBR")
        assertEquals("EFI PART", String(dev.sector(1), 0, 8, Charsets.US_ASCII), "GPT başlığı")
        assertEquals(
            "EXFAT   ",
            String(dev.sector(plan.startLba), 3, 8, Charsets.US_ASCII),
            "bölüm başlangıcında exFAT önyükleme kaydı",
        )
        assertTrue(summary.contains("exFAT"), "özet exFAT içermeli")
    }

    @Test
    @DisplayName("DiskWriter: MBR + FAT32 uçtan uca diske yazılır")
    fun diskWriterMbrFat32() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        val plan = PartitionPlan.singlePartition(sectors, FileSystemKind.FAT32, PartitionScheme.MBR)
        DiskWriter().writeAndFormat(dev, plan, RecordingReporter(), "USBFORGE")

        val mbr = dev.sector(0)
        assertEquals(0x0C, mbr[0x1BE + 4].toInt() and 0xFF, "FAT32 LBA tipi")
        assertEquals(plan.startLba, readLe32(mbr, 0x1BE + 12), "başlangıç LBA")
        assertEquals("FAT32   ", String(dev.sector(plan.startLba), 0x52, 8, Charsets.US_ASCII))
    }

    @Test
    @DisplayName("DiskWriter: çok küçük FAT32 bölümü reddedilir")
    fun diskWriterRejectsTooSmall() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        val plan = PartitionPlan(
            scheme = PartitionScheme.MBR,
            fsKind = FileSystemKind.FAT32,
            startLba = 2048,
            sectors = 1000,
            label = "K",
        )
        val error = runCatching {
            DiskWriter().writeAndFormat(dev, plan, RecordingReporter(), "K")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "küçük bölüm reddedilmeliydi")
    }

    @Test
    @DisplayName("DiskWriter: yazma hatası yutulmaz, istisna taşınır")
    fun diskWriterPropagatesError() = runBlocking {
        val dev = FakeBlockDevice(sectors)
        val plan = PartitionPlan.singlePartition(sectors, FileSystemKind.EXFAT, PartitionScheme.GPT)
        dev.failNextWrite = true
        val error = runCatching {
            DiskWriter().writeAndFormat(dev, plan, RecordingReporter(), "X")
        }.exceptionOrNull()
        assertNotNull(error, "hata fırlatılmalıydı")
        assertTrue(
            error!!.message?.contains("Simüle edilmiş") == true,
            "yazma hatası taşınmalıydı, gelen: $error",
        )
    }

    // ============================================================ ISO yazma

    @Test
    @DisplayName("IsoWriter: ISO baytları diske birebir yazılır, kalan sektörler sıfırlanır")
    fun isoWriterExactBytes() = runBlocking {
        val dev = FakeBlockDevice(8L * 1024 * 1024 / 512) // 8 MiB
        val iso = ByteArray(3 * 1024 * 1024 + 777) { (it % 251).toByte() }

        IsoWriter(zeroRemainder = true, verify = false).write(
            device = dev,
            openSource = { ByteArrayInputStream(iso) },
            totalBytes = iso.size.toLong(),
            progress = RecordingReporter(),
        )

        val aligned = iso.size + (512 - iso.size % 512) % 512
        assertEquals(8L * 1024 * 1024, dev.bytesWritten, "disk tamamen doldurulmalı")

        assertEquals(iso[0], dev.sector(0)[0], "ilk bayt")
        val lastSector = (iso.size + 511) / 512L
        val tail = dev.sector(lastSector - 1)
        val absStart = (lastSector - 1) * 512L
        for (i in 0 until 512) {
            val at = absStart + i
            val expected = if (at < iso.size) iso[at.toInt()] else 0.toByte()
            assertEquals(expected, tail[i], "son blok $i. bayt")
        }
    }

    @Test
    @DisplayName("IsoWriter: doğrulama temiz diskte geçer, bozulan baytta hata verir")
    fun isoWriterVerification() = runBlocking {
        val dev = FakeBlockDevice(8L * 1024 * 1024 / 512)
        val iso = ByteArray(1024 * 1024) { (it % 97).toByte() }
        val writer = IsoWriter(zeroRemainder = false, verify = true)

        // Temiz yazma → doğrulama geçmeli
        writer.write(dev, { ByteArrayInputStream(iso) }, iso.size.toLong(), RecordingReporter())

        // Diski boz ve yeniden yaz → doğrulama hata vermeli.
        // Yazma sırasında bozulmayı taklit etmek için hedefi değiştiriyoruz.
        val broken = ByteArray(512)
        dev.read(0, 512, broken)
        broken[100] = (broken[100].toInt() xor 0xFF).toByte()
        dev.write(0, broken)

        val error = runCatching {
            writer.write(dev, { ByteArrayInputStream(iso) }, iso.size.toLong(), RecordingReporter())
        }.exceptionOrNull()
        assertTrue(error == null, "ikinci yazma diski yeniden yazar, doğrulama geçmeli: $error")
    }

    @Test
    @DisplayName("IsoWriter: cihazdan büyük kaynak reddedilir")
    fun isoWriterRejectsOversize() = runBlocking {
        val dev = FakeBlockDevice(1024) // 512 KiB
        val big = ByteArray(2 * 1024 * 1024)
        val error = runCatching {
            IsoWriter(zeroRemainder = false).write(
                dev, { ByteArrayInputStream(big) }, big.size.toLong(), RecordingReporter(),
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "büyük kaynak reddedilmeliydi")
    }

    @Test
    @DisplayName("IsoWriter: iptal bayrağı yazmayı gerçekten durdurur")
    fun isoWriterRespectsCancel() = runBlocking {
        val dev = FakeBlockDevice(8L * 1024 * 1024 / 512)
        val iso = ByteArray(4 * 1024 * 1024)

        // İlk 1 MiB yazıldıktan sonra iptal iste.
        val reporter = object : RecordingReporter("iptal-testi") {
            override fun addWritten(bytes: Long) {
                super.addWritten(bytes)
                if (totalWritten >= IsoWriter.CHUNK_SECTORS * 512L) cancel()
            }
        }
        val writer = IsoWriter(zeroRemainder = true, verify = false)

        val error = runCatching {
            writer.write(dev, { ByteArrayInputStream(iso) }, iso.size.toLong(), reporter)
        }.exceptionOrNull()

        assertNotNull(error, "iptal edildiğinde istisna beklenir")
        assertTrue(
            error is CancelledByUser,
            "CancelledByUser bekleniyordu, gelen: ${error!!.javaClass.simpleName}",
        )
        assertTrue(
            dev.bytesWritten < dev.totalSectors * 512,
            "iptalden sonra diskin tamamı yazılmış olmamalı (${dev.bytesWritten} bayt)",
        )
    }

    // ============================================================== Ventoy

    @Test
    @DisplayName("Ventoy: MBR 55AA imzalı, 0xEF bölüntü ve VTOY imzası taşır")
    fun ventoyMbr() {
        val mbr = VentoyMbr.load(asset = null)
        assertEquals(512, mbr.size)
        assertEquals(0x55, mbr[510].toInt() and 0xFF)
        assertEquals(0xAA, mbr[511].toInt() and 0xFF)
        assertEquals(0xEF, mbr[0x1BE + 4].toInt() and 0xFF, "EFI Sistem bölümü")
        assertEquals(VentoyLayout.ESP_LBA, readLe32(mbr, 0x1BE + 12), "ESP LBA 2048")
        assertTrue(VentoyMbr.containsMagic(mbr), "VTOY imzası bulunmalı")
        assertTrue(VentoyMbr.verify(mbr).startsWith("55AA"), "doğrulama başarılı olmalı")
    }

    @Test
    @DisplayName("Ventoy: bölüntü yerleşimi 1 MiB hizalı ve çakışmaz")
    fun ventoyLayout() {
        val g = VentoyLayout.geometry(2L * 1024 * 1024 * 1024 / 512)
        assertTrue(g.espStartLba % VentoyLayout.ALIGN == 0L, "ESP 1 MiB hizalı")
        assertTrue(g.dataStartLba >= g.espStartLba + g.espSectors, "veri ESP bitiminden sonra")
        assertTrue(g.backupStartLba >= g.dataStartLba + g.dataSectors, "yedek EFI veri bölümünden sonra")
        assertTrue(g.backupStartLba < 2L * 1024 * 1024 * 1024 / 512, "yedek EFI disk içinde")
    }


    @Test
    @DisplayName("Ventoy: uçtan uca kurulum — GPT, VTOYEFI, veri bölümü ve MBR")
    fun ventoyFullInstall() = runBlocking {
        // 512 MiB: Ventoy'un minimumu (~300 MiB) uzerinde ama ByteArray'in
        // Int.MAX degerinin altinda kaliyor.
        val diskSectors = 512L * 1024 * 1024 / 512
        val dev = FakeBlockDevice(diskSectors)
        val payload = VentoyInstaller.Payload(
            files = mapOf(
                "EFI/BOOT/BOOTX64.EFI" to ByteArray(8 * 1024) { (it % 251).toByte() },
                "EFI/BOOT/grubx64_real.efi" to ByteArray(4 * 1024) { 0x42 },
                "ventoy/ventoy.cpio" to ByteArray(2 * 1024) { 0x37 },
            )
        )

        val summary = VentoyInstaller(RecordingReporter()).install(dev, payload)
        val g = VentoyLayout.geometry(diskSectors)

        // GPT başlığı ve üç bölüntü girdisi
        assertEquals("EFI PART", String(dev.sector(1), 0, 8, Charsets.US_ASCII))
        val entries = ByteArray(16 * 128)
        dev.read(GptTable.ENTRY_ARRAY_SECTOR.toLong(), entries.size, entries)
        assertEquals(VentoyLayout.ESP_NAME, utf16Name(entries, 56), "1. bölüntü adı")
        assertEquals(VentoyLayout.DATA_NAME, utf16Name(entries, 128 + 56), "2. bölüntü adı")
        assertEquals(VentoyLayout.BACKUP_NAME, utf16Name(entries, 256 + 56), "3. bölüntü adı")
        assertEquals(2048L, readLe64(entries, 32), "ESP LBA")
        assertEquals(g.dataStartLba, readLe64(entries, 128 + 32), "veri bölümü LBA")

        // VTOYEFI ve veri bölümü exFAT olarak biçimlendirilmiş olmalı
        assertEquals("FAT32   ", String(dev.sector(g.espStartLba), 0x52, 8, Charsets.US_ASCII), "VTOYEFI (FAT32)")
        assertEquals("EXFAT   ", String(dev.sector(g.dataStartLba), 3, 8, Charsets.US_ASCII), "veri bölümü")

        // MBR: 55AA + 0xEF + LBA 2048
        val mbr = dev.sector(0)
        assertEquals(0x55, mbr[510].toInt() and 0xFF)
        assertEquals(0xAA, mbr[511].toInt() and 0xFF)
        assertEquals(0xEF, mbr[0x1BE + 4].toInt() and 0xFF, "EFI Sistem bölümü")
        assertEquals(2048L, readLe32(mbr, 0x1BE + 12), "ESP LBA")

        assertTrue(summary.contains("Ventoy kuruldu"), "özet kurulumu belirtmeli")
        assertTrue(summary.contains("3 önyükleme dosyası"), "yazılan dosya sayısı belirtilmeli")
        assertTrue(summary.contains("UYARI"), "Legacy önyükleme uyarısı verilmeli")
    }

    @Test
    @DisplayName("Ventoy: ZIP içinden yalnızca ventoy/ dosyaları çıkarılır")
    fun ventoyPayloadExtraction() {
        val zip = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zip).use { out ->
            fun entry(name: String, data: ByteArray) {
                out.putNextEntry(java.util.zip.ZipEntry(name))
                out.write(data)
                out.closeEntry()
            }
            entry("ventoy/EFI/BOOT/BOOTX64.EFI", ByteArray(64) { 1 })
            entry("ventoy/ventoy.cpio", ByteArray(32) { 2 })
            entry("VTOYCLI.EXE", ByteArray(16) { 3 })
            entry("Ventoy2Disk.sh", ByteArray(8) { 4 })
            entry("ventoy.mbr", ByteArray(512) { 5 })
        }
        val payload = VentoyInstaller.extractPayload(ByteArrayInputStream(zip.toByteArray()))
        assertEquals(2, payload.files.size, "yalnızca ventoy/ altındakiler alınmalı")
        assertTrue(payload.files.containsKey("EFI/BOOT/BOOTX64.EFI"))
        assertTrue(payload.files.containsKey("ventoy.cpio"))
        assertTrue(payload.files["EFI/BOOT/BOOTX64.EFI"]!!.contentEquals(ByteArray(64) { 1 }))
        assertNotNull(payload.mbr, "ventoy.mbr yakalanmalı")
        assertEquals(512, payload.mbr!!.size)
    }

    @Test
    @DisplayName("Ventoy: paketlenmiş resmi MBR varsa birebir kullanılır")
    fun ventoyUsesOfficialMbr() {
        val official = ByteArray(512) { 0x7E }
        official[510] = 0x55
        official[511] = 0xAA.toByte()
        assertTrue(VentoyMbr.load(official).contentEquals(official), "paketlenmiş MBR aynen kullanılmalı")
        assertTrue(VentoyMbr.load(null).isNotEmpty(), "varsayılan olarak üretilen kayıt kullanılmalı")
    }
    // ============================================================= yardımcılar

    private fun shortNamesIn(dev: FakeBlockDevice, lba: Long): List<String> {
        val buf = ByteArray(512)
        dev.read(lba, 512, buf)
        val names = mutableListOf<String>()
        var off = 0
        while (off < 512 && buf[off].toInt() != 0) {
            val attr = buf[off + 11].toInt() and 0xFF
            if (attr != 0x0F && attr != 0) {
                names += String(buf, off, 11, Charsets.US_ASCII).trim()
            }
            off += 32
        }
        return names
    }

    /**
     * MFT kaydında verilen adda bir $FILE_NAME özniteliği var mı?
     *
     * $FILE_NAME değeri: 0x40 ad uzunluğu (UTF-16 karakter sayısı),
     * 0x41 ad türü, 0x42 ad (UTF-16LE). **Özniteliğin** ad uzunluğu
     * (offset 0x09) $FILE_NAME için her zaman 0'dır; ad uzunluğu değerin
     * içinde durur.
     */
    private fun hasFileName(record: ByteArray, name: String): Boolean {
        val attrsOffset = le16(record, 0x14)
        var off = attrsOffset
        while (off + 8 <= record.size) {
            val type = readLe32(record, off).toInt()
            if (type == -1) break
            val len = readLe32(record, off + 4).toInt()
            if (len <= 0 || len > record.size) break
            val nonResident = record[off + 8].toInt() and 0xFF
            if (type == 0x30 && nonResident == 0) {
                val valueOffset = le16(record, off + 20)
                val v = off + valueOffset
                val nameLen = record[v + 64].toInt() and 0xFF
                if (String(record, v + 66, nameLen * 2, Charsets.UTF_16LE) == name) return true
            }
            off += (len + 7) and 7.inv()
        }
        return false
    }

    private fun readLe32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    /** Küçük bayt sıralı 64 bit okuma (MBR/GPT/dosya sistemi kayıtları hep LE). */
    private fun readLe64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
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
}
