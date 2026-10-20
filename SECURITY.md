# Security policy

## Supported versions

Only the latest release is supported.

## Reporting a vulnerability

Please **do not** open a public issue for security problems.

Use GitHub's private vulnerability reporting:

https://github.com/leonaryto/MiniCraft/security/advisories/new

Include the affected version, device/Android version, steps to reproduce,
impact and any suggested fix. If private reporting is unavailable, contact the
maintainer through GitHub (`@leonaryto`) and ask for a private channel. Do not
include secrets in the first message.

## Scope

Examples of in-scope issues:

- remote code execution or path traversal in the server installer/launcher,
- playit.gg agent secret exposure,
- unsafe file handling in world import/export,
- privilege escalation inside the app sandbox.

Out of scope:

- issues that require root or a compromised device,
- vulnerabilities in Minecraft, the JRE, playit.gg or mods themselves
  (report those upstream),
- social engineering.

Never paste playit.gg secrets, tokens or personal data into issues or pull
requests. If you do, revoke the affected credential immediately.
