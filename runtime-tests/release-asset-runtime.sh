#!/usr/bin/env bash
set -euo pipefail

: "${DEVICE_SERIAL:?DEVICE_SERIAL is required}"
: "${API_LEVEL:?API_LEVEL is required}"
: "${PACKAGE_NAME:?PACKAGE_NAME is required}"

adb_target=(adb -s "${DEVICE_SERIAL}")

test "$("${adb_target[@]}" get-state)" = "device"
test "$("${adb_target[@]}" shell getprop sys.boot_completed | tr -d '\r')" = "1"
"${adb_target[@]}" shell pm path "${PACKAGE_NAME}" | grep -q '^package:'

runtime_dir="${RUNNER_TEMP:-/tmp}/release-runtime-api-${API_LEVEL}"
mkdir -p "${runtime_dir}"
"${adb_target[@]}" shell dumpsys package "${PACKAGE_NAME}" > "${runtime_dir}/package.txt"
"${adb_target[@]}" shell dumpsys activity activities > "${runtime_dir}/activities-before.txt"

has_launcher=false
launcher_probe="$("${adb_target[@]}" shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "${PACKAGE_NAME}" 2>/dev/null | tr -d '\r' | tail -n 1 || true)"
if [[ "${launcher_probe}" == */* ]]; then
  has_launcher=true
fi

launch_with_monkey() {
  local launch_output
  launch_output="$("${adb_target[@]}" shell monkey -p "${PACKAGE_NAME}" -c android.intent.category.LAUNCHER 1 2>&1)"
  echo "${launch_output}"
  grep -q 'Events injected: 1' <<<"${launch_output}"
}

assert_visible_or_running() {
  "${adb_target[@]}" shell dumpsys activity activities > "${runtime_dir}/activities-current.txt"
  if grep -Fq "${PACKAGE_NAME}" "${runtime_dir}/activities-current.txt"; then
    return 0
  fi
  "${adb_target[@]}" shell pidof "${PACKAGE_NAME}" | tr -d '\r' | grep -Eq '^[0-9]+'
}

if [ "${API_LEVEL}" = "30" ]; then
  echo "Runtime profile: API30 full"
  grep -E 'requested permissions:|install permissions:|runtime permissions:|granted=(true|false)' "${runtime_dir}/package.txt" || true

  "${adb_target[@]}" shell input keyevent KEYCODE_HOME
  sleep 2
  if [ "${has_launcher}" = true ]; then
    launch_with_monkey
    sleep 3
    assert_visible_or_running

    "${adb_target[@]}" shell am force-stop "${PACKAGE_NAME}"
    sleep 2
    launch_with_monkey
    sleep 3
    assert_visible_or_running
  else
    echo "No launcher activity resolved; lifecycle relaunch NOT TESTED."
    assert_visible_or_running
  fi
elif [ "${API_LEVEL}" = "31" ]; then
  echo "Runtime profile: API31 compatibility smoke"
  "${adb_target[@]}" shell input keyevent KEYCODE_HOME
  sleep 2
  if [ "${has_launcher}" = true ]; then
    launch_with_monkey
    sleep 3
  fi
  assert_visible_or_running
else
  echo "Unexpected API level: ${API_LEVEL}" >&2
  exit 1
fi

"${adb_target[@]}" logcat -d -v threadtime > "${runtime_dir}/logcat.txt"
if grep -E "FATAL EXCEPTION|ANR in ${PACKAGE_NAME}|SecurityException.*${PACKAGE_NAME}" "${runtime_dir}/logcat.txt"; then
  echo "Serious runtime error detected." >&2
  exit 1
fi

echo "Functional runtime checks completed for API ${API_LEVEL}."
