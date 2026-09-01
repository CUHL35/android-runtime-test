#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:?materialize root required}"
mkdir -p "$ROOT"
mkdir -p "$(dirname "$ROOT/app/build.gradle")"
cat > "$ROOT/app/build.gradle" <<'APKPRO_d01364dec5261e64'
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
        versionCode 35
        versionName '35'
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
APKPRO_d01364dec5261e64
mkdir -p "$(dirname "$ROOT/app/src/main/AndroidManifest.xml")"
cat > "$ROOT/app/src/main/AndroidManifest.xml" <<'APKPRO_13105db1d42b9f02'
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
APKPRO_13105db1d42b9f02
mkdir -p "$(dirname "$ROOT/app/src/main/assets/engine/SigningKeyCheck.java")"
cat > "$ROOT/app/src/main/assets/engine/SigningKeyCheck.java" <<'APKPRO_b4dc56894d6a70cc'
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
APKPRO_b4dc56894d6a70cc
mkdir -p "$(dirname "$ROOT/app/src/main/assets/engine/provision-packages.sh")"
cat > "$ROOT/app/src/main/assets/engine/provision-packages.sh" <<'APKPRO_119c2861eb167c6a'
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

echo "APKPRO_PACKAGE_CACHE=$ARCHIVES"
echo "TERMUX_PACKAGES=$*"
# Download only: dpkg must never install into /data/data/com.termux. Signed APT metadata
# validates package hashes; APK PRO keeps downloaded .deb files as cache, extracts them only
# into staging, relocates to its equal-length private prefix, validates, then atomically swaps.
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
APKPRO_119c2861eb167c6a
