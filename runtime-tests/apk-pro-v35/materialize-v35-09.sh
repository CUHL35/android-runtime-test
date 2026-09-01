#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:?materialize root required}"
mkdir -p "$ROOT"
mkdir -p "$(dirname "$ROOT/app/src/main/java/com/longdev/apkbuilder/core/AndroidSdkRepository.java")"
cat > "$ROOT/app/src/main/java/com/longdev/apkbuilder/core/AndroidSdkRepository.java" <<'APKPRO_80bca391982a4e3e'
package com.longdev.apkbuilder.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

/** Installs platform packages from Google's live Android SDK repository metadata. */
public final class AndroidSdkRepository {
    private static final String REPOSITORY_URL = "https://dl.google.com/android/repository/repository2-3.xml";
    private static final String ARCHIVE_BASE = "https://dl.google.com/android/repository/";

    private final File runtime;

    public AndroidSdkRepository(File runtime) {
        this.runtime = runtime;
    }

    public File ensurePlatform(int api, BuildListener listener) throws IOException {
        if (api <= 0) throw new IOException("compileSdk không hợp lệ: " + api);
        File sdk = new File(runtime, "usr/opt/android-sdk");
        File platforms = new File(sdk, "platforms");

        // Android 37 introduced minor-versioned SDK package coordinates. Gradle still
        // declares compileSdk 37, while Google's repository publishes the base platform
        // as platforms;android-37.0 and installs it under platforms/android-37.0.
        // Prefer an existing exact cache first, then the minor .0 cache.
        File exactCached = new File(platforms, "android-" + api);
        if (new File(exactCached, "android.jar").isFile()) {
            if (listener != null) listener.onLog("SDK platform android-" + api + " đã có trong cache");
            return exactCached;
        }
        File minorZeroCached = new File(platforms, "android-" + api + ".0");
        if (new File(minorZeroCached, "android.jar").isFile()) {
            if (listener != null) listener.onLog("SDK platform android-" + api + ".0 đã có trong cache");
            return minorZeroCached;
        }

        if (listener != null) listener.onLog("Thiếu SDK cho compileSdk " + api
                + " — đọc repository chính thức của Google...");
        File cacheDir = new File(runtime, "download-cache/android-sdk");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) throw new IOException("Không tạo được Android SDK cache");
        File metadata = new File(cacheDir, "repository2-3.xml");
        NetworkFiles.download(REPOSITORY_URL, metadata, listener, "Android repository metadata");

        String coordinate = "platforms;android-" + api;
        PackageInfo info = findPackage(metadata, coordinate, null);
        if (info == null) {
            // Current Google repository uses this form for API 37: platforms;android-37.0.
            coordinate = "platforms;android-" + api + ".0";
            info = findPackage(metadata, coordinate, null);
        }
        if (info == null) {
            throw new IOException("Google SDK repository chưa có stable platform cho compileSdk " + api
                    + " (đã thử platforms;android-" + api + " và platforms;android-" + api + ".0)");
        }

        String platformDirName = coordinate.substring("platforms;".length());
        File platform = new File(platforms, platformDirName);
        if (listener != null) listener.onLog("SDK coordinate: " + coordinate);

        File archive = new File(cacheDir, info.url.substring(info.url.lastIndexOf('/') + 1));
        String archiveUrl = new java.net.URL(new java.net.URL(ARCHIVE_BASE), info.url).toString();
        if (!archive.isFile()) NetworkFiles.download(archiveUrl, archive, listener, "SDK " + platformDirName);
        try {
            NetworkFiles.verify(archive, info.algorithm, info.checksum);
        } catch (IOException stale) {
            archive.delete();
            NetworkFiles.download(archiveUrl, archive, listener, "SDK " + platformDirName);
            NetworkFiles.verify(archive, info.algorithm, info.checksum);
        }
        if (listener != null) listener.onLog("Checksum " + info.algorithm.toUpperCase(Locale.US)
                + " SDK " + platformDirName + ": OK");

