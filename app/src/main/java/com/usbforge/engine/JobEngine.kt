package com.usbforge.engine

import com.usbforge.core.engine.JobSnapshot
import com.usbforge.core.engine.JobStatus
import com.usbforge.core.engine.LogLine
import com.usbforge.core.engine.ProgressReporter
import com.usbforge.core.usb.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean


/**
 * Tüm uzun süren blok I/O işlerini çalıştıran tek nokta.
 *
 * ## Neden bu sınıf
 * - **Tek eşzamanlı iş:** USB bloğu aynı anda yalnızca bir yazma alabilir;
 *   ikinci bir işi başlatmak cihazı kilitler. [start] çalışan bir iş varsa
 *   yeni işi reddeder.
 * - **Sabit bellek:** İş belleği `title + buffer + state` ile sınırlıdır;
 *   dosya boyutundan bağımsızdır (1 MB veya 4 MB blok akışı).
 * - **Ölçülebilir ilerleme:** [SpeedMeter] kayan pencere ile ortalama
 *   MB/s hesaplar; ani salınımları yumuşatır.
 */
class JobEngine(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(JobSnapshot())
    val state: StateFlow<JobSnapshot> = _state.asStateFlow()

    private var job: Job? = null
    private var nextId = 1L
    private val cancelFlag = AtomicBoolean(false)

    val isBusy: Boolean get() = _state.value.status == JobStatus.RUNNING

    /**
     * Yeni bir iş başlatır.
     *
     * @param block [ProgressReporter] alan iş gövdesi. `Dispatchers.IO`
     *              bağlamında çalıştırılır; `onChunk` geri çağırımları bu
     *              bağlamdan gelir.
     */
    fun start(title: String, block: suspend (ProgressReporter) -> Unit) {
        if (isBusy) return
        cancelFlag.set(false)
        val id = nextId++
        val meter = SpeedMeter()
        val started = System.nanoTime()

        _state.value = JobSnapshot(
            id = id,
            title = title,
            phase = "Hazırlanıyor…",
            status = JobStatus.RUNNING,
            log = listOf(LogLine(LogLevel.INFO, "▶ $title", 0L)),
        )

        job = scope.launch {
            val reporter = Reporter(id, title, meter, started)
            try {
                block(reporter)
                val snapshot = _state.value
                if (cancelFlag.get() || snapshot.status == JobStatus.CANCELLED) {
                    _state.update {
                        it.copy(
                            status = JobStatus.CANCELLED,
                            phase = "Kullanıcı tarafından durduruldu",
                            bytesPerSecond = 0L,
                            log = it.log + LogLine(LogLevel.WARN, "■ İş durduruldu.", System.currentTimeMillis()),
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            status = JobStatus.SUCCESS,
                            phase = "Tamamlandı",
                            bytesPerSecond = 0L,
                            log = it.log + LogLine(
                                LogLevel.INFO,
                                "✔ İş başarıyla tamamlandı (${it.elapsedMs / 1000} sn).",
                                System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                _state.update {
                    it.copy(
                        status = JobStatus.CANCELLED,
                        phase = "İptal edildi",
                        bytesPerSecond = 0L,
                        log = it.log + LogLine(LogLevel.WARN, "■ İş iptal edildi.", System.currentTimeMillis()),
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        status = JobStatus.FAILED,
                        phase = "Hata",
                        error = t.message ?: t.javaClass.simpleName,
                        bytesPerSecond = 0L,
                        log = it.log + LogLine(
                            LogLevel.ERROR,
                            "✘ ${t.message ?: t.javaClass.simpleName}",
                            System.currentTimeMillis(),
                        ),
                    )
                }
            } finally {
                job = null
            }
        }
    }

    /** Çalışan işi iptal eder. */
    fun cancel() {
        if (!isBusy) return
        cancelFlag.set(true)
        _state.update { it.copy(log = it.log + LogLine(LogLevel.WARN, "… durdurma isteği", System.currentTimeMillis())) }
    }

    /** Sonuç panelini temizler. */
    fun reset() {
        if (isBusy) return
        _state.value = JobSnapshot()
    }

    // ---------------------------------------------------------------- reporter

    private inner class Reporter(
        private val id: Long,
        override val title: String,
        private val meter: SpeedMeter,
        private val startedNanos: Long,
    ) : ProgressReporter {

        @Volatile
        private var total: Long = 0L

        @Volatile
        private var written: Long = 0L

        @Volatile
        private var currentPhase: String = "Hazırlanıyor…"

        override val totalBytes: Long get() = total
        override val writtenBytes: Long get() = written
        override val bytesPerSecond: Long get() = meter.current()
        override val phase: String get() = currentPhase
        override val isCancelled: Boolean get() = cancelFlag.get()

        override fun setTotal(totalBytes: Long) {
            total = totalBytes
            push()
        }

        override fun addTotal(bytes: Long) {
            total += bytes
            push()
        }

        override fun addWritten(bytes: Long) {
            written += bytes
            meter.add(bytes)
            push()
        }

        override fun setPhase(phase: String) {
            currentPhase = phase
            push()
        }

        override fun log(message: String, level: LogLevel) {
            _state.update { it.copy(log = it.log + LogLine(level, message, System.currentTimeMillis())) }
        }

        private fun push() {
            val elapsed = (System.nanoTime() - startedNanos) / 1_000_000L
            val phaseSnapshot = currentPhase
            _state.update {
                it.copy(
                    id = id,
                    phase = phaseSnapshot,
                    writtenBytes = written,
                    totalBytes = total,
                    bytesPerSecond = meter.current(),
                    elapsedMs = elapsed,
                )
            }
        }
    }

    // -------------------------------------------------------------- speed meter

    /**
     * Kayan pencere tabanlı hız ölçer.
     *
     * Sabit 3 saniyelik pencere kullanılır: anlık hız USB bağlantısında
     * çok dalgalıdır, kayan ortalama hem daha okunabilir hem de ETA
     * hesabında daha kararlıdır. Örneklemeler sabit boyutlu dizilerde
     * tutulur; pencere dolduğında en eski örneklem üzerine yazılır
     * (allocation yok).
     */
    private class SpeedMeter(private val windowMs: Long = 3_000L, private val capacity: Int = 128) {
        private val times = LongArray(capacity)
        private val bytes = LongArray(capacity)
        private var index = 0
        private var count = 0
        private var lastNow = 0L
        private var currentSpeed = 0L

        @Synchronized
        fun add(delta: Long) {
            val now = System.nanoTime()
            times[index] = now
            bytes[index] = delta
            index = (index + 1) % capacity
            if (count < capacity) count++
            lastNow = now
            recompute(now)
        }

        @Synchronized
        fun current(): Long = currentSpeed

        private fun recompute(now: Long) {
            val cutoff = now - windowMs * 1_000_000L
            var sum = 0L
            var newest = Long.MIN_VALUE
            var oldest = Long.MAX_VALUE
            for (i in 0 until count) {
                val t = times[i]
                if (t < cutoff) continue
                sum += bytes[i]
                if (t > newest) newest = t
                if (t < oldest) oldest = t
            }
            val span = if (newest == Long.MIN_VALUE || oldest == Long.MAX_VALUE) 0L else newest - oldest
            currentSpeed = if (span <= 0L) sum else (sum * 1_000_000_000L) / span
        }
    }
}
