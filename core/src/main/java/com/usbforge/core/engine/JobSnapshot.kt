package com.usbforge.core.engine

import com.usbforge.core.usb.LogLevel
import com.usbforge.core.util.Bytes

/** Bir işin durumu. */
enum class JobStatus { IDLE, RUNNING, SUCCESS, FAILED, CANCELLED }

/**
 * Arayüze yayınlanan tek anlık iş anlık görüntüsü.
 *
 * Saf veri sınıfıdır; Android'e bağımlılığı yoktur. Bu sayede UI testleri
 * (Robolectric) ve masaüstü araçları aynı anlık görüntüyü üretebilir.
 */
data class JobSnapshot(
    val id: Long = 0L,
    val title: String = "",
    val phase: String = "",
    val writtenBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val elapsedMs: Long = 0L,
    val status: JobStatus = JobStatus.IDLE,
    val error: String? = null,
    val log: List<LogLine> = emptyList(),
) {
    val isActive: Boolean get() = status == JobStatus.RUNNING

    val isTerminal: Boolean
        get() = status == JobStatus.SUCCESS ||
            status == JobStatus.FAILED ||
            status == JobStatus.CANCELLED

    /** 0..1 arası ilerleme. [totalBytes] bilinmiyorsa 0. */
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f
        else (writtenBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()

    /** Yüzde metni; toplam bilinmiyorsa `"—"`. */
    val percentText: String
        get() = if (totalBytes <= 0L) "—" else "%3.1f%%".format(fraction * 100f)

    /** Aktarılan miktar metni. */
    val transferredText: String
        get() = "${Bytes.human(writtenBytes)} / ${Bytes.human(totalBytes)}"

    /** Kalan süre tahmini (saniye); veri yetersizse -1. */
    val etaSeconds: Long
        get() {
            if (totalBytes <= 0L || bytesPerSecond <= 0L) return -1L
            val remaining = totalBytes - writtenBytes
            if (remaining <= 0L) return 0L
            return remaining / bytesPerSecond
        }
}

/** Bir log satırı. */
data class LogLine(val level: LogLevel, val message: String, val atMs: Long)
