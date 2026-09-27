package com.usbforge.core.disk

import com.usbforge.core.block.BlockDevice
import com.usbforge.core.block.CancelledByUser
import com.usbforge.core.engine.ProgressReporter
import com.usbforge.core.usb.LogLevel
import java.io.IOException
import java.io.InputStream

/**
 * ISO / IMG dosyasını diske sektör sektör kopyalar (klasik `dd` davranışı).
 *
 * ## Akış yönetimi
 * - Kaynak, 512 bayta hizalı bloklar halinde okunur; son blok kısa olabilir
 *   ve sıfırla doldurulur.
 * - Hedefe yazma sabit [CHUNK_SECTORS] (2048 = 1 MiB) blokluk parçalar
 *   halinde yapılır. Böylece:
 *   · bellek kullanımı dosya boyutundan bağımsız ve sabittir (OOM yok),
 *   · USB Host API'nin 256 KiB'lik `bulkTransfer` parçaları verimli kullanılır,
 *   · ilerleme her 1 MiB'de bir güncellenir (arayüz akıcı kalır).
 * - Dosya diskin tamamını doldurmuyorsa kalan sektörler sıfırlanır; aksi
 *   hâlde eski bölüntü tablosu ve dosya sistemi imzaları hayatta kalır ve
 *   ISO "bu diskten boot edilemez" gibi algılanabilir.
 *
 * ## Doğrulama
 * [verify] açıksa yazma sonrası diskten geri okunup **bayt bayt**
 * karşılaştırılır. Bu, gevşek kablo ve kararsız bağlantı gibi yazma
 * sırasında oluşan sessiz bozulmaları yakalar; ek süre yazma süresi
 * kadardır.
 *
 * ## Kaynak akışı
 * Kaynak bir [InputStream] olarak değil, **yeniden açılabilir bir fabrika**
 * olarak verilir; doğrulama aşamasının akışı baştan açabilmesi gerekir.
 */
