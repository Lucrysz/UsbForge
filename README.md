# UsbForge

Android üzerinde **USB bellek biçimlendirme**, **ham ISO/IMG yazma** ve
**Ventoy kurulumu** yapan açık kaynak uygulama.

Kotlin + Jetpack Compose, Min SDK 24, Target SDK 34.

---

## Mimari: iki modül, iki test düzlemi

```
:core   Saf JVM. android.* bağımlılığı YOK.
        partition/  MBR, GPT (CRC-32 + GUID), bölüntü planları
        fs/         FAT32, exFAT, NTFS biçimlendirici + FAT32 yazıcı
        disk/       DiskWriter (bölüntü + biçimlendirme), IsoWriter (dd)
        usb/        SCSI SBC-3 komut kodu, CBW/CSW (Bulk-Only Transport)
        block/      BlockDevice arayüzü, PcBlockDevice (masaüstü)
        ventoy/     Ventoy yerleşimi, MBR, kurulum
        engine/     ProgressReporter, JobSnapshot (saf veri)

:app    Android katmanı. Yalnızca burada android.* var.
        usb/        UsbMassStorageController (BOT + SCSI), izin yönetimi
        block/      ScsiBlockDevice, RootBlockDevice (kalıcı su kabuğu)
        engine/     JobEngine (tek eş zamanlı iş, kayan hız ölçer)
        vm/         MainViewModel
        ui/         Compose ekranı (durumsuz bileşenler)
        job/        Ön plan servisi (dataSync)
```

Bu ayrım sayesinde mantığın tamamı **masaüstünde, cihaz olmadan**
test edilir. Arayüz de Robolectric sayesinde emülatör olmadan test edilir.

---

## Testler

```bash
# Çekirdek mantık — 33 test, ~1 saniye, SDK gerekmez
./gradlew :core:test

# Arayüz — 30 test, ~13 saniye, emülatör/cihaz gerekmez
./gradlew :app:testDebugUnitTest

# İkisi birden
./gradlew test

# HTML rapor
#   core/build/reports/tests/test/index.html
#   app/build/reports/tests/testDebugUnitTest/index.html
```

### Ne doğrulanıyor

**Çekirdek (`FakeBlockDevice` üzerinde, bayt seviyesinde):**
- MBR: 55AA imzası, bölüntü girdisi, CHS↔LBA sınırı, koruyucu MBR
- GPT: `EFI PART` imzası, **başlık CRC'si**, **giriş dizisi CRC'si**,
  son kullanılabilir LBA, yedek başlık
- CRC-32: bilinen vektör `123456789 → 0xCBF43926`
- FAT32: BPB alanları, önyükleme kodu, FSInfo imzaları, yedek önyükleme
  (6. sektör), kök dizin zinciri, FAT giriş ofsetleri, LFN + 8.3 dosya
  yazımı, **yazılan verinin diske birebir yerleşmesi**
- exFAT: `EXFAT   ` imzası, 12+12 önyükleme sektörü, kesintisiz bölge
  yerleşimi, FAT giriş değerleri
- NTFS: önyükleme kaydı, MFT kayıtları 0–15, **fixup dizisi**, küme
  bitmap'i, `$UpCase` eşlemesi, mapping-pairs kodlaması
- ISO yazıcı: hizalama, kalan alanın sıfırlanması, iptal davranışı,
  cihazdan büyük kaynak reddi
- Ventoy: uçtan uca kurulum (GPT + VTOYEFI + veri bölümü + MBR),
  ZIP çıkarma, resmî MBR kullanımı

**Arayüz (Robolectric + Compose):**
- Ekran başlığı, cihaz listesi, boş durum
- İki reklam banner konteyneri ve görünürlüklerinin kapatılması
- İşlem / dosya sistemi / bölüntü tablosu seçimi
- Başlat butonunun etkin/pasif durumu, tıklama zinciri
- İlerleme yüzdesi, hız metni, ETA, hata metni, günlük satırları
- ISO modunda dosya seçici + doğrulama anahtarı
- Yıkıcı uyarının yalnızca cihaz seçiliyken görünmesi

