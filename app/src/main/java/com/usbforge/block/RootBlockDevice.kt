package com.usbforge.block

import java.io.File

/**
 * Root'lu cihazlarda doğrudan `/dev/block/*` erişimi.
 *
 * Android'in `dd` yardımcı programı her Android sürümünde bulunur ve
 * `toybox`/`coreutils` implementasyonları `iflag=fullblock` ve
 * `conv=fsync` seçeneklerini destekler. Bu nedenle shell üzerinden akış
 * kurmak, JNI ile `ioctl` çağırmaktan çok daha güvenilirdir.
 *
 * ## Akış protokolü
 * Kalıcı bir `su` kabuğu açılır. Her yazma isteği için kabuğa:
 * ```
 * head -c <bayt> /proc/self/fd/0 | dd of=<hedef> bs=1048576 oflag=direct conv=fsync
 * ```
 * yazılır. `head -c` sayesinde EOF belirsizliği ortadan kalkar: `dd`
 * tam olarak istenen bayt sayısını yazdıktan sonra kapanır. Ardından
 * `echo <marker>` komutu ile tamamlanma bildirimi alınır.
 *
 * ## Güvenlik
 * Blok cihaz yolu yalnızca uygulamanın kendi kök denetimi içinde
 * doğrulanır; kullanıcıdan gelen ham yol `^\/dev\/block\/[a-z0-9/]+$`
 * desenine uymak zorundadır.
 */
class RootBlockDevice private constructor(
    override val displayName: String,
    override val totalSectors: Long,
    private val devicePath: String,
) : BlockDevice {

    private val shell: RootShell = RootShellProvider.get()
        ?: throw BlockAccessException(
            "Root erişimi yok: `su` bulunamadı veya root reddedildi. Cihaz root'lu olmalı."
        )

    override val writable: Boolean = true

    private var unmounted: List<String> = emptyList()

    override fun prepareForWrite() {
        // Bağlı partition'ları ayır; kernel'in page cache'i blok
        // cihazın üzerine yazmasına izin vermezse yazma EIO ile başarısız olur.
        unmounted = unmountAllPartitions(devicePath)
        shell.sync()
    }

    override fun write(
        startLba: Long,
        src: ByteArray,
        srcOffset: Int,
        length: Int,
        isCancelled: () -> Boolean,
        onChunk: ((bytes: Long) -> Unit)?,
    ) {
        shell.writeToDevice(devicePath, startLba, src, srcOffset, length, isCancelled, onChunk)
    }

    override fun read(
        startLba: Long,
        length: Int,
        dst: ByteArray,
        dstOffset: Int,
        isCancelled: () -> Boolean,
    ) {
        val data = shell.readFromDevice(devicePath, startLba, length)
        System.arraycopy(data, 0, dst, dstOffset, length)
    }

    override fun healthHint(): String = buildString {
        append("root: ").append(devicePath)
        if (unmounted.isNotEmpty()) append(" · ayrılan: ").append(unmounted.joinToString())
    }

    override fun flush() {
        shell.sync()
    }

    override fun close() {
        // Disk partition'larını geri bağla — kullanıcı cihazı kullanamaz hale
        // gelmesin. Başarısız olursa sorun değil; bir sonr açılışta düzelir.
        for (p in unmounted) runCatching { shell.exec("mount $p") }
        unmounted = emptyList()
    }

    companion object {
        private val PATH_PATTERN = Regex("^/dev/block/[A-Za-z0-9/_.\\-]+$")

        /** `ls /dev/block` çıktısından blok cihaz adlarını döndürür. */
        fun listBlockDevices(): List<String> = runCatching {
            val process = ProcessBuilder("su", "-c", "ls -1 /dev/block 2>/dev/null")
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            out.lines().map { it.trim() }
                .filter { it.isNotEmpty() && PATH_PATTERN.matches("/dev/block/$it") }
                .sorted()
        }.getOrElse { emptyList() }

        /** [path] gerçek bir blok cihaz yolu olarak doğrulanır. */
        fun isValidPath(path: String): Boolean = PATH_PATTERN.matches(path)

        /**
         * Verilen blok cihaza erişim sağlar. [totalSectors] bilinmiyorsa
         * `blockdev --getsz` ile sorgulanır.
         */
        fun open(path: String, totalSectorsHint: Long = 0L): RootBlockDevice {
            require(isValidPath(path)) { "Geçersiz blok cihaz yolu: $path" }
            val sectors = if (totalSectorsHint > 0) totalSectorsHint else querySectors(path)
            return RootBlockDevice(File(path).name, sectors, path)
        }

        private fun querySectors(path: String): Long {
            val shell = RootShell.instance()
            val blockdev = shell.exec("blockdev --getsz $path 2>/dev/null").trim()
            blockdev.toLongOrNull()?.let { return it }
            val catSize = shell.exec("cat /sys/class/block/${File(path).name}/size 2>/dev/null").trim()
            return catSize.toLongOrNull()?.takeIf { it > 0 }
                ?: throw BlockAccessException("'$path' boyutu belirlenemedi (root erişimi yok?).")
        }

        /**
         * Verilen cihaza ait tüm bağlı partition'ları ayırır.
         * @return ayrılan partition yolları (yeniden bağlamak için)
         */
        private fun unmountAllPartitions(base: String): List<String> {
            val shell = RootShell.instance()
            val name = File(base).name
            val candidates = buildList {
                add(base)
                addAll(
                    shell.exec("ls -1 /sys/class/block/$name/ 2>/dev/null")
                        .lines().map { it.trim() }.filter { it.isNotEmpty() }
                        .map { "/dev/block/$name$it" }
                )
            }
            val unmounted = mutableListOf<String>()
            for (p in candidates) {
                val result = shell.exec("umount $p 2>&1")
                if (result.isBlank() || result.contains("not mounted", ignoreCase = true) ||
                    result.contains("Invalid argument")
                ) {
                    unmounted += p
                }
            }
            return unmounted
        }
    }
}
