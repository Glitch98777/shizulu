# Shizulu

Shizulu is a rootless Android module manager for Shizuku and standalone Wireless ADB. It installs small JSON modules called **shizules** and runs their declared actions with ADB/shell-level privileges instead of requiring Magisk or root.

Think of it as an experimental, no-root cousin of a Magisk-style module manager: shizules can tune Android settings, grant ADB-grantable permissions, run diagnostics, apply display comfort profiles, or disable optional packages for the current user. Shizulu does not bypass Android security boundaries; it only uses capabilities available to Shizuku or ADB shell.

This is not a replacement build of the official Shizuku app. Shizulu is a separate manager app that can use either Shizuku or its standalone Wireless ADB backend.

## Highlights

- Import `.json` shizules from Android's file picker.
- Browse the Shizule Store from the bottom navigation, install public shizules, and submit new community shizules through GitHub review.
- Install and remove shizules from private app storage.
- Request Shizuku permission and bind a Shizuku `UserService`.
- Run shizule commands as the ADB/shell identity.
- Choose between the Shizuku backend and standalone Wireless ADB execution.
- Persistent Wireless ADB keep-alive service with Android battery optimization exemption flow.
- Dry Run mode previews and logs commands without executing them.
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
  "schema": 1,
  "id": "com.example.my_shizule",
  "name": "My Shizule",
  "version": "1.0.0",
  "description": "Short description shown in Shizulu.",
  "signature": {
    "author": "Example Author",
    "sha256": "optional-content-digest"
  },
  "actions": [
    {
      "id": "apply",
      "label": "Apply",
      "commands": [
        {
          "exec": "settings put global animator_duration_scale 0"
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

The optional `signature` metadata is informational for now; it is a trust signal for authorship, not cryptographic verification yet.

## Manager features

- Dry Run mode logs every command that would run without changing the device.
- Profiles run grouped actions from installed shizules, such as Comfort Setup, Clean Pixel, and Stock Restore.
- Custom profiles can be created from installed shizule actions and removed later.
- Backups export installed shizules, custom profiles, logs, and dry-run state to a portable JSON file.
- Logs capture imports, dry runs, command exits, and failures.

## Included Samples

- `animation-tuner.shizule.json`: Off, fast, normal, and relaxed animation presets.
- `display-comfort-pack.shizule.json`: Calmer display settings and restore actions.
- `light-debloat-manager.shizule.json`: Conservative optional Google/Pixel app disable/restore profile for user 0.
- `shizulu-capability-booster.shizule.json`: Attempts legitimate ADB-level grants and app-ops for Shizulu.
- `disable-animations.shizule.json`: Minimal animation-disable sample.

## Shizule Store

The in-app Store reads the public index at `samples/store-index.json`. Each entry points to a raw shizule JSON URL and includes author, risk, tags, version, and description metadata.

To publish a shizule, use the app's Store > Publish button or open a GitHub issue with the `Shizule Store submission` template. Reviewed submissions can be added to the index so they become visible in the app for everyone.

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
