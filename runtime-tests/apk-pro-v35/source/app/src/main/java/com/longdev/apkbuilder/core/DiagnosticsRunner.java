package com.longdev.apkbuilder.core;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.StatFs;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Non-destructive diagnostics used by the "Chẩn đoán / TEST ALL" menu. */
public final class DiagnosticsRunner {
    public interface Listener {
        void onProgress(int percent, String stage);
        void onLine(String line);
    }

    public static final class Report {
        public final int pass;
        public final int warn;
        public final int fail;
        public final String text;

        Report(int pass, int warn, int fail, String text) {
            this.pass = pass;
            this.warn = warn;
            this.fail = fail;
            this.text = text;
        }

        public boolean isPass() { return fail == 0; }
    }

    private DiagnosticsRunner() {}

    public static Report run(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        Collector out = new Collector(listener);
        out.line("=== APK PRO · CHẨN ĐOÁN / TEST ALL ===");
        out.line("Không thay đổi source, cache hoặc signing key.");

        out.progress(5, "Thông tin app / Android");
        checkApp(out, app);
        checkRuntimeAbi(out);

        out.progress(18, "Quyền hệ thống");
        checkPermissions(out, app);

        out.progress(30, "Storage / app-private runtime");
        checkStorage(out, app);

        out.progress(42, "Toolchain cache");
        checkToolchainCache(out, app);

        out.progress(60, "Build state / log");
        checkBuildState(out, app);

        out.progress(70, "Kết nối nguồn tải chính thức");
        checkNetwork(out);

        out.progress(96, "Tổng hợp");
        out.line("--- SUMMARY ---");
        out.line("PASS=" + out.pass + " · WARN=" + out.warn + " · FAIL=" + out.fail);
        if (out.fail == 0) out.line("TEST_ALL_RESULT=PASS");
        else out.line("TEST_ALL_RESULT=FAIL");
        out.progress(100, out.fail == 0 ? "TEST ALL hoàn tất" : "TEST ALL có lỗi");
        return new Report(out.pass, out.warn, out.fail, out.text.toString());
    }

    private static void checkApp(Collector out, Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            String versionName = info.versionName == null ? "?" : info.versionName;
            long versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            out.pass("App package", context.getPackageName());
            out.pass("App version", versionName + " (code " + versionCode + ")");
        } catch (Throwable error) {
            out.fail("App metadata", message(error));
        }
        out.pass("Android", Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
        out.pass("Device", Build.MANUFACTURER + " " + Build.MODEL);
    }

    private static void checkRuntimeAbi(Collector out) {
        String abis = Arrays.toString(Build.SUPPORTED_ABIS);
        out.pass("ABI thiết bị", abis);
        if (Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) {
            out.pass("APK PRO ARM64 Core v2", "arm64-v8a được hỗ trợ");
        } else {
            out.warn("APK PRO ARM64 Core v2", "core chỉ hỗ trợ arm64-v8a; thiết bị này không có ABI phù hợp");
        }
    }

    private static void checkPermissions(Collector out, Context context) {
        checkPermission(out, context, Manifest.permission.INTERNET, "INTERNET", true);
        checkPermission(out, context, Manifest.permission.FOREGROUND_SERVICE, "FOREGROUND_SERVICE", true);
        checkPermission(out, context, Manifest.permission.WAKE_LOCK, "WAKE_LOCK", true);

        if (Build.VERSION.SDK_INT >= 26) {
            try {
                boolean allowed = context.getPackageManager().canRequestPackageInstalls();
                if (allowed) out.pass("Cài APK ngoài", "đã cho phép từ nguồn này");
                else out.warn("Cài APK ngoài", "chưa bật 'Cho phép từ nguồn này'");
            } catch (Throwable error) {
                out.warn("Cài APK ngoài", message(error));
            }
        } else {
            out.pass("Cài APK ngoài", "API < 26");
        }

        if (Build.VERSION.SDK_INT >= 33) {
            checkPermission(out, context, "android.permission.POST_NOTIFICATIONS", "POST_NOTIFICATIONS", false);
        } else {
            out.pass("Thông báo", "không cần runtime permission trên API " + Build.VERSION.SDK_INT);
        }
    }

