# Shizulu 0.1.0

Initial public release of Shizulu, a rootless Android shizule manager powered by Shizuku.

## Features

- Install `.json` shizules from Android's file picker.
- Run shizule actions through a Shizuku `UserService` with ADB/shell privileges.
- Dry Run mode to preview/log commands without modifying the device.
- Profiles for grouped module actions:
  - Comfort Setup
  - Clean Pixel
  - Stock Restore
- Custom profile creation from installed shizule actions.
- JSON backup export and restore for shizules, custom profiles, dry-run state, and logs.
- Persistent Logs screen for command exits, failures, imports, dry runs, and canceled actions.
- Polished manager-style UI and custom Shizulu app icon.

## Included Shizules

- Animation Tuner
- Display Comfort Pack
- Light Debloat Manager
- Shizulu Capability Booster
- Disable Animations

## Requirements

- Android 7.0 or newer.
- Shizuku installed and running.
- Shizulu granted Shizuku permission.

## Notes

This release does not provide root or Magisk-style systemless mounting. Shizules run only with Shizuku/ADB shell capabilities.
