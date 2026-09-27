package com.usbforge.core.disk

import com.usbforge.core.block.BlockDevice
import com.usbforge.core.engine.ProgressReporter
import com.usbforge.core.fs.ExFatFormatter
import com.usbforge.core.fs.Fat32Formatter
import com.usbforge.core.fs.FsFormatter
import com.usbforge.core.fs.NtfsFormatter
import com.usbforge.core.partition.FileSystemKind
import com.usbforge.core.partition.Guid
import com.usbforge.core.partition.GptTable
import com.usbforge.core.partition.MbrTable
import com.usbforge.core.partition.PartitionPlan
import com.usbforge.core.partition.PartitionScheme
import com.usbforge.core.usb.LogLevel

/**
 * Bölüntü tablosunu diske yazar ve istenen dosya sistemini biçimlendirir.
 *
 * ## İşlem sırası
 * 1. Cihaz yazmaya hazırlanır (denetleyici kilidi, önbellek, medya).
 * 2. **GPT** seçilmişse: koruyucu MBR, birincil başlık, giriş dizisi ve
 *    disk sonundaki yedek başlık + yedek giriş dizisi yazılır.
 *    **MBR** seçilmişse: tek sektörlük önyükleme kaydı.
 * 3. Bölüntünün dosya sistemi biçimlendirilir.
 * 4. Önbellek boşaltılır.
 *
 * ## Destructive uyarı
 * Bu sınıf diskin **tamamını** değiştirir. Çağırmadan önce kullanıcı
 * onayı alınmalıdır.
 */
class DiskWriter {

    /**
     * [plan] doğrultusunda bölüntü tablosunu yazar ve dosya sistemini
     * biçimlendirir.
     *
     * @param label bölüm etiketi
     * @return biçimlendirme özeti
     */
    suspend fun writeAndFormat(
        device: BlockDevice,
        plan: PartitionPlan,
        progress: ProgressReporter,
        label: String,
    ): String {
        plan.validate(device.totalSectors)

        progress.setPhase("Cihaz yazmaya hazırlanıyor")
        device.prepareForWrite()
        device.writable.let { if (!it) throw BlockNotWritableException("Cihaz yazılabilir değil.") }

        // --- Bölüntü tablosu --------------------------------------------------
        when (plan.scheme) {
            PartitionScheme.GPT -> writeGpt(device, plan, progress)
            PartitionScheme.MBR -> writeMbr(device, plan, progress)
        }

        // --- Dosya sistemi ----------------------------------------------------
        val result = when (plan.fsKind) {
            FileSystemKind.FAT32 -> Fat32Formatter(plan.startLba, plan.sectors, label)
            FileSystemKind.EXFAT -> ExFatFormatter(plan.startLba, plan.sectors, label)
            FileSystemKind.NTFS -> NtfsFormatter(plan.startLba, plan.sectors, label)
        }
        val fsResult = result.format(device, progress)

        device.flush()
        progress.log("Bölüm: LBA ${plan.startLba} · ${plan.sizeMiB} MiB · ${plan.fsKind.label}", LogLevel.INFO)
        progress.log("${fsResult.fileSystem} tamam: küme ${fsResult.clusterSizeBytes / 1024} KiB", LogLevel.INFO)
        return buildString {
            appendLine("${fsResult.fileSystem} biçimlendirildi")
            appendLine("Bölüm başlangıcı : LBA ${plan.startLba}")
            appendLine("Bölüm boyutu    : ${plan.sizeMiB} MiB")
            appendLine("Küme sayısı     : ${fsResult.totalClusters}")
            appendLine("Küme boyutu     : ${fsResult.clusterSizeBytes / 1024} KiB")
            fsResult.notes.forEach { appendLine("· $it") }
        }
    }

