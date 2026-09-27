package com.usbforge.job

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.usbforge.MainActivity
import com.usbforge.R
import com.usbforge.UsbForgeApp
import com.usbforge.core.util.Bytes

/**
 * Uzun süren yazma işlerini arka planda yaşatan ön plan servisi.
 *
 * ## Neden gerekli
 * Android 8.0 (API 26) itibaren bir uygulama arka plana geçtiğinde
 * `JobScheduler`/`WorkManager` kullanmadıkça süreç sonlandırılabilir. 8 GB'a
 * varan bir ISO yazımı dakikalar sürdüğünden, yazma sırasında ön plan
 * servisi (`dataSync` türü) çalıştırılır.
 *
 * ## Akış
 * ```
 * ViewModel  →  startForegroundService(WriteJobService)
 * Service    →  START_STICKY, bildirim güncellenir
 * ViewModel  →  stopService(...)  iş bitince
 * ```
 *
 * Servis tek başına yazma yapmaz; yalnızca sürecin canlı kalmasını ve
 * kullanıcıya durum bildirimini gösterir. Asıl iş `ViewModel` içindeki
 * `JobEngine` tarafından yürütülür. Bu ayrım sayesinde iş mantığı Android
 * yaşam döngüsünden bağımsız ve test edilebilir kalır.
 */
class WriteJobService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }

            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
                val phase = intent?.getStringExtra(EXTRA_PHASE) ?: getString(R.string.channel_write_name)
                startForegroundCompat(buildNotification(title, phase))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        stopForegroundCompat()
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(title: String, phase: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, UsbForgeApp.CHANNEL_WRITE)
            .setContentTitle(title)
            .setContentText(phase)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 4711
        const val ACTION_STOP = "com.usbforge.action.STOP_JOB"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PHASE = "phase"

        /** Servisi başlatır; iş bitince [stop] çağrılmalıdır. */
        fun start(context: Context, title: String, phase: String) {
            val intent = Intent(context, WriteJobService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PHASE, phase)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Servisi durdurur. */
        fun stop(context: Context) {
            context.stopService(Intent(context, WriteJobService::class.java))
        }

        /** Bildirim metnini üretir (testlerde doğrulama için). */
        internal fun describe(title: String, phase: String, written: Long, total: Long): String =
            "$title — $phase (${Bytes.human(written)} / ${Bytes.human(total)})"
    }
}
