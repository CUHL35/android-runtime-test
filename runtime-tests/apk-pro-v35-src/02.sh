#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:?materialize root required}"
mkdir -p "$ROOT"
mkdir -p "$(dirname "$ROOT/app/src/main/assets/engine/run-build.sh")"
cat > "$ROOT/app/src/main/assets/engine/run-build.sh" <<'APKPRO_de3d152c256ae052'
#!/system/bin/sh
set -eu

PROJECT="$1"
MODE="$2"
RESULT_FILE="$3"
FILES_ROOT="${BUILDER_RUNTIME:?BUILDER_RUNTIME missing}"

PREFIX="$FILES_ROOT/usr"
# Gradle runtime is intentionally pinned to JDK 17. Project Java toolchains may use
# additional local JDKs, but APK PRO never auto-switches the Gradle JVM by guessing.
JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
JAVA="$JAVA_HOME/bin/java"
JSPAWNHELPER="$JAVA_HOME/lib/jspawnhelper"
APKSIGNER_JAVA="$JAVA"
SDK="$PREFIX/opt/android-sdk"
GRADLE_ROOT="$PREFIX/opt/gradle"
GRADLE_HOME="$FILES_ROOT/gradle-home"
BUILD_HOME="$FILES_ROOT/build-home"
DOWNLOAD_CACHE="$FILES_ROOT/download-cache"
CURL="$PREFIX/bin/curl"
UNZIP="$PREFIX/bin/unzip"
SHA256SUM="$PREFIX/bin/sha256sum"
KEYCHECK="$FILES_ROOT/SigningKeyCheck.java"

fail() { echo "ERROR: $*"; exit 1; }
progress() { echo "@@PROGRESS|$1|$2"; }

progress 36 "Kiểm tra JDK/SDK"
[ -x "$JAVA" ] || fail "Thiếu JDK runtime: $JAVA_HOME"
[ -f "$JSPAWNHELPER" ] || fail "Thiếu JDK ProcessBuilder helper: $JSPAWNHELPER"
chmod 755 "$JSPAWNHELPER" 2>/dev/null || true
[ -x "$JSPAWNHELPER" ] || fail "jspawnhelper không có quyền execute — Java không thể spawn Gradle/worker process"
[ -d "$SDK/platforms" ] || fail "Thiếu Android SDK platforms"
[ -d "$SDK/build-tools" ] || fail "Thiếu Android build-tools"
mkdir -p "$GRADLE_HOME" "$BUILD_HOME/tmp" "$DOWNLOAD_CACHE" "$GRADLE_ROOT"
# Android /system/bin/sh (mksh) materializes here-documents in TMPDIR.
# Set it before the first heredoc; otherwise mksh may fall back to /data/local,
# which is not writable by the app UID and self-update fails before Gradle starts.
export HOME="$BUILD_HOME"
export TMPDIR="$BUILD_HOME/tmp"
[ -x "$SHA256SUM" ] || fail "APK PRO ARM64 Core thiếu sha256sum"

find_wrapper() {
  if [ -f "$PROJECT/gradle/wrapper/gradle-wrapper.properties" ]; then
    printf '%s\n' "$PROJECT/gradle/wrapper/gradle-wrapper.properties"
    return
  fi
  find "$PROJECT" -maxdepth 6 -type f -name 'gradle-wrapper.properties' 2>/dev/null | head -n 1 || true
}

progress 38 "Đọc Gradle wrapper"
WRAPPER="$(find_wrapper)"
GRADLE_VERSION=""
DIST_URL=""
WRAPPER_SHA=""
if [ -n "$WRAPPER" ] && [ -f "$WRAPPER" ]; then
  DIST_URL="$(tr -d '\r' < "$WRAPPER" \
    | sed -n '/^[^#]*distributionUrl[[:space:]]*=/ { s/^[^=]*=[[:space:]]*//; p; q; }' \
    | sed 's#\\:#:#g; s#\\=#=#g')"
  WRAPPER_SHA="$(tr -d '\r' < "$WRAPPER" \
    | sed -n '/^[^#]*distributionSha256Sum[[:space:]]*=/ { s/^[^=]*=[[:space:]]*//; p; q; }' \
    | tr -d '[:space:]')"
  case "$DIST_URL" in
    *gradle-*-bin.zip*) _gv="${DIST_URL##*gradle-}"; GRADLE_VERSION="${_gv%%-bin.zip*}" ;;
    *gradle-*-all.zip*) _gv="${DIST_URL##*gradle-}"; GRADLE_VERSION="${_gv%%-all.zip*}" ;;
  esac
  case "$GRADLE_VERSION" in ''|*[!0-9.]*) GRADLE_VERSION="" ;; esac
  if [ -n "$GRADLE_VERSION" ]; then echo "Gradle wrapper: $GRADLE_VERSION ($WRAPPER)"; fi
