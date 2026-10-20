# Third-party notices

Minicraft — Minecraft Java server for Android
Copyright (C) 2026 leonaryto and Minicraft contributors

This project is licensed under the GNU General Public License, version 3
or (at your option) any later version (GPL-3.0-or-later). See `LICENSE`.

This file lists the third-party components distributed with, or downloaded
by, Minicraft, and the notices required by their licenses.

## Bundled in the APK

### playitd — playit.gg agent

File: `app/src/main/jniLibs/arm64-v8a/libplayitd.so`
License: BSD 2-Clause "Simplified" License
Upstream: https://github.com/playit-cloud/playit-agent

Copyright 2022 Developed Methods LLC

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

### Java launcher stub (`libmcjvmboot.so`)

File: `app/src/main/jniLibs/arm64-v8a/libmcjvmboot.so`
License: GNU General Public License, version 2, with the Classpath Exception
Upstream: https://github.com/termux/termux-packages/tree/master/packages/openjdk-21
          https://openjdk.org/

This small ELF executable is the `java` launcher from the Termux OpenJDK 21
package. It is aggregated with Minicraft as a separate program: Minicraft
starts it with `execve` and does not link against it. The full GPL-2.0 text
is included in `licenses/GPL-2.0.txt` and in the app under
"Licenses and notices". Corresponding source is available from the upstream
links above.

### XZ for Java 1.9

File: `app/libs/xz-1.9.jar`
License: Public Domain
Author: Lasse Collin
Upstream: https://tukaani.org/xz/java.html

This file has been placed in the public domain. You can do whatever you
want with it.

### Android framework (`org.json`, `android.*`)

License: Apache License 2.0

The app uses the JSON and Android framework classes provided by the Android
platform. They are not redistributed as separate libraries.

## Downloaded at runtime (not distributed by Minicraft)

On first use, Minicraft downloads the following from the Termux package
repository and stores them in app-private storage:

- `openjdk-21` (OpenJDK 21 JRE)
- `zlib`
- `libandroid-shmem`
- `libandroid-spawn`
- `libc++`

Source: https://packages.termux.dev/apt/termux-main/pool/main/

Each package keeps its own license. `openjdk-21` is GPL-2.0 with the
Classpath Exception; the other packages use their respective licenses.
Because Minicraft does not redistribute these packages, it does not alter
their licensing; see the upstream packages for the corresponding source and
notices.

Minicraft also downloads the official Minecraft server jar from Mojang's
version manifest. The jar is not bundled with Minicraft.

## Trademarks and affiliation

Minicraft is an independent project. It is **not affiliated with, endorsed
by, or sponsored by** Mojang Studios, Microsoft, playit.gg, Modrinth, or the
Termux project.

- "Minecraft" is a trademark of Mojang Synergies AB. Use of the Minecraft
  server software is subject to the Minecraft End User License Agreement:
  https://aka.ms/MinecraftEULA
- playit.gg is a service of Developed Methods LLC.
- Modrinth is a trademark of Modrinth, Inc. Mods downloaded through the
  Modrinth API keep their own licenses; Minicraft does not relicense them.
- Termux is an independent project; Minicraft only downloads its packages.

## Artwork

The launcher icon was generated with an AI image tool and is distributed as
part of this project under the same GPL-3.0-or-later terms, to the extent
permitted by the tool's terms and applicable law.
