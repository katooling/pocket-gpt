#!/usr/bin/env bash
set -euo pipefail

TARGET_SERIAL="${ADB_SERIAL:-}"

adb start-server >/dev/null

DEVICE_LINES_RAW="$(adb devices -l | tail -n +2 | sed '/^\s*$/d')"

if [[ -z "${DEVICE_LINES_RAW}" ]]; then
  echo "No adb devices detected." >&2
  echo "Troubleshooting:" >&2
  echo "  1) Confirm USB cable supports data." >&2
  echo "  2) Enable Developer options + USB debugging on the phone." >&2
  echo "  3) Set USB mode to File transfer (MTP)." >&2
  echo "  4) Reconnect cable and accept RSA prompt on device." >&2
  exit 1
fi

if [[ -n "${TARGET_SERIAL}" ]]; then
  MATCHED_LINE="$(printf '%s\n' "${DEVICE_LINES_RAW}" | awk -v serial="${TARGET_SERIAL}" '$1 == serial {print}' | head -n 1)"
  if [[ -z "${MATCHED_LINE}" ]]; then
    echo "ADB_SERIAL='${TARGET_SERIAL}' not found in attached devices." >&2
    printf '%s\n' "${DEVICE_LINES_RAW}" >&2
    exit 1
  fi
  DEVICE_LINE="${MATCHED_LINE}"
else
  DEVICE_COUNT="$(printf '%s\n' "${DEVICE_LINES_RAW}" | wc -l | tr -d ' ')"
  if [[ "${DEVICE_COUNT}" -gt 1 ]]; then
    echo "Multiple adb devices detected. Set ADB_SERIAL to target one device." >&2
    printf '%s\n' "${DEVICE_LINES_RAW}" >&2
    exit 1
  fi
  DEVICE_LINE="$(printf '%s\n' "${DEVICE_LINES_RAW}" | head -n 1)"
fi

SERIAL="$(awk '{print $1}' <<<"${DEVICE_LINE}")"
STATE="$(awk '{print $2}' <<<"${DEVICE_LINE}")"

if [[ "${STATE}" != "device" ]]; then
  echo "Device ${SERIAL} is in state '${STATE}', not ready for automation." >&2
  if [[ "${STATE}" == "unauthorized" ]]; then
    echo "Unlock phone screen and accept the USB debugging RSA prompt." >&2
  fi
  if [[ "${STATE}" == "offline" ]]; then
    echo "Run: adb reconnect && adb devices -l" >&2
  fi
  exit 1
fi

echo "${SERIAL}"
