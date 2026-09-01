package com.longdev.apkbuilder.core;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates and reads the single-file RELEASE-KEY.zip format used by APK PRO. */
public final class ReleaseKeyManager {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private ReleaseKeyManager() { }

    public static final class ResolvedKey {
        public final File keyFile;
        public final String storePassword;
        public final String alias;
        public final String keyPassword;
        public final String bundleSavedName;

        ResolvedKey(File keyFile, String storePassword, String alias, String keyPassword, String bundleSavedName) {
            this.keyFile = keyFile;
            this.storePassword = storePassword == null ? "" : storePassword;
            this.alias = alias == null ? "" : alias;
            this.keyPassword = keyPassword == null ? "" : keyPassword;
            this.bundleSavedName = bundleSavedName;
        }
    }

    public static ResolvedKey fromBundle(Context context, Uri bundleUri, File session) throws IOException {
        if (context == null || bundleUri == null) throw new IOException("Thiếu RELEASE-KEY.zip");
        File root = new File(session, "release-key-bundle");
        IoUtils.deleteRecursively(root);
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("Không tạo được thư mục key bundle");
        File zip = new File(root, "RELEASE-KEY.zip");
        IoUtils.copyUri(context.getContentResolver(), bundleUri, zip);
        File unpacked = new File(root, "unpacked");
        if (!unpacked.mkdirs() && !unpacked.isDirectory()) throw new IOException("Không tạo được thư mục giải key");
        try (FileInputStream input = new FileInputStream(zip)) {
            ZipUtils.unzip(input, unpacked);
        }

        List<File> keys = new ArrayList<>();
        collectKeyFiles(unpacked, keys, 0);
        if (keys.size() != 1) {
            throw new IOException(keys.isEmpty()
                    ? "RELEASE-KEY.zip không có .jks/.keystore"
                    : "RELEASE-KEY.zip phải có đúng 1 .jks/.keystore");
        }
        List<File> infos = new ArrayList<>();
        collectTextFiles(unpacked, infos, 0);
        SigningInfoParser.Info info = null;
        for (File file : infos) {
            try {
                info = SigningInfoParser.parseFile(file);
                break;
            } catch (IOException ignored) {
            }
        }
        if (info == null) throw new IOException("RELEASE-KEY.zip thiếu SIGNING-KEY-INFO.txt hợp lệ");
        return new ResolvedKey(keys.get(0), info.storePassword, info.alias, info.keyPassword, null);
    }

    public static ResolvedKey generateAndExport(Context context, File runtime, File session, String packageName) throws IOException, InterruptedException {
        File javaHome = new File(runtime, "usr/lib/jvm/java-17-openjdk");
        File keytool = new File(javaHome, "bin/keytool");
        if (!keytool.isFile()) throw new IOException("JDK standalone thiếu keytool");
        // ZIP extraction can drop executable bits; restore before launch.
        //noinspection ResultOfMethodCallIgnored
        keytool.setExecutable(true, false);
        if (!keytool.canExecute()) throw new IOException("keytool không có quyền execute");

        String safePackage = sanitize(packageName == null || packageName.trim().isEmpty() ? "android-app" : packageName.trim());
        String alias = "release";
        String password = randomPassword(28);
        File keyDir = new File(session, "generated-release-key");
        IoUtils.deleteRecursively(keyDir);
        if (!keyDir.mkdirs() && !keyDir.isDirectory()) throw new IOException("Không tạo được thư mục key tạm");
        File key = new File(keyDir, safePackage + "-release.jks");
        File info = new File(keyDir, "SIGNING-KEY-INFO.txt");

        ProcessBuilder builder = new ProcessBuilder(
                keytool.getAbsolutePath(),
                "-genkeypair",
                "-keystore", key.getAbsolutePath(),
                "-storetype", "JKS",
                "-storepass", password,
                "-keypass", password,
                "-alias", alias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-dname", "CN=APK PRO Release,O=APK PRO,C=VN");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 4096) output.append(line).append('\n');
            }
        }
        int code = process.waitFor();
        if (code != 0 || !key.isFile()) {
            throw new IOException("Tạo release key thất bại (keytool exit " + code + "): " + output.toString().trim());
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(info, false))) {
            writer.write("Package: " + (packageName == null ? "" : packageName));
            writer.newLine();
            writer.write("Alias: " + alias);
            writer.newLine();
            writer.write("Store password: " + password);
            writer.newLine();
            writer.write("Key password: " + password);
            writer.newLine();
        }

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File bundle = new File(keyDir, safePackage + "-RELEASE-KEY-" + stamp + ".zip");
        createBundle(bundle, key, info);

        // Save all recovery artifacts, not only the bundle. The JKS + TXT can be copied to a new device
        // independently, while RELEASE-KEY.zip remains the easiest one-file backup.
        String jksName = safePackage + "-release-" + stamp + ".jks";
        String infoName = safePackage + "-SIGNING-KEY-INFO-" + stamp + ".txt";
        DownloadSaver.saveKey(context, key, jksName);
        DownloadSaver.saveText(context, info, infoName);
        String saved = DownloadSaver.saveZip(context, bundle, bundle.getName());
        return new ResolvedKey(key, password, alias, password, saved);
    }

    private static void createBundle(File bundle, File key, File info) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(bundle))) {
            addFile(zip, key, key.getName());
            addFile(zip, info, "SIGNING-KEY-INFO.txt");
        }
    }

    private static void addFile(ZipOutputStream zip, File file, String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        try (FileInputStream input = new FileInputStream(file)) {
            IoUtils.copy(input, zip);
        }
        zip.closeEntry();
    }

    private static void collectKeyFiles(File file, List<File> out, int depth) {
        if (file == null || depth > 6 || out.size() > 4) return;
        if (file.isFile()) {
            String name = file.getName().toLowerCase(Locale.US);
            if (name.endsWith(".jks") || name.endsWith(".keystore")) out.add(file);
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) collectKeyFiles(child, out, depth + 1);
    }

    private static void collectTextFiles(File file, List<File> out, int depth) {
        if (file == null || depth > 6 || out.size() > 20) return;
        if (file.isFile()) {
            String name = file.getName().toLowerCase(Locale.US);
            if (name.endsWith(".txt") && file.length() <= 128 * 1024) out.add(file);
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) collectTextFiles(child, out, depth + 1);
    }

    private static String sanitize(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]+", "-");
        while (cleaned.startsWith("-")) cleaned = cleaned.substring(1);
        while (cleaned.endsWith("-")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned.isEmpty() ? "android-app" : cleaned;
    }

    private static String randomPassword(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) out.append(PASSWORD_CHARS[RANDOM.nextInt(PASSWORD_CHARS.length)]);
        return out.toString();
    }
}
