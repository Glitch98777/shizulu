# Shizulu 0.1.0

Initial public release of Shizulu, a rootless Android shizule manager for Shizuku and standalone Wireless ADB.

## Features

- Install `.json` shizules from Android's file picker.
- Select and install multiple `.json` shizules at once from the file picker.
- Run shizule actions through a Shizuku `UserService` with ADB/shell privileges.
- Backend selection for Shizuku and standalone Wireless ADB execution.
- Wireless ADB setup surface now only asks for the pairing code and port from Android's pairing dialog.
- Wireless ADB mode now pairs with local `adbd`, discovers the connect service with mDNS, and executes shizule commands without Shizuku.
- Wireless ADB reconnect now tries existing trust and cached connect ports before using a fresh pairing code.
- Wireless ADB Test now auto-scans likely local connect ports, reports open ports, and tries real ADB handshakes against them.
- Saving a new Wireless ADB port now refreshes the cached connect port instead of reusing an old one.
- Wireless ADB Auto Repair clears stale cached ports and reruns the reconnect/discovery/scan flow.
- Persistent ADB tools for foreground keep-alive and battery optimization exemption.
- Shizule Lab in Tools can install safe built-in test shizules and open a JSON Module Maker editor.
- Dry Run now previews profile actions in one clean report, refreshes its button state reliably, and shows backend/environment details.
- Rootless Power Tools add a shell-level SU Bridge, RRO overlay controls, Advanced AppOps, package manipulation, and a faster boot reconnect loop.
- SU Bridge now has a real opt-in provider endpoint, self-test, and su-c command runner for apps/modules that intentionally integrate with Shizulu.
- SU Bridge can now run Max ADB Elevation to grant Shizulu all shell-accessible permissions, appops, and battery whitelist allowances.
- SU Bridge popup uses OK, and the bridge script now installs a /data/local/tmp/su compatibility shim for apps that support custom su paths.
- SU Bridge now accepts standard su call shapes like `su -c`, `su 0 -c`, `su --command`, compact `-ccommand`, and stdin through the provider or custom `/data/local/tmp/su` path.
- App updater now ignores tester APK assets, validates the downloaded package before launching Android's installer, and uses a stable signing key for future updates.
- Developer Options opens as a separate Android Settings task.
- Appearance settings in Tools for Light/Dark mode, Default no-accent styling, and accent themes.
- Dark mode now uses a classic charcoal-gray palette instead of near-black AMOLED styling.
- Dark mode borders are now subtle gray separators instead of bright white outlines.
- Theme contrast tuning so light mode keeps dark text on pale accents and dark mode keeps white text on dark accents.
- Module version badges now use readable dark text in light mode and white text in dark mode.
- Soft status and count badges now use readable foreground text instead of blended accent colors.
- Tools footer buttons now keep readable black text in light mode and white text in dark mode.
- The Shizuku grant/bind button now uses readable black text in light mode and white text in dark mode.
- Dry Run, Create Profile, and Restore Defaults buttons now keep readable text across default and accent themes.
- Selected bottom navigation tabs now use strong readable text/icons instead of pale accent gray.
- The Tools bottom navigation icon now uses a crossed-tools shape instead of a sun-like glyph.
- Home dashboard now shows Wireless ADB mode instead of Shizuku offline when Wireless ADB is selected.
- Home dashboard status indicator is now a compact row instead of a tall card.
- Dry Run mode to preview/log commands without modifying the device.
- Profiles for grouped module actions:
  - Comfort Setup
  - Clean Pixel
  - Stock Restore
- Custom profile creation from installed shizule actions.
- Custom profiles can now be deleted with a confirmation dialog.
- Built-in profiles can now be hidden/deleted and restored later with Restore Defaults.
- JSON backup export and restore for shizules, custom profiles, dry-run state, and logs.
- App updater in Tools for checking GitHub releases and installing newer APK builds without allowing stale release downgrades.
- Release publishing now builds from full git history so Android version codes keep increasing.
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
