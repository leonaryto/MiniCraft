<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="Minicraft icon">
</p>

# Minicraft — Minecraft Java Server on Android

Turn an Android phone into a real **Minecraft Java Edition** server. Minicraft installs and runs the official server software, manages mods and worlds, shows a live console and resource usage, and can expose the server through a [playit.gg](https://playit.gg) tunnel so friends can join without opening router ports.

> **Version 1.0.0.** Free and open source under **GPL-3.0-or-later**.

**Source:** [github.com/leonaryto/MiniCraft](https://github.com/leonaryto/MiniCraft) · **Issues:** [report a bug](https://github.com/leonaryto/MiniCraft/issues)

Minicraft is a free, non-commercial project. It is **not** a Termux script, a PRoot container or a remote-control app for a PC: the Java server runs on the phone itself, with no root required.

## How it works

Running Java on Android is the hard part, because Android does not ship a Java runtime and blocks executing binaries from normal app storage. Minicraft solves this in three pieces:

1. **Java runtime** — on first use the app downloads a bionic **OpenJDK 21** runtime (built from Termux packages), verifies it, and installs it into app-private storage (`files/jre`, ~250 MB). It is downloaded, not bundled in the APK.
2. **Native launcher** — a small C/JNI library starts the JVM with `fork` + `execve` from the app's `nativeLibraryDir`. That directory is executable and sits outside Android's W^X restriction, which is what lets a full JVM start on a non-rooted phone.
3. **Playit agent** — `playitd` is bundled as a native library and controlled by the app over a local abstract socket (`@playit_ipc`). Each install claims its own agent and creates its own tunnel.

The server runs inside a foreground service with a wake lock, so it keeps running with the screen off. A boot alarm re-arms the service after a reboot.

## Requirements

- Android **8.0 (API 26)** or newer.
- **arm64-v8a** device (64-bit ARM). 32-bit, x86 and emulators are not supported.
- **No root**, no Termux, no PC.
- Internet on first setup (JRE, server jar and libraries are downloaded).
- Free space for the runtime (~250 MB) plus the server, libraries and worlds.
- A recent phone is recommended: vanilla needs roughly 1 GB of heap (default), and Fabric with mods needs more.

## Supported

- **Server flavors**: **Vanilla** and **Fabric**. Forge and NeoForge are **not** supported.
- **Minecraft versions**: **1.21.1** is the tested version. The app also lists **1.21**, **1.20.6** and **1.20.5**; all four require Java 21, but only 1.21.1 has been verified end to end (1.20.5 is known not to start).
- **Mods**: Fabric mods for **1.21.1**, either searched on Modrinth or imported as local `.jar` files.

## Features

- **Server install**: pick a version and flavor, and Minicraft downloads and provisions everything.
- **Mod manager**: browse Modrinth (server-side, Fabric 1.21.1) or import local `.jar` mods; enable, disable and delete them.
- **World manager**: import, export and delete the worlds of the active server.
- **Live console**: watch server output and send commands in real time.
- **Resource monitor**: CPU, memory and device temperature, with a temperature graph.
- **Tunnel (playit.gg)**: a public address with no router configuration, and a switch to keep the server LAN-only.
- **Server settings**: JVM heap, thread count, port, view/simulation distance, and a `server.properties` editor (MOTD, difficulty, game mode, and more).
- **Background service**: keeps the server alive while the screen is off.
- **i18n**: English (default) and Spanish, switchable in Settings.

## Download and install

Get the latest APK from **[Releases](../../releases)** and sideload it on the device.

- The APK is **arm64-v8a only**; it will not install on other architectures.
- If a previous build was signed with a different key (for example an older debug build), uninstall it first.
- The first launch needs an internet connection to download the Java runtime and the server (~250 MB total).
- Installing a server means accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA); the app writes `eula=true` for you.

## Dependencies

**Runtime (downloaded, not bundled)**

| Dependency | Version | Why |
|---|---|---|
| OpenJDK (bionic build from Termux packages) | 21 | Runs the Minecraft server |
| Vanilla server jar | Matches the selected version | Downloaded from Mojang's `version_manifest_v2.json` |
| Fabric loader + installer | Latest for the selected version | Installed on top of a vanilla `server.jar` |

**Bundled in the APK**

| Dependency | License | Why |
|---|---|---|
| `playitd` (`jniLibs/arm64-v8a/libplayitd.so`) | BSD-2-Clause | playit.gg agent |
| Java launcher stub (`libmcjvmboot.so`) | Part of this project | Enters the JVM from `nativeLibraryDir` |
| `minicraft_launcher` (built from `cpp/launcher.c`) | Part of this project | JNI `fork`/`execve` launcher |

**App libraries**

| Dependency | License | Why |
|---|---|---|
| `org.tukaani:xz` | Public domain / 0BSD | XZ decompression (runtime archive) |
| `org.json` (Android SDK) | Apache-2.0 | JSON parsing |
| — | — | Networking uses `HttpURLConnection`; there is no HTTP client library |

APIs used at runtime: Mojang version manifest, Fabric Meta/installer, Modrinth API v2, and the playit.gg agent API.

**Build**

| Tool | Version |
|---|---|
| JDK | 17 or newer |
| Android SDK | compile/target 34, min 26 |
| Android NDK + CMake | CMake 3.22.1 |
| Gradle wrapper | 8.14.3 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |

## Project layout

| Path | What it is |
|---|---|
| `app/src/main/java/` | App source (UI, server lifecycle, JRE provisioning, mods/worlds, playit tunnel) |
| `app/src/main/cpp/` | Native launcher (`execve` without breaking W^X) |
| `app/src/main/jniLibs/` | `playitd` (BSD-2-Clause) and the Java launcher stub |
| `app/src/main/res/` | Layouts, strings (EN/ES), icon and theme |
| `app/src/main/assets/licenses/` | Full license texts shown inside the app |
| `LICENSE` / `NOTICE` | Project license (GPL-3.0) and copyright notice |
| `THIRD-PARTY-NOTICES.md` | Third-party licenses and trademark notices |
| `CONTRIBUTING.md` | How to build and contribute |
| `SECURITY.md` | How to report vulnerabilities privately |
| `.github/` | Issue and pull request templates |

## Building from source

You need JDK 17+, the Android SDK (platform 34) and the NDK with CMake 3.22.1. Point Gradle at the SDK with `local.properties` (`sdk.dir=...`).

```bash
./gradlew :app:assembleDebug      # debug
./gradlew :app:assembleRelease    # release (needs a local keystore.properties)
```

`keystore.properties` and the keystore are intentionally **not** committed.

## Legal notices

- Minicraft is **not affiliated with Mojang Studios or Microsoft**. "Minecraft" is a trademark of Mojang Synergies AB.
- The app downloads the official server jar from Mojang; it does not redistribute it. Running a server is subject to the Minecraft EULA.
- `playitd` is distributed under the BSD-2-Clause license; its notice must be kept in binary distributions.
- The bundled Java launcher stub comes from Termux's OpenJDK 21 package (GPL-2.0 with Classpath Exception). Full texts are in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) and inside the app under **Settings → About → Licenses and notices**.
- playit.gg and Modrinth are independent services; Minicraft is not affiliated with them.
- Mods installed from Modrinth or imported locally keep their own licenses.
- The launcher icon was generated with an AI image tool.

## License

Minicraft is licensed under the **GNU General Public License, version 3 or later (GPL-3.0-or-later)**. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

You can use, study, share and modify it. If you distribute a modified version, you must keep it under the GPL and make the source available. Third-party components keep their own licenses; see [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

The app also shows this information offline: **Settings → About → Licenses and notices**.

## Contributing

Contributions are welcome. Read [`CONTRIBUTING.md`](CONTRIBUTING.md), use the issue templates for bugs and feature requests, and send a pull request. Open an issue first for larger changes. By contributing you agree to license your work under GPL-3.0-or-later (inbound = outbound). For security problems, follow [`SECURITY.md`](SECURITY.md) instead of opening a public issue.