    /**
     * Diski [sectors] sektör boyunca sıfırlar veya rastgele doldurur.
     * Eski bölüntü tablolarının ve dosya sistemi imzalarının tespit
     * edilememesi için kullanılır (güvenli silme).
     */
    suspend fun wipeDisk(
        device: BlockDevice,
        sectors: Long,
        random: Boolean,
        progress: ProgressReporter,
    ) {
        progress.setPhase(if (random) "Disk rastgele veriyle temizleniyor" else "Disk sıfırlanıyor")
        device.prepareForWrite()
        require(sectors in 0..device.totalSectors) {
            "Geçersiz sektör aralığı: 0..$sectors (cihaz ${device.totalSectors})"
        }
        val buf = ByteArray(1024 * 1024)
        if (random) java.util.Random(0x5DEECE66DL).nextBytes(buf)
        var lba = 0L
        var remaining = sectors
        while (remaining > 0) {
            if (progress.isCancelled) throw CancelledByUser()
            val n = minOf(remaining, buf.size.toLong() / 512).toInt()
            val len = n * 512
            device.write(lba, buf, 0, len) { progress.addWritten(it) }
            lba += n
            remaining -= n
        }
        device.flush()
        progress.log("Temizleme tamamlandı: ${sectors * 512 / (1024 * 1024)} MiB", LogLevel.INFO)
    }

    // ------------------------------------------------------------- MBR / GPT

    private fun writeMbr(device: BlockDevice, plan: PartitionPlan, progress: ProgressReporter) {
        progress.setPhase("MBR önyükleme kaydı yazılıyor")
        val bootable = if (plan.fsKind == FileSystemKind.FAT32) true else false
        val mbr = MbrTable.build(
            entries = listOf(
                MbrTable.Entry(
                    type = plan.fsKind.mbrType,
                    startLba = plan.startLba,
                    sectors = plan.sectors,
                    bootable = bootable,
                )
            ),
            diskSectors = device.totalSectors,
            bootCode = null, // işletim sistemi yükleyen kod yazılmaz
        )
        device.write(0, mbr) { progress.addWritten(it) }
        device.flush()
        progress.log("MBR: tip=0x${plan.fsKind.mbrType.toString(16)} başlangıç=${plan.startLba}", LogLevel.INFO)
    }

    private fun writeGpt(device: BlockDevice, plan: PartitionPlan, progress: ProgressReporter) {
        progress.setPhase("GPT bölüntü tablosu yazılıyor")

        val uniqueGuid = Guid.format(Guid.random())
        val layout = GptTable.layout(
            diskSectors = device.totalSectors,
            entries = listOf(
                GptTable.Entry(
                    typeGuid = plan.fsKind.gptType,
                    uniqueGuid = uniqueGuid,
                    name = plan.label,
                    firstLba = plan.startLba,
                    lastLba = plan.startLba + plan.sectors - 1,
                    attributes = 0L,
                )
            ),
        )

        // 0: koruyucu MBR
        device.write(0, layout.protectiveMbr) { progress.addWritten(it) }
        // 1: birincil başlık
        device.write(GptTable.HEADER_SECTOR, layout.primaryHeader) { progress.addWritten(it) }
        // 2..33: giriş dizisi (32 sektör)
        device.write(GptTable.ENTRY_ARRAY_SECTOR, layout.entryArray) { progress.addWritten(it) }

        // Disk sonu: yedek giriş dizisi + yedek başlık
        val backupEntryLba = device.totalSectors - 1 - GptTable.BACKUP_ENTRY_ARRAY_SECTORS
        device.write(backupEntryLba, layout.backupEntryArray) { progress.addWritten(it) }
        device.write(device.totalSectors - 1, layout.backupHeader) { progress.addWritten(it) }
        device.write(device.totalSectors - 1, layout.protectiveMbr) { progress.addWritten(it) }

        device.flush()
        progress.log(
            "GPT: tip=${plan.fsKind.gptType} · ${plan.label} · LBA ${plan.startLba}.." +
                    "${plan.startLba + plan.sectors - 1}",
            LogLevel.INFO,
        )
    }
}

