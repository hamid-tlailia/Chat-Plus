# Zura — Architecture Blueprint

Zura is a native Android messenger built for speed, a small footprint and privacy on the device.
This document covers the design decisions. The code in `app/` implements them.

---

## 1. Tech stack & performance justification

| Layer | Choice | Why |
|---|---|---|
| Language / UI | **Kotlin 2.0 + Jetpack Compose (Material3 only for dialogs)** | Native rendering through RenderThread / HWUI with no bridge and no second runtime. Flutter adds a 4–5 MB engine plus Skia/Impeller; React Native adds Hermes plus a JS bridge. Compose with strong skipping, plus R8 and a Baseline Profile, gives 120 Hz scrolling on the platform's own pipeline. |
| DI | **Hand-written `AppGraph`, everything `lazy`** | Hilt/Dagger add generated factories and slow startup. Cold start builds only settings and the chat list. Keystore, ExoPlayer and media codecs start on first use. |
| Navigation | **About 30-line `Navigator` + `AnimatedContent`** | No navigation-compose and no route serialization. Transitions are springs and mirror automatically in RTL. |
| Persistence | **Custom encrypted append-only logs (no SQLite/Room)** | See §2. A chat opens with one sequential read. Nothing is stored in plaintext. |
| Crypto | **AES-256-GCM via JCA + Android Keystore (TEE/StrongBox)** | No Tink or SQLCipher dependency, so roughly 3 MB smaller. Key-encryption keys never leave secure hardware. |
| Network | **One OkHttp WebSocket, multiplexed** | Bidirectional and below 100 ms on a warm socket. Backoff uses jitter. The socket is closed in the background (FCM wakes the app). |
| Media | **Media3 ExoPlayer + Transformer (hardware MediaCodec)**, `ImageDecoder` | Video transcoding runs on the hardware encoder with almost no CPU. Photos are subsampled during decode, so a 50 MP image never needs 200 MB. |
| Images | **`VaultImages` (custom, about 60 lines)** instead of Coil/Glide | The vault already acts as the disk cache. Images are kept in a RAM LRU sized to 1/8 of the heap. This saves about 600 KB. |
| Icons / fonts | material-icons-core + 10 hand-drawn vectors; system fonts | `icons-extended` is about 10 MB before R8, and no font bytes ship in the APK. |

**Measured:** the release APK is **2.0 MB** (`./gradlew assembleRelease`, R8 + resource shrinking, locales limited to `en`/`ar`).

**Jank budget rules used in the code**
- Animations change draw-layer values only (`graphicsLayer` scale, alpha and translation), so they never trigger relayout. Swipe-to-reply, press scale, the moments cube and dismiss gestures all follow this rule.
- Grouping and day separators are computed once per change in `ChatViewModel`, off the UI thread. The `LazyColumn` uses `reverseLayout`, stable keys and `contentType` per message kind, so composition can be reused.
- Glass effects use a translucent fill plus a lit hairline. They are **not** a live backdrop blur, because RenderEffect blur over a scrolling list costs 2–4 ms per frame and breaks the 8.3 ms budget at 120 Hz.
- Every image message carries an inline **micro-preview of about 1 KB**, so the first frame paints with no disk I/O and no layout jump. The full image then cross-fades in.
- All motion uses springs (`ZuraMotion`). Springs keep their velocity when interrupted.

---

## 2. Local storage & secure deletion

### Sandbox layout
```
files/zura/chats/<chatId>/
    log.zl            line 0:  K1:<gen>:<wrapped chat key>
                      line 1…: base64(AES-GCM(chatKey, json(Record), aad=chatId))
    media/<id>.zv     segmented AES-GCM ciphertext, one random key per file
files/zura/chats/moments/   Moments: same engine, purged after 24 h
no_backup/tmp/              plaintext scratch (recorder/transcoder output), erased after use and at boot
cache/export/               decrypted copies explicitly shared out, erased at boot
```
Cloud backup and device-transfer extraction are disabled (`data_extraction_rules.xml`, `allowBackup=false`).

### Key hierarchy
```
Android Keystore KEK  "zc_<chatId>_<gen>"   (hardware, deletable)
   └─ wraps → chat key (256-bit, stored in log header)
         └─ seals → every log record
               └─ contains → per-file media key  → media/<id>.zv
```

