package com.usbforge.core.partition

import java.util.UUID

/**
 * GUID (UUID) <-> onaltılık metin dönüşümleri.
 *
 * ## Byte sırası tuzağı
 * GUID'ler "mixed-endian" olarak saklanır: ilk üç alan (Data1, Data2,
 * Data3) **küçük bayt**, kalan iki alan (Data4) **büyük bayt** sırasıyla
 * yazılır. `UUID.toString()` zaten bu sırayı verdiği için pratikte
 * metin -> bayt dönüşümü `UUID.toString()` üzerinden yapılır.
 */
object Guid {

    fun parse(text: String): UUID = UUID.fromString(text.trim())

    /** GUID metnini 16 bayta çevirir (mixed-endian sıralama). */
    fun toBytes(text: String): ByteArray = toBytes(parse(text))

    fun toBytes(uuid: UUID): ByteArray {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val out = ByteArray(16)
        for (i in 0 until 8) out[i] = ((msb ushr (56 - 8 * i)) and 0xFF).toByte()
        for (i in 0 until 8) out[8 + i] = ((lsb ushr (56 - 8 * i)) and 0xFF).toByte()
        return out
    }

    fun fromBytes(buf: ByteArray, offset: Int = 0): UUID {
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (buf[offset + i].toLong() and 0xFF)
        for (i in 0 until 8) lsb = (lsb shl 8) or (buf[offset + 8 + i].toLong() and 0xFF)
        return UUID(msb, lsb)
    }

    fun format(uuid: UUID): String = uuid.toString().uppercase()

    /** Yeni bir rastgele disk GUID'i üretir. */
    fun random(): UUID = UUID.randomUUID()

    /** İyi bilinen bölüntü tipi GUID'leri. */
    object Types {
        /** EFI Sistem Bölümü (FAT32, önyüklenebilir). */
        val EFI_SYSTEM = "C12A7328-F81F-11D2-BA4B-00A0C93EC93B"

        /** Temel Veri (NTFS / exFAT / FAT32/16 veri bölümü). */
        val BASIC_DATA = "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7"

        /** Microsoft Rezerve (MSR — 16 MiB, NTFS için gerekli). */
        val MICROSOFT_RESERVED = "E3C9E316-0B5C-4DB8-817D-F92DF00215AE"

        /** Linux dosya sistemi veri. */
        val LINUX_FILESYSTEM = "0FC63DAF-8483-4772-8E79-3D69D8477DE4"

        /** Linux değişim (swap). */
        val LINUX_SWAP = "0657FD6D-A4AB-43C4-84E5-0933C84B4F4F"

        /** Ventoy EFI bölümü (EFI System türevi + özel ad). */
        val VTOY_EFI = "C12A7328-F81F-11D2-BA4B-00A0C93EC93B"
    }
}
