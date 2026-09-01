#!/usr/bin/env bash
set -euo pipefail

: "${DEVICE_SERIAL:?DEVICE_SERIAL is required}"
: "${API_LEVEL:?API_LEVEL is required}"
: "${APK_PATH:?APK_PATH is required}"
: "${PACKAGE_NAME:?PACKAGE_NAME is required}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "RUNTIME_TEST_EXECUTED=standard API=${API_LEVEL} PACKAGE=${PACKAGE_NAME}"

# Reuse the install/launch/process/logcat smoke baseline first.
# shellcheck source=smoke.sh
source "${script_dir}/smoke.sh"

runtime_dir="${RUNNER_TEMP:-/tmp}/runtime-test-api-${API_LEVEL}"

# Background -> foreground lifecycle.
"${adb_target[@]}" shell input keyevent KEYCODE_HOME
sleep 2
background_pid="$("${adb_target[@]}" shell pidof "${PACKAGE_NAME}" 2>/dev/null | tr -d '\r' || true)"
if [[ -z "${background_pid}" ]]; then
  "${adb_target[@]}" shell dumpsys activity activities > "${runtime_dir}/activities-background.txt"
  grep -Fq "${PACKAGE_NAME}" "${runtime_dir}/activities-background.txt"
fi
launch_app
sleep 2
assert_process_or_activity

# Force-stop -> relaunch.
"${adb_target[@]}" shell am force-stop "${PACKAGE_NAME}"
sleep 2
stopped_pid="$("${adb_target[@]}" shell pidof "${PACKAGE_NAME}" 2>/dev/null | tr -d '\r' || true)"
test -z "${stopped_pid}"
launch_app
sleep 3
assert_process_or_activity

# Basic configuration/lifecycle exercise: controlled rotation and restore.
original_auto="$("${adb_target[@]}" shell settings get system accelerometer_rotation 2>/dev/null | tr -d '\r' || true)"
original_rotation="$("${adb_target[@]}" shell settings get system user_rotation 2>/dev/null | tr -d '\r' || true)"
restore_rotation() {
  if [[ "${original_auto}" =~ ^[01]$ ]]; then
    "${adb_target[@]}" shell settings put system accelerometer_rotation "${original_auto}" >/dev/null 2>&1 || true
  fi
  if [[ "${original_rotation}" =~ ^[0-3]$ ]]; then
    "${adb_target[@]}" shell settings put system user_rotation "${original_rotation}" >/dev/null 2>&1 || true
  fi
}
trap restore_rotation EXIT
"${adb_target[@]}" shell settings put system accelerometer_rotation 0
"${adb_target[@]}" shell settings put system user_rotation 1
sleep 2
assert_process_or_activity
"${adb_target[@]}" shell settings put system user_rotation 0
sleep 2
assert_process_or_activity
restore_rotation
trap - EXIT

# Light deterministic Monkey pass. Relaunch afterwards so final state is known.
monkey_output="$("${adb_target[@]}" shell monkey -p "${PACKAGE_NAME}" \
  -s 4242 --throttle 100 --pct-syskeys 0 --pct-appswitch 0 20 2>&1)"
echo "${monkey_output}"
grep -q "Events injected: 20" <<<"${monkey_output}"
launch_app
sleep 2
assert_process_or_activity

scan_logcat

echo "RUNTIME_TEST_COMPLETED=standard API=${API_LEVEL} PACKAGE=${PACKAGE_NAME}"
