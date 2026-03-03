#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-status}"
SERIAL="$(bash "$(dirname "${BASH_SOURCE[0]}")/ensure_device.sh")"

print_status() {
  echo "Device: ${SERIAL}"
  echo -n "stay_on_while_plugged_in="
  adb -s "${SERIAL}" shell settings get global stay_on_while_plugged_in
  echo -n "window_animation_scale="
  adb -s "${SERIAL}" shell settings get global window_animation_scale
  echo -n "transition_animation_scale="
  adb -s "${SERIAL}" shell settings get global transition_animation_scale
  echo -n "animator_duration_scale="
  adb -s "${SERIAL}" shell settings get global animator_duration_scale
  echo -n "screen_off_timeout="
  adb -s "${SERIAL}" shell settings get system screen_off_timeout
}

case "${MODE}" in
  status)
    print_status
    ;;
  apply)
    adb -s "${SERIAL}" shell settings put global stay_on_while_plugged_in 3
    adb -s "${SERIAL}" shell settings put global window_animation_scale 0
    adb -s "${SERIAL}" shell settings put global transition_animation_scale 0
    adb -s "${SERIAL}" shell settings put global animator_duration_scale 0
    adb -s "${SERIAL}" shell settings put system screen_off_timeout 1800000
    print_status
    ;;
  reset)
    adb -s "${SERIAL}" shell settings put global stay_on_while_plugged_in 0
    adb -s "${SERIAL}" shell settings put global window_animation_scale 1
    adb -s "${SERIAL}" shell settings put global transition_animation_scale 1
    adb -s "${SERIAL}" shell settings put global animator_duration_scale 1
    print_status
    ;;
  *)
    echo "Usage: $0 [status|apply|reset]" >&2
    exit 1
    ;;
esac
