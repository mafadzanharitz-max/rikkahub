# CIPHER 🔐 — RikkaHub ARMv7 Engineering Playbook

**Versi:** 1.1  
**Repository:** `mafadzanharitz-max/rikkahub`  
**Target:** Android ARMv7 (`armeabi-v7a`), minimum Android 8.1 / API 27  
**Branch kerja ARMv7:** `hardening/rootfs-transactional-install`

Dokumen ini adalah pedoman operasional engineering untuk pekerjaan RikkaHub ARMv7. Dokumen ini bukan bukti bahwa suatu fitur, build, atau pengujian telah berhasil; semua klaim keberhasilan wajib didukung bukti aktual.

---

## 1. Prinsip utama

1. Utamakan stabilitas, kompatibilitas Android API 27, dan ABI ARMv7.
2. Pertahankan application ID, signing certificate, mekanisme update, serta konfigurasi sensitif.
3. Jangan mengubah ruang lingkup tanpa kebutuhan teknis yang terbukti.
4. Jangan mengulang pekerjaan atau pengujian yang sudah memiliki bukti valid tanpa alasan.
5. Pisahkan **FAKTA**, **INFERENSI**, dan **BELUM DIKETAHUI** dalam laporan.
6. Keberhasilan CI bukan pengganti validasi perilaku pada perangkat sasaran.
7. Setelah kriteria tahap terpenuhi, tutup tahap dan bekukan ruang lingkup. Jangan menambah hardening atau fitur tanpa temuan baru yang material.

## 2. Perlindungan perangkat pengguna

- Pengguna menggunakan perangkat Samsung Android 8.1 dan tidak memiliki PC/laptop.
- Dilarang meminta pengguna menjalankan build, compile, instalasi dependensi, pengujian berat, atau perintah berat melalui Termux.
- Jangan membebani perangkat dengan proses yang berisiko menyebabkan panas berlebih, macet, atau mati.
- Prioritaskan GitHub, GitHub Actions, dan lingkungan jarak jauh.
- Validasi perangkat harus ringan, terarah, dan dilakukan melalui aplikasi RikkaHub bila memungkinkan.
- Jangan menganggap izin mengelola repository sebagai izin membebani perangkat pengguna.

## 3. Prosedur sebelum perubahan

Sebelum menyentuh repository:

1. Baca playbook ini pada sesi kerja baru atau sesi yang dilanjutkan.
2. Verifikasi repository, branch aktif, HEAD SHA, status workflow terbaru, dan file yang akan disentuh.
3. Periksa perubahan yang sudah ada; jangan menimpa pekerjaan yang belum dipahami.
4. Baca pemanggil, dependensi, efek samping, dan pengujian yang berkaitan dengan masalah.
5. Telusuri sumber primer/upstream bila perilaku API, toolchain, atau dependency belum pasti.
6. Catat hipotesis dan bukti yang dapat membantahnya sebelum membuat patch.

Jika file ini tidak tersedia pada branch yang sedang dikerjakan, laporkan kondisi tersebut. Jangan mengklaim telah membacanya.

## 4. Baseline dan kompatibilitas

- Build #93 adalah baseline keberhasilan historis yang harus dipertimbangkan ketika menyelidiki regresi.
- Build #197, #198, dan #199 adalah bukti build yang lebih baru; masing-masing hanya membuktikan pemeriksaan yang benar-benar dijalankan pada run tersebut.
- Jangan menyimpulkan kesetaraan perilaku hanya dari nomor build atau status hijau.
- Target ABI utama: `armeabi-v7a`; verifikasi native library sebagai ELF32, little-endian, ARM (`EM_ARM`) dan atribut CPU ARMv7 yang diharapkan.
- Minimum Android: API 27. Periksa pemanggilan API baru, manifest, foreground service, permission, dan SDK guard.
- Pertahankan package/application ID dan jalur instalasi-update yang sudah digunakan.
- Jangan mengubah minSdk, ABI filter, versi toolchain, dependency, atau overlay tanpa alasan dan pemeriksaan dampaknya.

## 5. RootFS dan workspace

Untuk perubahan RootFS, periksa seluruh alur berikut sesuai relevansinya:

- Unduh dan validasi sumber serta integritas arsip.
- Batas ukuran, path traversal, symlink, dan ekstraksi arsip.
- Instalasi transaksional, backup, aktivasi, rollback, dan pemulihan setelah interupsi.
- Locking dan concurrency antar instalasi, cleanup, pemeriksaan integritas, serta terminal.
- Urutan recovery sebelum terminal atau operasi lain menggunakan RootFS.
- Lifecycle proses terminal dan operasi cleanup yang dapat berjalan ketika proses masih aktif.
- Kegagalan disk penuh, proses dibatalkan, aplikasi ditutup, dan proses dihentikan sistem.

Jangan menyatakan crash recovery telah terbukti hanya dari simulasi direktori sementara. Bedakan unit test, simulasi, dan uji interupsi pada RootFS produksi.

## 6. Keamanan dan integritas

