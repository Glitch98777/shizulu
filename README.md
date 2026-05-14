# Shizulu

Shizulu is a rootless Android module manager for Shizuku and standalone Wireless ADB. It installs small JSON modules called **shizules** and runs their declared actions with ADB/shell-level privileges instead of requiring Magisk or root.

Think of it as an experimental, no-root cousin of a Magisk-style module manager: shizules can tune Android settings, grant ADB-grantable permissions, run diagnostics, apply display comfort profiles, or disable optional packages for the current user. Shizulu does not bypass Android security boundaries; it only uses capabilities available to Shizuku or ADB shell.

This is not a replacement build of the official Shizuku app. Shizulu is a separate manager app that can use either Shizuku or its standalone Wireless ADB backend.

## Highlights

- Import `.json` shizules from Android's file picker.
- Browse the Shizule Store from the bottom navigation, install public shizules, and submit new community shizules through GitHub review.
- Build modules with a Visual Shizule Builder, or start from the public Template Gallery.
- Install and remove shizules from private app storage.
- Request Shizuku permission and bind a Shizuku `UserService`.
- Run shizule commands as the ADB/shell identity.
- Choose between the Shizuku backend and standalone Wireless ADB execution.
- Persistent Wireless ADB keep-alive service with Android battery optimization exemption flow.
- Dry Run mode previews and logs commands without executing them.
- Shared risk scanning blocks critical/destructive/root-only/bypass-looking commands and explains risky shell operations before install or run.
- Restore snapshots capture restorable settings values before supported actions run, then expose Restore Last from installed modules.
- Appearance settings in Tools for Light/Dark mode, Default no-accent styling, and optional Blue, Jade, Violet, or Rose accents.
- Profiles run grouped module actions like `Comfort Setup`, `Clean Pixel`, and `Stock Restore`.
- Create custom profiles from installed shizule actions.
- Export and restore JSON backups for installed shizules, custom profiles, dry-run state, and logs.
- Persistent logs show imports, dry runs, command exits, and failures.
- App updater checks GitHub releases, downloads newer APKs, and opens Android's installer.
- Includes sample shizules for animation tuning, display comfort, conservative debloating, and Shizulu capability boosting.

## Shizule Format

