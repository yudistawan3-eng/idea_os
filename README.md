# Idea OS – Android (com.ici.hris)

Pembungkus WebView untuk web app Google Apps Script HRIS "Idea Operating System".

- Kamera (selfie absen, langsung kamera depan), GPS, upload surat dokter/foto profil
- Tombol Back → riwayat halaman (Home) → keluar
- Halaman offline + tarik ke bawah untuk muat ulang
- Unduh slip gaji PDF lewat `AndroidBridge.saveFile`
- Edge-to-edge siap targetSdk 36

## Build otomatis (GitHub Actions)
Isi Secrets repo: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
(opsional `PLAY_SERVICE_ACCOUNT_JSON` untuk upload otomatis ke jalur Internal).
Jalankan: Actions → "Build AAB Play Store" → Run workflow → isi versi.
Hasil .aab ada di bagian Artifacts.

URL web app ada di `app/build.gradle` (`APP_URL`).
