#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:?materialize root required}"
mkdir -p "$ROOT"
mkdir -p "$(dirname "$ROOT/settings.gradle")"
cat > "$ROOT/settings.gradle" <<'APKPRO_05efc8b165_0'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "APKBuilder"
include ':app'
APKPRO_05efc8b165_0
mkdir -p "$(dirname "$ROOT/build.gradle")"
cat > "$ROOT/build.gradle" <<'APKPRO_f078667362_0'
plugins {
    id 'com.android.application' version '9.4.0' apply false
}
APKPRO_f078667362_0
mkdir -p "$(dirname "$ROOT/gradle.properties")"
cat > "$ROOT/gradle.properties" <<'APKPRO_2afbb999f0_0'
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
org.gradle.daemon=false
org.gradle.parallel=false
org.gradle.workers.max=1
android.useAndroidX=false
APKPRO_2afbb999f0_0
mkdir -p "$(dirname "$ROOT/gradlew")"
cat > "$ROOT/gradlew" <<'APKPRO_5bbfa66edb_0'
#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
  [ -x "$JAVA_CMD" ] || { echo "ERROR: JAVA_HOME does not contain executable bin/java" >&2; exit 1; }
else
  JAVA_CMD=$(command -v java 2>/dev/null || true)
  [ -n "$JAVA_CMD" ] || { echo "ERROR: Java 17+ is required to run the Gradle wrapper." >&2; exit 1; }
fi
exec "$JAVA_CMD" -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
APKPRO_5bbfa66edb_0
mkdir -p "$(dirname "$ROOT/gradlew.bat")"
cat > "$ROOT/gradlew.bat" <<'APKPRO_2a45a911a8_0'
@echo off
setlocal
set "APP_HOME=%~dp0"
if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" -jar "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" %*
exit /b %ERRORLEVEL%
APKPRO_2a45a911a8_0
mkdir -p "$(dirname "$ROOT/gradle/wrapper/gradle-wrapper.properties")"
cat > "$ROOT/gradle/wrapper/gradle-wrapper.properties" <<'APKPRO_fbe448ebfc_0'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.0-bin.zip
distributionSha256Sum=bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
APKPRO_fbe448ebfc_0
mkdir -p "$(dirname "$ROOT/app/build.gradle")"
cat > "$ROOT/app/build.gradle" <<'APKPRO_f4a01d6a4f_0'
plugins {
    id 'com.android.application'
}

android {
    namespace 'com.longdev.apkbuilder'
    compileSdk 37
    buildToolsVersion '36.0.0'

    defaultConfig {
        applicationId 'com.apkbld'
        minSdk 30
        targetSdk 31
        versionCode 34
        versionName '34'
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            minifyEnabled false
            shrinkResources false
        }
    }
}
APKPRO_f4a01d6a4f_0
mkdir -p "$(dirname "$ROOT/app/src/main/AndroidManifest.xml")"
cat > "$ROOT/app/src/main/AndroidManifest.xml" <<'APKPRO_8c55c3ccc2_0'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/AppTheme">
        <activity
            android:name="com.longdev.apkbuilder.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name="com.longdev.apkbuilder.core.BuildService"
            android:exported="false"
            android:foregroundServiceType="dataSync"
            android:stopWithTask="false" />
    </application>
</manifest>
APKPRO_8c55c3ccc2_0
mkdir -p "$(dirname "$ROOT/app/src/main/assets/engine/SigningKeyCheck.java")"
cat > "$ROOT/app/src/main/assets/engine/SigningKeyCheck.java" <<'APKPRO_f6aaa3031d_0'
import java.io.File;
import java.io.FileInputStream;
import java.security.Key;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/** Small JDK-side preflight used before assembleRelease. Never prints passwords. */
public final class SigningKeyCheck {
    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static void fail(String message) {
        System.err.println("KEYCHECK_ERROR=" + message);
        System.exit(2);
    }

