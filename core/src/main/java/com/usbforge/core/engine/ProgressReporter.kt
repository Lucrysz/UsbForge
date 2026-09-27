package com.usbforge.core.engine

import com.usbforge.core.usb.LogLevel

/**
 * Uzun süren I/O işlerinin ilerlemesini yayınlayan arayüz.
 *
 * Uygulamadaki tüm iş motorları (ISO yazıcı, biçimlendirici, Ventoy
 * kurulumu) bu arayüzü doldurur; [JobEngine] bunu `StateFlow`'a çevirerek
 * arayüze akış olarak sunar.
 *
 * ## Uygulama sözleşmesi
 * - Tüm metotlar `Dispatchers.IO` bağlamından, yani tek bir iş parçacığı
 *   üzerinden çağrılır; ek senkronizasyon gerekmez.
 * - Metotlar **maliyeti düşük** olmalıdır: her bayt için değil, her yazma
 *   birimi (≥ 64 KiB) için çağrılır.
 */
interface ProgressReporter {

    /** İşin adı (ör. "ISO Yazma — ubuntu-24.04.iso"). */
    val title: String

    /** Toplam iş boyutu. Bilinmiyorsa 0. */
    val totalBytes: Long

    /** Şu ana kadar yazılan bayt. */
    val writtenBytes: Long

    /** Anlık yazma hızı (bayt/saniye, kayan ortalama). */
    val bytesPerSecond: Long

    /** İşin mevcut aşaması (ör. "Bölüntü tablosu yazılıyor"). */
    val phase: String

    /** Kullanıcı iptal istedi mi? */
    val isCancelled: Boolean

    /** Toplam baytı belirler. [totalBytes] 0 ise belirsiz ilerleme gösterilir. */
    fun setTotal(totalBytes: Long)

    /** Toplam baytı artırır (ör. çok parçalı bir işlemde). */
    fun addTotal(bytes: Long)

    /** Yazılan baytı artırır ve ilerlemeyi yeniden hesaplar. */
    fun addWritten(bytes: Long)

    /** Aşama metnini değiştirir. */
    fun setPhase(phase: String)

    /** Log satırı ekler. */
    fun log(message: String, level: LogLevel = LogLevel.INFO)
}
