# Skill: troubleshoot

# Troubleshoot Log Issues

Investigate and diagnose issues using device logs.

## Usage

Invoke with a description of the problem:

```
troubleshoot the app crashes when scanning a QR code
troubleshoot files aren't downloading from nearby device
troubleshoot notification keeps disappearing on Samsung
```

## Steps

### 1. Capture EventLog logs

EventLog is the app's structured logging system. Tag: `EventLog`.

```bash
adb -s <DEVICE_ID> logcat -s EventLog:V -d
```

Use `-d` for dump (non-blocking) or omit for live streaming.

### 2. Capture full system logs with BLE context

```bash
adb -s <DEVICE_ID> logcat -d | grep -iE "ble|sync|scan|adv|sub|app|BluetoothGatt|BleCentral|BlePeripheral|SyncEngine|EventLog"
```

### 3. Check device connectivity

```bash
adb devices -l
```

### 4. Check installed package

```bash
adb -s <DEVICE_ID> shell pm list packages | grep pyramid
```

### 5. Force stop and restart

```bash
adb -s <DEVICE_ID> shell am force-stop com.pyramidrelay
```

### 6. Clear logs and reproduce

```bash
adb -s <DEVICE_ID> logcat -c
# Then reproduce the issue
adb -s <DEVICE_ID> logcat -s EventLog:V -d
```

## Log Prefixes

| Prefix | Component | Example |
|--------|-----------|---------|
| `[ble]` | BLE operations | Streaming, downloading, connections |
| `[sync]` | Transfer orchestration | Start, finish, retry |
| `[scan]` | Scan matching | Discovery, dedup, cooldown |
| `[adv]` | Advertising | Start, stop, relay |
| `[sub]` | Subscription operations | Add, delete, convert |
| `[app]` | Application-level | Boot, permissions, errors |

## Common Issues

- **BLE scan returns nothing**: Check `BLUETOOTH_SCAN` permission, Bluetooth enabled, Samsung scan restart workaround (every 5 min)
- **META characteristic not found**: Periodic GATT server restart (every 10 min), check `PERMISSION_READ` on characteristics
- **Transfer stalls**: Check 60s inactivity timeout, dedup/probe cooldowns, `transferSemaphore` contention
- **File not downloading**: Check `metaPayload.fileSize > MAX_FILE_SIZE` (5MB compressed), retry count (max 3)
- **Notification cleared on Samsung**: Persistent notification re-posts every 30s via `getActiveNotifications()` check
- **Hidden subscription cap**: `visibleSubscriptions + visibleBroadcasts + 1`, check EventLog for "cap reached"
