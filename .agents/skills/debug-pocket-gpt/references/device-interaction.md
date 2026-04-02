# ADB Device Interaction Patterns

Hard-won patterns for reliably interacting with PocketGPT on a connected Android device.

## Logcat Capture

### The Buffer Rotation Problem

Budget phones (Samsung A51, etc.) have small logcat buffers (~256KB). During inference,
the native layer produces many log lines. By the time generation completes, your
diagnostic output may have already been rotated out of the buffer.

**Always stream logcat to a file:**

```bash
# Clear old logs, start fresh capture
adb -s <device-id> logcat -c
adb -s <device-id> logcat -s PocketLlamaJNI:I > /tmp/pocket-diag.log 2>&1 &
LOGCAT_PID=$!

# ... do your testing ...

# When done, kill the capture
kill $LOGCAT_PID 2>/dev/null
```

### Filtered vs Full Capture

```bash
# Filtered (recommended for inference debugging)
adb -s <device-id> logcat -s PocketLlamaJNI:I > /tmp/diag.log &

# With core layer too (for ggml allocation / graph errors)
adb -s <device-id> logcat -s PocketLlamaJNI:I PocketLlamaCore:D > /tmp/diag.log &

# Full logcat (when you don't know where the problem is)
adb -s <device-id> logcat > /tmp/full.log &
```

### Querying Existing Buffer

```bash
# Dump existing buffer (no streaming)
adb -s <device-id> logcat -d -s PocketLlamaJNI:I | tail -30

# Search for specific diagnostic output
adb -s <device-id> logcat -d | grep "DIAG_" | head -30
```

## Reading Screen Text

### UI Hierarchy Dump (Preferred)

More reliable than screenshots -- gives you exact text content programmatically.

```bash
adb -s <device-id> shell uiautomator dump /data/local/tmp/ui.xml
adb -s <device-id> shell cat /data/local/tmp/ui.xml \
  | sed 's/></>\n</g' \
  | grep 'text="' | grep -v 'text=""' \
  | sed 's/.*text="\([^"]*\)".*/\1/'
```

### Screenshots

```bash
adb -s <device-id> exec-out screencap -p > /tmp/screen.png
```

Useful when you need visual layout context, but text extraction is
harder (requires OCR or visual inspection).

## Tap Interactions

### Finding Element Coordinates

Extract bounds from the UI hierarchy dump:

```bash
adb -s <device-id> shell cat /data/local/tmp/ui.xml \
  | sed 's/></>\n</g' \
  | grep 'text="Send"'
# Output: ... bounds="[903,1317][996,1376]" ...
# Center = ((903+996)/2, (1317+1376)/2) = (949, 1346)
```

### Tapping

```bash
adb -s <device-id> shell input tap 949 1346
```

### Keyboard Coordinate Shift (Critical Pitfall)

When the soft keyboard appears, all UI elements ABOVE the keyboard shift upward.
Button coordinates from before the keyboard appeared are now WRONG.

**Always re-dump the UI hierarchy after the keyboard appears:**

```bash
# 1. Tap input field
adb -s <device-id> shell input tap 322 2238
sleep 1
# 2. Type text
adb -s <device-id> shell input text 'Hello'
sleep 1
# 3. RE-DUMP UI to get updated Send button coordinates
adb -s <device-id> shell uiautomator dump /data/local/tmp/ui.xml
# 4. Find Send button in new coordinates
adb -s <device-id> shell cat /data/local/tmp/ui.xml \
  | sed 's/></>\n</g' | grep 'send_button'
# 5. Tap at the NEW coordinates
```

## Text Input

### Spaces in Text

`adb shell input text` doesn't handle spaces directly. Use `%s`:

```bash
# "Say hi to me"
adb -s <device-id> shell input text 'Say%shi%sto%sme'
```

Note: `%s` encoding can be inconsistent across Android versions.
For short test messages, just use a single word like `Hello` or `Hi`.

### Input Appending Problem

Repeated `input text` calls APPEND to existing text. If previous text wasn't sent
or cleared, you get concatenated garbage.

**Solution**: Force-stop the app and restart for a clean composer state:

```bash
adb -s <device-id> shell am force-stop com.pocketagent.android
sleep 1
adb -s <device-id> shell am start -n com.pocketagent.android/.MainActivity
```

## Clearing Stale State

### SharedPreferences

Stale runtime tuning (KV cache type, GPU settings) can persist across builds:

```bash
adb -s <device-id> shell run-as com.pocketagent.android \
  rm -f shared_prefs/pocketagent_runtime_tuning.xml
```

Then force-stop and restart the app.

### Full App Data Clear

Nuclear option -- clears everything including downloaded models:

```bash
adb -s <device-id> shell pm clear com.pocketagent.android
```

Only use this if you want a truly fresh start (model re-download required).

## Process Monitoring

### Check if App is Running

```bash
adb -s <device-id> shell pidof com.pocketagent.android
```

### Check Thread State

```bash
APP_PID=$(adb -s <device-id> shell pidof com.pocketagent.android)
# List threads
adb -s <device-id> shell ls /proc/$APP_PID/task/
# Check specific thread status (R=running, S=sleeping, D=disk wait)
adb -s <device-id> shell cat /proc/$APP_PID/task/<TID>/status | head -5
```

Thread in state `R (running)` during generation = active inference (not stuck).
Thread in state `S (sleeping)` during generation = possibly blocked on I/O or lock.

### Check Generation Status via UI

Look for these indicators in the UI dump:
- `Cancel` button visible = generation in progress
- `Cancelling...` = cancel signal sent, waiting for native thread
- `Send` button visible = idle, ready for input
- `Thinking...` = model is in `<think>` block (Qwen3 behavior)
- Three `"` dots = loading/generating indicator

## Device Serial Discovery

```bash
# List connected devices
adb devices -l

# For WiFi-connected devices
adb connect <ip>:<port>
```

Common serial formats:
- USB: `RR8NB087YTF` (Samsung A51), `SM-S906N` (Samsung S22+)
- WiFi: `192.168.1.35:43115`
- ADB over network: `adb-RR8NB087YTF-P4Pfzs._adb-tls-connect._tcp`
