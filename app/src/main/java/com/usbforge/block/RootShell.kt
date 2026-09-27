package com.usbforge.block

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kalıcı bir `su` kabuğu üzerinden komut çalıştırır ve ikili veri akışı
 * sağlar.
 *
 * Android'de root erişimi olan uygulamaların kullandığı standart desen
 * budur: `ProcessBuilder("su")` ile bir kabuk açılır, komutlar stdin'den
 * yazılır, çıktı stdout'tan okunur. Kabuk açık kaldığı için her yazma
 * işleminde yeniden süreç başlatma maliyeti (hundreds of ms) ödenmez.
 *
 * ## Neden `head -c` ile sınırlandırıyoruz
 * Ham ikili veri stdin'e yazıldığında kabuktan EOF üretmek imkânsızdır
 * (süreç kapanmadan stdin kapatılamaz). `head -c N /proc/self/fd/0`
 * yazma işlemini tam olarak N bayta sınırlar ve kendisi kapanır; ardından
 * `dd` işini bitirir. Böylece belirsizlik yoktur.
 *
 * ## Eşzamanlılık
 * Tüm metotlar [lock] ile serileştirilir; kabuk üzerinde aynı anda yalnızca
 * bir komut çalışır.
 */
class RootShell private constructor(
    private val process: Process,
) {

    private val lock = Any()
    private val stdout: InputStream = BufferedInputStream(process.inputStream, BUFFER_SIZE)
    private val stdin: OutputStream = process.outputStream
    private val markerCounter = AtomicInteger(0)

    @Volatile
    private var closed = false

    /** Kabuk gerçekten root mu? */
    val isRoot: Boolean by lazy {
        runCatching { exec("id -u").trim().endsWith("0") }.getOrDefault(false)
    }

    /** Kabuk üzerinde tek satırlık komut çalıştırır, stdout içeriğini döndürür. */
    fun exec(command: String): String = synchronized(lock) {
        sendCommand(command)
        val marker = nextMarker()
        sendCommand("echo $marker")
        String(readUntil(marker.toByteArray(StandardCharsetsAscii), TIMEOUT_CONTROL), StandardCharsetsAscii)
            .trimEnd('\n', '\r')
    }

    /** Sayfa önbelleğini diske yazar. */
    fun sync() {
        exec("sync")
    }

    /**
     * [path] bloğuna, [startLba] sektöründen başlayarak [length] bayt yazar.
     *
     * @param onChunk her parça yazıldığında bildirilir
     */
    fun writeToDevice(
        path: String,
        startLba: Long,
        data: ByteArray,
        offset: Int,
        length: Int,
        isCancelled: () -> Boolean,
        onChunk: ((bytes: Long) -> Unit)?,
    ) = synchronized(lock) {
        val marker = nextMarker()
        // `head -c` veri akışını N bayta sınırlar; `skip=` ise blok cihazda
        // lseek ile konumlanır (bayt bayt okumaz, bu yüzden hızlıdır).
        val cmd = "head -c $length /proc/self/fd/0 | dd of=$path bs=512 seek=$startLba" +
                " conv=fsync 2>/dev/null; echo $marker"
        sendCommand(cmd)
        // Şimdi ikili veriyi yaz: kabuk `head` komutunu çalıştırırken stdin'i
        // tüketir.
        var written = 0
        while (written < length) {
            if (isCancelled()) throw BlockWriteCancelled()
            val piece = minOf(BUFFER_SIZE, length - written)
            stdin.write(data, offset + written, piece)
            written += piece
            onChunk?.invoke(piece.toLong())
        }
        stdin.flush()
        val trailing = readUntil(marker.toByteArray(StandardCharsetsAscii), TIMEOUT_IO)
        val err = String(trailing, StandardCharsetsAscii).trim()
        if (err.isNotEmpty()) {
            throw BlockAccessException("dd çıktısı: $err")
        }
    }

    /** [path] bloğundan [startLba] sektöründen başlayarak [length] bayt okur. */
    fun readFromDevice(path: String, startLba: Long, length: Int): ByteArray = synchronized(lock) {
        val marker = nextMarker()
        val sectors = length / 512
        val cmd = "dd if=$path bs=512 skip=$startLba count=$sectors iflag=fullblock 2>/dev/null; echo $marker"
        sendCommand(cmd)
        val buf = ByteArray(length)
        readFully(buf, length, TIMEOUT_IO)
        readUntil(marker.toByteArray(StandardCharsetsAscii), TIMEOUT_CONTROL)
        buf
    }

    // -------------------------------------------------------------- iç detaylar

    private fun sendCommand(command: String) {
        check(!closed) { "Root kabuğu kapatılmış." }
        stdin.write(command.toByteArray(StandardCharsetsAscii))
        stdin.write('\n'.code)
        stdin.flush()
    }

    private fun nextMarker(): String = "__UF${markerCounter.incrementAndGet()}__"

    private fun readUntil(marker: ByteArray, timeoutMs: Int): ByteArray {
        val out = ByteArrayOutputStream(256)
        var matched = 0
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            if (System.nanoTime() > deadline) {
                throw BlockAccessException(
                    "Root komutu zaman aşımına uğradı ($timeoutMs ms): " +
                            String(out.toByteArray(), StandardCharsetsAscii)
                )
            }
            val b = stdout.read()
            if (b < 0) {
                throw BlockAccessException(
                    "Root kabuğu kapandı. Çıktı: " +
                            String(out.toByteArray(), StandardCharsetsAscii)
                )
            }
            if (b.toByte() == marker[matched]) {
                matched++
                if (matched == marker.size) return out.toByteArray()
            } else {
                if (matched > 0) {
                    out.write(marker, 0, matched)
                    matched = 0
                    // Kısmi eşleşmeden sonra bu bayt yeni eşleşme başlangıcı
                    // olabilir; tek bayt olduğu için doğrudan yazmak yeterlidir.
                    if (b.toByte() == marker[0]) matched = 1
                }
                out.write(b)
            }
        }
    }

    private fun readFully(dst: ByteArray, length: Int, timeoutMs: Int) {
        var got = 0
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (got < length) {
            if (System.nanoTime() > deadline) {
                throw BlockAccessException("Root okuma zaman aşımı ($got/$length bayt).")
            }
            val n = stdout.read(dst, got, minOf(BUFFER_SIZE, length - got))
            if (n < 0) throw BlockAccessException("Root kabuğu okuma sırasında kapandı.")
            got += n
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { sendCommand("exit") }
        runCatching { stdin.close() }
        runCatching { process.destroy() }
    }

    private companion object {
        val StandardCharsetsAscii = java.nio.charset.StandardCharsets.US_ASCII
        const val BUFFER_SIZE = 256 * 1024
        const val DD_BLOCK = 1024 * 1024
        const val TIMEOUT_CONTROL = 10_000
        const val TIMEOUT_IO = 60_000
    }
}