Jika relevan terhadap perubahan, audit:

- HTTP cleartext, HTTPS, redirect, downgrade, dan pengecualian localhost/provider.
- Validasi input eksternal, intent/deep link, autentikasi, dan otorisasi.
- Redaksi log dan perlindungan data sensitif.
- Hash/checksum dependency, pin versi/action, dan integritas artefak.
- ELF/native dependency serta ABI yang benar.
- Penyimpanan, permission, concurrency, dan lifecycle Android.

Jangan menyebut sistem aman secara menyeluruh berdasarkan inspeksi parsial. Laporkan batas audit secara eksplisit.

## 7. Signing dan identitas rilis

- Jangan mengubah keystore, alias, password, secrets, certificate, atau proses signing tanpa alasan dan otorisasi eksplisit.
- Jangan mencetak atau membocorkan material signing maupun secret.
- Verifikasi APK dengan `apksigner`; periksa skema signature dan fingerprint sertifikat permanen yang diharapkan.
- Pastikan application ID dan varian APK sesuai target.
- Jangan menyamakan APK yang berhasil dibuat dengan APK yang telah lolos verifikasi signature.

## 8. CI dan GitHub Actions

- Build rutin hanya **Release APK**. Debug hanya untuk investigasi bug serius dengan alasan yang jelas.
- Sebelum menjalankan workflow, periksa apakah bukti yang dibutuhkan sudah tersedia dari run sebelumnya.
- Jangan rerun workflow berulang tanpa membaca log lengkap dan memahami penyebab kegagalan.
- Pilih retry job yang gagal saja bila aman dan didukung; jangan mengulang seluruh build tanpa kebutuhan.
- Periksa commit SHA, branch, workflow run ID, job, seluruh langkah penting, dan artifact.
- Pastikan tidak ada perubahan signing, package identity, atau konfigurasi sensitif yang tidak direncanakan.
- Perhatikan kuota Actions storage/artifact dan masa retensi.
- Jangan mengklaim build berhasil sebelum status workflow dan hasil verifikasi artifact tersedia.
- Dokumentasi saja tidak memerlukan build; hindari CI yang tidak memberi nilai verifikasi.

## 9. Prosedur investigasi kegagalan

Untuk setiap kegagalan:

1. Simpan run ID, commit SHA, branch, job, dan langkah yang gagal.
2. Baca log lengkap di sekitar kegagalan, bukan hanya ringkasan merah.
3. Tentukan apakah penyebab berasal dari source, patch/overlay, dependency, toolchain, environment, signing, atau layanan eksternal.
4. Bandingkan dengan build terakhir yang berhasil dan baseline relevan.
5. Reproduksi secara terarah pada lingkungan jarak jauh bila diperlukan.
6. Buat perubahan sekecil mungkin yang menyelesaikan akar masalah.
7. Periksa diff dan efek samping sebelum commit.
8. Jalankan satu validasi yang paling relevan; hindari build tambahan yang tidak diperlukan.
9. Catat penyebab, perbaikan, bukti, dan pencegahan regresi.

Jangan menutupi warning atau kegagalan nonfatal. Klasifikasikan berdasarkan dampak dan relevansinya.

## 10. Release gate dan penutupan tahap

Sebelum menyatakan Release kandidat siap:

- [ ] Commit dan branch yang dibangun telah diverifikasi.
- [ ] Workflow Release selesai dengan status sukses.
- [ ] Unit test dan pemeriksaan wajib pada workflow sukses.
- [ ] APK tunggal yang diharapkan tersedia sebagai artifact dan belum kedaluwarsa.
- [ ] Native library lolos validasi ABI ARMv7.
- [ ] Signature dan fingerprint sertifikat permanen cocok.
- [ ] Package identity dan konfigurasi kompatibilitas tidak berubah tanpa persetujuan.
- [ ] Risiko yang belum diuji dicatat; CI tidak diklaim sebagai uji perangkat.
- [ ] Validasi ringan perangkat dilakukan bila diperlukan untuk kriteria tahap.

Tahap hanya boleh dinyatakan selesai jika seluruh kriteria tahap yang disepakati terpenuhi atau pengecualian yang tersisa dinyatakan secara eksplisit dan diterima. Setelah itu bekukan ruang lingkup. Temuan baru harus memiliki bukti, tingkat dampak, dan alasan untuk membuka kembali tahap.

## 11. Format checkpoint

Setiap checkpoint harus memuat:

1. **Kondisi terverifikasi** — branch, HEAD, workflow, artifact, dan hasil pemeriksaan.
2. **Masalah/risiko** — fakta dan hal yang belum diketahui.
3. **Perubahan** — file dan ringkasan diff; atau nyatakan tidak ada perubahan.
4. **Bukti** — commit SHA, workflow run, test, artifact, dan tautan jika tersedia.
5. **Langkah berikutnya** — tindakan minimum yang diperlukan.

Gunakan bahasa Indonesia untuk laporan dan instruksi. Jangan mengarang file, fungsi, class, commit, branch, versi, log, hasil build, atau hasil tes.
