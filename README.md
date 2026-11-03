# Zura

A fast, lightweight native Android messenger (Kotlin + Jetpack Compose). Chats and media are stored only on the device, encrypted and crypto-shredded on delete. It supports Arabic and English with full RTL, four themes, and a system-wide share target.

- Accounts on Supabase: sign-up with photo, saved accounts on the device (one-tap or password-only sign-in), invitations (send / accept / decline / cancel), contacts, blocking
- **End-to-end encrypted** messages and call signalling; the server only relays opaque envelopes and deletes them on delivery
- Voice & video calls (WebRTC + Cloudflare TURN) with live network-quality indicator, automatic reconnection and adaptive video
- ≈8 MB download per device (arm64), of which ≈11.5 MB installed is the WebRTC engine
- Encrypted, file-based, append-only storage with no SQLite. Deleting a message or chat destroys its Android Keystore key, so it cannot be recovered.
- Text, voice notes with a live waveform, photos and video compressed on the hardware encoder, files, and 24 h Moments
- Edit, delete for me / for everyone, reactions, download, reply, and live read receipts and typing indicators
- Direct Share targets in the Android sharesheet

See **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** for the full blueprint: stack rationale, storage and deletion design, module map, protocol, and roadmap.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```