---

## Derleme

### Gereksinimler
- **JDK 17** (AGP 8.9 + Kotlin 2.1 derlemesi JDK 17 gerektirir; JDK 25'te
  Kotlin derleyicisi sürüm dizesini çözemiyor)
- Android SDK: platform 34, build-tools 34
- `local.properties` içinde `sdk.dir`

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
```

---

## Donanım notları (dürüstlük)

### Ham yazma yetkisi
Android'de bir USB belleğe iki yolla erişilir:

| Yöntem | Gereken | Güvenilirlik |
|---|---|---|
| USB Host API + SCSI (uyguladığımız) | USB izni (otomatik açılır) | Donanıma bağlı |
| `su` + `/dev/block` | Root | Yüksek |

**Bazı flash denetleyicileri işletim sistemi dışından ham `WRITE(10)`
komutunu reddeder** (`DATA PROTECT / ASC 0x27`). Bunun sebebi üreticiye
özgü bir "kilidi açma" komutudur. Bu uygulama **tüm standart SCSI
adımlarını** yürütür (MODE SENSE/SELECT, START STOP UNIT, SYNCHRONIZE
CACHE, TEST UNIT READY) ancak üreticiye özgü vendor opcode **göndermez**:
yanlış bir komut denetleyiciyi kalıcı olarak kilitleyebilir (brick).
Bu durumda `WriteProtectedException` fırlatılır ve arayüzde açık bir
mesaj gösterilir.

### Ventoy önyükleme kaydı hakkında
Ventoy'un `EFI/BOOT/BOOTX64.EFI` ve `ventoy/` dosyaları **derlenmiş x86
ikili dosyalarıdır**; kaynak koddan üretilemezler. Bu yüzden:

- **UEFI önyükleme** çalışır: kurulum, resmî dağıtımdan sağlanan
  dosyaları `VTOYEFI` bölümüne yazar. UEFI firmware'i MBR'e değil, bu
  bölüme bakar.
- **Legacy (CSM) önyükleme** için resmî `ventoy.mbr` gerekir. Paketlenirse
  (`assets/ventoy.mbr`) birebir kullanılır.
- Paketlenmezse **uydurma bir bootloader üretilmez**. Üretilen kayıt, disk
  önyüklenemez olduğunu BIOS'a bildiren gerçek bir yordamdır
  (`int 10h/AH=09h`). Çalışmayan bir bootloader'ın "çalışıyor" gibi
  sunulması, kullanıcının belleğini önyüklenebilir sanmasına yol açardı.

### exFAT'in minimum küme sayısı
exFAT şartnamesi en az 1024 küme ister. 32 MiB'lık bir VTOYEFI bölümü
en küçük geçerli küme boyutuyla (64 KiB) yalnızca 512 küme sunduğu
için exFAT **geçersizdir**. Bu yüzden VTOYEFI FAT32 olarak biçimlendirilir.

### NTFS ve flash
NTFS, flash aşınma dengelemesini (wear levelling) engeller ve TRIM'i
kullanmaz. USB belleklerde **exFAT önerilir**. NTFS desteği, Windows
uyumluluğu gereken durumlar içindir.

---

## Güvenlik

- `su` üzerinden yalnızca `^/dev/block/[A-Za-z0-9/._-]+$` desenine uyan
  yollara erişilir; kullanıcıdan gelen serbest metin dosyaya
  yönlendirilemez.
- Tüm yazma işleri `Dispatchers.IO` üzerinde, sabit blok boyutlu
  (1 MiB) tamponlarla çalışır; bellek kullanımı dosya boyutundan
  bağımsızdır.
- İlerleme hesabı kayan 3 saniyelik pencere ile yapılır; USB bağlantısındaki
  hız salınımları yumuşatılır.
- Her iş sonunda `SYNCHRONIZE CACHE` (SCSI) ve `fsync` (root) çağrılır.

---

## Lisans

MIT
