package com.usbforge.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.Closeable
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * USB Mass Storage Class cihazı üzerinde ham blok erişimi sağlar.
 *
 * ## Neden hazır bir kütüphane yok
 * Android USB Host API yalnızca uç nokta (endpoint) düzeyinde ham aktarım
 * sunar; Mass Storage için üst seviye bir kütüphane sağlamaz. Bu sınıf
 * USB 2.0 Bulk-Only Transport (BOT) protokolünü ve gereken SCSI komutlarını
 * (SBC-3) baştan uygular.
 *
 * ## Thread güvenliği
 * Bir [UsbDeviceConnection] üzerinde aynı anda yalnızca BİR aktarım
 * olabilir. Tüm komut gönderimleri [commandLock] ile serileştirilir. Uzun
 * süren yazma işleri [write] gibi metotlarda `Dispatchers.IO` üzerinden
 * çağrılır (bkz. `BlockAccess` sözleşmesi).
 *
 * ## Gerçek donanım notu
 * Bazı flash denetleyicileri WRITE(10) komutunu `DATA PROTECT / ASC 0x27`
 * ile reddeder; bunun sebebi üreticiye özgü "kilidi açma" komutudur.
 * [prepareForWrite] tüm *standart* SCSI adımlarını yürütür. Üreticiye özgü
 * vendor opcode'ler kasıtlı olarak gönderilmez: yanlış bir komut denetleyiciyi
 * kalıcı olarak kilitleyebilir (brick). Bu durumda [WriteProtectedException]
 * fırlatılır ve arayüz kullanıcıya anlaşılır bir mesaj gösterir.
 */
