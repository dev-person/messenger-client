# SecureMessenger — Architecture Overview

> **Группы (в разработке с 1.0.68):** детальный дизайн см. [GROUPS_DESIGN.md](GROUPS_DESIGN.md) — архитектура групповых чатов, протокол Sender Keys для E2E, план на три релиза (фундамент → история → звонки).

## Project Structure

```
app/src/main/java/com/secure/messenger/
│
├── data/
│   ├── local/
│   │   ├── dao/          — Room DAOs (ChatDao, MessageDao, UserDao)
│   │   ├── database/     — AppDatabase (Room)
│   │   └── entities/     — Room entities (mapped from/to domain models)
│   ├── remote/
│   │   ├── api/          — Retrofit REST API + DTOs
│   │   ├── websocket/    — SignalingClient (WebSocket, real-time events)
│   │   └── webrtc/       — WebRtcManager (calls, DTLS-SRTP)
│   └── repository/       — Repository implementations
│
├── domain/
│   ├── model/            — Pure Kotlin data classes (User, Message, Chat, Call)
│   ├── repository/       — Repository interfaces
│   └── usecase/          — Single-responsibility use cases
│
├── presentation/
│   ├── navigation/       — NavHost + Screen routes
│   ├── theme/            — MaterialTheme colours + typography
│   ├── ui/
│   │   ├── auth/         — OTP login screen + ViewModel
│   │   ├── chat/         — ChatList + Chat screens + ViewModels
│   │   ├── calls/        — Video/audio call screen + ViewModel
│   │   ├── contacts/     — Contacts screen + ViewModel
│   │   └── main/         — MainActivity + HomeScreen (bottom nav)
│   └── viewmodel/        — Hilt ViewModels
│
├── di/                   — Hilt modules (AppModule, RepositoryModule, AuthModule)
├── service/              — CallService (foreground) + MessagingService (WS)
└── utils/                — CryptoManager, LocalKeyStore, TimeUtils
```

## Security Architecture

### Message Encryption (E2E)
```
1. On first login each user generates an X25519 key pair
   - Private key: stored locally in DataStore (device-only)
   - Public key:  uploaded to server (public — no secrecy needed)

2. To send a message to Bob:
   sharedSecret = X25519(alicePrivate, bobPublic)   ← ECDH
   messageKey   = HKDF(sharedSecret, salt=messageId, info="secure-messenger-msg-v1")
   ciphertext   = AES-256-GCM(messageKey, iv=random12bytes, plaintext)
   stored/sent  = base64(iv || ciphertext || gcmTag)

3. Bob decrypts:
   sharedSecret = X25519(bobPrivate, alicePublic)   ← same secret!
   messageKey   = HKDF(sharedSecret, salt=messageId, ...)
   plaintext    = AES-256-GCM-Decrypt(messageKey, ciphertext)
```
- Every message uses a **unique key** (HKDF with messageId as salt) → no key reuse
- GCM authentication tag detects any tampering

### Call Encryption (DTLS-SRTP)
- WebRTC enforces **DTLS 1.2+** for key negotiation before any media flows
- All audio/video is encrypted with **SRTP** (AES-128-CM by default, configurable to AES-256-GCM)
- ICE credentials are exchanged over the authenticated WebSocket channel (TLS)
- No extra code needed — the WebRTC library handles it automatically

### Transport Security
- REST API: HTTPS only (`network_security_config.xml` blocks cleartext)
- WebSocket: WSS (TLS) — `wss://` scheme
- Cleartext allowed only for localhost in debug builds

## Technology Stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt |
| Local DB | Room |
| Network | Retrofit + OkHttp + Moshi |
| Real-time | WebSocket (OkHttp) |
| Audio/Video calls | WebRTC (stream-webrtc-android) with DTLS-SRTP |
| E2E Encryption | BouncyCastle: X25519 ECDH + HKDF-SHA256 + AES-256-GCM |
| Async | Kotlin Coroutines + Flow |
| Image loading | Coil |
| Logging | Timber |

## Key Files

| File | Responsibility |
|---|---|
| `utils/CryptoManager.kt` | Key generation, ECDH, HKDF, AES-GCM encrypt/decrypt |
| `utils/LocalKeyStore.kt` | Persist X25519 key pair in DataStore |
| `data/remote/webrtc/WebRtcManager.kt` | WebRTC peer connection, media tracks, DTLS-SRTP |
| `data/remote/websocket/SignalingClient.kt` | WebSocket: SDP offer/answer/ICE exchange |
| `service/CallService.kt` | Foreground service keeping calls alive in background |
| `service/MessagingService.kt` | Background WS connection + incoming call notifications |

## What You Need to Implement on the Server Side

1. **REST API** matching `MessengerApi.kt` endpoints
2. **WebSocket server** handling signaling messages:
   - `offer`, `answer`, `ice_candidate` — forwarded between peers
   - `incoming_call`, `call_ended` — call lifecycle events
   - `message` — real-time message delivery
3. **Public key storage** — store and serve users' X25519 public keys
4. **OTP service** (Twilio Verify, Firebase Auth, etc.)
