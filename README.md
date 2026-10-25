# Philips Kumanda (IR)

Telefonun kızılötesi (IR) vericisiyle Philips TV'yi kontrol eden Android uygulaması. Eşleştirme ve Wi-Fi gerekmez.

## APK'yı GitHub'da derleme
1. Bu klasörün içindekileri (`.github` klasörü dahil) yeni bir GitHub deposuna yükle.
2. Depoda **Actions** sekmesi → **APK derle** → bitince en alttaki **PhilipsKumanda-APK** dosyasını indir.
3. İndirdiğin zip'in içindeki `app-debug.apk`'yı telefona kur (bilinmeyen kaynaklara izin ver).

## IR kodlarını düzeltme
Kodlar `MainActivity.kt` içindeki `CODES` listesindedir (Philips RC-6, adres 0). Bir tuş TV'de tepki vermezse sadece o satırdaki sayıyı değiştir.
