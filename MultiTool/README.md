# MultiTool

Native Android toolbox: **Kotlin + Jetpack Compose (Material 3)**, min SDK 29, target SDK 35.

## Why this stack
Almost every feature here is a platform API: MediaCodec/Media3 (video/audio), `PdfRenderer`, `ImageDecoder`,
`StorageStatsManager`, `TileService`, `MediaProjection`, CameraX. Kotlin + Compose calls them directly with no
bridge, which gives the best startup time, memory use and access to new OS features. Flutter/React Native would
add a rendering/bridge layer and platform channels for nearly every tool.

## Build
1. Open the `MultiTool` folder in Android Studio (Ladybug or newer), let Gradle sync.
2. Run on a device/emulator (API 29+). Release: `./gradlew assembleRelease` (R8 shrinking is on).

> This project was written without an Android SDK or network access, so **it has not been compiled or run yet**.
> Expect a few small compile fixes on first sync (dependency versions may need bumping; the pinned versions
> are older stable ones). Nothing is mocked: every screen is wired to a real implementation.

## Tools
| Area | Tool | Implementation |
|---|---|---|
| Media | Video editor (trim, rotate, resize, mute, bitrate, extract audio) | Media3 Transformer (hardware codecs, streaming) |
| | Audio editor (trim, → AAC m4a) | Media3 Transformer |
| | Music tags (title/artist/album/year/genre/track, length info) | jaudiotagger (Android fork) |
| Images | Convert PNG/JPEG/WebP, batch | `ImageDecoder` + `Bitmap.compress` |
| | Resize (percent / exact / keep ratio) | `Bitmap.createScaledBitmap` (filtered) |
| | Telegram sticker (static) | 512 px WebP, auto-reduces quality to fit 512 KB |
| | Telegram sticker (video/GIF) | needs an FFmpeg runner, see below |
| Documents | Persian/English fixer | Unicode bidi isolates (RLI/LRI/PDI), linear time, no length limit |
| | PDF → images, Images → PDF | PdfBox-Android, `PdfDocument` |
| | File viewer/editor (PDF, text, ZIP/RAR/7z) | `PdfRenderer`, commons-compress, junrar; registered for "Open with" |
| Security | QR scan + generate | CameraX + ML Kit (on-device), ZXing |
| | Network: ping, route, ports, DNS, WHOIS, HTTP headers, TLS cert, IP owner (RDAP) | system `ping`, sockets, dnsjava |
| | Encrypt/encode: Base64/32, Hex, Binary, URL, ROT13, MD5/SHA-1/2/3, HMAC, AES-256-GCM/CBC, ChaCha20-Poly1305, Caesar, Vigenère | JCA; PBKDF2 210k + random salt/IV |
| Notes | Notes (autosave) | Room |
| | Mind maps: unlimited depth, pan/zoom, collapse/expand, focus into any node | Room tree + Compose canvas |
| System | Screenshot Quick Settings tile | TileService + MediaProjection |
| | Cleaner: per-app cache/data/app sizes, junk scan (temp, logs, thumbnails, APKs, empty folders, large files, duplicates) | StorageStatsManager, file walk |

## Known limits (OS rules, not bugs)
- **Cleaner:** Android does not allow normal apps to clear *other apps'* caches. The app shows the sizes and opens each
  app's system storage page; it can clear its own cache and delete junk files in shared storage. True one-tap
  clear-all needs Shizuku/root.
- **Screenshot tile:** Android 14+ requires the screen-capture consent dialog for every capture.
- **Video stickers** need VP9 WebM encoding; phones rarely have a hardware VP9 encoder. Plug a maintained FFmpeg
  build into `FfmpegProvider.runner` (`image/StickerScreen.kt`); the exact FFmpeg arguments for the Telegram spec
  are already in `StickerEngine.videoArgs`.
- `MANAGE_EXTERNAL_STORAGE` and `QUERY_ALL_PACKAGES` need a declaration form on Google Play (fine for sideloading).
- PDF image extraction saves decoded PNGs (vector pages have no images to extract).
- EPUB opens as an archive listing; a full reflowable reader is not included.

## Adding a feature
Create a `@Composable fun MyScreen(onBack: () -> Unit)` and add one line to `ToolRegistry.kt`.