class IsoWriter(
    /** Hedefe yazılacak sektör sayısı. -1 ise kaynağın boyutundan alınır. */
    private val sectorsOverride: Long = -1L,
    /** Diskin kalan kısmını sıfırla. */
    private val zeroRemainder: Boolean = true,
    /** Yazma sonrası geri okuma doğrulaması yap. */
    private val verify: Boolean = false,
) {

    fun write(
        device: BlockDevice,
        openSource: () -> InputStream,
        totalBytes: Long,
        progress: ProgressReporter,
    ) {
        val contentSectors = when {
            sectorsOverride >= 0 -> sectorsOverride
            totalBytes > 0 -> (totalBytes + 511) / 512
            else -> device.totalSectors
        }
        require(contentSectors > 0) { "Kaynak boş görünüyor (0 bayt)." }
        require(contentSectors <= device.totalSectors) {
            "Kaynak cihazdan büyük: ${contentSectors * 512 / (1024 * 1024)} MiB > " +
                    "${device.totalSectors * 512 / (1024 * 1024)} MiB (cihaz kapasitesi)."
        }

        progress.setTotal(contentSectors * 512)
        progress.setPhase("ISO yazılıyor — ${contentSectors * 512 / (1024 * 1024)} MiB")

        device.prepareForWrite()

        val writtenSectors = writeStream(device, openSource, contentSectors, progress)
        progress.log("Yazma tamamlandı: $writtenSectors sektör", LogLevel.INFO)

        // --- Kalan alanı sıfırla --------------------------------------------
        if (zeroRemainder && writtenSectors < device.totalSectors) {
            zeroTail(device, writtenSectors, progress)
        }

        device.flush()

        if (verify) {
            verifyWritten(device, openSource, contentSectors, progress)
        }
    }

    /** Kaynağı [sectors] sektöre yazar. */
    private fun writeStream(
        device: BlockDevice,
        openSource: () -> InputStream,
        sectors: Long,
        progress: ProgressReporter,
    ): Long {
        val chunk = ByteArray(CHUNK_SECTORS * 512)
        var lba = 0L
        openSource().use { source ->
            while (lba < sectors) {
                if (progress.isCancelled) throw CancelledByUser()
                val sectorsThisRound = minOf(CHUNK_SECTORS.toLong(), sectors - lba).toInt()
                val want = sectorsThisRound * 512

                var filled = 0
                while (filled < want) {
                    val n = try {
                        source.read(chunk, filled, want - filled)
                    } catch (e: IOException) {
                        throw IOException("Kaynak okunamadı: ${e.message}", e)
                    }
                    if (n < 0) break
                    filled += n
                    if (n == 0) break
                }
                if (filled < want) java.util.Arrays.fill(chunk, filled, want, 0.toByte())

                device.write(lba, chunk, 0, want) { progress.addWritten(it) }
                lba += sectorsThisRound
            }
        }
        return lba
    }

    /** Disk sonuna kadar 0 yazar. */
    private fun zeroTail(device: BlockDevice, fromLba: Long, progress: ProgressReporter) {
        val remaining = device.totalSectors - fromLba
        if (remaining <= 0) return
        progress.addTotal(remaining * 512)
        progress.setPhase("Kalan alan temizleniyor — ${remaining * 512 / (1024 * 1024)} MiB")

        val zeros = ByteArray(CHUNK_SECTORS * 512)
        var cursor = fromLba
        while (cursor < device.totalSectors) {
            if (progress.isCancelled) throw CancelledByUser()
            val n = minOf(device.totalSectors - cursor, CHUNK_SECTORS.toLong()).toInt()
            device.write(cursor, zeros, 0, n * 512) { progress.addWritten(it) }
            cursor += n
        }
        progress.log("Kalan alan sıfırlandı ($remaining sektör).", LogLevel.INFO)
    }

    /**
     * Diske geri okuma yapar ve kaynakla bayt bayt karşılaştırır.
     * @throws VerificationFailedException ilk uyuşmazlıkta
     */
    private fun verifyWritten(
        device: BlockDevice,
        openSource: () -> InputStream,
        sectors: Long,
        progress: ProgressReporter,
    ) {
        progress.setPhase("Doğrulama — disk geri okunuyor")
        val deviceBuf = ByteArray(CHUNK_SECTORS * 512)
        val sourceBuf = ByteArray(CHUNK_SECTORS * 512)
        var lba = 0L
        var verified = 0L

        openSource().use { source ->
            while (lba < sectors) {
                if (progress.isCancelled) throw CancelledByUser()
                val n = minOf(CHUNK_SECTORS.toLong(), sectors - lba).toInt()
                val want = n * 512

                device.read(lba, want, deviceBuf, 0)

                var filled = 0
                while (filled < want) {
                    val r = source.read(sourceBuf, filled, want - filled)
                    if (r < 0) break
                    filled += r
                    if (r == 0) break
                }
                // Kaynak diske yazılandığından daha kısaysa, kalan sektörler
                // sıfırla doldurulmuştur; karşılaştırmada da öyle sayılır.
                if (filled < want) java.util.Arrays.fill(sourceBuf, filled, want, 0.toByte())

                if (!deviceBuf.contentEquals(sourceBuf)) {
                    val diff = deviceBuf.indices.first { deviceBuf[it] != sourceBuf[it] }
                    throw VerificationFailedException(
                        "LBA ${lba + diff / 512} ofset ${diff % 512} doğrulanamadı: " +
                                "disk=0x${deviceBuf[diff].toInt().and(0xFF).toString(16)} " +
                                "kaynak=0x${sourceBuf[diff].toInt().and(0xFF).toString(16)}"
                    )
                }
                lba += n
                verified += want
            }
        }
        progress.log("Doğrulama tamamlandı: $verified bayt eşleşti.", LogLevel.INFO)
    }

    companion object {
        /** Yazma parçası: 1 MiB. */
        const val CHUNK_SECTORS = 2048
    }
}

/** Doğrulama sırasında fark bulundu. */
class VerificationFailedException(message: String) : RuntimeException(message)
