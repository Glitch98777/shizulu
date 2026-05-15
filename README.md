# ShizGuru

ShizGuru is the next version of Shizulu: a rootless Android module manager that combines a Shizuku-style core service with Shizulu's JSON module system.

The old Shizulu idea was a separate manager app that depended on Shizuku or standalone Wireless ADB. ShizGuru moves the project into a single app experience: ShizGuru Core starts and manages the shell service, while shizules provide the module layer on top.

Main repo and APK releases:

- ShizGuru app/source: <https://github.com/Glitch98777/shizguru>
- Latest ShizGuru APKs: <https://github.com/Glitch98777/shizguru/releases/latest>
- Shizulu legacy repo: <https://github.com/Glitch98777/shizulu>

This repository is now the legacy Shizulu project home and migration page. The working ShizGuru app lives in the `shizguru` repository so the old Shizulu release history stays intact and old updater builds are not accidentally served a different Android package.

## What Changed

ShizGuru is not just a rename. It changes the structure of the app:

- Shizuku-style core and Shizulu module manager are now in one app.
- ShizGuru Core is the only module execution backend shown in the app shell.
- The old standalone Wireless ADB backend UI was removed from the module manager flow.
- The Shizulu shell now uses ShizGuru/Shizuku indigo light and dark colors instead of custom accent themes.
- The app updater now checks the ShizGuru GitHub releases and parses build numbers more reliably.
- Visible Shizuku core wording was changed to ShizGuru where it is part of the forked app experience.
- Shizules remain JSON modules and still run only with shell/ADB-level privileges.

## What Did Not Change

ShizGuru is still intentionally rootless and transparent:

- It is not root.
- It is not Magisk.
- It is not KernelSU.
- It is not a banking, Play Integrity, DRM, anti-cheat, or security bypass tool.
- It only runs commands available to the ShizGuru/Shizuku shell service.
- User consent is still required before installing or running shizules.

## Why This Repo Still Exists

The original Shizulu repo has the early history, sample shizules, Store metadata, and legacy releases. It remains useful for:

- Understanding how Shizulu started.
- Browsing example `.shizule.json` modules.
- Keeping legacy Shizulu builds available.
- Tracking the transition from standalone Shizulu to ShizGuru.

New app development should happen in:

```text
https://github.com/Glitch98777/shizguru
```

## Shizule Format

Shizules are JSON modules. The format remains compatible with old Shizulu modules.

```json
{
  "schema": 2,
  "id": "com.example.my_shizule",
  "name": "My Shizule",
  "version": "1.0.0",
  "versionCode": 1,
  "description": "Short description shown in ShizGuru.",
  "author": {
    "name": "Example Author",
    "url": "https://example.com",
    "verified": false
  },
  "tags": ["settings", "restore"],
  "categories": ["Display"],
  "compatibility": {
    "worksOn": ["pixel", "samsung"],
    "androidMin": 13,
    "androidMax": 16,
    "requires": ["shizuku", "adb"]
  },
  "permissions": ["system_settings", "restore"],
  "safety": {
    "risk": "Medium",
    "notes": "Changes animation scale settings.",
    "reversible": true,
    "requiresReview": true
  },
  "restore": {
    "level": "snapshot",
    "notes": "ShizGuru can snapshot settings values before running.",
    "snapshotBeforeRun": true
  },
  "actions": [
    {
      "id": "apply",
      "label": "Apply",
      "stopOnError": true,
      "commands": [
        {
          "exec": "settings put global animator_duration_scale 0",
          "explanation": "Sets Android animator duration scale to off.",
          "mutates": true
        }
      ],
      "restoreCommands": [
        {
          "exec": "settings put global animator_duration_scale 1",
          "mutates": true
        }
      ]
    }
  ]
}
```

Each command runs through the app's shell service as a normal shell command. ShizGuru does not add root privileges.

## Safety Model

ShizGuru keeps Shizulu's safety model:

- Dry Run previews commands without mutating the device.
- Risk scanning flags settings, package manager, AppOps, DeviceConfig, overlay, and destructive command patterns.
- Critical destructive/root-only/bypass-style commands are blocked or heavily warned.
- Restore snapshots are captured when the command pattern is restorable.
- Logs show install, run, restore, update, and failure events.

## Included Legacy Samples

This repository still includes sample shizules under `samples/`:

- `animation-tuner.shizule.json`
- `display-comfort-pack.shizule.json`
- `display-debugging.shizule.json`
- `appops-viewer.shizule.json`
- `screen-timeout-presets.shizule.json`
- `notification-permission-helper.shizule.json`
- `light-debloat-manager.shizule.json`
- `shizulu-capability-booster.shizule.json`

These samples are kept for learning and Store testing. New ShizGuru app releases are published from the `shizguru` repo.

## Download

Use the ShizGuru releases, not the legacy Shizulu releases:

```text
https://github.com/Glitch98777/shizguru/releases/latest
```

The old Shizulu releases remain here only for archive/history. They use a different app package and should not be mixed with ShizGuru updater releases.
