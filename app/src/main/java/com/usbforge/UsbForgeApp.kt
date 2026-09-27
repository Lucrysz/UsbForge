package com.usbforge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.usbforge.job.WriteJobService

/**
 * Uygulama giriş noktası.
 *
 * Burada yalnızca uzun süren yazma işlerinin arka planda ölmemesi için
 * bildirim kanalı oluşturulur. Başka bir başlangıç işi yoktur: USB cihaz
 * takılma olayları `MainActivity` üzerindeki `USB_DEVICE_ATTACHED` intent
 * filtresiyle yakalanır.
 */
class UsbForgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_WRITE,
            getString(R.string.channel_write_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_write_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_WRITE = "usbforge_write"
    }
}
