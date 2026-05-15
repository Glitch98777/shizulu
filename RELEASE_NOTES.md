# ShizGuru Transition Notes

Shizulu has moved into the ShizGuru direction.

ShizGuru is the combined app: ShizGuru Core plus the Shizulu shizule manager. It keeps the rootless module idea, but removes the old feeling of being a separate command launcher that depends on another app.

## Current Public Repos

- Legacy Shizulu repo: <https://github.com/Glitch98777/shizulu>
- Active ShizGuru repo: <https://github.com/Glitch98777/shizguru>
- ShizGuru API submodule fork: <https://github.com/Glitch98777/shizguru-api>

## Major Changes In ShizGuru

- Integrated a Shizuku-style core into the Shizulu app experience.
- Renamed visible forked core branding to ShizGuru.
- Made ShizGuru Core the single visible module backend.
- Removed the separate standalone Wireless ADB backend from the module manager UI.
- Kept shell-level execution transparent and user-approved.
- Updated the UI palette to use ShizGuru/Shizuku indigo light and dark colors.
- Fixed updater release detection so it can read build numbers from GitHub tags, APK names, URLs, and `r####` version names.
- Published ShizGuru APK releases from the new `shizguru` repo.

## Legacy Shizulu Status

This repo remains available because it contains:

- The early Shizulu app.
- The first shizule module format work.
- The original Store/sample modules.
- Legacy releases.

It should not serve ShizGuru APKs under old Shizulu release names because Android treats Shizulu and ShizGuru as different packages. Mixing them would make the old updater confusing and could look like a downgrade or package mismatch.

## Active Download Path

Download new builds here:

```text
https://github.com/Glitch98777/shizguru/releases/latest
```

Latest ShizGuru builds use assets named like:

```text
ShizGuru-build-1097.apk
```

## Safety Notes

ShizGuru is still rootless:

- No root spoofing.
- No Play Integrity, banking, DRM, anti-cheat, or security bypass.
- No Magisk-style systemless mount behavior.
- Commands run only through shell/ADB-style privileges provided by the core service.

ShizGuru is a safer rootless module platform, not a root replacement.
