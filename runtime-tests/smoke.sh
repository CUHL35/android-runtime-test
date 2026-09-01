#!/usr/bin/env bash
set -euo pipefail

: "${DEVICE_SERIAL:?DEVICE_SERIAL is required}"
: "${API_LEVEL:?API_LEVEL is required}"
: "${APK_PATH:?APK_PATH is required}"
: "${PACKAGE_NAME:?PACKAGE_NAME is required}"

adb_target=(adb -s "${DEVICE_SERIAL}")
runtime_dir="${RUNNER_TEMP:-/tmp}/runtime-test-api-${API_LEVEL}"
mkdir -p "${runtime_dir}"

echo "RUNTIME_TEST_EXECUTED=smoke API=${API_LEVEL} PACKAGE=${PACKAGE_NAME}"
test -f "${APK_PATH}"
test "$("${adb_target[@]}" get-state)" = "device"
test "$("${adb_target[@]}" shell getprop sys.boot_completed | tr -d '\r')" = "1"
test "$("${adb_target[@]}" shell getprop ro.build.version.sdk | tr -d '\r')" = "${API_LEVEL}"

resolve_launcher() {
  if [[ -n "${LAUNCH_COMPONENT:-}" ]]; then
    printf '%s\n' "${LAUNCH_COMPONENT}"
    return 0
  fi

  local resolved
  resolved="$("${adb_target[@]}" shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER \
    -p "${PACKAGE_NAME}" 2>/dev/null | tr -d '\r' | tail -n 1 || true)"
  if [[ "${resolved}" == */* ]]; then
    printf '%s\n' "${resolved}"
  fi
}

launch_app() {
  local component output
  component="$(resolve_launcher || true)"
  if [[ -n "${component}" ]]; then
    echo "Resolved launcher: ${component}"
    output="$("${adb_target[@]}" shell am start -W -n "${component}" 2>&1)"
    echo "${output}"
    grep -q "Status: ok" <<<"${output}"
  else
    echo "Launcher component not resolved; using package LAUNCHER intent via Monkey."
    output="$("${adb_target[@]}" shell monkey -p "${PACKAGE_NAME}" -c android.intent.category.LAUNCHER 1 2>&1)"
    echo "${output}"
    grep -q "Events injected: 1" <<<"${output}"
  fi
}

assert_process_or_activity() {
  local pid=""
  pid="$("${adb_target[@]}" shell pidof "${PACKAGE_NAME}" 2>/dev/null | tr -d '\r' || true)"
  "${adb_target[@]}" shell dumpsys activity activities > "${runtime_dir}/activities-current.txt"
  if [[ -n "${pid}" ]]; then
    echo "Process active: ${pid}"
    return 0
  fi
  grep -Fq "${PACKAGE_NAME}" "${runtime_dir}/activities-current.txt"
}

scan_logcat() {
  local log_file="${runtime_dir}/logcat.txt"
  "${adb_target[@]}" logcat -d -v threadtime > "${log_file}"
  if grep -E "ANR in ${PACKAGE_NAME}|SecurityException.*${PACKAGE_NAME}|${PACKAGE_NAME}.*SecurityException" "${log_file}"; then
    echo "Serious runtime error detected for ${PACKAGE_NAME}." >&2
    return 1
  fi
  if grep -A8 -B2 "FATAL EXCEPTION" "${log_file}" | grep -Fq "${PACKAGE_NAME}"; then
    echo "Fatal exception detected for ${PACKAGE_NAME}." >&2
    return 1
  fi
}

"${adb_target[@]}" logcat -c
install_output="$("${adb_target[@]}" install -r -t "${APK_PATH}" 2>&1)"
echo "${install_output}"
grep -q "Success" <<<"${install_output}"
"${adb_target[@]}" shell pm path "${PACKAGE_NAME}" | grep -q '^package:'
"${adb_target[@]}" shell dumpsys package "${PACKAGE_NAME}" > "${runtime_dir}/package.txt"

launch_app
sleep 3
assert_process_or_activity
scan_logcat

echo "RUNTIME_TEST_COMPLETED=smoke API=${API_LEVEL} PACKAGE=${PACKAGE_NAME}"