        File stage = new File(cacheDir, "platform-stage-" + platformDirName.replace('.', '_'));
        IoUtils.deleteRecursively(stage);
        if (!stage.mkdirs()) throw new IOException("Không tạo được SDK stage");
        try {
            try (FileInputStream input = new FileInputStream(archive)) {
                ZipUtils.unzip(input, stage);
            }

            File extracted = findAndroidJarParent(stage, 0);
            if (extracted == null) throw new IOException("SDK platform ZIP không có android.jar");
            if (!platforms.exists() && !platforms.mkdirs()) throw new IOException("Không tạo được platforms cache");
            IoUtils.deleteRecursively(platform);
            moveTree(extracted, platform);
        } finally {
            IoUtils.deleteRecursively(stage);
        }
        if (!new File(platform, "android.jar").isFile()) {
            throw new IOException("Cài SDK " + platformDirName + " chưa hoàn tất");
        }
        if (listener != null) listener.onLog("Đã cache " + coordinate);
        return platform;
    }

    /**
     * Creates an SDK Build Tools façade: Android-native ARM64 binaries come from the
     * APK PRO core, while architecture-neutral apksigner/core-lambda-stubs come from
     * Google's official Build Tools archive for the exact requested revision.
     */
    public File ensureBuildToolsFacade(String revision, BuildListener listener) throws IOException {
        if (revision == null || !revision.matches("[0-9]+(?:\\.[0-9]+){1,2}")) revision = "36.0.0";
        File prefix = new File(runtime, "usr");
        File sdk = new File(prefix, "opt/android-sdk");
        File target = new File(sdk, "build-tools/" + revision);
        File aapt2 = new File(prefix, "bin/aapt2");
        File aapt = new File(prefix, "bin/aapt");
        File aidl = new File(prefix, "bin/aidl");
        File zipalign = new File(prefix, "bin/zipalign");

        if (!aapt2.isFile()) throw new IOException("APK PRO ARM64 Core thiếu aapt2");
        if (!aidl.isFile()) throw new IOException("APK PRO ARM64 Core thiếu aidl");
        if (!zipalign.isFile()) throw new IOException("APK PRO ARM64 Core thiếu zipalign");

        ensureOfficialBuildToolsJavaArtifacts(revision, target, listener);
        File apksignerJar = new File(target, "lib/apksigner.jar");
        File coreLambda = new File(target, "core-lambda-stubs.jar");
        if (!apksignerJar.isFile()) throw new IOException("Google Build Tools thiếu lib/apksigner.jar");
        if (!coreLambda.isFile()) throw new IOException("Google Build Tools thiếu core-lambda-stubs.jar");

        if (!target.exists() && !target.mkdirs()) throw new IOException("Không tạo được build-tools façade");
        linkOrCopy(aapt2, new File(target, "aapt2"));
        if (aapt.isFile()) linkOrCopy(aapt, new File(target, "aapt"));
        linkOrCopy(aidl, new File(target, "aidl"));
        linkOrCopy(zipalign, new File(target, "zipalign"));

        // sdklib validates a few legacy Build Tools paths even when a project never uses them.
        // These launchers fail loudly if an imported project really requires unsupported host tools.
        writeUnsupportedLauncher(new File(target, "dexdump"), "dexdump");
        writeUnsupportedLauncher(new File(target, "split-select"), "split-select");

        File sourceProperties = new File(target, "source.properties");
        try (FileOutputStream output = new FileOutputStream(sourceProperties)) {
            output.write(("Pkg.Desc=Android SDK Build-Tools (APK PRO ARM64 façade)\n"
                    + "Pkg.Revision=" + revision + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (listener != null) listener.onLog("Build Tools " + revision
                + " façade: ARM64 aapt2/aidl/zipalign + Google apksigner/core-lambda-stubs");
        return target;
    }

    private void ensureOfficialBuildToolsJavaArtifacts(
            String revision, File target, BuildListener listener) throws IOException {
        File apksigner = new File(target, "lib/apksigner.jar");
        File lambda = new File(target, "core-lambda-stubs.jar");
        if (apksigner.isFile() && apksigner.length() > 1024
                && lambda.isFile() && lambda.length() > 1024) {
            if (listener != null) listener.onLog("Google Build Tools " + revision + " Java artifacts đã cache");
            return;
        }

        File cacheDir = new File(runtime, "download-cache/android-sdk");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) throw new IOException("Không tạo được Android SDK cache");
        File metadata = new File(cacheDir, "repository2-3.xml");
        NetworkFiles.download(REPOSITORY_URL, metadata, listener, "Android repository metadata");

        PackageInfo info = findPackage(metadata, "build-tools;" + revision, "linux");
        if (info == null) throw new IOException("Google SDK repository chưa có stable build-tools;" + revision + " cho Linux");

        File archive = new File(cacheDir, info.url.substring(info.url.lastIndexOf('/') + 1));
        String archiveUrl = new java.net.URL(new java.net.URL(ARCHIVE_BASE), info.url).toString();
        if (!archive.isFile()) NetworkFiles.download(archiveUrl, archive, listener, "Google Build Tools " + revision);
        try {
            NetworkFiles.verify(archive, info.algorithm, info.checksum);
        } catch (IOException stale) {
            archive.delete();
            NetworkFiles.download(archiveUrl, archive, listener, "Google Build Tools " + revision);
            NetworkFiles.verify(archive, info.algorithm, info.checksum);
        }
        if (listener != null) listener.onLog("Checksum " + info.algorithm.toUpperCase(Locale.US)
                + " Google Build Tools " + revision + ": OK");

        File stage = new File(cacheDir, "build-tools-stage-" + revision.replace('.', '_'));
        IoUtils.deleteRecursively(stage);
        if (!stage.mkdirs() && !stage.isDirectory()) throw new IOException("Không tạo được Build Tools stage");
        try {
            try (FileInputStream input = new FileInputStream(archive)) {
                ZipUtils.unzip(input, stage);
            }
            File extracted = findBuildToolsRoot(stage, 0);
            if (extracted == null) throw new IOException("Build Tools ZIP thiếu apksigner/core-lambda-stubs");

            File lib = new File(target, "lib");
            if (!lib.exists() && !lib.mkdirs()) throw new IOException("Không tạo được build-tools lib cache");
            copyAtomic(new File(extracted, "lib/apksigner.jar"), apksigner);
            copyAtomic(new File(extracted, "core-lambda-stubs.jar"), lambda);
        } finally {
            IoUtils.deleteRecursively(stage);
        }
        if (!apksigner.isFile() || apksigner.length() <= 1024) throw new IOException("Cache apksigner.jar chưa hoàn tất");
        if (!lambda.isFile() || lambda.length() <= 1024) throw new IOException("Cache core-lambda-stubs.jar chưa hoàn tất");
    }


    private static void copyAtomic(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Không tạo được " + parent);
        File part = new File(destination.getAbsolutePath() + ".part");
        Files.deleteIfExists(part.toPath());
        Files.copy(source.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(part.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
APKPRO_80bca391982a4e3e
