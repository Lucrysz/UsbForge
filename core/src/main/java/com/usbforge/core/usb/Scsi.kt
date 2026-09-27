package com.usbforge.core.usb

/**
 * USB Mass Storage Class — Bulk-Only Transport (BOT) ve SCSI komut seti.
 *
 * Referanslar:
 *  - USB Mass Storage Class Compliance Test Specification 1.2  -> BOT
 *  - SPC-3 / SBC-3 (SCSI Block Commands)
 *
 * Tüm değerler `Int`/`Long` olarak tutulur; Byte tamponları elle doldurulur
 * çünkü Android USB Host API `ByteBuffer` değil `ByteArray` bekler ve
 * yazma yolu boyunca fazladan kopya (allocation) yapmamak performans
 * açısından kritiktir.
 */
object Scsi {

    // ---------------------------------------------------------------- BOT

    /** "USBC" — Command Block Wrapper imzası. */
    const val CBW_SIGNATURE = 0x43425355L

    /** "USBS" — Command Status Wrapper imzası. */
    const val CSW_SIGNATURE = 0x53425355L

    const val CBW_LENGTH = 31
    const val CSW_LENGTH = 13

    /** CBW bayrak bitleri. */
    object CbwFlags {
        /** 1 = veri cihazdan cihaza (IN), 0 = veri ana bilgisayardan cihaza (OUT). */
        const val IN = 0x80
        const val OUT = 0x00
    }

    /** bCSWStatus. */
    object CswStatus {
        const val GOOD = 0x00
        const val FAILED = 0x01
        const val PHASE_ERROR = 0x02
    }

    // ------------------------------------------------------------- opcodes

    const val OP_TEST_UNIT_READY = 0x00
    const val OP_REQUEST_SENSE = 0x03
    const val OP_INQUIRY = 0x12
    const val OP_MODE_SELECT_6 = 0x15
    const val OP_MODE_SENSE_6 = 0x1A
    const val OP_START_STOP_UNIT = 0x1E
    const val OP_PREVENT_ALLOW_MEDIUM_REMOVAL = 0x1E
    const val OP_READ_CAPACITY_10 = 0x25
    const val OP_READ_10 = 0x28
    const val OP_WRITE_10 = 0x2A
    const val OP_SYNCHRONIZE_CACHE_10 = 0x35
    const val OP_READ_FORMAT_CAPACITIES = 0x23
    const val OP_READ_16 = 0x88
    const val OP_WRITE_16 = 0x8A
    const val OP_READ_CAPACITY_16 = 0x9E

    // -------------------------------------------------- sense / hata kodları

    /** Sense Key değerleri (SPC-3 tablo 4-4). */
    object SenseKey {
        const val NO_SENSE = 0x00
        const val RECOVERED_ERROR = 0x01
        const val NOT_READY = 0x02
        const val MEDIUM_ERROR = 0x03
        const val HARDWARE_ERROR = 0x04
        const val ILLEGAL_REQUEST = 0x05
        const val UNIT_ATTENTION = 0x06
        const val DATA_PROTECT = 0x07
    }

    /** Additional Sense Code için yaygın değerler. */
    object Asc {
        const val INVALID_COMMAND = 0x20
        const val LBA_OUT_OF_RANGE = 0x21
        const val INVALID_FIELD_IN_CDB = 0x24
        const val WRITE_PROTECTED = 0x27
        const val BUS_RESET = 0x29
        const val POWER_FAILURE = 0x29
        const val PARAMETER_LIST_LENGTH = 0x1A
        const val LOGICAL_UNIT_NOT_SUPPORTED = 0x25
        const val INCOMPATIBLE_FORMAT = 0x26
        const val MEDIUM_FORMAT_CORRUPTED = 0x31
        const val MEDIUM_NOT_PRESENT = 0x3A
        const val INTERNAL_TARGET_FAILURE = 0x44
        const val COMMAND_ABORTED_BY_HOST = 0x0B
    }

    // ------------------------------------------------------------- CDW

    /**
     * Command Block Wrapper oluşturur.
     *
     * @param tag           benzersiz istek etiketi, CSW eşleştirmesi için
     * @param direction     [CbwFlags.IN] veya [CbwFlags.OUT]
     * @param dataLength    veri aşamasında taşınacak bayt sayısı
     * @param lun           Mantıksal birim numarası (genelde 0)
     * @param cdbLength     CDB uzunluğu (6/10/12/16)
     */
    fun buildCbw(
        tag: Int,
        direction: Int,
        dataLength: Int,
        lun: Int,
        cdbLength: Int,
    ): ByteArray {
        val cbw = ByteArray(CBW_LENGTH)
        putLe32(cbw, 0, CBW_SIGNATURE)
        putLe32(cbw, 4, tag.toLong() and 0xFFFFFFFFL)
        putLe32(cbw, 8, dataLength.toLong() and 0xFFFFFFFFL)
        cbw[12] = direction.toByte()
        cbw[13] = (lun and 0x0F).toByte()
        cbw[14] = cdbLength.toByte()
        return cbw
    }

