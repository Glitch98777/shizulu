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
- Rootless Power Tools now focus on real ADB/Shizuku capabilities: Shizulu elevation, RRO overlay controls, Advanced AppOps, package manipulation, and a faster boot reconnect loop.
- Removed the experimental fake/spoof-root tools, SU bridge endpoint, shell interceptors, fake system `su`, and spoof-root app grants.
- Shizulu elevation output is now trimmed and avoids huge package dumps so the app stays stable after running elevated privilege setup.
- Shizule JSON now supports dynamic variables with `{{variable}}` placeholders, including package prompts that ask which app/package to apply an action to before it runs.
- Fixed a launch crash caused by Android rejecting the dynamic variable placeholder parser pattern.
- Package-style variables such as `{{target_app}}` now open an in-app installed-app picker instead of requiring manual package-name typing.
- Bottom navigation now has a Shizule Store tab with a cart icon, public index loading, install/reinstall buttons, and a GitHub submission path for community shizules.
- Module Maker now keeps Install/Publish controls visible below a fixed-height scrolling JSON editor, even for very long shizules.
- Store publishing now lives in Create: publishing installs the shizule, makes it visible in your Store immediately, and opens a prefilled GitHub submission.
- Public Store refresh also reads open `[Store]` GitHub issues, so community shizules can become visible from GitHub without waiting for a manual index merge.
- Store cards now show Install, Update, or Already up to date based on the installed shizule version instead of saying Reinstall.
- Update checks no longer depend on GitHub's compare API, and fall back to the public latest-release redirect if the releases API returns HTTP 403.
- Community Store submissions can now point to raw `.shizule.json` URLs, and the expanded low/medium/high risk module pack is published through GitHub submissions instead of the curated local index.
- Visual Shizule Builder creates installable modules from fields, actions, compatibility metadata, permission declarations, and publish flow without hand-writing JSON.
- Shizule JSON and Store entries now support compatibility metadata: `worksOn`, `androidMin`, `androidMax`, and `requires`.
- Store cards and install previews now show verified tiers, compatibility warnings, and module permission statements before install.
- Template Gallery adds working starter shizules for animation tuning, display debugging, AppOps viewing, screen timeout presets, and notification permission grants.
- Expanded shizule schema normalization keeps old modules working while adding author info, version codes, changelogs, categories, screenshots, update URLs, safety notes, restore metadata, and known issues.
- New shared risk scanner classifies settings, AppOps, package manager, `cmd`, `dumpsys`, `am`, `device_config`, destructive file commands, package disabling, data clearing, network-script execution, and bypass-looking commands.
- Install and run flows now block critical commands, warn before high-risk actions, show per-command risk reasons, and label SHA digests honestly as integrity checks instead of identity verification.
- Dry Run reports now include expanded commands, risk per command, compatibility scan results, restore snapshot coverage, prechecks, postchecks, and plain-English command reasons without executing mutating commands.
- Before real runs, Shizulu snapshots restorable `settings put` and `device_config put` values when readable, stores restore history, and exposes Restore Last per installed module.
- Store now has search, risk filters, sorting, cached offline/rate-limit fallback, validation before install, compatibility badges, trust labels, and risk badges.
- Added a clean `CommandRunner` abstraction plus unit tests for schema validation, risk scanning, restore planning, and digest/tamper labeling.
- Compatibility badges now infer "Probably compatible" from generic shell modules instead of showing "Unknown compatibility" for every module that lacks explicit metadata.
- Risk labels now use readable text like `medium risk`, and the critical scanner avoids false positives from harmless `bypass` text or optional package names containing `android`.
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
