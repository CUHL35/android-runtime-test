#!/usr/bin/env bash
set -euo pipefail

: "${DEVICE_SERIAL:?DEVICE_SERIAL is required}"
: "${API_LEVEL:?API_LEVEL is required}"
: "${PACKAGE_NAME:?PACKAGE_NAME is required}"

adb_target=(adb -s "${DEVICE_SERIAL}")
echo "APP_RUNTIME_TEST_EXECUTED=${PACKAGE_NAME} API=${API_LEVEL}"
"${adb_target[@]}" shell pm path "${PACKAGE_NAME}" | grep -q '^package:'
pid="$("${adb_target[@]}" shell pidof "${PACKAGE_NAME}" 2>/dev/null | tr -d '\r' || true)"
if [[ -z "${pid}" ]]; then
  "${adb_target[@]}" shell dumpsys activity activities | grep -Fq "${PACKAGE_NAME}"
fi
echo "APP_RUNTIME_TEST_COMPLETED=${PACKAGE_NAME} API=${API_LEVEL}"