### Write paths (`ChatStore`)
- **Send, receive, status change, reaction:** append one sealed line. O(1).
- **Edit or delete (purge):**
  1. Rewrite the log without the old content, under a **new chat key** wrapped by a **new Keystore KEK** (generation + 1). The key lives in the log header, so key and log swap in **one atomic rename** (`writeAtomically`: temp file, fsync, rename).
  2. **Destroy the old KEK inside Keystore.**
  3. Zero-fill and unlink the media files that were removed.
- **Compaction:** the log is rewritten when it has more than twice as many lines as live records.

### Why this is "permanent" on flash
Overwriting files multiple times does not erase data on eMMC/UFS. The flash translation layer remaps writes, so old pages survive until garbage collection. Zura relies on **crypto-shredding** instead:
- Old copies of the log on flash are encrypted under a chat key.
- That chat key was wrapped by a KEK that no longer exists in Keystore.
- Media keys existed only inside those records.

Destroying the KEK therefore turns every stale copy into noise. **Deleting a chat** (`VaultStore.deleteChat`) destroys its KEK, then zeroes and unlinks the log, then unlinks all media.

### Crash safety
- A torn final append fails GCM authentication and is skipped.
- If the app crashes between "rewrite log" and "unlink media", the media file is left without a key. `StorageJanitor` removes it at the next start (4 s after launch, never on the startup path).
- The janitor also destroys Keystore aliases that belong to no live generation. It never touches the keys of a chat it failed to read.

### Media format (`SegmentedCipher`)
Tink-style streaming AEAD:
- Layout: `ZRA1 | prefix(7) | seg0 | seg1 | …`. Each segment is 64 KiB, sealed with nonce `prefix ‖ index ‖ isLastFlag`.
- Memory use is O(64 KiB) regardless of file size. Platform GCM, by contrast, buffers the whole ciphertext.
- Seeking is O(1). ExoPlayer streams video from ciphertext through `EncryptedDataSource`, and no decrypted file touches disk.
- Truncation and reordering are detected.
- Files are uploaded to the relay **in vault format**. Recipients store them as they arrive, with no re-encryption.

### Test coverage (JVM, `app/src/test`)
`StorageEngineTest` covers:
- cipher round-trip and random access at segment boundaries
- truncation detection
- persistence across a simulated process restart, with no plaintext on disk
- purge: key rotation, the old KEK destroyed, the media file removed
- edit
- chat deletion
- the janitor's orphan sweep

---

## 3. Core modules (where to find them)

| Requirement | Module |
|---|---|
| Local file storage manager | `core/storage/{VaultStore,ChatStore,SecureEraser,StorageJanitor,ZuraPaths}.kt`, `core/crypto/*` |
| Real-time sync, receipts, typing | `core/sync/{Protocol,RelayClient,BlobClient}.kt`, `data/ChatRepository.kt` |
| Chat UI | `ui/chat/{ChatScreen,MessageBubble,Composer,ActionOverlay,Waveform,ChatViewModel}.kt` |
| Voice notes | `media/VoiceRecorder.kt` (Opus 24 kbps, 25 fps amplitude), `media/MediaPlayback.kt`, `ui/chat/Waveform.kt` |
| Photo / video | `media/{ImageCompressor,VideoCompressor,VaultImages,Previews,EncryptedDataSource}.kt`, `ui/media/MediaViewer.kt` |
| Moments | `data/MomentsRepository.kt`, `ui/moments/{MomentsRow,MomentViewer}.kt` |
| Share target | `share/ShareReceiverActivity.kt`, `share/DirectShare.kt`, `res/xml/share_targets.xml`, manifest |
| Bilingual engine | `i18n/{LocaleEngine,ZuraFormat}.kt`, `res/values{,-ar}/strings.xml` |
| Theme engine | `ui/theme/ZuraTheme.kt` (4 palettes, 450 ms animated cross-fade), `ui/components/{Glass,Motion,Haptics}.kt` |

### Sync protocol (`Frame`, JSON over WebSocket, `"t"` discriminator)
`hello · msg · ack · rcpt(DELIVERED|READ) · typing · edit · del · react · presence · moment · ping`