    private static void checkPermission(Collector out, Context context, String permission, String label, boolean required) {
        try {
            int result = context.checkSelfPermission(permission);
            if (result == PackageManager.PERMISSION_GRANTED) out.pass(label, "GRANTED");
            else if (required) out.fail(label, "DENIED");
            else out.warn(label, "DENIED");
        } catch (Throwable error) {
            if (required) out.fail(label, message(error));
            else out.warn(label, message(error));
        }
    }

    private static void checkStorage(Collector out, Context context) {
        File files = context.getFilesDir();
        File cache = context.getCacheDir();
        if (files.isDirectory()) out.pass("filesDir", files.getAbsolutePath());
        else out.fail("filesDir", "không tồn tại");
        if (cache.isDirectory()) out.pass("cacheDir", cache.getAbsolutePath());
        else out.fail("cacheDir", "không tồn tại");

        File probe = new File(cache, ".apk-pro-diagnostic-write-test");
        try (FileOutputStream stream = new FileOutputStream(probe)) {
            stream.write("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stream.flush();
            out.pass("Ghi app-private", "OK");
        } catch (Throwable error) {
            out.fail("Ghi app-private", message(error));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
        }

        try {
            StatFs stat = new StatFs(files.getAbsolutePath());
            long free = stat.getAvailableBytes();
            String value = humanBytes(free) + " trống";
            if (free >= 2L * 1024L * 1024L * 1024L) out.pass("Dung lượng", value);
            else if (free >= 750L * 1024L * 1024L) out.warn("Dung lượng", value + " · build lớn có thể thiếu chỗ");
            else out.fail("Dung lượng", value + " · không đủ an toàn để tải/build toolchain");
        } catch (Throwable error) {
            out.warn("Dung lượng", message(error));
        }
    }

    private static void checkToolchainCache(Collector out, Context context) {
        File root = context.getFilesDir();
        File prefix = new File(root, "usr");
        if (ToolchainManager.hasCore(root)) {
            out.pass("APK PRO ARM64 Core v2", "local/independent");
        } else {
            out.warn("APK PRO ARM64 Core v2", "chưa cache · build/RUN đầu sẽ tự tải ARM64 core nếu có mạng");
        }

        cacheBinary(out, new File(prefix, "bin/curl"), "curl", true);
        cacheBinary(out, new File(prefix, "bin/unzip"), "unzip", true);
        cacheBinary(out, new File(prefix, "bin/sha256sum"), "sha256sum", true);
        cacheBinary(out, new File(prefix, "bin/aapt2"), "aapt2", true);
        cacheBinary(out, new File(prefix, "bin/aidl"), "aidl", true);
        cacheBinary(out, new File(prefix, "bin/zipalign"), "zipalign", true);
        cacheBinary(out, new File(prefix, "lib/jvm/java-17-openjdk/bin/java"), "JDK 17", true);
        File buildTools36 = new File(prefix, "opt/android-sdk/build-tools/36.0.0");
        cacheFile(out, new File(buildTools36, "lib/apksigner.jar"), "Google apksigner.jar", false);
        cacheFile(out, new File(buildTools36, "core-lambda-stubs.jar"), "Google core-lambda-stubs.jar", false);
        File lock = new File(root, "toolchain-package-lock.sha256");
        cacheFile(out, lock, "ARM64 package SHA-256 lock", false);
        File marker = ToolchainManager.coreMarker(root);
        if (marker.isFile() && marker.length() > 0) out.pass("Core metadata", "v2");
        else if (ToolchainManager.hasCore(root)) out.warn("Core metadata", "sẽ tạo khi build/RUN lần tới");

        File sdk = new File(prefix, "opt/android-sdk");
        File platforms = new File(sdk, "platforms");
        File buildTools = new File(sdk, "build-tools");
        cacheDirectory(out, platforms, "Android SDK platforms");
        cacheDirectory(out, buildTools, "Android build-tools");
        cacheDirectory(out, new File(prefix, "opt/gradle"), "Gradle distributions");
        cacheDirectory(out, new File(root, "gradle-home/caches"), "Gradle/Maven cache");
    }

    private static void checkBuildState(Collector out, Context context) {
        if (BuildStateStore.isRunning()) out.warn("Build state", "đang chạy");
        else out.pass("Build state", "idle");

        File marker = new File(context.getFilesDir(), "last-project-path.txt");
        if (marker.isFile()) out.pass("Project gần nhất", "marker có sẵn");
        else out.warn("Project gần nhất", "chưa có build project trong phiên/cache hiện tại");

        File log = BuildCoordinator.lastBuildLogFile(context);
        if (log.isFile() && log.length() > 0) out.pass("Build log gần nhất", humanBytes(log.length()));
        else out.warn("Build log gần nhất", "chưa có log");
    }

    private static void checkNetwork(Collector out) {
        List<Endpoint> endpoints = new ArrayList<>();
        endpoints.add(new Endpoint("ARM64 bootstrap", "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.08.30-r1%2Bapt.android-7/bootstrap-aarch64.zip"));
        endpoints.add(new Endpoint("Gradle", "https://services.gradle.org/distributions/"));
        endpoints.add(new Endpoint("Google Android SDK", "https://dl.google.com/android/repository/repository2-3.xml"));
        endpoints.add(new Endpoint("Google Maven", "https://dl.google.com/dl/android/maven2/master-index.xml"));
        endpoints.add(new Endpoint("Maven Central", "https://repo.maven.apache.org/maven2/"));
        for (Endpoint endpoint : endpoints) {
            probeEndpoint(out, endpoint);
        }
    }

    private static void probeEndpoint(Collector out, Endpoint endpoint) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint.url).openConnection();
            connection.setConnectTimeout(4500);
            connection.setReadTimeout(4500);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "APK-PRO-Diagnostics/33");
            int code = connection.getResponseCode();
            if (code >= 200 && code < 400) out.pass("Mạng · " + endpoint.name, "HTTP " + code);
            else out.fail("Mạng · " + endpoint.name, "HTTP " + code);
        } catch (Throwable error) {
            out.fail("Mạng · " + endpoint.name, message(error));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void cacheBinary(Collector out, File file, String label, boolean required) {
        if (file.isFile() && file.canExecute()) out.pass("Cache · " + label, "OK");
        else if (required) out.fail("Cache · " + label, "MISSING");
        else out.warn("Cache · " + label, "chưa cache");
    }

    private static void cacheFile(Collector out, File file, String label, boolean required) {
        if (file.isFile() && file.length() > 0) out.pass("Cache · " + label, "OK");
        else if (required) out.fail("Cache · " + label, "MISSING");
        else out.warn("Cache · " + label, "chưa cache");
    }

    private static void cacheDirectory(Collector out, File dir, String label) {
        File[] children = dir.listFiles();
        if (dir.isDirectory() && children != null && children.length > 0) {
            out.pass("Cache · " + label, children.length + " mục");
        } else {
            out.warn("Cache · " + label, "chưa cache");
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int index = 0;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024.0;
            index++;
        }
        return new DecimalFormat(value >= 10 ? "0.0" : "0.00").format(value) + " " + units[index];
    }

    private static String message(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return message.trim();
    }

    private static final class Endpoint {
        final String name;
        final String url;
        Endpoint(String name, String url) { this.name = name; this.url = url; }
    }

    private static final class Collector {
        final Listener listener;
        final StringBuilder text = new StringBuilder();
        int pass;
        int warn;
        int fail;

        Collector(Listener listener) { this.listener = listener; }

        void progress(int percent, String stage) {
            if (listener != null) listener.onProgress(percent, stage);
        }

        void line(String line) {
            text.append(line == null ? "" : line).append('\n');
            if (listener != null) listener.onLine(line);
        }

        void pass(String name, String detail) {
            pass++;
            line("[PASS] " + name + " · " + detail);
        }

        void warn(String name, String detail) {
            warn++;
            line("[WARN] " + name + " · " + detail);
        }

        void fail(String name, String detail) {
            fail++;
            line("[FAIL] " + name + " · " + detail);
        }
    }
}
