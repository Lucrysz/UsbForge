package com.usbforge.core.usb

/**
 * Bir blok cihazın kapasite bilgisi.
 *
 * Saf veri sınıfı: hem USB Mass Storage hem de masaüstü/root arka uçları
 * aynı tipi döndürür, böylece biçimlendiriciler cihaz türünden bağımsız
 * çalışır.
 */
data class DiskCapacity(
    /** Mantıksal blok boyutu; uygulama genelinde her zaman 512. */
    val sectorSize: Int,
    /** Toplam sektör sayısı. */
    val totalSectors: Long,
) {
    /** Toplam boyut (bayt). */
    val sizeBytes: Long get() = totalSectors * sectorSize

    /** Disk 2 TiB'ı aşıyor mu (READ/WRITE(16) gerekir)? */
    val needs16ByteCommands: Boolean get() = totalSectors > 0x0FFFFFFF1L
}