class UsbMassStorageController private constructor(
    val usbDevice: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
    private val lun: Int,
) : Closeable {

    private val commandLock = Any()

    @Volatile
    private var tagCounter: Int = 1

    @Volatile
    var isClosed: Boolean = false
        private set

    lateinit var inquiry: Inquiry
        private set

    lateinit var capacity: DiskCapacity
        private set

    data class Inquiry(
        val vendor: String,
        val product: String,
        val revision: String,
        val serial: String,
        val peripheralType: Int,
    )

    // ------------------------------------------------------------ cihaz bilgisi

    val blockSize: Int get() = capacity.sectorSize
    val totalBlocks: Long get() = capacity.totalSectors
    val sizeBytes: Long get() = capacity.sizeBytes

    /** READ(16)/WRITE(16) gerektiren (> 2 TiB) cihaz mı? */
    private val use16ByteCdb: Boolean get() = capacity.totalSectors > LBA_10BIT_LIMIT

    // ------------------------------------------------------------- açma / kapama

    /**
     * Cihazı açar: sistem izni ister, Mass Storage arayüzünü ve bulk uç
     * noktalarını bulur, arayüzü claim eder, ardından [probe] ile cihazı tanır.
     *
     * @throws UsbUnsupportedException cihaz Mass Storage sınıfında değilse
     * @throws UsbAccessException     izin verilmezse veya arayüz kilitliyse
     */
    suspend fun probe() {
        inquiry = readInquiry()
        capacity = readCapacity()
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching {
            runCommand(Scsi.cdwSyncCache(), 0, Scsi.CbwFlags.OUT, null, TIMEOUT_CONTROL)
            runCommand(Scsi.cdwStartStopUnit(lun, start = false), 0, Scsi.CbwFlags.OUT, null, TIMEOUT_CONTROL)
        }
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }

    // ------------------------------------------------------ düşük seviye BOT

    /**
     * Tek bir SCSI komutunu yürütür: CBW → veri aşaması → CSW.
     *
     * @param cdb        komut tanım bloğu
     * @param dataInSize veri-IN aşamasında beklenecek bayt sayısı (0 = veri aşaması yok)
     * @param direction  [Scsi.CbwFlags.IN] veya [Scsi.CbwFlags.OUT]
     * @param dataOut    veri-OUT tamponu (null = veri aşaması yok)
     * @return veri-IN aşamasında okunan tampon, yoksa null
     */
    @Throws(UsbTransferException::class)
    private fun runCommand(
        cdb: ByteArray,
        dataInSize: Int,
        direction: Int,
        dataOut: ByteArray?,
        timeoutMs: Int,
    ): ByteArray? = synchronized(commandLock) {

        check(!isClosed) { "Kontrolcü kapatılmış." }

        val tag = tagCounter++
        val cbw = Scsi.buildCbw(
            tag = tag,
            direction = direction,
            dataLength = if (dataOut != null) dataOut.size else dataInSize,
            lun = lun,
            cdbLength = cdb.size,
        )
        Scsi.copyCdb(cbw, cdb, 0, cdb.size)

        // 1) Command Block Wrapper (her zaman OUT)
        val cbwSent = bulkOut(cbw, 0, cbw.size, TIMEOUT_CONTROL)
        if (cbwSent != cbw.size) {
            throw UsbTransferException("CBW gönderilemedi (gönderilen=$cbwSent/${cbw.size}).")
        }

        // 2) Veri aşaması
        var inBuffer: ByteArray? = null
        if (dataOut != null) {
            val sent = bulkOut(dataOut, 0, dataOut.size, timeoutMs)
            if (sent != dataOut.size) {
                drainCsw()
                throw UsbTransferException(
                    "Veri gönderilemedi ($sent/${dataOut.size} bayt). " +
                            "Cihaz kapasitesi dolu olabilir, kablo gevşek olabilir veya " +
                            "cihaz firmware'i yazmayı sessizce reddediyor olabilir."
                )
            }
        } else if (dataInSize > 0) {
            val buf = ByteArray(dataInSize)
            val read = bulkIn(buf, 0, dataInSize, timeoutMs)
            if (read != dataInSize) {
                drainCsw()
                throw UsbTransferException("Veri okunamadı ($read/$dataInSize bayt).")
            }
            inBuffer = buf
        }

        // 3) Command Status Wrapper
        val csw = readCsw()
        if (!csw.signatureOk) {
            throw UsbTransferException("Geçersiz CSW imzası — cihaz BOT durumunda değil.")
        }
        if (csw.status != Scsi.CswStatus.GOOD) {
            val sense = runCatching {
                runCommand(Scsi.cdwRequestSense(), SENSE_LENGTH, Scsi.CbwFlags.IN, null, TIMEOUT_CONTROL)
            }.getOrNull()
            val detail = if (sense != null) Scsi.describeSense(sense) else "sense alınamadı"
            Log.w(TAG, "SCSI hata: status=${csw.status} $detail cdb=${cdb.toHexString()}")
            throw UsbTransferException("SCSI komutu başarısız (status=${csw.status}): $detail")
        }

        inBuffer
    }

    private fun readCsw(): Scsi.Csw {
        val buf = ByteArray(Scsi.CSW_LENGTH)
        val n = bulkIn(buf, 0, buf.size, TIMEOUT_CONTROL)
        if (n < Scsi.CSW_LENGTH) {
            throw UsbTransferException("CSW okunamadı ($n bayt).")
        }
        return Scsi.parseCsw(buf)
    }

    /** Hata halinde kalan CSW'yi temizlemek için "best effort" okuma. */
    private fun drainCsw() {
        runCatching {
            val buf = ByteArray(Scsi.CSW_LENGTH)
            bulkIn(buf, 0, buf.size, TIMEOUT_CONTROL)
        }
    }

    private fun bulkOut(buf: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        var sent = 0
        while (sent < length) {
            val piece = min(length - sent, TRANSFER_PIECE)
            val n = connection.bulkTransfer(endpointOut, buf, offset + sent, piece, timeoutMs)
            if (n <= 0) return sent + maxOf(n, -1)
            sent += n
        }
        return sent
    }

    private fun bulkIn(buf: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        var got = 0
        while (got < length) {
            val piece = min(length - got, TRANSFER_PIECE)
            val n = connection.bulkTransfer(endpointIn, buf, offset + got, piece, timeoutMs)
            if (n <= 0) return got + maxOf(n, -1)
            got += n
        }
        return got
    }

    // ------------------------------------------------------------ SCSI komutları

    private fun readInquiry(): Inquiry {
        val standard = runCommand(Scsi.cdwInquiry(), 36, Scsi.CbwFlags.IN, null, TIMEOUT_CONTROL)
            ?: throw UsbTransferException("INQUIRY boş veri döndürdü.")

        // Seri numarası VPD sayfa 0x80'de isteğe bağlıdır; sürücü vermeseydi
        // işlem başarısız sayılmamalıdır.
        val serial = runCatching { readSerialFromVpd() }.getOrDefault("")

        return Inquiry(
            vendor = asciiTrim(standard, 8, 8),
            product = asciiTrim(standard, 16, 16),
            revision = asciiTrim(standard, 32, 4),
            serial = serial,
            peripheralType = standard[0].toInt() and 0x1F,
        )
    }

    private fun readSerialFromVpd(): String {
        val buf = ByteArray(VPD_SERIAL_MAX)
        val cdb = Scsi.cdwInquiryVpd(page = 0x80, allocLength = buf.size)
        val data = runCommand(cdb, buf.size, Scsi.CbwFlags.IN, null, TIMEOUT_CONTROL) ?: return ""
        // Yanıt: byte0=0x80, byte1=device type, byte2=page length (LE16)
        if (data.size < 4 || (data[0].toInt() and 0xFF) != 0x80) return ""
        val len = Scsi.getLe16(data, 2)
        if (len <= 0) return ""
        val usable = min(len, data.size - 4)
        return asciiTrim(data, 4, usable)
    }

    /**
     * READ CAPACITY (10) dener; sınırda veya geçersiz yanıtta (16).
     * Sonuç her durumda 512 baytlık sektör tabanına normalize edilir.
     */
    private fun readCapacity(): DiskCapacity {
        val raw10 = runCommand(Scsi.cdwReadCapacity10(), 8, Scsi.CbwFlags.IN, null, TIMEOUT_CONTROL)
            ?: throw UsbTransferException("READ CAPACITY boş veri döndürdü.")

        var lastLba = Scsi.getBe32(raw10, 0)
        var block = Scsi.getBe32(raw10, 4).toInt()

        if (lastLba == 0xFFFFFFFFL || lastLba == 0L) {
            val raw16 = runCommand(Scsi.cdwReadCapacity16(), 32, Scsi.CbwFlags.IN, null, TIMEOUT_CONTROL)
                ?: throw UsbTransferException("READ CAPACITY(16) boş veri döndürdü.")
            lastLba = Scsi.getBe64(raw16, 0)
            if (block <= 0) block = Scsi.getBe32(raw16, 8).toInt()
        }

        if (lastLba <= 0L) {
            throw UsbTransferException(
                "Cihaz geçerli bir kapasite bildirmedi (READ CAPACITY: lastLBA=$lastLba)."
            )
        }

        // 512/1024/2048/4096 dışındaki (uydurma) değerler 512'ye sabitlenir.
        val normalized = when (block) {
            512, 1024, 2048, 4096 -> SECTOR
            else -> SECTOR
        }

        val totalSectors = lastLba + 1
        if (totalSectors > MAX_SECTORS) {
            throw UsbTransferException("Anlamsız kapasite bildirimi: $totalSectors sektör.")
        }
        Log.i(
            TAG,
            "Kapasite: $totalSectors × ${block}B (normalize $normalized) = " +
                    "${totalSectors * 512 / (1024 * 1024)} MiB"
        )
        return DiskCapacity(normalized, totalSectors)
    }

    /**
     * Cihazı yazmaya hazırlar.
     *
     * Sıra önemlidir: önbellek senkronize edilir → medya durdurulur →
     * MODE SENSE ile yazma koruması okunur → koruma varsa MODE SELECT ile
     * kaldırılır → medya başlatılır → TEST UNIT READY ile doğrulanır.
     */
    @Throws(UsbTransferException::class, WriteProtectedException::class)
    fun prepareForWrite() {
        runCommand(Scsi.cdwSyncCache(), 0, Scsi.CbwFlags.OUT, null, TIMEOUT_CONTROL)

        val sense = runCatching {
            runCommand(Scsi.cdwModeSense6(), MODE_SENSE_HEADER_LEN, Scsi.CbwFlags.IN, null, TIMEOUT_CONTROL)
        }.getOrNull()

        val writeProtected = sense != null &&
                sense.size >= MODE_SENSE_HEADER_LEN &&
                (sense[2].toInt() and 0x80) != 0

        if (writeProtected) {
            // MODE SELECT (6) parametre bloğu: uzunluk=3, medium=0, device-specific=0
            val parameter = byteArrayOf(3, 0, 0, 0)
            try {
                runCommand(Scsi.cdwModeSelect6(parameter), 0, Scsi.CbwFlags.OUT, parameter, TIMEOUT_CONTROL)
                Log.i(TAG, "Yazma koruması (WP) MODE SELECT ile kaldırıldı.")
            } catch (t: UsbTransferException) {
                throw WriteProtectedException(
                    "Cihaz yazma korumalı (WP) ve koruma kaldırılamadı: ${t.message}",
                    t,
                )
            }
        }

        runCommand(Scsi.cdwStartStopUnit(lun, start = true), 0, Scsi.CbwFlags.OUT, null, TIMEOUT_CONTROL)
        runCommand(Scsi.cdwSyncCache(), 0, Scsi.CbwFlags.OUT, null, TIMEOUT_CONTROL)

        val ready = runCatching {
            runCommand(Scsi.cdwTestUnitReady(), 0, Scsi.CbwFlags.OUT, null, TIMEOUT_CONTROL)
            true
        }.getOrDefault(false)

        if (!ready) {
            val detail = runCatching {
                val s = runCommand(Scsi.cdwRequestSense(), SENSE_LENGTH, Scsi.CbwFlags.IN, null, TIMEOUT_CONTROL)
                if (s != null) Scsi.describeSense(s) else "sense alınamadı"
            }.getOrDefault("bilinmiyor")
            throw UsbTransferException("Cihaz yazmaya hazır değil (TEST UNIT READY): $detail")
        }
    }

    /** Yazma önbelleğini fiziksel medyaya zorlar. Her iş sonunda çağrılmalı. */
    fun flush() {
        runCommand(Scsi.cdwSyncCache(), 0, Scsi.CbwFlags.OUT, null, TIMEOUT_CONTROL)
    }

    /** LBA aralığının cihaz sınırları içinde olduğunu doğrular. */
    fun requireInRange(startLba: Long, sectorCount: Int) {
        require(startLba >= 0) { "Geçersiz LBA: $startLba" }
        require(sectorCount > 0) { "Geçersiz sektör sayısı: $sectorCount" }
        if (startLba + sectorCount > totalBlocks) {
            throw UsbTransferException(
                "LBA aralığı disk dışında: ${startLba}+$sectorCount > $totalBlocks " +
                        "(${capacity.sizeBytes / (1024L * 1024L)} MiB cihaz)"
            )
        }
    }

    // ------------------------------------------------------------- blok okuma

    /**
     * [startLba] konumundan [sectorCount] sektörü [dst] tamponuna okur.
     * `dst` içinde yeterli yer olmalıdır (`dstOffset` hariç `sectorCount * 512`).
     * `Dispatchers.IO` üzerinden çağrılmalıdır.
     */
    @Throws(UsbTransferException::class)
    fun read(startLba: Long, sectorCount: Int, dst: ByteArray, dstOffset: Int = 0) {
        requireInRange(startLba, sectorCount)
        val need = sectorCount * SECTOR
        require(dstOffset + need <= dst.size) {
            "Hedef tampon yetersiz: ${dst.size - dstOffset} < $need"
        }

        val chunkSectors = min(READ_CHUNK_SECTORS, MAX_16_SECTORS)
        var done = 0
        while (done < sectorCount) {
            val n = min(chunkSectors, sectorCount - done)
            val len = n * SECTOR
            val cdb = if (use16ByteCdb) {
                Scsi.cdw16(Scsi.OP_READ_16, lun, startLba + done, n)
            } else {
                Scsi.cdw10(Scsi.OP_READ_10, lun, startLba + done, n)
            }
            val buf = runCommand(cdb, len, Scsi.CbwFlags.IN, null, TIMEOUT_IO)
                ?: throw UsbTransferException("READ(10) veri döndürmedi.")
            System.arraycopy(buf, 0, dst, dstOffset + done * SECTOR, len)
            done += n
        }
    }

    // ------------------------------------------------------------ blok yazma

    /**
     * [startLba] konumuna [src] tamponundaki [length] baytı yazar.
     *
     * @param isCancelled her komut öncesinde kontrol edilir
     * @param onChunk     her komut tamamlandığında yazılan bayt sayısı
     * @throws ScsiTransferCancelled kullanıcı işlemi durdurursa
     */
    @Throws(UsbTransferException::class)
    fun write(
        startLba: Long,
        src: ByteArray,
        srcOffset: Int = 0,
        length: Int = src.size - srcOffset,
        isCancelled: () -> Boolean = { false },
        onChunk: ((bytes: Long) -> Unit)? = null,
    ) {
        require(length % SECTOR == 0) { "Yazma uzunluğu 512'nin katı olmalı, $length verildi." }
        val sectorCount = length / SECTOR
        requireInRange(startLba, sectorCount)

        val chunkSectors = min(WRITE_CHUNK_SECTORS, MAX_16_SECTORS)
        var done = 0
        while (done < sectorCount) {
            if (isCancelled()) throw ScsiTransferCancelled("Yazma kullanıcı tarafından durduruldu.")
            val n = min(chunkSectors, sectorCount - done)
            val offset = srcOffset + done * SECTOR
            val len = n * SECTOR

            val cdb = if (use16ByteCdb) {
                Scsi.cdw16(Scsi.OP_WRITE_16, lun, startLba + done, n)
            } else {
                Scsi.cdw10(Scsi.OP_WRITE_10, lun, startLba + done, n)
            }
            val payload = if (offset == 0 && len == src.size) src else src.copyOfRange(offset, offset + len)

            try {
                runCommand(cdb, 0, Scsi.CbwFlags.OUT, payload, TIMEOUT_IO)
            } catch (t: UsbTransferException) {
                val msg = t.message.orEmpty()
                if (msg.contains("0x27") || msg.contains("DATA PROTECT")) {
                    throw WriteProtectedException(
                        "Cihaz yazmayı reddetti (write-protected). Bu denetleyici firmware'i " +
                                "işletim sistemi dışından ham yazmaya izin vermiyor.",
                        t,
                    )
                }
                throw t
            }
            done += n
            onChunk?.invoke(len.toLong())
        }
    }

    /**
     * [startLba]'dan itibaren [sectors] sektörü sıfırlar veya rastgele
     * veriyle doldurur. Flash belleklerde tek rastgele geçiş yeterlidir
     * (aşınma dengeleme ve eski blok işaretleme amacıyla).
     */
    fun wipe(
        startLba: Long,
        sectors: Long,
        random: Boolean = true,
        seed: Long = 0x5DEECE66DL,
        onChunk: ((bytes: Long) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ) {
        val buf = ByteArray(SECTOR * WIPE_CHUNK_SECTORS)
        val rnd = java.util.Random(seed)
        if (random) {
            rnd.nextBytes(buf)
        } else {
            java.util.Arrays.fill(buf, 0.toByte())
        }

        var lba = startLba
        var remaining = sectors
        while (remaining > 0) {
            if (isCancelled()) throw ScsiTransferCancelled("Silme kullanıcı tarafından durduruldu.")
            val n = min(remaining, WIPE_CHUNK_SECTORS.toLong()).toInt()
            val len = n * SECTOR
            if (random && n < WIPE_CHUNK_SECTORS) {
                // Son kısmi geçişte kalıbın tekrar etmemesi için tazele.
                java.util.Arrays.fill(buf, 0, len, 0)
                rnd.nextBytes(buf, 0, len)
            }
            write(lba, buf, 0, len, isCancelled) { onChunk?.invoke(len.toLong()) }
            lba += n
            remaining -= n
        }
    }

    // ------------------------------------------------------------- yardımcılar

    private fun asciiTrim(buf: ByteArray, off: Int, len: Int): String {
        if (off >= buf.size || len <= 0) return ""
        val end = min(off + len, buf.size)
        val raw = String(buf, off, end - off, StandardCharsets.US_ASCII)
        return raw.trim { it <= ' ' }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    companion object {
        private const val TAG = "UsbMSC"

        const val LUN0 = 0

        /** Tüm LBA hesapları bu sabite göre yapılır. */
        const val SECTOR = 512

        /** Yazma komut başına sektör sayısı (1 MiB). */
        const val WRITE_CHUNK_SECTORS = 2048

        /** Okuma komut başına sektör sayısı (1 MiB). */
        const val READ_CHUNK_SECTORS = 2048

        /** Tek `bulkTransfer` çağrısında taşınan azami bayt. */
        const val TRANSFER_PIECE = 256 * 1024

        /** Silme tamponundaki sektör sayısı (1 MiB). */
        const val WIPE_CHUNK_SECTORS = 2048

        /** READ/WRITE(16) komut başına sektör sınırı. */
        const val MAX_16_SECTORS = 65535

        /** 10 baytlık komutlarda adreslenebilecek azami LBA + 1. */
        const val LBA_10BIT_LIMIT = 0x0FFFFFFF1L

        const val TIMEOUT_CONTROL = 5_000
        const val TIMEOUT_IO = 30_000

        /** REQUEST SENSE yanıt uzunluğu. */
        const val SENSE_LENGTH = 0x12

        /** MODE SENSE(6) başlık uzunluğu (sayfa verisi istenmez). */
        const val MODE_SENSE_HEADER_LEN = 4

        /** INQUIRY VPD seri numarası sayfası için azami istek boyutu. */
        const val VPD_SERIAL_MAX = 64

        /** 128 PiB üzeri kapasiteler reddedilir (bozuk cihaz yanıtlarına karşı). */
        const val MAX_SECTORS = 0x1_0000_0000_0000L

        // -------------------------------------------------- statik yardımcılar

        /**
         * Sistemdeki tüm Mass Storage cihazlarını listeler.
         * Sadece bulk IN **ve** bulk OUT ucu olan arayüzler kabul edilir.
         */
        fun enumerate(context: Context): List<UsbDevice> {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return emptyList()
            return manager.deviceList.values
                .filter { findMassStorageInterface(it) != null }
                .sortedBy { it.deviceName }
        }

        /** Verilen cihazın Mass Storage arayüzünü bulur; yoksa null. */
        fun findMassStorageInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val candidate = device.getInterface(i)
                if (hasBulkInAndOut(candidate)) return candidate
            }
            return null
        }

        private fun hasBulkInAndOut(iface: UsbInterface): Boolean {
            var inFound = false
            var outFound = false
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inFound = true else outFound = true
            }
            return inFound && outFound
        }

        private fun resolveEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (ep.direction) {
                    UsbConstants.USB_DIR_IN ->
                        if (epIn == null || ep.maxPacketSize > epIn.maxPacketSize) epIn = ep
                    else ->
                        if (epOut == null || ep.maxPacketSize > epOut.maxPacketSize) epOut = ep
                }
            }
            return if (epIn != null && epOut != null) epIn to epOut else null
        }

        /** Cihazı açar ve tanır. Detaylı hata durumları için [UsbException] türevleri fırlatır. */
        suspend fun open(context: Context, device: UsbDevice): UsbMassStorageController {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: throw UsbUnsupportedException(
                    "Bu cihazda USB Host desteği yok (UsbManager bulunamadı). OTG/OTD özellikli telefon gerekir."
                )

            if (!UsbPermissionBus.request(manager, device)) {
                throw UsbAccessException("USB erişim izni verilmedi.")
            }

            val iface = findMassStorageInterface(device)
                ?: throw UsbUnsupportedException(
                    "Bu USB cihazı Mass Storage sınıfında değil (bulk IN/OUT uç noktası yok)."
                )

            val conn = manager.openDevice(device)
                ?: throw UsbAccessException(
                    "Cihaza bağlanılamadı; başka bir uygulama cihazı kullanıyor olabilir."
                )

            val endpoints = resolveEndpoints(iface)
            if (endpoints == null) {
                conn.close()
                throw UsbUnsupportedException("Bulk uç noktaları çözümlenemedi.")
            }

            val claimed = runCatching { conn.claimInterface(iface, /* force = */ true) }
                .getOrElse { error ->
                    conn.close()
                    throw UsbAccessException(
                        "USB arayüzü claim edilemedi: cihaz başka bir uygulama tarafından kullanılıyor.",
                        error,
                    )
                }
            if (!claimed) {
                conn.close()
                throw UsbAccessException("USB arayüzü claim edilemedi.")
            }

            val controller = UsbMassStorageController(device, conn, iface, endpoints.first, endpoints.second, LUN0)
            try {
                controller.probe()
            } catch (t: Throwable) {
                controller.close()
                throw t
            }
            return controller
        }
    }
}

// --------------------------------------------------------------- hata tipleri

open class UsbException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UsbUnsupportedException(message: String) : UsbException(message)

class UsbAccessException(message: String, cause: Throwable? = null) : UsbException(message, cause)

class UsbTransferException(message: String, cause: Throwable? = null) : UsbException(message, cause)

/** Cihaz firmware'i yazmayı reddediyor (denetleyici kilidi). */
class WriteProtectedException(message: String, cause: Throwable? = null) : UsbException(message, cause)

class ScsiTransferCancelled(message: String) : UsbException(message)
