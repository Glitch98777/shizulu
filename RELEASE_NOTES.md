# Shizulu 0.1.0

Initial public release of Shizulu, a rootless Android shizule manager for Shizuku and standalone Wireless ADB.

## Features

- Install `.json` shizules from Android's file picker.
- Run shizule actions through a Shizuku `UserService` with ADB/shell privileges.
- Backend selection for Shizuku and standalone Wireless ADB execution.
- Wireless ADB setup surface now only asks for the pairing code and port from Android's pairing dialog.
- Wireless ADB mode now pairs with local `adbd`, discovers the connect service with mDNS, and executes shizule commands without Shizuku.
- Persistent ADB tools for foreground keep-alive and battery optimization exemption.
- Developer Options opens as a separate Android Settings task.
- Appearance settings in Tools for Light/Dark mode, Default no-accent styling, and accent themes.
- Theme contrast tuning so light mode keeps dark text on pale accents and dark mode keeps white text on dark accents.
- Tools footer buttons now keep readable black text in light mode and white text in dark mode.
- Dry Run mode to preview/log commands without modifying the device.
- Profiles for grouped module actions:
  - Comfort Setup
  - Clean Pixel
  - Stock Restore
- Custom profile creation from installed shizule actions.
- JSON backup export and restore for shizules, custom profiles, dry-run state, and logs.
- App updater in Tools for checking GitHub releases and installing newer APK builds without allowing stale release downgrades.
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
