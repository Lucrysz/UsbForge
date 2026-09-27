package com.usbforge.core.test

import com.usbforge.core.engine.ProgressReporter
import com.usbforge.core.usb.LogLevel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Testlerde kullanılan [ProgressReporter].
 *
 * Yazma motorlarının `ProgressReporter` API'sini doğru kullandığını
 * doğrulamak için çağrı geçmişini kaydeder: hangi fazlara geçildi, toplam
 * ne bildirildi, kaç bayt sayıldı.
 */
open class RecordingReporter(
    override val title: String = "test",
    override val totalBytes: Long = 0L,
) : ProgressReporter {

    val phases = mutableListOf<String>()
    val logs = mutableListOf<Pair<LogLevel, String>>()
    var flushRequests = 0

    /** [addWritten] ile bildirilen toplam bayt (alt sınıflar bunu kullanır). */
    var totalWritten: Long = 0L
        private set

    private val cancelled = AtomicBoolean(false)

    override val writtenBytes: Long get() = totalWritten
    override val bytesPerSecond: Long = 0L
    override val phase: String get() = phases.lastOrNull() ?: ""
    override val isCancelled: Boolean get() = cancelled.get()

    fun cancel() = cancelled.set(true)

    override fun setTotal(totalBytes: Long) {
        phases += "total:$totalBytes"
    }

    override fun addTotal(bytes: Long) {
        phases += "addTotal:$bytes"
    }

    override fun addWritten(bytes: Long) {
        totalWritten += bytes
    }

    override fun setPhase(phase: String) {
        phases += phase
    }

    override fun log(message: String, level: LogLevel) {
        logs += level to message
    }
}