fi
[ -n "$WRAPPER" ] && [ -f "$WRAPPER" ] || fail "Project thiếu gradle-wrapper.properties; không tự đoán Gradle"
[ -n "$GRADLE_VERSION" ] || fail "Không đọc được Gradle version từ wrapper; không tự đoán/nâng Gradle"
case "$DIST_URL" in
  https://services.gradle.org/distributions/*|https://downloads.gradle.org/distributions/*) ;;
  *) fail "Gradle distributionUrl không phải nguồn chính thức Gradle: $DIST_URL" ;;
esac

ensure_gradle() {
  GRADLE_DIR="$GRADLE_ROOT/gradle-$GRADLE_VERSION"
  if [ ! -d "$GRADLE_DIR" ]; then
    progress 40 "Tải Gradle $GRADLE_VERSION"
    [ -n "$DIST_URL" ] || DIST_URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    [ -x "$CURL" ] || fail "Thiếu curl để tải Gradle $GRADLE_VERSION"
    [ -x "$UNZIP" ] || fail "Thiếu unzip để cài Gradle $GRADLE_VERSION"
    DIST_ZIP="$DOWNLOAD_CACHE/gradle-$GRADLE_VERSION.zip"
    echo "Thiếu Gradle $GRADLE_VERSION — tự tải và cache: $DIST_URL"
    if [ ! -s "$DIST_ZIP" ]; then
      "$CURL" -L --fail --retry 3 --connect-timeout 20 -o "$DIST_ZIP.part" "$DIST_URL" || { rm -f "$DIST_ZIP.part"; fail "Không tải được Gradle $GRADLE_VERSION"; }
      mv "$DIST_ZIP.part" "$DIST_ZIP"
    fi
    EXPECTED_SHA="$WRAPPER_SHA"
    if [ -z "$EXPECTED_SHA" ]; then
      case "$DIST_URL" in
        https://services.gradle.org/*|https://downloads.gradle.org/*)
          SHA_URL="$DIST_URL.sha256"; SHA_FILE="$DOWNLOAD_CACHE/gradle-$GRADLE_VERSION.sha256"
          if "$CURL" -L --fail --retry 2 --connect-timeout 15 -o "$SHA_FILE.part" "$SHA_URL"; then
            mv "$SHA_FILE.part" "$SHA_FILE"; EXPECTED_SHA="$(tr -cd '0-9A-Fa-f' < "$SHA_FILE" | cut -c1-64)"
          else rm -f "$SHA_FILE.part"; fi ;;
      esac
    fi
    if [ -n "$EXPECTED_SHA" ]; then
      ACTUAL_SHA="$($SHA256SUM "$DIST_ZIP" | awk '{print $1}')"
      [ "$ACTUAL_SHA" = "$EXPECTED_SHA" ] || { rm -f "$DIST_ZIP"; fail "Gradle ZIP SHA-256 sai: expected=$EXPECTED_SHA actual=$ACTUAL_SHA"; }
      echo "Gradle $GRADLE_VERSION SHA-256: OK"
    else rm -f "$DIST_ZIP"; fail "Không lấy được SHA-256 chính thức cho Gradle $GRADLE_VERSION"; fi
    progress 44 "Giải nén Gradle $GRADLE_VERSION"
    TMP_GRADLE="$DOWNLOAD_CACHE/gradle-install-$GRADLE_VERSION"; rm -rf "$TMP_GRADLE"; mkdir -p "$TMP_GRADLE"
    "$UNZIP" -q "$DIST_ZIP" -d "$TMP_GRADLE" || fail "Gradle ZIP $GRADLE_VERSION bị lỗi"
    NEW_DIR="$(find "$TMP_GRADLE" -mindepth 1 -maxdepth 1 -type d -name 'gradle-*' | head -n 1 || true)"
    [ -n "$NEW_DIR" ] || fail "Gradle ZIP không có thư mục distribution"
    rm -rf "$GRADLE_DIR"; mv "$NEW_DIR" "$GRADLE_DIR"; rm -rf "$TMP_GRADLE"
    echo "Đã cache Gradle $GRADLE_VERSION"
  fi
  LAUNCHER="$(find "$GRADLE_DIR/lib" -maxdepth 1 -type f -name 'gradle-launcher-*.jar' | head -n 1 || true)"
  [ -f "$LAUNCHER" ] || fail "Gradle $GRADLE_VERSION thiếu launcher jar"
}
ensure_gradle

