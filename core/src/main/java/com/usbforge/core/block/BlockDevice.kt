package com.usbforge.core.block

import java.io.Closeable

/**
 * Android'e bağımlı olmayan blok cihaz erişimi.
 *
 * Aynı [BlockDevice] arayüzünü üç farklı arka uç uygular:
 *
 * | Uygulama        | Ortam                    | Gereken yetki        |
 * |-----------------|--------------------------|----------------------|
 * | [ScsiBlockDevice] | Android, USB Host API   | USB izni (kullanıcı) |
 * | [RootBlockDevice]  | Android, root'lu        | `su`                 |
 * | [PcBlockDevice]    | Masaüstü (Windows/Linux) | yönetici / disk izni |
 *
 * `PcBlockDevice` masaüstü test aracıdır: `\\.\PhysicalDriveN` ya da
 * `/dev/sdX` üzerinde çalışır ve biçimlendiricilerin çıktısını gerçek
 * donanım üzerinde sınamayı sağlar. Bu sınıf `:core` içinde yaşar çünkü
 * `java.nio.channels.FileChannel` kullanır ve Android'de de (root'suz
 * kalıcı depolama erişimi olan) çalışabilir.
 */
interface BlockDevice : Closeable {

    /** Cihazın kısa adı (log ve arayüzde gösterilir). */
    val displayName: String

    /** Toplam sektör sayısı (512 bayt/sektör). */
    val totalSectors: Long

    /** Cihazın toplam boyutu (bayt). */
    val sizeBytes: Long get() = totalSectors * 512

    /** Cihaz yazmaya hazır mı? */
    val writable: Boolean

    /** Cihazı yazmaya hazırlar: önbellek boşaltılır, kilit varsa açılır. */
    fun prepareForWrite()

    /**
     * [startLba] konumuna [src] tamponundaki [length] baytı yazar.
     *
     * @param length 512'nin katı olmalıdır
     * @param onChunk her yazma birimi tamamlandığında bildirilir
     */
    fun write(
        startLba: Long,
        src: ByteArray,
        srcOffset: Int = 0,
        length: Int = src.size - srcOffset,
        isCancelled: () -> Boolean = { false },
        onChunk: ((bytes: Long) -> Unit)? = null,
    )

    /** [startLba] konumundan [length] bayt okur. */
    fun read(
        startLba: Long,
        length: Int,
        dst: ByteArray,
        dstOffset: Int = 0,
        isCancelled: () -> Boolean = { false },
    )

    /** Cihazın sağlık/üretici bilgisi; yoksa null. */
    fun healthHint(): String? = null

    /** Yazma önbelleğini fiziksel medyaya zorlar. */
    fun flush()

    override fun close() {}
}

/** İş iptal edildi. */
class BlockWriteCancelled(message: String = "İşlem kullanıcı tarafından durduruldu.") :
    RuntimeException(message)

/** Bloğa erişilemedi: kilitli, arızalı veya kapasite dışı. */
class BlockAccessException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** Cihaz yazma korumalı. */
class BlockNotWritableException(message: String) : RuntimeException(message)

/** Kullanıcı işlemi iptal etti. */
class CancelledByUser : RuntimeException("İşlem kullanıcı tarafından iptal edildi.")
