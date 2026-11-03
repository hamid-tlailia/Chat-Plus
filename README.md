# Zura

A fast, lightweight native Android messenger (Kotlin + Jetpack Compose). Chats and media are stored only on the device, encrypted and crypto-shredded on delete. It supports Arabic and English with full RTL, four themes, and a system-wide share target.

- **2.0 MB** release APK (minified, all features included)
- Encrypted, file-based, append-only storage with no SQLite. Deleting a message or chat destroys its Android Keystore key, so it cannot be recovered.
- Text, voice notes with a live waveform, photos and video compressed on the hardware encoder, files, and 24 h Moments
- Edit, delete for me / for everyone, reactions, download, reply, and live read receipts and typing indicators
- Direct Share targets in the Android sharesheet

See **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** for the full blueprint: stack rationale, storage and deletion design, module map, protocol, and roadmap.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```
