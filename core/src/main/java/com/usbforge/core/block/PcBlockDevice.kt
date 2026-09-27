package com.usbforge.core.block

import com.usbforge.core.util.Bytes
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale

/**
 * Masaüstü (Windows / Linux / macOS) blok cihaz erişimi.
 *
 * Bu sınıf **yalnızca masaüstü test/araç işlerinde** kullanılır; Android
 * uygulaması [ScsiBlockDevice] ve [RootBlockDevice] arka uçlarını kullanır.
 * Amaç, biçimlendiricilerin ve bölüntü yazıcının çıktısını telefona göndermeden
 * gerçek donanım üzerinde sınamaktır.
 *
 * ## Windows
 * ```
 * PcBlockDevice.openPhysicalDrive(2)          // \\.\PhysicalDrive2
 * PcBlockDevice.open("\\\\.\\PhysicalDrive2")
 * ```
 * `PhysicalDriveN` cihazları `\\?\PhysicalDriveN` veya `\\.\PhysicalDriveN`
 * önekiyle açılmalıdır; yönetici hakkı gerekir.
 *
 * ## Linux
 * ```
 * PcBlockDevice.open("/dev/sdb")
 * ```
 *
 * ## Kapalı bölüm uyarısı
 * Windows, yazma başlamadan önce bölümü ayırmak ister. `physicaldrive` veya
 * `mountvol X: /p` ile ayırma yapılmazsa ilk `write()` `ERROR_ACCESS_DENIED`
 * ile başarısız olur. [prepareForWrite] bu durumu yakalayıp anlaşılır bir
 * mesajla bildirir.
 */
