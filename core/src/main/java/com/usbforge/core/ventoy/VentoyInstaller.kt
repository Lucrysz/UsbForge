package com.usbforge.core.ventoy

import com.usbforge.core.block.BlockDevice
import com.usbforge.core.block.CancelledByUser
import com.usbforge.core.disk.DiskWriter
import com.usbforge.core.engine.ProgressReporter
import com.usbforge.core.fs.ExFatFormatter
import com.usbforge.core.fs.Fat32Formatter
import com.usbforge.core.fs.Fat32Writer
import com.usbforge.core.fs.FsMath
import com.usbforge.core.partition.FileSystemKind
import com.usbforge.core.partition.Guid
import com.usbforge.core.partition.GptTable
import com.usbforge.core.partition.PartitionPlan
import com.usbforge.core.partition.PartitionScheme
import com.usbforge.core.usb.LogLevel
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Ventoy kurulumu.
 *
 * ## Adımlar
 * 1. [payload] ile önyükleme dosyaları sağlanır (aşağıya bakın).
 * 2. Bölüntü yerleşimi hesaplanır ([VentoyLayout.geometry]).
 * 3. GPT yazılır: VTOYEFI (32 MiB) + veri bölümü + yedek EFI.
 * 4. VTOYEFI bölümü biçimlendirilir ve önyükleme dosyaları içine yazılır.
 * 5. Veri bölümü boş exFAT olarak biçimlendirilir.
 * 6. 0. sektöre MBR yazılır ve doğrulanır.
 *
 * ## Neden 3. adımda 0. sektör yazılmıyor
 * Önyükleme kaydı **en son** yazılır. Aksi hâlde GPT yazımı sırasında
 * kalan alan hesabı tutarsızlaşır ve bazı araçlar diski geçersiz sayar.
 *
 * ## Önyükleme dosyaları nereden gelir
 * Ventoy'un `EFI/BOOT/BOOTX64.EFI` ve `ventoy/` dosyaları **derlenmiş x86
 * ikili dosyalarıdır**; bu kaynak koddan üretilemez. Üç seçenek vardır:
 *
 * 1. `install(device, payload = ...)` — çağıran, resmî dağıtımdan indirdiği
 *    dosyaları [VentoyPayload] olarak verir. **Önerilen yol.**
 * 2. `install(device)` — yalnızca bölüntüleri kurar, önyükleme dosyalarını
 *    yazmaz. UEFI önyükleme çalışmaz; kullanıcıya bu durum bildirilir.
 * 3. `assets/ventoy.mbr` paketlenirse Legacy (CSM) önyükleme de çalışır.
 */