    public static void main(String[] args) {
        if (args.length != 1) fail("Thiếu đường dẫn JKS/keystore");
        File file = new File(args[0]);
        if (!file.isFile()) fail("Không tìm thấy JKS/keystore");

        String storePass = env("STORE_PASS");
        String requestedAlias = env("KEY_ALIAS").trim();
        String keyPass = env("KEY_PASS");
        if (storePass.isEmpty()) fail("Thiếu Store password");
        if (keyPass.isEmpty()) keyPass = storePass;

        String[] types = {"JKS", "PKCS12"};
        String lastProblem = "Không mở được keystore";

        for (String type : types) {
            try {
                KeyStore store = KeyStore.getInstance(type);
                try (FileInputStream input = new FileInputStream(file)) {
                    store.load(input, storePass.toCharArray());
                }

                List<String> keyAliases = new ArrayList<>();
                Enumeration<String> aliases = store.aliases();
                while (aliases.hasMoreElements()) {
                    String alias = aliases.nextElement();
                    if (store.isKeyEntry(alias)) keyAliases.add(alias);
                }
                if (keyAliases.isEmpty()) {
                    lastProblem = "Keystore không có private-key entry";
                    continue;
                }

                String alias = requestedAlias;
                if (alias.isEmpty()) {
                    if (keyAliases.size() == 1) {
                        alias = keyAliases.get(0);
                    } else {
                        fail("Keystore có nhiều alias: " + String.join(", ", keyAliases) + ". Hãy nhập Alias.");
                    }
                }
                if (!store.containsAlias(alias) || !store.isKeyEntry(alias)) {
                    fail("Alias không tồn tại hoặc không phải private key: " + alias);
                }

                Key key;
                try {
                    key = store.getKey(alias, keyPass.toCharArray());
                } catch (Exception wrongKeyPassword) {
                    fail("Key password sai cho alias: " + alias);
                    return;
                }
                if (key == null) fail("Không đọc được private key của alias: " + alias);

                System.out.println("KEYCHECK_OK=1");
                System.out.println("KEYCHECK_TYPE=" + type);
                System.out.println("KEYCHECK_ALIAS=" + alias);
                return;
            } catch (java.io.IOException wrongStorePasswordOrFormat) {
                String message = wrongStorePasswordOrFormat.getMessage();
                lastProblem = message == null || message.trim().isEmpty()
                        ? "Store password sai hoặc format keystore không hợp lệ"
                        : message;
            } catch (Exception unsupported) {
                String message = unsupported.getMessage();
                lastProblem = message == null || message.trim().isEmpty()
                        ? unsupported.getClass().getSimpleName()
                        : message;
            }
        }

        fail("Store password sai hoặc JKS/PKCS12 không hợp lệ: " + lastProblem);
    }
}
APKPRO_f6aaa3031d_0
mkdir -p "$(dirname "$ROOT/app/src/main/assets/engine/provision-packages.sh")"
cat > "$ROOT/app/src/main/assets/engine/provision-packages.sh" <<'APKPRO_405042fb05_0'
#!/system/bin/sh
set -eu

FILES_ROOT="$1"
shift
[ "$#" -gt 0 ] || { echo "ERROR: no Termux packages requested"; exit 2; }

PREFIX="$FILES_ROOT/usr"
HOME="$FILES_ROOT/build-home"
TMPDIR="$HOME/tmp"
CACHE="$FILES_ROOT/download-cache"
APT_CACHE="$CACHE/apt"
ARCHIVES="$APT_CACHE/archives"
STAGE="$CACHE/pkg-stage"
APT="$PREFIX/bin/apt-get"
DPKG_DEB="$PREFIX/bin/dpkg-deb"

export PREFIX HOME TMPDIR
export PATH="$PREFIX/bin:/system/bin:/system/xbin"
# Termux packages on Android >= 7 are built to run without LD_LIBRARY_PATH.
unset LD_LIBRARY_PATH 2>/dev/null || true
export DEBIAN_FRONTEND=noninteractive

mkdir -p "$HOME" "$TMPDIR" "$CACHE" "$ARCHIVES/partial"
[ -x "$APT" ] || { echo "ERROR: bootstrap missing apt-get"; exit 3; }
[ -x "$DPKG_DEB" ] || { echo "ERROR: bootstrap missing dpkg-deb"; exit 3; }

echo "TERMUX_APT_UPDATE=1"
echo "TERMUX_APT_ARCHIVES=$ARCHIVES"
# Termux APT is compiled with /data/data/com.termux/cache/apt as CACHE_DIR.
# Override it explicitly so APK PRO never touches another package's private cache path.
"$APT" -o "Dir::Cache=$APT_CACHE" -o "Dir::Cache::archives=$ARCHIVES" update

rm -f "$ARCHIVES"/*.deb 2>/dev/null || true

echo "TERMUX_PACKAGES=$*"
# Download only: dpkg must never write into /data/data/com.termux. APK PRO extracts
# packages to a private staging root and relocates the equal-length Termux prefix in Java.
"$APT" -o "Dir::Cache=$APT_CACHE" -o "Dir::Cache::archives=$ARCHIVES" \
  -y --download-only --no-install-recommends install "$@"

rm -rf "$STAGE"
mkdir -p "$STAGE"
found=0
for deb in "$ARCHIVES"/*.deb; do
  [ -f "$deb" ] || continue
  found=1
  echo "EXTRACT_DEB=$(basename "$deb")"
  "$DPKG_DEB" -x "$deb" "$STAGE"
done
[ "$found" -eq 1 ] || { echo "ERROR: apt downloaded no .deb files"; exit 4; }

echo "PKG_STAGE=$STAGE"
APKPRO_405042fb05_0