- Writes are **local-first**: the message is durable and on screen within one frame. It is then sent, and `PENDING` messages are resent on reconnect.
- Ticks move PENDING → SENT (relay `ack`) → DELIVERED → READ (`rcpt`). Status only ever moves forward.
- Typing is throttled to one frame per 3 s, and receivers expire it after 6 s, so a lost "stop" never leaves a stale indicator.
- Delete-for-everyone sends `del`. Each device then runs the same crypto-shred purge. Peers can only erase their own messages.

### Share target
- Accepts `SEND` and `SEND_MULTIPLE` for text, image, video, audio and any file.
- Direct Share shortcuts (the four most recent chats) appear in the first row of the system sharesheet.
- Received URIs are copied into the private tmp directory **before** the activity finishes, because the sender's grant dies with the activity. They are then compressed and encrypted in the background, and the tmp copy is erased.

### Localization
- API 33+ uses the platform per-app language (`LocaleManager`, with `generateLocaleConfig`), so Zura also appears in system Settings → App languages.
- API 26–32 use a wrapped `attachBaseContext`.
- Layout direction comes from the locale. All padding uses start/end, gestures and pager taps mirror, and icons use `autoMirror`.
- Bubble text uses `TextDirection.Content`, so an Arabic message in an English UI still aligns correctly.
- Dates use `java.time` with `DecimalStyle.of(locale)`, giving Arabic-Indic digits for `ar` and Latin digits for Maghreb locales.
- Arabic line height is 1.45× to avoid Naskh clipping.

---

## 4. Roadmap

| Phase | Weeks | Deliverables |
|---|---|---|
| **0. Foundation** ✅ | — | Storage engine + tests, protocol, all screens, share target, i18n, themes (this repo) |
| **1. Relay server** | 1–2 | Stateless relay (Go or Kotlin/Ktor) on Fly.io / Cloud Run: WebSocket fan-out, in-memory queue with Redis spill-over, frames dropped after all recipients ACK, blob store on R2/S3 with a 30-day TTL and delete-on-fetch-by-all. Phone/OTP auth that issues a short-lived token used in `hello`. |
| **2. End-to-end encryption** | 2–4 | Wrap each `Frame.Msg` envelope in an MLS (RFC 9420) or Signal double-ratchet session (libsignal). Identity keys are stored in Keystore, and a safety-number QR verifies them. After this the relay sees only routing metadata. |
| **3. Push & background** | 1 | FCM high-priority *data* messages carry no content, only "wake". On wake: connect, drain, notify (MessagingStyle with inline reply), then disconnect. |
| **4. Performance hardening** | 1 | Macrobenchmark module + Baseline Profile generation for cold start, chat open and scrolling. Targets: cold start < 350 ms on a Pixel 6a, 0 % janky frames at 120 Hz, idle RAM < 60 MB. Add a meta-snapshot cache so the chat list does not replay every log on start. |
| **5. Polish** | 1–2 | Shared-element transitions from bubble to viewer, a contacts / QR invite flow, message search (in-memory over decrypted chats), a voice note "lock" mode, app lock with BiometricPrompt + a Keystore key that requires authentication. |
| **6. Release** | 1 | Play App Signing, an AAB with ABI/density splits (expected download ≈ 1.6 MB), staged rollout 5 % → 100 %, crash reporting through a self-hosted Sentry or Play Vitals only (no third-party analytics SDKs). |

### Build & run
```bash
./gradlew :app:testDebugUnitTest   # storage engine tests (JVM)
./gradlew :app:assembleDebug       # install: adb install app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease     # 2.0 MB minified APK
```
Without a relay, the built-in **notes chat** (a chat with yourself) works fully offline: text, voice, photos, video, files, edit, delete, reactions, themes and language switching. Point `RELAY_URL` / `BLOB_URL` in `app/build.gradle.kts` at your relay to enable two-party sync.

### Known gaps (honest list)
- The relay server is specified here but not included in the repo.
- Envelopes are protected by TLS only until phase 2 adds E2EE. Media is already encrypted with keys the relay never sees in plaintext. The keys do travel inside the TLS-protected envelope.
- On API 26–27, photos fall back to `BitmapFactory` and EXIF rotation is not applied.
- The UI has not yet been profiled on a physical device. It compiles, and the storage engine is unit-tested.