ACTIVE_BT="36.0.0"
if [ -s "$FILES_ROOT/active-build-tools.txt" ]; then _bt="$(head -n 1 "$FILES_ROOT/active-build-tools.txt" | tr -d '\r')"; case "$_bt" in [0-9]*.[0-9]*) ACTIVE_BT="$_bt" ;; esac; fi
BT_DIR="$SDK/build-tools/$ACTIVE_BT"; [ -d "$BT_DIR" ] || fail "Thiếu Build Tools façade $ACTIVE_BT — ToolchainManager chưa provision xong"
AAPT2="$PREFIX/bin/aapt2"; AIDL="$PREFIX/bin/aidl"; ZIPALIGN="$PREFIX/bin/zipalign"; APKSIGNER_JAR="$BT_DIR/lib/apksigner.jar"
[ -x "$AAPT2" ] || fail "APK PRO ARM64 Core thiếu aapt2"
[ -x "$AIDL" ] || fail "APK PRO ARM64 Core thiếu aidl"
[ -x "$ZIPALIGN" ] || fail "APK PRO ARM64 Core thiếu zipalign"
[ -f "$APKSIGNER_JAR" ] || fail "Build Tools façade thiếu Google apksigner.jar"
[ -x "$APKSIGNER_JAVA" ] || fail "Thiếu JDK17 để chạy apksigner"

BT_INIT="$BUILD_HOME/apk-pro-buildtools.init.gradle"
printf '%s\n' \
  "allprojects { p ->" \
  "    def forced = System.getenv('APKPRO_BUILD_TOOLS')" \
  "    if (forced != null && !forced.trim().isEmpty()) {" \
  "        ['com.android.application', 'com.android.library', 'com.android.test', 'com.android.dynamic-feature'].each { pluginId ->" \
  "            p.pluginManager.withPlugin(pluginId) {" \
  "                def androidExt = p.extensions.findByName('android')" \
  "                if (androidExt != null) {" \
  "                    androidExt.buildToolsVersion = forced" \
  "                    println('APK PRO: Build Tools forced to ' + forced + ' for ' + p.path)" \
  "                }" \
  "            }" \
  "        }" \
  "    }" \
  "}" > "$BT_INIT"
export APKPRO_BUILD_TOOLS="$ACTIVE_BT"
printf 'sdk.dir=%s\n' "$SDK" > "$PROJECT/local.properties"
JAVA_TOOLCHAIN_PATHS="$(find "$PREFIX/lib/jvm" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | paste -sd, - || true)"; [ -n "$JAVA_TOOLCHAIN_PATHS" ] || JAVA_TOOLCHAIN_PATHS="$JAVA_HOME"
export PREFIX JAVA_HOME ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK" GRADLE_USER_HOME="$GRADLE_HOME" HOME="$BUILD_HOME" TMPDIR="$BUILD_HOME/tmp"
export PATH="$JAVA_HOME/bin:$PREFIX/bin:/system/bin:/system/xbin"
GRADLE_JVM_ARGS="-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"; export JAVA_OPTS="$GRADLE_JVM_ARGS"; export GRADLE_OPTS="-Dorg.gradle.daemon=false"

