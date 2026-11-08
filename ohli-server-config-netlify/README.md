# OHLI Server Config (Netlify)

Folder ini adalah tempat menyimpan **alamat server OHLI**, bukan server/backend OHLI itu sendiri.

## Isi file

`server.json`:

```json
{
  "server_url": "https://information-nsw-rug-gallery.trycloudflare.com/"
}
```

Saat Cloudflare Quick Tunnel berubah, cukup edit `server_url` di file tersebut dan publikasikan ulang folder ini ke situs Netlify yang sama. Nama/alamat situs Netlify harus tetap agar APK selalu membaca endpoint konfigurasi yang sama.

## Cara publikasi pertama kali

1. Login ke Netlify.
2. Buka https://app.netlify.com/drop.
3. Upload folder ini (folder yang berisi `server.json`, `_headers`, dan `index.html`).
4. Setelah situs dibuat, catat alamat situs Netlify, misalnya `https://nama-situs-kamu.netlify.app`.
5. Pastikan file konfigurasi dapat dibuka di `https://nama-situs-kamu.netlify.app/server.json` dan menampilkan JSON.
6. Buka proyek Android OHLI, edit `app/build.gradle` satu kali pada `SERVER_CONFIG_URL` menjadi alamat langkah 5.
7. Build APK satu kali lagi melalui GitHub Actions.

## Cara mengganti alamat Quick Tunnel berikutnya

1. Edit hanya `server.json`.
2. Ganti nilai `server_url` dengan alamat Quick Tunnel HTTPS terbaru.
3. Publikasikan ulang folder yang sama ke situs Netlify yang sama (production deploy).
4. Buka ulang APK OHLI. APK mengambil konfigurasi saat aplikasi dimulai.

Tidak perlu build APK ulang untuk pergantian `server_url` setelah URL Netlify konfigurasi sudah dipasang ke APK.

## Catatan penting

- Situs Netlify ini hanya menyimpan file konfigurasi kecil. Backend, database, upload, chat, dan WebRTC tetap berjalan pada server OHLI milikmu.
- Tunnel Cloudflare harus aktif agar alamat yang ditulis di `server.json` bisa digunakan.
- APK menggunakan timeout singkat untuk mengambil konfigurasi. Bila endpoint konfigurasi tidak dapat dijangkau, APK memakai alamat terakhir yang tersimpan di perangkat, lalu memakai alamat fallback dari build.
- Karena hostname Quick Tunnel berubah, cookie dan local storage browser dapat dianggap origin baru. Dalam kondisi itu, pengguna mungkin perlu login kembali, tergantung sistem login website OHLI.
- Jangan menyimpan password, token rahasia, API key, atau data pribadi di `server.json`. File ini bersifat publik.
