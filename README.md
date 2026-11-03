# NiNi LaLa

A peer-to-peer Android baby monitor that streams camera video over your local network — no servers, no accounts, no internet required.

## How it works

One phone acts as the **camera** (Stream mode) and another as the **viewer** (Watch mode). The camera phone discovers nearby viewers using mDNS/NSD and streams H.264 video over a direct TCP connection.

```
┌──────────────┐      LAN (Wi-Fi)        ┌──────────────┐
│   Baby       │ ◄─────────────────────► │   Parents    │
│  (Stream)    │    TCP + NSD/mDNS       │   (Watch)    │
└──────────────┘                         └──────────────┘
```

## Features

- **Zero-config discovery** — phones find each other automatically via `_ninilala._tcp.` NSD service
- **Low-latency streaming** — raw H.264 Annex-B access units over TCP with length-prefixed framing
- **Foreground service** — camera keeps streaming when the app is backgrounded
- **Front/back camera** toggle
- **Minimal UI** — Material 3 with a soft pastel theme

## Tech stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Camera | Camera2 API |
| Encoding | MediaCodec (H.264) |
| Discovery | NSD (mDNS/DNS-SD) |
| Transport | Raw TCP sockets |
| Min SDK | 33 (Android 13) |
| Target SDK | 37 |

## Project structure

```
app/src/main/java/dev/iyas/ninilala/
├── stream/          # Camera capture, encoding, and TCP server
│   ├── CameraEncoder.kt
│   ├── PreviewCamera.kt
│   ├── StreamServer.kt
│   ├── StreamService.kt
│   └── NsdAdvertiser.kt
├── watch/           # Viewer-side discovery and playback
│   ├── LanDiscovery.kt
│   └── StreamPlayer.kt
├── ui/              # Compose screens and navigation
│   ├── HomeScreen.kt
│   ├── StreamScreen.kt
│   ├── WatchScreen.kt
│   └── ...
├── LanProtocol.kt   # Shared protocol constants
├── MainActivity.kt
└── SplashActivity.kt
```

## Building

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Usage

1. Install the app on two Android phones connected to the same Wi-Fi network
2. On the **camera phone**: tap **Stream** → grant camera permission → tap **Start**
3. On the **viewer phone**: tap **Watch** → select the discovered stream → live video plays

## License

Proprietary — all rights reserved.
