---
name: deploy
description: Build and deploy the debug APK to all connected Android devices via ADB. Use when the user asks to deploy, install, or push the app to devices.
---

# Deploy

Build the debug APK and install it on all connected Android devices.

## Steps

### 1. Check connected devices

```bash
adb devices
```

### 2. Build the debug APK

```bash
./gradlew assembleDebug
```

### 3. Install on all devices

For each connected device, run:

```bash
adb -s <DEVICE_ID> install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. Verify installation

Confirm the package is installed on each device:

```bash
adb -s <DEVICE_ID> shell pm list packages p2p.broadcaster
```

## APK Location

`app/build/outputs/apk/debug/app-debug.apk`

## Expected Test Devices

- `R52X104ZSRD` — Samsung device (primary)
- `R5CY91WAZGB` — Samsung device (secondary, may disconnect during ADB)

## Notes

- `R5CY91WAZGB` sometimes USB-disconnects during ADB operations — retry if needed
- Use `-r` flag to reinstall (replace existing installation)
- Build must succeed before installation can proceed
