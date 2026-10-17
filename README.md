# Minicraft — Minecraft Java Server on Android

An Android app that turns a phone into a real **Minecraft Java Edition** server (Vanilla / Fabric / NeoForge / Paper), with mod and world management, a live console, resource monitoring, and a [playit.gg](https://playit.gg) tunnel so friends can join without opening router ports.

> **Status: in development (private).** The full cycle works on a real device: install → JRE download → Fabric 1.21.1 → load mods → `Done` with a healthy server.

## Features

- **Server flavors**: Vanilla, Fabric, NeoForge, Paper — download and install from the app.
- **Mod manager**: load `.jar` mods locally or search [Modrinth](https://modrinth.com) and install with one tap.
- **World manager**: import/export worlds from the device.
- **Live console**: run commands, watch server logs in real time.
- **Resource monitor**: TPS, CPU, RAM and players at a glance.
- **Tunnel (playit.gg)**: public address for your server with zero router configuration.
- **Server config**: MOTD, port, RAM, difficulty, game mode and performance presets.
- **Background service**: keeps the server running (foreground service) when the screen turns off.
- **i18n**: English (default) and Spanish, switchable in Settings.

## Requirements

- Android 8.0+ (API 26), arm64-v8a
- JDK 17+ and Android SDK (compileSdk 34)
- ~150 MB free storage (JRE + server files)
- Internet connection for server/mod downloads

## Building

```bash
./gradlew :app:assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project layout

| Path | What it is |
|---|---|
| `app/src/main/java/` | App source (UI, server lifecycle, JRE provisioning, playit tunnel) |
| `app/src/main/cpp/` | Native launcher (execve without breaking W^X) |
| `app/src/main/jniLibs/` | `playitd` (BSD-2-Clause) and Java stub binaries |

## License

_TBD — license not yet chosen._