```json
{
  "schema": 2,
  "id": "com.example.my_shizule",
  "name": "My Shizule",
  "version": "1.0.0",
  "versionCode": 1,
  "description": "Short description shown in Shizulu.",
  "author": {
    "name": "Example Author",
    "url": "https://example.com",
    "verified": false
  },
  "tags": ["settings", "restore"],
  "categories": ["Display"],
  "screenshots": [],
  "updateUrl": "https://example.com/my-shizule.json",
  "changelog": ["Initial release"],
  "tier": "Community module",
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
    "notes": "Shizulu can snapshot settings values before running.",
    "snapshotBeforeRun": true
  },
  "knownIssues": [],
  "signature": {
    "author": "Example Author",
    "sha256": "optional-content-digest"
  },
  "actions": [
    {
      "id": "apply",
      "label": "Apply",
      "stopOnError": true,
      "prechecks": [
        {
          "exec": "settings get global animator_duration_scale",
          "mutates": false
        }
      ],
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

Each command is executed as:

```sh
/system/bin/sh -c "<exec>"
```

Commands receive these environment variables:

```sh
SHIZULU=1
SHIZULU_API_VERSION=1
SHIZULU_MODULE_ID=<installed module id>
```

Keep shizules readable and only install files you trust. Shizulu intentionally runs actions only when you tap an action button.

Schema 1 modules still work. Shizulu normalizes older modules into the newer internal model with safe defaults.

The optional `signature.sha256` metadata is treated as an integrity check only. A matching digest can show content was not changed; it does not prove identity unless a future real crypto signature system is added.

Optional Store metadata helps Shizulu warn before install:

- `compatibility.worksOn`: manufacturer families such as `pixel` or `samsung`.
- `compatibility.androidMin` / `androidMax`: supported Android API range.
- `compatibility.requires`: supported backends such as `shizuku` or `adb`.
- `tier`: `Community module`, `Reviewed module`, `Verified author`, or `Official Shizulu module`.
- `permissions`: declared capability tags such as `system_settings`, `package_manager`, `appops`, `permissions`, `rro`, `app_data`, `diagnostics`, and `restore`.
- `safety`: risk notes and whether the module expects review.
- `restore`: restore level, restore notes, and whether Shizulu should snapshot readable values before running.
- `knownIssues`, `changelog`, `screenshots`, and `updateUrl`: Store/detail metadata.

## Risk Scanner

Shizulu scans every command before install, dry run, and live execution. It classifies commands as Low, Medium, High, or Critical and explains why.

Critical commands are blocked when they look destructive, root-only, or bypass-oriented. That includes broad recursive deletes, `dd`/`mkfs`/`mount`/`setenforce`, disabling core system packages, network downloads piped to shell, Magisk/root commands, and banking/Play Integrity/DRM/anti-cheat bypass attempts.

High-risk commands such as package disabling, AppOps writes, app data clearing, advanced package manager commands, and DeviceConfig changes require an extra run warning.

## Manager features

- Dry Run mode logs every command that would run without changing the device.
- Profiles run grouped actions from installed shizules, such as Comfort Setup, Clean Pixel, and Stock Restore.
- Custom profiles can be created from installed shizule actions and removed later.
- Backups export installed shizules, custom profiles, logs, and dry-run state to a portable JSON file.
- Logs capture imports, dry runs, command exits, and failures.

## Included Samples

- `animation-tuner.shizule.json`: Off, fast, normal, and relaxed animation presets.
- `display-debugging.shizule.json`: Read-only display size, density, brightness, and refresh checks.
- `appops-viewer.shizule.json`: Dynamic package picker plus AppOps inspection.
- `screen-timeout-presets.shizule.json`: Working screen timeout presets with restore.
- `notification-permission-helper.shizule.json`: Grant/revoke Android notification permission for a selected app.
- `display-comfort-pack.shizule.json`: Calmer display settings and restore actions.
- `light-debloat-manager.shizule.json`: Conservative optional Google/Pixel app disable/restore profile for user 0.
- `shizulu-capability-booster.shizule.json`: Attempts legitimate ADB-level grants and app-ops for Shizulu.
- `disable-animations.shizule.json`: Minimal animation-disable sample.

## Shizule Store

The in-app Store reads the public index at `samples/store-index.json`. Each entry points to a raw shizule JSON URL and includes author, risk, tags, version, tier, compatibility, permissions, template, and description metadata.

To publish a shizule, use Store > Visual Builder or JSON Editor, then tap Publish. Shizulu installs it, makes it visible in your local Store immediately, and opens a prefilled GitHub submission. The public Store also reads open `[Store]` GitHub issues, so community submissions can appear from GitHub without waiting for a manual index merge.

Community Store submissions may paste full JSON or link to a raw `.shizule.json` file in this repository. The expanded low/medium/high risk module pack is published through GitHub submissions instead of being hardcoded into the curated baseline index.

## Safety Model

Shizulu is powerful because Shizuku and Wireless ADB expose shell-level capabilities. A shizule can run shell commands, so treat modules like scripts:

- Use Dry Run before running a new shizule.
- Prefer shizules with clear, reversible actions.
- Read commands before applying them.
- Avoid shizules from unknown sources.
- Use `Restore` actions when provided.

Shizulu does not provide root, kernel access, SELinux bypasses, boot image changes, or Magisk-style systemless mounts.

## Execution Backends

Shizulu currently has two backend modes:

- `Shizuku`: fully supported. Shizulu binds a Shizuku `UserService` and runs shizule commands with Shizuku's ADB/shell identity.
- `Wireless ADB`: standalone mode. The app stores the Wireless debugging pairing code and port, pairs with local `adbd`, discovers the `_adb-tls-connect` service, and executes shizule commands over ADB without Shizuku.

Wireless ADB mode requires Android 11+ Wireless debugging. Open the separate Android Settings app, choose `Pair device with pairing code`, enter the code and port in Shizulu, then run a shizule with the Wireless ADB backend selected.

## Build From Source

Open this folder in Android Studio and build the `app` module. You need Android SDK 36 and internet access for Gradle dependencies.

The project uses the official Shizuku API artifacts plus Kadb for standalone Wireless ADB:

- `dev.rikka.shizuku:api:13.1.5`
- `dev.rikka.shizuku:provider:13.1.5`
- `com.flyfishxu:kadb:2.1.1`

## Releases

Every successful push build on `main` publishes a normal GitHub release with the debug APK attached. Automated release tags use the format `build-<github-run-number>`.

## Try It

1. Install and start Shizuku on the phone, or enable Wireless debugging and pair Shizulu from the Tools tab.
2. Install Shizulu.
3. Tap `Grant Shizuku`.
4. Tap `Import .json`.
5. Pick `samples/disable-animations.shizule.json`.
6. Run `Apply` or `Restore`.

There is also `samples/shizulu-capability-booster.shizule.json`, which grants Shizulu legitimate ADB-level permissions and app-ops that can be reversed with its `Restore` action.

Additional shizule samples:

- `samples/animation-tuner.shizule.json`
- `samples/light-debloat-manager.shizule.json`
- `samples/display-comfort-pack.shizule.json`
