package com.usbforge.core.partition

/**
 * GPT başlıkları ve bölüntü giriş dizisi için CRC-32 (IEEE 802.3, ters
 * çevrilmiş polinom 0xEDB88320).
 *
 * UEFI şartnamesi, hem başlığın hem de 128 girişlik dizinin CRC değerini
 * doğruladığı için bu uygulama `java.util.zip.CRC32`'den bağımsız çalışır:
 * `CRC32.update(ByteBuffer)` Android'un bazı API seviyelerinde bellek
 * kopyası yapar ve milyonlarça bayt için gereksiz maliyet getirir.
 */
object Crc32 {

    private val TABLE = IntArray(256) { i ->
        var c = i
        repeat(8) {
            c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
        }
        c
    }

    /** CRC hesabını [state] üzerinde devam ettirir. Başlangıç: `Crc32.init()`. */
    fun update(state: Int, data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var c = state
        val end = offset + length
        for (i in offset until end) {
            c = TABLE[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        return c
    }

    /** Yeni bir hesap başlatır. */
    fun init(): Int = -1

    /** Sonlandırır ve nihai (ters çevrilmiş) CRC değerini döndürür. */
    fun finalize(state: Int): Int = state.inv()

    /** Tek seferde hesaplar. */
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size): Int =
        finalize(update(init(), data, offset, length))
}
