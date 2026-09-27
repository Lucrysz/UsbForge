package com.usbforge.core.fs

import com.usbforge.core.block.BlockDevice
import com.usbforge.core.engine.ProgressReporter
import java.io.InputStream

/**
 * Biçimlendirilmiş bir FAT32 bölümüne dosya/dizin ekleyen yazıcı.
 *
 * ## Neden gerekli
 * Ventoy kurulumu, 32 MiB'lık `VTOYEFI` bölümünü biçimlendirdikten sonra
 * içine resmî dağıtımdan gelen önyükleme dosyalarını (`EFI/BOOT/...`,
 * `ventoy/...`) yazmak zorundadır. Android'de `libfatfs` benzeri bir
 * kütüphane bulunmadığından asgari FAT32 yazma sürücüsü burada
 * uygulanmıştır.
 *
 * ## Desteklenen işlemler
 * - Çok seviyeli dizin oluşturma (`.` ve `..` girdileriyle)
 * - Streaming dosya yazma (sabit küme boyutlu tampon — dosya boyutundan bağımsız)
 * - Uzun dosya adı (LFN) + benzersiz 8.3 kısa ad üretimi
 * - FAT zinciri kurulumu (her giriş iki FAT kopyasına da yazılır)
 * - FAT zinciri boyunca dizin genişletme
 * - FSInfo boş küme sayacının tazelenmesi
 *
 * ## Sınırlar
 * Silme/taşıma uygulanmaz (kurulum sırasında gerekmez). Yalnızca FAT32
 * desteklenir; 12/16 bit FAT'ler boyut gereksinimi nedeniyle gereksizdir.
 */