class PcBlockDevice private constructor(
    override val displayName: String,
    override val totalSectors: Long,
    private val channel: FileChannel,
    private val closeables: List<Closeable>,
) : BlockDevice {

    override val writable: Boolean = true

    override fun prepareForWrite() {
        if (!channel.isOpen) throw BlockAccessException("$displayName kapalı.")
    }

    override fun write(
        startLba: Long,
        src: ByteArray,
        srcOffset: Int,
        length: Int,
        isCancelled: () -> Boolean,
        onChunk: ((bytes: Long) -> Unit)?,
    ) {
        require(length % 512 == 0) { "Yazma uzunluğu 512'nin katı olmalı, $length verildi." }
        if (startLba + length / 512 > totalSectors) {
            throw BlockAccessException(
                "LBA aralığı disk dışında: ${startLba}+${length / 512} > $totalSectors"
            )
        }
        val position = startLba * 512L
        var written = 0L
        while (written < length) {
            if (isCancelled()) throw BlockWriteCancelled()
            val n = minOf(Piece, length - written.toInt()).toInt()
            val buf = ByteBuffer.wrap(src, srcOffset + written.toInt(), n)
            var remaining = n
            while (remaining > 0) {
                val w = channel.write(buf, position + written)
                if (w <= 0) throw BlockAccessException("$displayName yazma durdu (0 bayt).")
                written += w
                remaining -= w
            }
            onChunk?.invoke(n.toLong())
        }
    }

    override fun read(
        startLba: Long,
        length: Int,
        dst: ByteArray,
        dstOffset: Int,
        isCancelled: () -> Boolean,
    ) {
        val position = startLba * 512L
        var got = 0L
        while (got < length) {
            if (isCancelled()) throw BlockWriteCancelled()
            val n = minOf(Piece, length - got.toInt()).toInt()
            val buf = ByteBuffer.wrap(dst, dstOffset + got.toInt(), n)
            var remaining = n
            while (remaining > 0) {
                val r = channel.read(buf, position + got)
                if (r < 0) throw BlockAccessException("$displayName okuma sınırına dayandı.")
                if (r == 0) throw BlockAccessException("$displayName okuma durdu (0 bayt).")
                got += r
                remaining -= r
            }
        }
    }

    override fun healthHint(): String =
        "masaüstü · ${Bytes.human(totalSectors * 512)} · 512 B/sektör"

    override fun flush() {
        channel.force(true)
    }

    override fun close() {
        runCatching { channel.force(true) }
        closeables.forEach { runCatching { it.close() } }
    }

    companion object {
        private const val Piece = 4 * 1024 * 1024

        /**
         * Verilen yolu açar. Windows'ta `\\.\PhysicalDriveN` ve
         * `/dev/sdX` biçimlerini kabul eder.
         */
        fun open(path: String): PcBlockDevice {
            val channel = FileChannel.open(
                Paths.get(path),
                java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE,
            )
            val size = channel.size()
            return PcBlockDevice(path, size / 512, channel, listOf(channel))
        }

        /**
         * Windows'ta `\\.\PhysicalDriveN` açar.
         * @param index 0, 1, 2 … (Disk Management'daki "Disk 2" gibi)
         */
        fun openPhysicalDrive(index: Int): PcBlockDevice {
            val path = if (System.getProperty("os.name").lowercase(Locale.US).contains("win")) {
                "\\\\.\\PhysicalDrive$index"
            } else {
                "/dev/sd${'a' + index}"
            }
            return open(path)
        }

        /**
         * Sistemdeki harici/USB blok cihazları listeler.
         * Masaüstü test aracının cihaz seçme ekranını besler.
         */
        fun listPhysicalDrives(): List<PcDriveInfo> {
            val os = System.getProperty("os.name").lowercase(Locale.US)
            return if (os.contains("win")) listWindowsDrives() else listLinuxBlockDevices()
        }

        private fun listWindowsDrives(): List<PcDriveInfo> {
            val result = mutableListOf<PcDriveInfo>()
            // \\.\PhysicalDrive0 .. 15 denenir; erişilemeyenler atlanır.
            for (i in 0..15) {
                val path = "\\\\.\\PhysicalDrive$i"
                runCatching {
                    FileChannel.open(
                        Paths.get(path),
                        java.nio.file.StandardOpenOption.READ,
                        java.nio.file.StandardOpenOption.WRITE,
                    ).use { ch ->
                        val size = ch.size()
                        val model = readWindowsDiskModel(i)
                        result += PcDriveInfo(
                            index = i,
                            path = path,
                            sizeBytes = size,
                            model = model,
                            removable = true,
                        )
                    }
                }
            }
            return result
        }

        /**
         * Windows fiziksel disk model bilgisini WMI ile okur.
         * `Get-CimInstance` veya `wmic` yoksa null döner.
         */
        private fun readWindowsDiskModel(index: Int): String? = runCatching {
            val p = ProcessBuilder(
                "powershell", "-NoProfile", "-Command",
                "(Get-CimInstance Win32_DiskDrive -Filter \"Index=$index\").Model",
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        }.getOrNull()

        private fun listLinuxBlockDevices(): List<PcDriveInfo> {
            val root = Paths.get("/sys/block")
            if (!Files.isDirectory(root)) return emptyList()
            return Files.list(root).use { stream ->
                stream.map { p ->
                    val name = p.fileName.toString()
                    val removable = Files.exists(p.resolve("removable")) &&
                        runCatching { Files.readString(p.resolve("removable")).trim() == "1" }.getOrDefault(false)
                    val sectors = runCatching {
                        Files.readString(p.resolve("size")).trim().toLong()
                    }.getOrDefault(0L)
                    PcDriveInfo(
                        index = 0,
                        path = "/dev/$name",
                        sizeBytes = sectors * 512,
                        model = runCatching { Files.readString(p.resolve("device/model")).trim() }.getOrNull(),
                        removable = removable,
                    )
                }.toList()
            }
        }
    }
}

/** Masaüstünde listelenen bir blok cihaz. */
data class PcDriveInfo(
    val index: Int,
    val path: String,
    val sizeBytes: Long,
    val model: String?,
    val removable: Boolean,
) {
    override fun toString(): String =
        "${Bytes.human(sizeBytes)}  $path  ${model ?: "?"}"
}