run_gradle() {
  cd "$PROJECT"
  "$JAVA" -Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8 -Duser.home="$BUILD_HOME" -Djava.io.tmpdir="$BUILD_HOME/tmp" -Dorg.gradle.java.home="$JAVA_HOME" -Dorg.gradle.java.installations.paths="$JAVA_TOOLCHAIN_PATHS" -Dorg.gradle.daemon=false -classpath "$LAUNCHER" org.gradle.launcher.GradleMain --init-script "$BT_INIT" -Pandroid.aapt2FromMavenOverride="$AAPT2" --no-daemon --console=plain --no-watch-fs --max-workers=1 --stacktrace "$@" </dev/null
}
run_gradle_logged() {
  GRADLE_RUN_LOG="$BUILD_HOME/gradle-last-run.log"; GRADLE_RC_FILE="$BUILD_HOME/gradle-last-run.rc"; rm -f "$GRADLE_RUN_LOG" "$GRADLE_RC_FILE"; : > "$GRADLE_RUN_LOG"
  ( set +e; run_gradle "$@"; rc=$?; printf '%s\n' "$rc" > "$GRADLE_RC_FILE"; exit 0 ) 2>&1 | while IFS= read -r line || [ -n "$line" ]; do printf '%s\n' "$line"; printf '%s\n' "$line" >> "$GRADLE_RUN_LOG"; done
  [ -f "$GRADLE_RC_FILE" ] || return 98; rc="$(cat "$GRADLE_RC_FILE")"; [ "$rc" -eq 0 ]
}
run_build_task() { run_gradle_logged "$@"; }
dump_gradle_diagnostics() { echo "=== GRADLE DIAGNOSTIC ==="; echo "JAVA_HOME=$JAVA_HOME"; echo "GRADLE_VERSION=$GRADLE_VERSION"; echo "GRADLE_JVM_ARGS=$GRADLE_JVM_ARGS"; ls -l "$JSPAWNHELPER" 2>/dev/null || true; if [ -f "$BUILD_HOME/gradle-last-run.log" ]; then echo "--- gradle last run tail ---"; tail -n 100 "$BUILD_HOME/gradle-last-run.log" 2>/dev/null || true; fi; }
find_apk() { variant="$1"; find "$PROJECT" -type f -name '*.apk' -path '*/build/outputs/apk/*' 2>/dev/null | grep "/$variant/" | grep -v -- '-aligned\.apk$' | grep -v -- '-signed\.apk$' | head -n 1 || true; }
progress 47 "Kiểm tra compileSdk"
COMPILE_SDK="$(grep -R -h -E '^[[:space:]]*(compileSdk|compileSdkVersion)([[:space:]]*=)?[[:space:]]+[0-9]+' "$PROJECT" --include='build.gradle' --include='build.gradle.kts' 2>/dev/null | sed -n -E 's/.*(compileSdk|compileSdkVersion)([[:space:]]*=)?[[:space:]]+([0-9]+).*/\3/p' | head -n 1 || true)"
if [ -n "$COMPILE_SDK" ]; then PLATFORM_DIR="$SDK/platforms/android-$COMPILE_SDK"; if [ ! -f "$PLATFORM_DIR/android.jar" ] && [ -f "$SDK/platforms/android-$COMPILE_SDK.0/android.jar" ]; then PLATFORM_DIR="$SDK/platforms/android-$COMPILE_SDK.0"; fi; [ -f "$PLATFORM_DIR/android.jar" ] \
APKPRO_de3d152c256ae052
cat >> "$ROOT/app/src/main/assets/engine/run-build.sh" <<'APKPRO_0d6d75458e102c2b'
    || fail "Thiếu SDK platform cho compileSdk $COMPILE_SDK — ToolchainManager chưa provision xong"
  echo "SDK platform $(basename "$PLATFORM_DIR"): cache OK"