class VentoyInstaller(
    private val progress: ProgressReporter,
    /** Paketlenmiş özgün Ventoy MBR'i (512 bayt) veya null. */
    private val officialMbr: ByteArray? = null,
) {

    /**
     * Ventoy önyükleme dosyaları.
     *
     * @param files  göreli yol → içerik (ör. `EFI/BOOT/BOOTX64.EFI`)
     * @param mbr    resmî 512 baytlık MBR (Legacy önyükleme için), veya null
     */
    data class Payload(val files: Map<String, ByteArray>, val mbr: ByteArray? = null) {
        val totalBytes: Long get() = files.values.sumOf { it.size.toLong() }

        /** Dosya yolları, uzun yollar önce gelsin diye sıralanır. */
        fun sortedFiles(): List<Pair<String, ByteArray>> =
            files.entries.sortedBy { it.key.count { c -> c == '/' } }.map { it.key to it.value }
    }

    /** Bölüntüleri ve (varsa) önyükleme dosyalarını kurar. */
    suspend fun install(device: BlockDevice, payload: Payload? = null): String {
        val geometry = VentoyLayout.geometry(device.totalSectors)
        val minBytes = VentoyLayout.MIN_SIZE_MIB * 1024 * 1024
        require(device.sizeBytes >= minBytes) {
            "Ventoy için disk en az ${VentoyLayout.MIN_SIZE_MIB} MiB olmalı " +
                    "(${device.sizeBytes / (1024 * 1024)} MiB bulundu)."
        }

        val notes = mutableListOf<String>()

        // --- 1. Bölüntü tablosu --------------------------------------------
        progress.setPhase("Ventoy bölüntü tablosu yazılıyor")
        device.prepareForWrite()
        writeGpt(device, geometry, progress)

        // --- 2. VTOYEFI ----------------------------------------------------
        // FAT32: 32 MiB'da exFAT geçersizdir (küme sayısı < 1024) ve
        // önyükleme dosyalarının yazılabilmesi için bir yazıcı gerekir.
        progress.setPhase("VTOYEFI biçimlendiriliyor (${geometry.espSizeMiB} MiB)")
        val esp = Fat32Formatter(geometry.espStartLba, geometry.espSectors, VentoyLayout.ESP_LABEL)
        val espResult = esp.format(device, progress)
        notes += "VTOYEFI: ${espResult.fileSystem}, ${geometry.espSizeMiB} MiB, " +
                "LBA ${geometry.espStartLba}..${geometry.espStartLba + geometry.espSectors - 1}"

        if (payload != null && payload.files.isNotEmpty()) {
            progress.setPhase("Önyükleme dosyaları yazılıyor (${payload.files.size} dosya)")
            val writer = esp.newWriter(device, progress)
            payload.sortedFiles().forEach { (path, bytes) ->
                if (progress.isCancelled) throw CancelledByUser()
                writer.addFile(path, ByteArrayInputStream(bytes), bytes.size.toLong())
            }
            writer.finalizeVolume()
            notes += "${payload.files.size} önyükleme dosyası yazıldı " +
                    "(${(payload.totalBytes / 1024)} KiB)"
        } else {
            notes += "UYARI: önyükleme dosyaları yazılmadı — UEFI önyükleme çalışmayacak."
        }

        // --- 3. Veri bölümü -------------------------------------------------
        progress.setPhase("Veri bölümü biçimlendiriliyor (${geometry.dataSizeMiB} MiB)")
        val dataPlan = PartitionPlan(
            scheme = PartitionScheme.GPT,
            fsKind = FileSystemKind.EXFAT,
            startLba = geometry.dataStartLba,
            sectors = geometry.dataSectors,
            label = VentoyLayout.DATA_LABEL,
        )
        ExFatFormatter(
            startLba = dataPlan.startLba,
            sectors = dataPlan.sectors,
            label = VentoyLayout.DATA_LABEL,
        ).format(device, progress)
        notes += "Veri bölümü: exFAT, ${geometry.dataSizeMiB} MiB, " +
                "LBA ${geometry.dataStartLba}..${geometry.dataStartLba + geometry.dataSectors - 1}"

        // --- 4. Önyükleme kaydı (en son) -----------------------------------
        progress.setPhase("Önyükleme kaydı yazılıyor")
        val mbr = VentoyMbr.load(officialMbr ?: payload?.mbr)
        device.write(0, mbr) { progress.addWritten(it) }
        device.flush()

        // --- 5. Doğrulama ---------------------------------------------------
        val sector0 = ByteArray(512)
        device.read(0, 512, sector0)
        val verification = VentoyMbr.verify(sector0)
        progress.log("Doğrulama: $verification", LogLevel.INFO)
        notes += verification

        if (officialMbr == null && payload?.mbr == null) {
            notes += "UYARI: Legacy (CSM) önyükleme için resmi ventoy.mbr " +
                    "paketlenmedi. Yalnızca UEFI önyükleme desteklenir."
        }

        return buildString {
            appendLine("Ventoy kuruldu")
            appendLine(VentoyLayout.describe(geometry).trimEnd())
            notes.forEach { appendLine("· $it") }
        }
    }

    private fun writeGpt(device: BlockDevice, g: VentoyLayout.Geometry, progress: ProgressReporter) {
        val layout = GptTable.layout(
            diskSectors = g.diskSectors,
            entries = listOf(
                GptTable.Entry(
                    typeGuid = Guid.Types.EFI_SYSTEM,
                    uniqueGuid = Guid.format(Guid.random()),
                    name = VentoyLayout.ESP_NAME,
                    firstLba = g.espStartLba,
                    lastLba = g.espStartLba + g.espSectors - 1,
                ),
                GptTable.Entry(
                    typeGuid = Guid.Types.BASIC_DATA,
                    uniqueGuid = Guid.format(Guid.random()),
                    name = VentoyLayout.DATA_NAME,
                    firstLba = g.dataStartLba,
                    lastLba = g.dataStartLba + g.dataSectors - 1,
                ),
                GptTable.Entry(
                    typeGuid = Guid.Types.EFI_SYSTEM,
                    uniqueGuid = Guid.format(Guid.random()),
                    name = VentoyLayout.BACKUP_NAME,
                    firstLba = g.backupStartLba,
                    // Yedek GPT başlığı disk sonundaki 33 sektörü işgal
                    // ettiği için bölüntü orada bitmelidir.
                    lastLba = GptTable.lastUsableLba(g.diskSectors),
                ),
            ),
        )
        device.write(0, layout.protectiveMbr) { progress.addWritten(it) }
        device.write(GptTable.HEADER_SECTOR.toLong(), layout.primaryHeader) { progress.addWritten(it) }
        device.write(GptTable.ENTRY_ARRAY_SECTOR.toLong(), layout.entryArray) { progress.addWritten(it) }
        device.write(GptTable.backupEntryArrayLba(g.diskSectors), layout.backupEntryArray) {
            progress.addWritten(it)
        }
        device.write(g.diskSectors - 1, layout.backupHeader) { progress.addWritten(it) }
        device.flush()
    }

    companion object {
        /** Resmî dağıtımın indirileceği adres (Ventoy kendi sunucusu). */
        const val RELEASE_PAGE = "https://github.com/ventoy/Ventoy/releases/latest"

        /**
         * Bir ZIP arşivindeki Ventoy önyükleme dosyalarını çıkarır.
         *
         * Yalnızca `ventoy/` dizini altındaki dosyalar alınır; dağıtımın
         * içindeki araçlar (VTOYCLI.EXE, Ventoy2Disk.sh vb.) kullanılmaz,
         * çünkü bunlar masaüstü/PC önyükleme araçlarıdır.
         *
         * @param input ZIP akışı (kullanıcı indirir ve önbelleğe yazar)
         */
        fun extractPayload(input: InputStream): Payload {
            val files = LinkedHashMap<String, ByteArray>()
            var mbr: ByteArray? = null
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (entry.isDirectory) continue
                    when {
                        name.startsWith("ventoy/") && !name.contains("..") -> {
                            val bytes = zip.readBytes()
                            val relative = name.removePrefix("ventoy/")
                            files[relative] = bytes
                        }
                        // Bazı dağıtımlarda MBR ayrı dosya olarak bulunur.
                        name.equals("ventoy.mbr", ignoreCase = true) -> {
                            mbr = zip.readBytes().takeIf { it.size >= 512 }?.copyOf(512)
                        }
                    }
                }
            }
            return Payload(files, mbr)
        }

        /** [url] adresindeki ZIP'i indirir ve [extractPayload] ile açar. */
        fun downloadAndExtract(url: String, progress: ProgressReporter): Payload {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("İndirme başarısız: HTTP ${connection.responseCode}")
                }
                val length = connection.contentLengthLong
                if (length > 0) {
                    progress.setTotal(length)
                    progress.setPhase("Ventoy paketi indiriliyor (${length / (1024 * 1024)} MiB)")
                }
                val payload = extractPayload(
                    object : InputStream() {
                        override fun read(): Int = connection.inputStream.read()
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val n = connection.inputStream.read(b, off, len)
                            if (n > 0) progress.addWritten(n.toLong())
                            return n
                        }
                    }
                )
                connection.disconnect()
                return payload
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }
}