class Fat32Writer(
    private val device: BlockDevice,
    private val geo: Fat32Geometry,
    private val progress: ProgressReporter,
    /** Tüm LBA'lar diske göre mutlak kabul edilir (bölüm başlangıcı eklenir). */
    private val partitionStartLba: Long,
) {

    /** Kurulum sonunda raporlanır. */
    var fileCount: Int = 0
        private set

    var directoryCount: Int = 0
        private set

    private var nextHint = 2
    private var freeClusters: Int = geo.clusterCount - 2

    // ------------------------------------------------------------------ genel API

    /**
     * [relativePath] yoluna dosya yazar; ara dizinler yoksa oluşturulur.
     *
     * @param stream         veri kaynağı (kapatılmaz)
     * @param declaredSize   bilinen boyut; bilinmiyorsa -1 (streaming)
     */
    fun addFile(relativePath: String, stream: InputStream, declaredSize: Long = -1L) {
        val segments = splitPath(relativePath)
        require(segments.isNotEmpty()) { "Geçersiz dosya yolu: $relativePath" }

        val parent = ensureDirectories(segments.dropLast(1))
        val fileName = segments.last()

        if (declaredSize == 0L) {
            addDirectoryEntry(parent, fileName, Fat32Formatter.ATTR_ARCHIVE, 0, 0)
            fileCount++
            return
        }
        if (declaredSize > 0) {
            writeKnownSize(parent, fileName, stream, declaredSize)
        } else {
            writeStreaming(parent, fileName, stream)
        }
        fileCount++
    }

    /** Dizin zincirini oluşturur/çözer, son dizinin kümesini döndürür. */
    fun ensureDirectories(segments: List<String>): Int {
        var current = geo.rootCluster
        for (seg in segments) {
            val existing = findEntry(current, seg)
            current = if (existing != null && existing.isDirectory) {
                existing.firstCluster
            } else {
                val cluster = allocate(1)
                writeDirSelfAndParent(cluster, current)
                addDirectoryEntry(
                    dirCluster = current,
                    name = seg,
                    attr = Fat32Formatter.ATTR_DIRECTORY,
                    firstCluster = cluster,
                    size = 0,
                )
                directoryCount++
                cluster
            }
        }
        return current
    }

    /** Son rötuş: FSInfo'yi tazeler ve önbelleği boşaltır. */
    fun finalizeVolume() {
        updateFsInfo()
        device.flush()
    }

    // --------------------------------------------------------- bilinen boyut

    private fun writeKnownSize(
        dirCluster: Int,
        fileName: String,
        stream: InputStream,
        size: Long,
    ) {
        val clusters = FsMath.divUp(size, geo.clusterSize.toLong()).toInt()
        val first = allocate(clusters)

        val buf = ByteArray(geo.clusterSize)
        var cluster = first
        var remaining = size

        while (remaining > 0) {
            val want = minOf(remaining, geo.clusterSize.toLong()).toInt()
            var filled = 0
            while (filled < want) {
                val n = stream.read(buf, filled, want - filled)
                if (n < 0) break
                filled += n
            }
            if (filled < want) java.util.Arrays.fill(buf, filled, want, 0.toByte())
            device.write(partitionStartLba + geo.dataSectorOf(cluster), buf, 0, geo.clusterSize) {
                progress.addWritten(it)
            }
            remaining -= want
            cluster++
        }
        // Son kümenin zinciri zaten END_OF_CHAIN (allocate ile kuruldu)
        addDirectoryEntry(dirCluster, fileName, Fat32Formatter.ATTR_ARCHIVE, first, size)
    }

    // --------------------------------------------------------------- streaming

    /**
     * Boyutu önceden bilinmeyen akışı küme küme yazar.
     * İlk veri geldiğinde ilk küme tahsis edilir; her küme dolduğunda
     * zincir uzatılır. Bu sayede 4 GiB'lık bir ISO'yu 1 MiB bellekle
     * yazmak mümkündür.
     */
    private fun writeStreaming(dirCluster: Int, fileName: String, stream: InputStream) {
        val clusterSize = geo.clusterSize
        val buf = ByteArray(clusterSize)

        var current = -1
        var total = 0L
        var eof = false

        while (!eof) {
            var filled = 0
            while (filled < clusterSize) {
                val n = stream.read(buf, filled, clusterSize - filled)
                if (n < 0) { eof = true; break }
                filled += n
            }
            if (filled == 0) break
            if (filled < clusterSize) java.util.Arrays.fill(buf, filled, clusterSize, 0.toByte())

            if (current < 0) {
                current = allocate(1)
            } else {
                val next = allocate(1)
                setFatEntry(current, next)
                current = next
            }

            device.write(partitionStartLba + geo.dataSectorOf(current), buf, 0, clusterSize) {
                progress.addWritten(it)
            }
            total += filled
        }

        if (current < 0) {
            addDirectoryEntry(dirCluster, fileName, Fat32Formatter.ATTR_ARCHIVE, 0, 0)
        } else {
            addDirectoryEntry(dirCluster, fileName, Fat32Formatter.ATTR_ARCHIVE, current, total)
            updateFsInfo()
        }
    }

    // ------------------------------------------------------------- cluster yönetimi

    /** [count] küme tahsis eder, zinciri kurar, ilk küme numarasını döndürür. */
    private fun allocate(count: Int): Int {
        require(count > 0) { "Sıfır küme tahsisi yapılamaz." }
        if (nextHint + count > geo.clusterCount + 2) {
            // Disk sonuna gelindi: baştan tara ve ilk yeterli boşluğu bul.
            val found = linearSearch(count)
            nextHint = found
        }
        val first = nextHint
        val last = first + count - 1
        for (c in first until last) setFatEntry(c, c + 1)
        setFatEntry(last, END_OF_CHAIN)
        nextHint = last + 1
        freeClusters -= count
        return first
    }

    private fun linearSearch(count: Int): Int {
        val buf = ByteArray(512)
        var run = 0
        var candidate = 2
        for (c in 2 until geo.clusterCount + 2) {
            val secIndex = geo.fatSectorIndexOf(c)
            val byteOff = geo.fatByteOffsetOf(c)
            if (byteOff == 0) {
                device.read(partitionStartLba + geo.fatOffset + secIndex, 512, buf)
            }
            val value = Fat32Formatter.readLe32(buf, byteOff)
            run = if (value == FREE) run + 1 else 0
            if (run == count) {
                candidate = c - count + 1
                // Aday küme zincirini kesinleştir
                for (k in candidate until candidate + count - 1) setFatEntry(k, k + 1)
                setFatEntry(candidate + count - 1, END_OF_CHAIN)
                freeClusters -= count
                return candidate
            }
        }
        throw IllegalStateException(
            "FAT32 bölümünde ${count} kümlük boş alan kalmadı (toplam ${geo.clusterCount} küme)."
        )
    }

    private fun setFatEntry(cluster: Int, value: Int) {
        val lba = partitionStartLba + geo.fatOffset + geo.fatSectorIndexOf(cluster)
        val byteOff = geo.fatByteOffsetOf(cluster)
        val buf = ByteArray(512)
        device.read(lba, 512, buf)
        Fat32Formatter.putLe32(buf, byteOff, value.toLong() and 0xFFFFFFFFL)
        device.write(lba, buf)
        // Yedek FAT kopyası — aynı sektör, ikinci kopyada
        device.write(lba + geo.fatSectors, buf)
    }

    private fun fatEntry(cluster: Int): Int {
        val lba = partitionStartLba + geo.fatOffset + geo.fatSectorIndexOf(cluster)
        val buf = ByteArray(512)
        device.read(lba, 512, buf)
        return Fat32Formatter.readLe32(buf, geo.fatByteOffsetOf(cluster)).toInt()
    }

    // ------------------------------------------------------------ dizin gezme

    data class DirEntry(val isDirectory: Boolean, val firstCluster: Int, val size: Long)

    /** [dirCluster] dizininde [name] adlı girdiyi arar. */
    fun findEntry(dirCluster: Int, name: String): DirEntry? {
        val buf = ByteArray(512)
        val lfn = StringBuilder()
        var cluster = dirCluster
        var guard = 0

        while (cluster >= 2 && guard++ < geo.clusterCount + 4) {
            for (s in 0 until geo.sectorsPerCluster) {
                device.read(partitionStartLba + geo.dataSectorOf(cluster) + s, 512, buf)
                var off = 0
                while (off < 512) {
                    val first = buf[off].toInt() and 0xFF
                    if (first == 0x00) return null
                    if (first == 0xE5) { lfn.setLength(0); off += 32; continue }
                    val attr = buf[off + 11].toInt() and 0xFF
                    if (attr == Fat32Formatter.ATTR_LFN) {
                        // LFN girdileri ordinal artan sırada yazılır (1,2,3...)
                        lfn.append(parseLfnName(buf, off))
                        off += 32
                        continue
                    }
                    val shortName = String(buf, off, 11, Charsets.US_ASCII).trimEnd()
                    if (nameMatches(lfn.toString(), name) || nameMatches(shortName, name)) {
                        val clus = ((buf[off + 20].toInt() and 0xFF) shl 8) or (buf[off + 26].toInt() and 0xFF)
                        return DirEntry(
                            isDirectory = (attr and Fat32Formatter.ATTR_DIRECTORY) != 0,
                            firstCluster = clus,
                            size = Fat32Formatter.readLe32(buf, off + 28),
                        )
                    }
                    lfn.setLength(0)
                    off += 32
                }
            }
            val next = fatEntry(cluster) and 0x0FFFFFFF
            cluster = if (next in 2..(geo.clusterCount + 1)) next else -1
        }
        return null
    }

    private fun nameMatches(a: String, b: String): Boolean {
        if (a.isEmpty()) return false
        if (a.equals(b, ignoreCase = true)) return true
        // LFN'de nokta, 8.3'te boşluk olarak temsil edilir
        return a.replace('.', ' ').trim().equals(b.replace('.', ' ').trim(), ignoreCase = true)
    }

    // ------------------------------------------------------ dizin girişi yazma

    /** [dirCluster] dizinine dosya girdisi (LFN + 8.3) ekler. */
    fun addDirectoryEntry(
        dirCluster: Int,
        name: String,
        attr: Int,
        firstCluster: Int,
        size: Long,
    ) {
        val chars = lfnChars(name)
        val shortName = generateShortName(dirCluster, name)
        val checksum = shortNameChecksum(shortName)
        val chunks = chars.chunked(13)
        val need = chunks.size + 1

        val slot = findFreeSlot(dirCluster, need)
        val buf = ByteArray(512)
        val lba = partitionStartLba + geo.dataSectorOf(slot.cluster) + slot.sector
        device.read(lba, 512, buf)

        var off = slot.offset
        chunks.forEachIndexed { i, chunk ->
            writeLfnEntry(buf, off, i + 1, chunk, checksum, isLast = i == chunks.size - 1)
            off += 32
        }
        writeShortEntry(buf, off, shortName, attr, firstCluster, size)
        device.write(lba, buf)
    }

    private data class DirSlot(val cluster: Int, val sector: Int, val offset: Int)

    /** FAT zinciri boyunca [needed] adet boş 32 baytlık yuva bulur. */
    private fun findFreeSlot(dirCluster: Int, needed: Int): DirSlot {
        val buf = ByteArray(512)
        var cluster = dirCluster
        var guard = 0

        while (cluster >= 2 && guard++ < geo.clusterCount + 4) {
            for (s in 0 until geo.sectorsPerCluster) {
                device.read(partitionStartLba + geo.dataSectorOf(cluster) + s, 512, buf)
                var off = 0
                while (off < 512) {
                    if (off + needed * 32 <= 512) {
                        var free = 0
                        while (free < needed && off + free * 32 < 512 &&
                            buf[off + free * 32].toInt() and 0xFF == 0x00
                        ) {
                            free++
                        }
                        if (free >= needed) return DirSlot(cluster, s, off)
                    }
                    off += 32
                }
            }
            val next = fatEntry(cluster) and 0x0FFFFFFF
            cluster = if (next in 2..(geo.clusterCount + 1)) next else -1
        }
        throw IllegalStateException("Dizin girdisi için boş yer bulunamadı (dizin: küme $dirCluster).")
    }

    private fun writeDirSelfAndParent(cluster: Int, parent: Int) {
        val buf = ByteArray(geo.clusterSize)
        writeShortEntry(buf, 0, ".          ", Fat32Formatter.ATTR_DIRECTORY, cluster, 0)
        writeShortEntry(buf, 32, "..         ", Fat32Formatter.ATTR_DIRECTORY, parent, 0)
        device.write(partitionStartLba + geo.dataSectorOf(cluster), buf) { progress.addWritten(it) }
    }

    // ------------------------------------------------------- 8.3 kısa ad üretimi

    /**
     * 8.3 kısa ad üretir.
     *
     * 1. Ad zaten geçerli bir 8.3 ise (yalnızca büyük harf/rakam/tire/alt
     *    çizgi, kök ad ≤ 8, uzantı ≤ 3) doğrudan kullanılır.
     * 2. Değilse `KOK1~1.UZ` biçiminde benzersiz sayısal kuyruk bulunur.
     *    Kuyruk, dizinde daha önce kullanılmış adlar taranarak seçilir.
     */
    fun generateShortName(dirCluster: Int, name: String): String {
        val dot = name.lastIndexOf('.')
        val stemRaw = if (dot >= 0) name.substring(0, dot) else name
        val extRaw = if (dot >= 0) name.substring(dot + 1) else ""
        val ext = extRaw.uppercase().filter { it.isLetterOrDigit() }.take(3)

        val plainStem = stemRaw.uppercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        val isPlain83 = stemRaw == plainStem &&
            plainStem.isNotEmpty() && plainStem.length <= 8 &&
            (dot < 0 || (extRaw == ext && extRaw.length <= 3))

        if (isPlain83) {
            val candidate = plainStem.padEnd(8, ' ') + ext.padEnd(3, ' ')
            if (!shortNameInUse(dirCluster, candidate)) return candidate
        }

        val stem6 = plainStem.take(6)
        for (i in 1..999_999) {
            val tail = i.toString()
            val base = (if (stem6.length + tail.length > 6) stem6.take(6 - tail.length) else stem6) + tail
            val candidate = base.padEnd(8, ' ') + ext.padEnd(3, ' ')
            if (!shortNameInUse(dirCluster, candidate)) return candidate
        }
        throw IllegalStateException("Benzersiz 8.3 ad üretilemedi: $name")
    }

    private fun shortNameInUse(dirCluster: Int, shortName: String): Boolean {
        val buf = ByteArray(512)
        var cluster = dirCluster
        var guard = 0
        while (cluster >= 2 && guard++ < geo.clusterCount + 4) {
            for (s in 0 until geo.sectorsPerCluster) {
                device.read(partitionStartLba + geo.dataSectorOf(cluster) + s, 512, buf)
                var off = 0
                while (off < 512) {
                    val first = buf[off].toInt() and 0xFF
                    if (first == 0x00) return false
                    if (first != 0xE5) {
                        val attr = buf[off + 11].toInt() and 0xFF
                        if (attr != Fat32Formatter.ATTR_LFN && attr != 0) {
                            if (String(buf, off, 11, Charsets.US_ASCII) == shortName) return true
                        }
                    }
                    off += 32
                }
            }
            val next = fatEntry(cluster) and 0x0FFFFFFF
            cluster = if (next in 2..(geo.clusterCount + 1)) next else -1
        }
        return false
    }

    private fun shortNameChecksum(shortName: String): Int {
        var sum = 0
        for (c in shortName.take(11).padEnd(11, ' ')) sum = (sum + c.code) and 0xFF
        return sum
    }

    // ---------------------------------------------------------------- girdi yazımı

    private fun writeShortEntry(
        buf: ByteArray,
        offset: Int,
        name: String,
        attr: Int,
        firstCluster: Int,
        size: Long,
    ) {
        val padded = name.take(11).padEnd(11, ' ')
        for (i in 0 until 11) buf[offset + i] = padded[i].code.toByte()
        buf[offset + 11] = attr.toByte()
        buf[offset + 12] = 0                       // NTRes
        buf[offset + 13] = 0                       // createTimeTenth
        val stamp = DosTime.now()
        Fat32Formatter.putLe16(buf, offset + 14, stamp and 0xFFFF)          // createTime
        Fat32Formatter.putLe16(buf, offset + 16, (stamp shr 16) and 0xFFFF)  // createDate
        Fat32Formatter.putLe16(buf, offset + 18, (stamp shr 16) and 0xFFFF)  // lastAccessDate
        Fat32Formatter.putLe16(buf, offset + 20, (firstCluster shr 16) and 0xFFFF)
        Fat32Formatter.putLe16(buf, offset + 22, stamp and 0xFFFF)          // writeTime
        Fat32Formatter.putLe16(buf, offset + 24, (stamp shr 16) and 0xFFFF)  // writeDate
        Fat32Formatter.putLe16(buf, offset + 26, firstCluster and 0xFFFF)
        Fat32Formatter.putLe32(buf, offset + 28, size and 0xFFFFFFFFL)
    }

    /**
     * LFN girdisi (32 bayt).
     * Yerleşim: ordinal(1) + 5×UTF-16 + attr(1) + type(1) + checksum(1)
     * + 6×UTF-16 + cluster(2) + 2×UTF-16.
     */
    private fun writeLfnEntry(
        buf: ByteArray,
        offset: Int,
        ordinal: Int,
        chunk: List<Int>,
        checksum: Int,
        isLast: Boolean,
    ) {
        for (i in 0 until 32) buf[offset + i] = 0
        buf[offset] = (if (isLast) ordinal or 0x40 else ordinal).toByte()
        for (i in 0 until 5) Fat32Formatter.putLe16(buf, offset + 1 + i * 2, chunk.getOrElse(i) { NUL })
        buf[offset + 11] = Fat32Formatter.ATTR_LFN.toByte()
        buf[offset + 12] = 0
        buf[offset + 13] = checksum.toByte()
        for (i in 0 until 6) Fat32Formatter.putLe16(buf, offset + 14 + i * 2, chunk.getOrElse(5 + i) { NUL })
        Fat32Formatter.putLe16(buf, offset + 26, 0)
        for (i in 0 until 2) Fat32Formatter.putLe16(buf, offset + 28 + i * 2, chunk.getOrElse(11 + i) { NUL })
    }

    private fun parseLfnName(buf: ByteArray, offset: Int): String {
        val sb = StringBuilder(13)
        for (i in 0 until 5) sb.append(unit(buf, offset + 1 + i * 2))
        for (i in 0 until 6) sb.append(unit(buf, offset + 14 + i * 2))
        for (i in 0 until 2) sb.append(unit(buf, offset + 28 + i * 2))
        // unit() sonlandırıcıları boşluk karakterine çevirdiği için sondaki
        // boşluklar temizlenir.
        return sb.toString().trimEnd(' ')
    }

    private fun unit(buf: ByteArray, off: Int): Char {
        val v = (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
        return if (v == 0 || v == 0xFFFF) ' ' else v.toChar()
    }

    private fun lfnChars(name: String): List<Int> =
        name.map { if (it.code in 32..126) it.code else REPLACEMENT }

    private fun splitPath(path: String): List<String> =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }

    // ------------------------------------------------------------------ FSInfo

    private fun updateFsInfo() {
        val buf = ByteArray(512)
        device.read(partitionStartLba + 1, 512, buf)
        Fat32Formatter.putLe32(buf, 0x08, freeClusters.toLong())
        Fat32Formatter.putLe32(buf, 0x0C, nextHint.toLong())
        device.write(partitionStartLba + 1, buf)
        device.write(partitionStartLba + 7, buf)
    }

    companion object {
        const val END_OF_CHAIN = 0x0FFFFFF8
        const val FREE = 0
        private const val NUL = 0x0000
        private const val REPLACEMENT = 0xFFFD
    }
}