fi
progress 56 "Chuẩn bị Gradle $GRADLE_VERSION"
echo "Dynamic cache: JAVA_HOME=$JAVA_HOME | Build Tools=$ACTIVE_BT | Gradle=$GRADLE_VERSION"
echo "Mạng: Gradle/SDK/AGP/dependency tải từ upstream chính thức; ARM64 core tải một lần và giữ cache"
if [ "$MODE" = "debug" ]; then progress 60 "Gradle assembleDebug"; if ! run_build_task assembleDebug; then dump_gradle_diagnostics; fail "Gradle assembleDebug lỗi"; fi; progress 91 "Tìm debug APK"; APK="$(find_apk debug)"; [ -n "$APK" ] && [ -f "$APK" ] || fail "Không tìm thấy debug APK"; printf '%s\n' "$APK" > "$RESULT_FILE"; progress 93 "Debug APK sẵn sàng"; exit 0; fi
[ "$MODE" = "release" ] || fail "Build mode không hợp lệ: $MODE"
[ -n "${SIGNING_KEY:-}" ] || fail "Thiếu JKS/keystore"; [ -f "$SIGNING_KEY" ] || fail "Không tìm thấy JKS/keystore: $SIGNING_KEY"; [ -n "${STORE_PASS:-}" ] || fail "Thiếu Store password"; [ -n "${KEY_PASS:-}" ] || KEY_PASS="$STORE_PASS"; export SIGNING_KEY STORE_PASS KEY_ALIAS KEY_PASS
[ -f "$KEYCHECK" ] || fail "Thiếu SigningKeyCheck.java trong APK PRO"; progress 58 "Kiểm tra JKS / mật khẩu"; KEYCHECK_OUT_FILE="$BUILD_HOME/keycheck.out"; rm -f "$KEYCHECK_OUT_FILE"
if ! "$JAVA" "$KEYCHECK" "$SIGNING_KEY" >"$KEYCHECK_OUT_FILE" 2>&1; then cat "$KEYCHECK_OUT_FILE"; fail "JKS/keystore hoặc mật khẩu không hợp lệ"; fi
cat "$KEYCHECK_OUT_FILE"; RESOLVED_ALIAS="$(sed -n 's/^KEYCHECK_ALIAS=//p' "$KEYCHECK_OUT_FILE" | head -n 1)"; [ -n "$RESOLVED_ALIAS" ] || fail "Không xác định được Alias trong keystore"; KEY_ALIAS="$RESOLVED_ALIAS"; export KEY_ALIAS
progress 59 "Chuẩn bị Gradle signing"; SIGN_INIT="$BUILD_HOME/apk-builder-signing.init.gradle"
printf '%s\n' "allprojects { p ->" "    p.afterEvaluate {" "        if (p.plugins.hasPlugin('com.android.application')) {" "            def androidExt = p.extensions.findByName('android')" "            if (androidExt == null) return" "            def signing = androidExt.signingConfigs.findByName('apkBuilderInjected')" "            if (signing == null) signing = androidExt.signingConfigs.create('apkBuilderInjected')" "            signing.storeFile = new File(System.getenv('SIGNING_KEY'))" "            signing.storePassword = System.getenv('STORE_PASS')" "            signing.keyAlias = System.getenv('KEY_ALIAS')" "            signing.keyPassword = System.getenv('KEY_PASS')" "            def releaseType = androidExt.buildTypes.findByName('release')" "            if (releaseType == null) throw new GradleException('APK PRO: project không có buildType release')" "            releaseType.signingConfig = signing" "            println('APK PRO: release signing injected for ' + p.path)" "        }" "    }" "}" > "$SIGN_INIT"
progress 60 "Gradle assembleRelease + ký"; if ! run_build_task -I "$SIGN_INIT" assembleRelease; then dump_gradle_diagnostics; fail "Gradle assembleRelease/signing lỗi"; fi
progress 90 "Tìm release APK đã ký"; APK="$(find_apk release)"; [ -n "$APK" ] && [ -f "$APK" ] || fail "Không tìm thấy release APK"; [ -f "$APKSIGNER_JAR" ] || fail "Thiếu apksigner.jar"
progress 92 "Verify chữ ký Release"; if ! "$APKSIGNER_JAVA" -jar "$APKSIGNER_JAR" verify --verbose --print-certs "$APK"; then fail "APK release build xong nhưng verify chữ ký thất bại"; fi
printf '%s\n' "$APK" > "$RESULT_FILE"; progress 93 "Release APK đã ký và verify"
APKPRO_0d6d75458e102c2b
mkdir -p "$(dirname "$ROOT/app/src/main/assets/toolchain/README.txt")"
cat > "$ROOT/app/src/main/assets/toolchain/README.txt" <<'APKPRO_48c9b0983ac7099d'
APK PRO v35 lightweight ARM64 toolchain.

No JDK/SDK/Gradle/native ARM64 payload is embedded in the APK source.
arm64-core.properties pins the first-use bootstrap URL/SHA-256 and required core packages.
Fresh install downloads into app-private staging/cache, validates, then activates Core v2 with rollback.
No Termux app installation is required.
Google SDK/Build Tools and Gradle use official upstream metadata/distributions with checksum validation.
APKPRO_48c9b0983ac7099d
mkdir -p "$(dirname "$ROOT/app/src/main/assets/toolchain/arm64-core.properties")"
cat > "$ROOT/app/src/main/assets/toolchain/arm64-core.properties" <<'APKPRO_2cbb719639c0478e'
format=1
abi=arm64-v8a
coreVersion=2
bootstrapUrl=https://github.com/termux/termux-packages/releases/download/bootstrap-2026.08.30-r1%2Bapt.android-7/bootstrap-aarch64.zip
bootstrapSha256=7e92f4c435d16207cdda63d5629e666ab98441f09eefa6a8423037ef13263346
corePackages=ca-certificates,ca-certificates-java,resolv-conf,openjdk-17,aapt,aapt2,aidl
APKPRO_2cbb719639c0478e
