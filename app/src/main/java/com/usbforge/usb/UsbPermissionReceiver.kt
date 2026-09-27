package com.usbforge.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * USB izin (permission) sonucunu taşıyan yayın tabanlı veri yolu.
 *
 * Neden BroadcastReceiver? `UsbManager.requestPermission()` API 30'dan önce
 * sonucu `PendingIntent`'e yazar, API 30+ ise geri çağırma imzası sunar.
 * Tek bir kod yolu ve iki API seviyesini de desteklemek için Broadcast
 * tabanlı yol seçilmiştir; `receiver` manifest'te kayıtlıdır.
 */
object UsbPermissionBus {

    const val ACTION = "com.usbforge.USB_PERMISSION"

    private val waiters = ConcurrentHashMap<String, (Boolean) -> Unit>()

    /** Cihaz için sistem izni ister. [granted] sonucu ile döner. */
    suspend fun request(manager: UsbManager, device: UsbDevice): Boolean {
        if (manager.hasPermission(device)) return true

        val key = device.deviceName
        return suspendCancellableCoroutine { cont ->
            @Suppress("UNCHECKED_CAST")
            waiters[key] = { granted ->
                if (cont.isActive) cont.resume(granted)
            }
            cont.invokeOnCancellation { waiters.remove(key) }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pending = PendingIntent.getBroadcast(
                /* context = */ manager.contextOrNull(),
                /* requestCode = */ key.hashCode(),
                Intent(ACTION).setPackage(manager.contextOrNull()?.packageName),
                flags,
            )
            manager.requestPermission(device, pending)
        }
    }

    /** Receiver tarafından çağrılır. */
    fun complete(deviceName: String?, granted: Boolean) {
        if (deviceName == null) return
        waiters.remove(deviceName)?.invoke(granted)
    }
}

/** [UsbManager] üzerinde bağlama bilgisi döndürmek için küçük genişletme. */
private fun UsbManager.contextOrNull(): Context? =
    runCatching {
        val m = UsbManager::class.java.getDeclaredMethod("getContext")
        m.isAccessible = true
        m.invoke(this) as? Context
    }.getOrNull()

/**
 * `com.usbforge.USB_PERMISSION` yayınını yakalar ve [UsbPermissionBus]'e iletir.
 * Manifest'te `exported="false"` olarak kayıtlıdır.
 */
class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbPermissionBus.ACTION) return
        val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        UsbPermissionBus.complete(device?.deviceName, granted)
    }
}
