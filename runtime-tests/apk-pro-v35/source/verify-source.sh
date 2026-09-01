#!/usr/bin/env sh
set -eu

fail() { echo "FAIL: $*" >&2; exit 1; }
req() { [ -s "$1" ] || fail "missing/empty $1"; }

for f in settings.gradle build.gradle app/build.gradle gradle.properties gradlew gradlew.bat \
  gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.jar \
  app/src/main/AndroidManifest.xml app/src/main/java/com/longdev/apkbuilder/MainActivity.java \
  app/src/main/java/com/longdev/apkbuilder/core/ToolchainManager.java \
  app/src/main/java/com/longdev/apkbuilder/core/AndroidSdkRepository.java \
  app/src/main/assets/engine/run-build.sh app/src/main/assets/engine/provision-packages.sh \
  app/src/main/assets/toolchain/arm64-core.properties \
  release-signing/APK-Builder-release.jks release-signing/SIGNING-KEY-INFO.txt; do req "$f"; done

grep -q "version '9.3.2'" build.gradle || fail "AGP != 9.3.2"
grep -q "compileSdk 37" app/build.gradle || fail "compileSdk != 37"
grep -q "minSdk 30" app/build.gradle || fail "minSdk != 30"
grep -q "targetSdk 33" app/build.gradle || fail "targetSdk != 33"
grep -q "versionCode 36" app/build.gradle || fail "versionCode != 36"
grep -q "versionName '36'" app/build.gradle || fail "versionName != 36"
grep -q "buildToolsVersion '36.0.0'" app/build.gradle || fail "Build Tools != 36.0.0"
grep -q "gradle-9.5.0-bin.zip" gradle/wrapper/gradle-wrapper.properties || fail "Gradle != 9.5.0"
grep -q "distributionSha256Sum=553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746" gradle/wrapper/gradle-wrapper.properties || fail "Gradle SHA missing/wrong"
grep -q "applicationId 'com.apkbld'" app/build.gradle || fail "applicationId changed"
if grep -RIn --exclude='*.jks' --exclude='SOURCE-FILES-SHA256.txt' -E "version '9\.4\.0'|gradle-9\.6\.0-bin\.zip" . >/dev/null 2>&1; then
  fail "stale unpublished AGP/Gradle final reference remains"
fi
grep -q "namespace 'com.longdev.apkbuilder'" app/build.gradle || fail "namespace changed"

# Baseline signing material must remain byte-for-byte unchanged.
[ "$(sha256sum release-signing/APK-Builder-release.jks | awk '{print $1}')" = "bda2537e844918d913ea8096c050efb068f419ad2e282fbd60e70a2730a61a75" ] || fail "release JKS changed"
[ "$(sha256sum release-signing/SIGNING-KEY-INFO.txt | awk '{print $1}')" = "7e58c5067927be7e34444c9cc89491cb7eccbb58f060d0e4e433c8f5b30e0681" ] || fail "signing info changed"

# Lightweight ARM64 manifest is pinned and source contains no large embedded toolchain payload.
grep -q '^abi=arm64-v8a$' app/src/main/assets/toolchain/arm64-core.properties || fail "ARM64 ABI manifest missing"
grep -q '^coreVersion=2$' app/src/main/assets/toolchain/arm64-core.properties || fail "Core v2 manifest missing"
grep -q '^bootstrapSha256=7e92f4c435d16207cdda63d5629e666ab98441f09eefa6a8423037ef13263346$' app/src/main/assets/toolchain/arm64-core.properties || fail "bootstrap SHA changed"
grep -q '^corePackages=ca-certificates,ca-certificates-java,resolv-conf,openjdk-17,aapt,aapt2,aidl$' app/src/main/assets/toolchain/arm64-core.properties || fail "core package manifest changed"
grep -q 'installPackagesAtomically' app/src/main/java/com/longdev/apkbuilder/core/ToolchainManager.java || fail "atomic core install path missing"
grep -q 'recoverInterruptedSwap' app/src/main/java/com/longdev/apkbuilder/core/ToolchainManager.java || fail "core swap recovery missing"
grep -q -- '--download-only' app/src/main/assets/engine/provision-packages.sh || fail "package path is not download-only"
if grep -nE '(^|[[:space:]])dpkg[[:space:]]+(-i|--install)' app/src/main/assets/engine/provision-packages.sh >/dev/null 2>&1; then
  fail "package script must not dpkg-install into Termux prefix"
