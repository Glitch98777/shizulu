# Shizulu

Shizulu is a rootless Android module manager powered by [Shizuku](https://shizuku.rikka.app/). It installs small JSON modules called **shizules** and runs their declared actions through Shizuku's ADB/shell identity instead of requiring Magisk or root.

Think of it as an experimental, no-root cousin of a Magisk-style module manager: shizules can tune Android settings, grant ADB-grantable permissions, run diagnostics, apply display comfort profiles, or disable optional packages for the current user. Shizulu does not bypass Android security boundaries; it only uses capabilities available to Shizuku/ADB shell.

This is not a replacement build of the official Shizuku app. Shizulu is a separate manager app that depends on Shizuku being installed, running, and granted to Shizulu.

## Highlights

- Import `.json` shizules from Android's file picker.
- Install and remove shizules from private app storage.
- Request Shizuku permission and bind a Shizuku `UserService`.
- Run shizule commands as the ADB/shell identity.
- Warn before installing or running unsigned shizules.
- Dry Run mode previews and logs commands without executing them.
- Profiles run grouped module actions like `Comfort Setup`, `Clean Pixel`, and `Stock Restore`.
- Persistent logs show imports, dry runs, command exits, failures, and canceled unsigned actions.
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

If `signature` is missing, Shizulu treats the file as unsigned and warns before installing or running it. The current signature metadata is informational; it is a trust signal for authorship, not cryptographic verification yet.

## Manager features

- Dry Run mode logs every command that would run without changing the device.
- Profiles run grouped actions from installed shizules, such as Comfort Setup, Clean Pixel, and Stock Restore.
- Logs capture imports, canceled unsigned actions, dry runs, command exits, and failures.

## Included Samples

- `animation-tuner.shizule.json`: Off, fast, normal, and relaxed animation presets.
- `display-comfort-pack.shizule.json`: Calmer display settings and restore actions.
- `light-debloat-manager.shizule.json`: Conservative optional Google/Pixel app disable/restore profile for user 0.
- `shizulu-capability-booster.shizule.json`: Attempts legitimate ADB-level grants and app-ops for Shizulu.
- `disable-animations.shizule.json`: Minimal animation-disable sample.

## Safety Model

Shizulu is powerful because Shizuku is powerful. A shizule can run shell commands, so treat modules like scripts:

- Use Dry Run before running a new shizule.
- Prefer shizules with clear, reversible actions.
- Read commands before applying them.
- Avoid shizules from unknown sources.
- Use `Restore` actions when provided.

Shizulu does not provide root, kernel access, SELinux bypasses, boot image changes, or Magisk-style systemless mounts.

## Build From Source

Open this folder in Android Studio and build the `app` module. You need Android SDK 35 and internet access for Gradle dependencies.

The project uses the official Shizuku API artifacts:

- `dev.rikka.shizuku:api:13.1.5`
- `dev.rikka.shizuku:provider:13.1.5`

## Try It

1. Install and start Shizuku on the phone.
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
