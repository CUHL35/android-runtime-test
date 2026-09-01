#!/usr/bin/env sh
set -eu

# Host-side build helper. Requires Java 17+ and an Android SDK installed from official Google tooling.
# It does not provision a host toolchain or change project versions.
command -v java >/dev/null 2>&1 || { echo "ERROR: java not found" >&2; exit 1; }
JAVA_MAJOR=$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')
[ -n "$JAVA_MAJOR" ] || { echo "ERROR: cannot detect Java version" >&2; exit 1; }
[ "$JAVA_MAJOR" -ge 17 ] || { echo "ERROR: Java 17+ required" >&2; exit 1; }
[ -n "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}" ] || { echo "ERROR: ANDROID_SDK_ROOT/ANDROID_HOME required" >&2; exit 1; }
exec ./gradlew "$@"
