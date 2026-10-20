# Contributing to Minicraft

Thanks for taking the time to contribute. Minicraft is a small Android app
that runs a real Minecraft Java server on a phone, and it is released under
**GPL-3.0-or-later**. By submitting a contribution you agree to license it
under the same terms (inbound = outbound). There is no CLA.

## Ways to help

- Report bugs with the device model, Android version, Minecraft version and
  server type (Vanilla/Fabric), plus `adb logcat` output if you can. Use the issue
  templates so the report includes the right details.
- Test on devices other than the one in the issue reports.
- Improve the UI, translations (English/Spanish), documentation or build.
- Fix bugs and small features. For larger changes, open an issue first so we
  can agree on the approach.

## Development setup

Requirements: JDK 17+, Android SDK platform 34, NDK with CMake 3.22.1.

```bash
git clone https://github.com/leonaryto/MiniCraft.git
cd MiniCraft
# create local.properties with sdk.dir=/path/to/Android/Sdk
./gradlew :app:assembleDebug
```

Release builds read signing details from `keystore.properties` at the repo
root. **Never commit** `keystore.properties`, `*.jks`, `*.keystore`,
`local.properties`, the `backups/` folder, or any playit.gg secret.

## Pull requests

- Work from a fork and open a pull request; do not force-push `main`.
- Pull requests are reviewed and merged by the maintainer.
- Keep changes focused; one topic per PR.
- Match the existing Kotlin style (4 spaces, no new dependencies unless
  discussed).
- Run `./gradlew :app:assembleDebug` before opening the PR.
- If you add a third-party component, add its notice to
  `THIRD-PARTY-NOTICES.md` (and the matching asset under
  `app/src/main/assets/licenses/`).
- Do not remove the copyright, license or affiliation notices.

## Security

Do not open a public issue for a vulnerability. Follow
[`SECURITY.md`](SECURITY.md) and use GitHub's private vulnerability reporting.

## Legal notes

- Do not commit Mojang's `server.jar` or any Minecraft assets. The app
  downloads them at runtime.
- Do not commit playit.gg agent secrets or claim codes.
- Mods downloaded from Modrinth keep their own licenses; do not relicense
  them.
