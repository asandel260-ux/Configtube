# MultiKit — Android Multi-Tool App

A native, performance-first Android toolbox: media editing, image/PDF tools, network
analysis, crypto, notes & infinite mind maps, universal file viewing, screenshots and
storage cleaning — all in one app.

## Tech stack (chosen for native performance)

| Layer | Choice | Why |
|---|---|---|
| Language | **Kotlin 2.2** | First-class Android language, coroutines for heavy work off the UI thread |
| UI | **Jetpack Compose + Material 3** | Native rendering, no JS/bridge overhead (fastest option for Android-only) |
| Media engine | **FFmpeg** (`dev.ffmpegkit-maintained:ffmpeg-kit-full`, LGPL) | Industry-standard native codec/filter engine; maintained drop-in replacement for the retired FFmpegKit |
| Playback | **Media3 / ExoPlayer** | Hardware-accelerated native playback |
| Camera / QR | **CameraX + ML Kit** | On-device, fast barcode scanning |
| PDF | **PdfRenderer + PDFBox-Android** | Native rendering + embedded image/text extraction |
| Archives | **Commons Compress + XZ + junrar** | zip / 7z / tar / gz / rar |
| DNS | **dnsjava** | Full record-type queries |

- minSdk **26** (Android 8.0), target/compile SDK **36**
- AGP **8.13.2**, Gradle **8.14.3** (wrapper included), JDK **17**

## Build

1. Install **Android Studio** (latest stable) with Android SDK 36.
2. Open this folder (`MultiKit`) as a project — Gradle sync downloads everything else.
3. Run on a device/emulator (▶), or build an APK:

```bash
# Windows
gradlew.bat assembleDebug
# macOS / Linux
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

Release build (minified + resource-shrunk): `gradlew assembleRelease`
(configure your signing config in `app/build.gradle.kts` first).

> First build downloads the FFmpeg native engine (~31 MB AAR) and Compose libraries;
> subsequent builds are cached. Release APKs are large because FFmpeg ships native
> libraries for all ABIs — add an `ndk { abiFilters += listOf("arm64-v8a") }` block in
> `app/build.gradle.kts` if you only target modern phones.

## Feature map

| Tool | What it does |
|---|---|
| Video Editor | Trim, crop, rotate, speed, mute, convert (mp4/mkv/webm/gif), resize, extract frame/audio |
| Audio Editor | Trim, join, fades, volume, speed, normalize, convert (mp3/wav/m4a/ogg/flac) |
| Music Tags | Edit title/artist/album/genre/year/track, embed cover art, cut length |
| Circle Video | Video → circular (alpha WebM) video messages and back onto any background |
| Sticker Lab | Image/video → Telegram-style stickers (512px WebP / ≤3s ≤256kbps WebM), stickers → image/video |
| Image Converter | Batch convert PNG/JPEG/WebP/BMP/GIF with quality control |
| Image Resizer | Percentage/exact/preset resize, aspect lock, target file size |
| PDF Tools | Images → PDF, render PDF → images, extract embedded images, text/info extraction |
| File Viewer | Native PDF viewer (zoom), text/code editor, EPUB reader, zip/rar/7z/tar/gz browser & extractor, image/audio/video player. Registered as an "Open with" handler for these types |
| RTL Text Fixer | Fixes mixed Persian/English bidi text of any length (isolation controls, Persian normalization, digits) |
| Notes & Mind Maps | Autosaved notes + infinite pan/zoom mind-map canvas, unlimited nested expandable nodes, colors/categories, PNG export |
| QR | Live camera scanning (ML Kit) + QR generation (ZXing) |
| Network Analyzer | Ping, traceroute, concurrent port scanner, full DNS records, WHOIS, HTTP headers + TLS certificate details, public/local IP info |
| Crypto & Encoding | AES-GCM/CBC, ChaCha20, 3DES, RSA-2048, PBKDF2; MD5–SHA-512, HMAC, CRC32; Base64/Hex/URL/HTML/Unicode/binary/Morse/ROT13 + auto-detect; Caesar/Vigenère/Atbash/XOR |
| Screenshot | One-tap capture via MediaProjection + **Quick Settings tile** in the notification shade |
| Cleaner | Per-app cache/data/app sizes (StorageStatsManager), junk scan (temp/log/empty/duplicates/thumbnails/apk leftovers), selective delete |

## Permissions & platform notes

- **Usage access** (Cleaner): grant in Settings → Apps → Special access → Usage access.
  Android does not allow third-party apps to clear other apps' caches directly;
  MultiKit shows exact sizes and deep-links to the system screen where you clear them.
- **Screenshot tile**: add it via the shade's edit (pencil) button. Each capture asks for
  screen-capture consent (Android 14+ requires fresh consent per projection session).
- **Scoped storage**: outputs are saved to public **Downloads** via MediaStore; the junk
  scanner can only see storage regions Android exposes to apps.
- FFmpeg build is **LGPL** (no GPL x264); video re-encoding uses high-quality MPEG-4/VP9.

## Project layout

```
app/src/main/java/com/multikit/app/
├── MainActivity.kt, MultiKitApp.kt
├── navigation/        # Nav graph + deep links from "Open with…"
├── ui/theme|components/  # Material 3 theme + shared tool UI kit
├── util/              # Files (SAF/MediaStore/share), Ffmpeg coroutine bridge
└── feature/
    ├── home/  media/  image/  pdf/  text/  notes/
    ├── qr/  network/  crypto/  viewer/  system/
```