    // --------------------------------------------- 10 bayt komutlar

    /** READ(10) / WRITE(10) CDB. */
    fun cdw10(opcode: Int, lun: Int, lba: Long, blockCount: Int): ByteArray {
        val cdb = ByteArray(10)
        cdb[0] = opcode.toByte()
        cdb[1] = (lun and 0x1F).toByte()
        putBe32(cdb, 2, lba)
        // 10 baytlık komutta transfer uzunluğu 0 ise "256 blok" anlamına gelir.
        val len = if (blockCount == 0) 256 else (blockCount and 0xFFFF)
        cdb[7] = ((len ushr 8) and 0xFF).toByte()
        cdb[8] = (len and 0xFF).toByte()
        return cdb
    }

    /** READ(16) / WRITE(16) CDB — 2 TiB üstü cihazlar için. */
    fun cdw16(opcode: Int, lun: Int, lba: Long, blockCount: Int): ByteArray {
        val cdb = ByteArray(16)
        cdb[0] = opcode.toByte()
        cdb[1] = (lun and 0x1F).toByte()
        putBe64(cdb, 2, lba)
        putBe32(cdb, 10, blockCount.toLong() and 0xFFFFFFFFL)
        return cdb
    }

    /** INQUIRY (standart sayfa 0x00, 36 bayt). */
    fun cdwInquiry(lun: Int = 0): ByteArray = ByteArray(6).also {
        it[0] = OP_INQUIRY.toByte()
        it[1] = (lun and 0x1F).toByte()
        it[4] = 36
    }

    /**
     * INQUIRY VPD (Vital Product Data) — seri numarası için 0x80 sayfası.
     * @param page VPD sayfa numarası (0x80 = unit serial number, 0x83 = device id)
     */
    fun cdwInquiryVpd(page: Int, allocLength: Int, lun: Int = 0): ByteArray = ByteArray(6).also {
        it[0] = OP_INQUIRY.toByte()
        it[1] = (lun and 0x1F).toByte() or 0x01 // EVPD bit
        it[2] = (page and 0xFF).toByte()
        it[3] = ((page ushr 8) and 0xFF).toByte()
        it[4] = (allocLength and 0xFF).toByte()
    }

    /** TEST UNIT READY. */
    fun cdwTestUnitReady(lun: Int = 0): ByteArray = ByteArray(6).also {
        it[0] = OP_TEST_UNIT_READY.toByte()
        it[1] = (lun and 0x1F).toByte()
    }

    /** READ CAPACITY (10 bayt). */
    fun cdwReadCapacity10(lun: Int = 0): ByteArray = ByteArray(10).also {
        it[0] = OP_READ_CAPACITY_10.toByte()
        it[1] = (lun and 0x1F).toByte()
    }

    /** READ CAPACITY (16 bayt) — 2 TiB üstü. */
    fun cdwReadCapacity16(lun: Int = 0): ByteArray = ByteArray(10).also {
        it[0] = OP_READ_CAPACITY_16.toByte()
        it[1] = (lun and 0x1F).toByte()
        it[5] = 0x10 // SERVICE ACTION = 0x10 (READ CAPACITY)
    }

    /**
     * START STOP UNIT.
     *
     * @param start true -> LOEJ=1 (medyayı çalıştır), false -> LOEJ=0 (durdur)
     */
    fun cdwStartStopUnit(lun: Int = 0, start: Boolean, immediate: Boolean = false): ByteArray =
        ByteArray(6).also {
            it[0] = OP_START_STOP_UNIT.toByte()
            it[1] = (lun and 0x1F).toByte()
            it[4] = (if (start) 0x01 else 0x00) or 0x02 // LOEJ + Immed
        }

    /** SYNCHRONIZE CACHE (10) — yazma önbelleğini diske zorlar. */
    fun cdwSyncCache(lba: Long = 0, blockCount: Int = 0xFFFF): ByteArray = ByteArray(10).also {
        it[0] = OP_SYNCHRONIZE_CACHE_10.toByte()
        putBe32(it, 2, lba)
        val c = blockCount and 0xFFFF
        it[7] = ((c ushr 8) and 0xFF).toByte()
        it[8] = (c and 0xFF).toByte()
    }

    /** MODE SENSE (6) — yazma koruması durumunu okumak için. */
    fun cdwModeSense6(lun: Int = 0, page: Int = 0x3F): ByteArray = ByteArray(6).also {
        it[0] = OP_MODE_SENSE_6.toByte()
        it[1] = (lun and 0x1F).toByte()
        it[2] = page.toByte()
        it[4] = 0xFF // tüm sayfalar
    }

