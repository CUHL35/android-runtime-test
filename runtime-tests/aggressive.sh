#!/usr/bin/env bash
set -euo pipefail

: "${DEVICE_SERIAL:?DEVICE_SERIAL is required}"
: "${API_LEVEL:?API_LEVEL is required}"
: "${APK_PATH:?APK_PATH is required}"
: "${PACKAGE_NAME:?PACKAGE_NAME is required}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "RUNTIME_TEST_EXECUTED=aggressive API=${API_LEVEL} PACKAGE=${PACKAGE_NAME}"

# Aggressive extends the standard profile; it is never selected by default.
# shellcheck source=standard.sh
source "${script_dir}/standard.sh"

for cycle in 1 2 3; do
  echo "Aggressive force-stop/relaunch cycle ${cycle}"
  "${adb_target[@]}" shell am force-stop "${PACKAGE_NAME}"
  sleep 1
  test -z "$("${adb_target[@]}" shell pidof "${PACKAGE_NAME}" 2>/dev/null | tr -d '\r' || true)"
  launch_app
  sleep 2
  assert_process_or_activity

done

for cycle in 1 2 3; do
  echo "Aggressive background/foreground cycle ${cycle}"
  "${adb_target[@]}" shell input keyevent KEYCODE_HOME
  sleep 1
  launch_app
  sleep 1
  assert_process_or_activity
done

original_auto="$("${adb_target[@]}" shell settings get system accelerometer_rotation 2>/dev/null | tr -d '\r' || true)"
original_rotation="$("${adb_target[@]}" shell settings get system user_rotation 2>/dev/null | tr -d '\r' || true)"
restore_rotation_aggressive() {
  if [[ "${original_auto}" =~ ^[01]$ ]]; then
    "${adb_target[@]}" shell settings put system accelerometer_rotation "${original_auto}" >/dev/null 2>&1 || true
  fi
  if [[ "${original_rotation}" =~ ^[0-3]$ ]]; then
    "${adb_target[@]}" shell settings put system user_rotation "${original_rotation}" >/dev/null 2>&1 || true
  fi
}
trap restore_rotation_aggressive EXIT
"${adb_target[@]}" shell settings put system accelerometer_rotation 0
for rotation in 0 1 2 3; do
  "${adb_target[@]}" shell settings put system user_rotation "${rotation}"
  sleep 1
  assert_process_or_activity
done
restore_rotation_aggressive
trap - EXIT

monkey_output="$("${adb_target[@]}" shell monkey -p "${PACKAGE_NAME}" \
  -s 8484 --throttle 75 --pct-syskeys 0 --pct-appswitch 0 100 2>&1)"
echo "${monkey_output}"
grep -q "Events injected: 100" <<<"${monkey_output}"
launch_app
sleep 2
assert_process_or_activity
scan_logcat

echo "RUNTIME_TEST_COMPLETED=aggressive API=${API_LEVEL} PACKAGE=${PACKAGE_NAME}"
