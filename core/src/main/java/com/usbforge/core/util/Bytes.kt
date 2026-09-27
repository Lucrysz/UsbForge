package com.usbforge.core.util

import java.util.Locale

/**
 * Bayt sayısını insan tarafından okunabilir birime çevirir.
 *
 * Tüm `:core` sınıfları tarafından kullanılır; Android'e bağımlı değildir,
 * böylece JVM testlerinde de doğrulanabilir.
 */
object Bytes {

    private val UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")

    /** `1536` -> `"1.5 KiB"`, `0` -> `"0 B"`. */
    fun human(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < UNITS.size - 1) {
            value /= 1024.0
            unit++
        }
        return if (value >= 100) {
            String.format(Locale.US, "%.0f %s", value, UNITS[unit])
        } else {
            String.format(Locale.US, "%.1f %s", value, UNITS[unit])
        }
    }

    /** Saniyeye çevrilmiş hız: `"12.4 MB/s"`. */
    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "—"
        val mib = bytesPerSecond.toDouble() / (1024.0 * 1024.0)
        return if (mib >= 1.0) {
            String.format(Locale.US, "%.1f MB/s", mib)
        } else {
            String.format(Locale.US, "%.0f KB/s", bytesPerSecond.toDouble() / 1024.0)
        }
    }

    /** Tahmini kalan süre: `"-1"` yerine `"--"` döner. */
    fun duration(seconds: Long): String {
        if (seconds < 0) return "--:--"
        if (seconds > 359_999) return ">99:59:59"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    /** 16 baytlık bayt dizisini `"AA BB CC"` biçimine çevirir. */
    fun hex(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): String =
        buildString {
            for (i in offset until offset + length) {
                if (i > offset) append(' ')
                append(String.format(Locale.US, "%02X", buf[i].toInt() and 0xFF))
            }
        }

    /** Bayt dizisinin ilk 4 baytını ASCII olarak gösterir (yazdırılabilirse). */
    fun ascii(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): String =
        buildString {
            for (i in offset until offset + length) {
                val c = buf[i].toInt() and 0xFF
                append(if (c in 32..126) c.toChar() else '.')
            }
        }
}
