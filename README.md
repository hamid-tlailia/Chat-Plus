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

## Privacy & power features (0.9)

- **Stealth mode** — hide read receipts, "typing…", online / last seen; or go invisible to one
  contact (ghost mode). Fair play: what you hide, you don't see. Last seen travels end-to-end
  encrypted per contact; the server no longer stores it.
- **Disappearing messages** — per chat: after reading, 1 h, 24 h or 7 days. Expired messages are
  crypto-shredded on both phones; screenshots are blocked while such a chat is open.
- **Locked chats** — fingerprint / face / phone PIN to open; hidden behind "Locked chats";
  notifications never show who or what.
- **Media & files vault** — every stored photo, video, audio and file; filter, sort by size or
  date, wipe in bulk.
- **Sounds & vibration** — message tone, ringtone and vibration pattern globally or per chat.
- **Translation** — any message, on the phone (ML Kit; each language model downloads once).
- **Theme Studio** — colours, bubble roundness, your own font and chat wallpaper; share as a
  `.zuratheme` file that friends apply with one tap.
- **Screen sharing** in voice and video calls.
- **Walkie-talkie** — hold to talk live when the other person is in the app (8 kHz μ-law chunks,
  sealed E2EE over realtime); otherwise the same gesture sends a voice note.

## Enable push (calls & messages while the app is closed)

1. [Firebase console](https://console.firebase.google.com) → **Add project** → add an **Android app** with package name `app.zura`.
2. Download **google-services.json** and put it at `app/google-services.json`, then rebuild. The build reads it directly; no plugin is needed.
3. Firebase → Project settings → **Service accounts** → **Generate new private key** (a JSON file).
4. Supabase → project **zura** → Edge Functions → **Secrets** → add `FCM_SERVICE_ACCOUNT` = the whole JSON content.

The service-account JSON is a server secret: put it only in Supabase secrets, never in the app or the repo.