fi
grep -q 'APKSIGNER_JAVA="$JAVA"' app/src/main/assets/engine/run-build.sh || fail "apksigner must use JDK17"
grep -q 'APKSIGNER_JAR="$BT_DIR/lib/apksigner.jar"' app/src/main/assets/engine/run-build.sh || fail "Google apksigner path missing"
grep -q 'copyAtomic' app/src/main/java/com/longdev/apkbuilder/core/AndroidSdkRepository.java || fail "Build Tools atomic copy missing"
grep -q 'menu.getMenu().add("Tải toolchain")' app/src/main/java/com/longdev/apkbuilder/MainActivity.java || fail "manual toolchain menu missing"
grep -q 'PREF_TOOLCHAIN_PROMPT_SHOWN' app/src/main/java/com/longdev/apkbuilder/MainActivity.java || fail "first-launch toolchain prompt state missing"
grep -q 'prefetchCommonSdk' app/src/main/java/com/longdev/apkbuilder/core/ToolchainManager.java || fail "common SDK prefetch missing"
grep -q 'platforms;android-37.0' app/src/main/java/com/longdev/apkbuilder/core/ToolchainManager.java || fail "API 37.0 prefetch coordinate missing"
grep -q 'coordinate = "platforms;android-" + api + ".0"' app/src/main/java/com/longdev/apkbuilder/core/AndroidSdkRepository.java || fail "minor-version SDK fallback missing"
grep -q 'android-\$COMPILE_SDK.0/android.jar' app/src/main/assets/engine/run-build.sh || fail "minor-version SDK build preflight missing"
grep -q 'validateCoreRuntime' app/src/main/java/com/longdev/apkbuilder/core/ToolchainManager.java || fail "core runtime smoke gate missing"
grep -q 'MAX_ATTEMPTS = 3' app/src/main/java/com/longdev/apkbuilder/core/NetworkFiles.java || fail "download retry missing"
grep -q 'Content-Range' app/src/main/java/com/longdev/apkbuilder/core/NetworkFiles.java || fail "download resume validation missing"
grep -q 'StandardCopyOption.ATOMIC_MOVE' app/src/main/java/com/longdev/apkbuilder/core/NetworkFiles.java || fail "download atomic finalize missing"

# No build/cache/APK outputs or multi-megabyte toolchain payload in source.
for d in build .gradle app/build; do [ ! -e "$d" ] || fail "forbidden generated path: $d"; done
find . -type d \( -name build -o -name .gradle \) -print | grep -q . && fail "nested build/.gradle found" || true
find . -type f -name '*.apk' -print | grep -q . && fail "APK output embedded in source" || true
find app/src/main/assets -type f -size +5M -print | grep -q . && fail "large binary payload embedded in assets" || true
find . -type f -size 0 -print | grep -q . && fail "zero-byte file found" || true

sh -n gradlew
sh -n build-local.sh
sh -n app/src/main/assets/engine/run-build.sh
sh -n app/src/main/assets/engine/provision-packages.sh
unzip -tqq gradle/wrapper/gradle-wrapper.jar >/dev/null
unzip -l gradle/wrapper/gradle-wrapper.jar | grep -q 'ApkProGradleWrapper.class' || fail "wrapper bootstrap class missing"
unzip -p gradle/wrapper/gradle-wrapper.jar META-INF/MANIFEST.MF | grep -q 'Main-Class: ApkProGradleWrapper' || fail "wrapper Main-Class mismatch"

# settings module includes must resolve.
for module in $(sed -n "s/.*include[[:space:]]*['\"]:\([^'\"]*\)['\"].*/\1/p" settings.gradle); do
  [ -d "$module" ] || fail "included module missing: $module"
done

if [ -s SOURCE-FILES-SHA256.txt ]; then
  sha256sum -c SOURCE-FILES-SHA256.txt >/dev/null || fail "source file hash manifest mismatch"
fi

echo "STATIC_PREFLIGHT=PASS"