    /** MODE SELECT (6) — yazma korumasını kaldırmak için. */
    fun cdwModeSelect6(parameter: ByteArray, lun: Int = 0): ByteArray = ByteArray(6).also {
        it[0] = OP_MODE_SELECT_6.toByte()
        it[1] = (lun and 0x1F).toByte()
        it[4] = parameter.size.toByte()
    }

    /** REQUEST SENSE (6) — ayrıntılı hata bilgisi. */
    fun cdwRequestSense(lun: Int = 0, allocLength: Int = 0x12): ByteArray = ByteArray(6).also {
        it[0] = OP_REQUEST_SENSE.toByte()
        it[1] = (lun and 0x1F).toByte()
        it[4] = allocLength.toByte()
    }

    // ------------------------------------------------ bayt / endian yardımcıları

    fun putLe32(dst: ByteArray, off: Int, v: Long) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((v ushr 16) and 0xFF).toByte()
        dst[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun putBe32(dst: ByteArray, off: Int, v: Long) {
        dst[off] = ((v ushr 24) and 0xFF).toByte()
        dst[off + 1] = ((v ushr 16) and 0xFF).toByte()
        dst[off + 2] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 3] = (v and 0xFF).toByte()
    }

    fun putBe16(dst: ByteArray, off: Int, v: Int) {
        dst[off] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 1] = (v and 0xFF).toByte()
    }

    fun putLe16(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun putBe64(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) dst[off + i] = ((v ushr (56 - 8 * i)) and 0xFF).toByte()
    }

    fun getLe32(src: ByteArray, off: Int): Long = (src[off].toLong() and 0xFF) or
            ((src[off + 1].toLong() and 0xFF) shl 8) or
            ((src[off + 2].toLong() and 0xFF) shl 16) or
            ((src[off + 3].toLong() and 0xFF) shl 24)

    fun getBe32(src: ByteArray, off: Int): Long = ((src[off].toLong() and 0xFF) shl 24) or
            ((src[off + 1].toLong() and 0xFF) shl 16) or
            ((src[off + 2].toLong() and 0xFF) shl 8) or
            (src[off + 3].toLong() and 0xFF)

    fun getBe16(src: ByteArray, off: Int): Int = ((src[off].toInt() and 0xFF) shl 8) or
            (src[off + 1].toInt() and 0xFF)

    fun getLe16(src: ByteArray, off: Int): Int = (src[off].toInt() and 0xFF) or
            ((src[off + 1].toInt() and 0xFF) shl 8)

    fun getBe64(src: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (src[off + i].toLong() and 0xFF)
        return v
    }

    /** CDW dizisini belirtilen offset'ten [count] bayt kopyalar. */
    fun copyCdb(cbw: ByteArray, cdb: ByteArray, cdbOffset: Int, count: Int) {
        System.arraycopy(cdb, cdbOffset, cbw, 15, count)
    }

    /** CSW yapısını çözümler. */
    fun parseCsw(csw: ByteArray): Csw {
        val sig = getLe32(csw, 0)
        val status = csw[12].toInt() and 0xFF
        return Csw(
            signatureOk = sig == CSW_SIGNATURE,
            status = status,
            residue = getLe32(csw, 8),
            valid = sig == CSW_SIGNATURE && status == CswStatus.GOOD,
        )
    }

    /** REQUEST SENSE bayt dizisini okunabilir metne çevirir. */
    fun describeSense(sense: ByteArray): String {
        if (sense.size < 3) return "boş sense verisi"
        val key = sense[2].toInt() and 0x0F
        val asc = sense[12].toInt() and 0xFF
        val ascq = if (sense.size > 13) sense[13].toInt() and 0xFF else 0
        val keyName = when (key) {
            SenseKey.NO_SENSE -> "NO SENSE"
            SenseKey.RECOVERED_ERROR -> "RECOVERED ERROR"
            SenseKey.NOT_READY -> "NOT READY"
            SenseKey.MEDIUM_ERROR -> "MEDIUM ERROR"
            SenseKey.HARDWARE_ERROR -> "HARDWARE ERROR"
            SenseKey.ILLEGAL_REQUEST -> "ILLEGAL REQUEST"
            SenseKey.UNIT_ATTENTION -> "UNIT ATTENTION"
            SenseKey.DATA_PROTECT -> "DATA PROTECT"
            else -> "key=$key"
        }
        return "$keyName / ASC=0x${hex8(asc)} ASCQ=0x${hex8(ascq)}"
    }

    private fun hex8(v: Int): String = v.toString(16).padStart(2, '0').uppercase()

    data class Csw(
        val signatureOk: Boolean,
        val status: Int,
        val residue: Long,
        val valid: Boolean,
    )
}