/**
 * [RootShell] örneğini yönetir. `su` bulunamazsa veya root reddedilirse
 * `null` döner; çağıran taraf root'suz modu kullanmalıdır.
 */
object RootShellProvider {

    @Volatile
    private var shell: RootShell? = null

    @Volatile
    private var unavailable = false

    /** Root kabuğunu açar veya `null` döner. */
    @Synchronized
    fun get(): RootShell? {
        shell?.let { return if (it.isRoot) it else null.also { release() } }
        if (unavailable) return null

        val created = runCatching {
            val p = ProcessBuilder("su")
                .redirectErrorStream(false)
                .start()
            RootShell(p)
        }.getOrNull() ?: run {
            unavailable = true
            return null
        }

        // Bazı `su` uygulamaları ilk çıktıda banner basar; kabuğu bir komutla
        // "uyandırıp" marker mekanizmasını hazırlıyoruz.
        val ok = runCatching { created.exec("echo ready") }.getOrNull()?.contains("ready") == true
        if (!ok || !created.isRoot) {
            runCatching { created.close() }
            unavailable = true
            return null
        }
        shell = created
        return created
    }

    /** Root yetkisi var mı? */
    val isRooted: Boolean get() = get() != null

    @Synchronized
    private fun release() {
        shell?.close()
        shell = null
    }
}

/** Kolaylık erişimi: root kabuğu ya da hata. */
fun requireRootShell(): RootShell =
    RootShellProvider.get() ?: throw BlockAccessException(
        "Root erişimi yok. Bu işlem için cihazın root'lu olması ve `su` üzerinden izin verilmesi gerekir."
    )

/** [RootBlockDevice] için root kontrolü yapan kısa yardımcı. */
val RootBlockDevice.isRootAccessible: Boolean get() = RootShellProvider.isRooted
