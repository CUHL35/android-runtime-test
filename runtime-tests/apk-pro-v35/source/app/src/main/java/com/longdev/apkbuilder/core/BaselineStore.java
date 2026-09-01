package com.longdev.apkbuilder.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/** Keeps exact imported FULL SOURCE ZIP bytes so PATCH UPDATE can recover its baseline later. */
public final class BaselineStore {
    private static final String PREFS = "apk_pro_source_cache";
    private static final int MAX_CACHE_FILES = 2;

    private BaselineStore() { }

    public static final class CachedSource {
        public final File file;
        public final Uri uri;
        public final String sha256;
        public final String versionName;
        public final String packageName;
        public final String originalUri;

        CachedSource(File file, String sha256, String versionName, String packageName, String originalUri) {
            this.file = file;
            this.uri = Uri.fromFile(file);
            this.sha256 = sha256;
            this.versionName = PatchManager.normalizeVersion(versionName);
            this.packageName = packageName == null ? "" : packageName;
            this.originalUri = originalUri == null ? "" : originalUri;
        }
    }

    public static void cacheInspectedSource(Context context, File exactZip, String sha256,
                                            String versionName, String packageName, Uri originalUri) throws IOException {
        if (context == null || exactZip == null || !exactZip.isFile()) return;
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) return;
        File root = root(context);
        File target = new File(root, sha256.toLowerCase(java.util.Locale.US) + ".zip");
        if (!target.isFile() || target.length() != exactZip.length()) {
            File tmp = new File(root, target.getName() + ".part");
            copy(exactZip, tmp);
            try {
                String copiedSha = PatchManager.sha256(tmp);
                if (!sha256.equalsIgnoreCase(copiedSha)) {
                    tmp.delete();
                    throw new IOException("Source cache SHA-256 không khớp sau copy");
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
            if (target.exists()) target.delete();
            if (!tmp.renameTo(target)) {
                copy(tmp, target);
                tmp.delete();
            }
        }
        target.setLastModified(System.currentTimeMillis());
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = sha256.toLowerCase(java.util.Locale.US);
        prefs.edit()
                .putString("version_" + key, PatchManager.normalizeVersion(versionName))
                .putString("package_" + key, packageName == null ? "" : packageName)
                .putString("uri_" + key, originalUri == null ? "" : originalUri.toString())
                .apply();
        prune(root, target);
    }

    /** Returns only an exact SHA match. The cached bytes are re-hashed before use. */
    public static CachedSource findExact(Context context, String expectedSha, String expectedVersion,
                                         String expectedPackage) throws Exception {
        if (context == null || expectedSha == null || !expectedSha.matches("(?i)[0-9a-f]{64}")) return null;
        String key = expectedSha.toLowerCase(java.util.Locale.US);
        File file = new File(root(context), key + ".zip");
        if (!file.isFile()) return null;
        String actual = PatchManager.sha256(file);
        if (!key.equalsIgnoreCase(actual)) {
            file.delete();
            return null;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String version = prefs.getString("version_" + key, "");
        String packageName = prefs.getString("package_" + key, "");
        String originalUri = prefs.getString("uri_" + key, "");
        if (expectedVersion != null && !expectedVersion.trim().isEmpty()
                && !PatchManager.sameVersion(expectedVersion, version)) return null;
        if (expectedPackage != null && !expectedPackage.trim().isEmpty()
                && !expectedPackage.equals(packageName)) return null;
        file.setLastModified(System.currentTimeMillis());
        return new CachedSource(file, key, version, packageName, originalUri);
    }


    public static Uri readableOriginalUri(Context context, CachedSource cached) {
        if (context == null || cached == null || cached.originalUri == null || cached.originalUri.trim().isEmpty()) return null;
        try {
            Uri uri = Uri.parse(cached.originalUri);
            try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input != null) return uri;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String exportCachedToDownloads(Context context, CachedSource cached, String outputName) throws IOException {
        if (cached == null || cached.file == null || !cached.file.isFile()) throw new IOException("Không có FULL SOURCE cache để xuất");
        return DownloadSaver.saveZip(context, cached.file, outputName);
    }

    public static void cacheBuiltSource(Context context, File zip, String versionName, String packageName) throws Exception {
        String sha = PatchManager.sha256(zip);
        cacheInspectedSource(context, zip, sha, versionName, packageName, null);
    }

    private static File root(Context context) throws IOException {
        File root = new File(context.getFilesDir(), "source-cache");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Không tạo được source-cache");
        return root;
    }

    private static void prune(File root, File keep) {
        File[] files = root.listFiles((dir, name) -> name.endsWith(".zip"));
        if (files == null || files.length <= MAX_CACHE_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        int kept = 0;
        for (File file : files) {
            if (file.equals(keep) || kept < MAX_CACHE_FILES) {
                kept++;
                continue;
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static void copy(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Không tạo được cache dir");
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(destination)) {
            IoUtils.copy(in, out);
        }
    }
}
