package com.longdev.apkbuilder.core;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Auto-scans a selected ZIP as FULL SOURCE, PATCH, or INVALID. */
public final class SourceInspector {
    private static final Pattern APPLICATION_ID = Pattern.compile("(?m)\\bapplicationId\\s*(?:=\\s*)?[\\\"']([^\\\"']+)[\\\"']");
    private static final Pattern NAMESPACE = Pattern.compile("(?m)\\bnamespace\\s*(?:=\\s*)?[\\\"']([^\\\"']+)[\\\"']");
    private static final Pattern MANIFEST_PACKAGE = Pattern.compile("\\bpackage\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
    private static final Pattern VERSION_NAME = Pattern.compile("(?m)\\bversionName\\s*(?:=\\s*)?[\\\"']([^\\\"']+)[\\\"']");
    private static final Pattern VERSION_CODE = Pattern.compile("(?m)\\bversionCode\\s*(?:=\\s*)?([0-9]+)");
    private static final int MAX_TEXT_BYTES = 1024 * 1024;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public SourceInspector(Context context) {
        this.context = context.getApplicationContext();
    }

    public enum Type { SOURCE, PATCH, INVALID }

    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        public final Type type;
        public final String fileName;
        public final String packageName;
        public final String versionName;
        public final int versionCode;
        public final String sha256;
        public final int embeddedKeyCount;
        public final boolean signingInfoPresent;
        public final boolean releaseReady;
        public final PatchManager.PatchInfo patchInfo;
        public final String error;

        Result(Type type, String fileName, String packageName, String versionName, int versionCode,
               String sha256, int embeddedKeyCount, boolean signingInfoPresent, boolean releaseReady,
               PatchManager.PatchInfo patchInfo, String error) {
            this.type = type;
            this.fileName = fileName;
            this.packageName = packageName;
            this.versionName = PatchManager.normalizeVersion(versionName);
            this.versionCode = versionCode;
            this.sha256 = sha256 == null ? "" : sha256;
            this.embeddedKeyCount = embeddedKeyCount;
            this.signingInfoPresent = signingInfoPresent;
            this.releaseReady = releaseReady;
            this.patchInfo = patchInfo;
            this.error = error;
        }
    }

    public void inspect(Uri uri, Callback callback) {
        if (uri == null || callback == null) return;
        executor.execute(() -> {
            Result result = inspectNow(uri);
            main.post(() -> callback.onResult(result));
        });
    }

    private Result inspectNow(Uri uri) {
        File root = new File(context.getCacheDir(), "archive-inspector");
        IoUtils.deleteRecursively(root);
        String fileName = displayName(uri);
        try {
            if (!root.mkdirs() && !root.isDirectory()) throw new IOException("Không tạo được archive-inspector");
            File zip = new File(root, "selected.zip");
            IoUtils.copyUri(context.getContentResolver(), uri, zip);
            String sha = PatchManager.sha256(zip);

            File patchExtract = new File(root, "patch-check");
            PatchManager.PatchInfo patch = PatchManager.tryInspectZip(zip, patchExtract);
            if (patch != null) {
                // Re-run strict patch validation now so the UI never labels a broken patch as ready.
                patch = PatchManager.inspectZip(zip, patchExtract);
                return new Result(Type.PATCH, fileName, patch.packageName, patch.targetVersion, 0,
                        sha, 0, false, false, patch, null);
            }

            File project = SourceProjectPreparer.prepare(zip, root, new SilentListener());
            if (project == null) {
                return new Result(Type.INVALID, fileName, null, "", 0, sha, 0, false, false,
                        null, "Không tìm thấy project Gradle hoặc patch manifest");
            }

            String packageName = readPackageName(project);
            String versionName = readVersionName(project);
            int versionCode = readVersionCode(project);
            // Signing state must be derived from the resolved Gradle project only.
            // Scanning the whole inspector workspace can accidentally include duplicate/stale
            // files created while resolving nested ZIPs and incorrectly downgrade a valid source
            // to KEY FOUND. The actual build also consumes signing files from this project root.
            List<File> keys = new ArrayList<>();
            collectSigningKeys(project, keys, 0);
            SigningInfoParser.Info info = findSigningInfo(project);
            boolean signingInfoPresent = info != null;
            boolean releaseReady = keys.size() == 1 && info != null && info.isComplete();

            try {
                BaselineStore.cacheInspectedSource(context, zip, sha, versionName, packageName, uri);
            } catch (Throwable ignored) {
                // Source is still usable; cache failure only disables automatic future baseline recovery.
            }

            return new Result(Type.SOURCE, fileName, packageName, versionName, versionCode, sha,
                    keys.size(), signingInfoPresent, releaseReady, null, null);
        } catch (Throwable error) {
            String message = error.getMessage();
            if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
            return new Result(Type.INVALID, fileName, null, "", 0, "", 0, false, false, null, message);
        } finally {
            IoUtils.deleteRecursively(root);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) return value.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "selected.zip" : last;
    }

    public static String readPackageName(File project) {
        String appId = findInGradle(project, APPLICATION_ID);
        if (appId != null) return appId;

        File appManifest = new File(project, "app/src/main/AndroidManifest.xml");
        String manifestPackage = findInFile(appManifest, MANIFEST_PACKAGE);
        if (manifestPackage != null) return manifestPackage;

        String namespace = findInGradle(project, NAMESPACE);
        if (namespace != null) return namespace;

        File manifest = findFile(project, "AndroidManifest.xml", 6);
        return findInFile(manifest, MANIFEST_PACKAGE);
    }

    public static String readVersionName(File project) {
        String value = findInGradle(project, VERSION_NAME);
        return value == null ? "" : PatchManager.normalizeVersion(value);
    }

    public static int readVersionCode(File project) {
        String value = findInGradle(project, VERSION_CODE);
        if (value == null) return 0;
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; }
    }

    private static String findInGradle(File project, Pattern pattern) {
        File appGradle = new File(project, "app/build.gradle");
        String found = findInFile(appGradle, pattern);
        if (found != null) return found;
        File appGradleKts = new File(project, "app/build.gradle.kts");
        found = findInFile(appGradleKts, pattern);
        if (found != null) return found;
        File rootGradle = new File(project, "build.gradle");
        found = findInFile(rootGradle, pattern);
        if (found != null) return found;
        File rootGradleKts = new File(project, "build.gradle.kts");
        return findInFile(rootGradleKts, pattern);
    }

    private static String findInFile(File file, Pattern pattern) {
        if (file == null || !file.isFile() || file.length() > MAX_TEXT_BYTES) return null;
        StringBuilder text = new StringBuilder((int) Math.min(file.length(), MAX_TEXT_BYTES));
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        } catch (IOException ignored) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return null;
        String value = matcher.group(1);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static File findFile(File dir, String name, int depth) {
        if (dir == null || depth < 0 || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) if (file.isFile() && name.equals(file.getName())) return file;
        for (File file : files) if (file.isDirectory()) {
            File found = findFile(file, name, depth - 1);
            if (found != null) return found;
        }
        return null;
    }

    private static SigningInfoParser.Info findSigningInfo(File file) {
        if (file == null) return null;
        if (file.isFile()) {
            String name = file.getName().toLowerCase(Locale.US);
            if (!name.endsWith(".txt") || file.length() > 128 * 1024) return null;
            try { return SigningInfoParser.parseFile(file); } catch (IOException ignored) { return null; }
        }
        File[] children = file.listFiles();
        if (children == null) return null;
        SigningInfoParser.Info found = null;
        for (File child : children) {
            SigningInfoParser.Info candidate = findSigningInfo(child);
            if (candidate == null) continue;
            if (found == null) found = candidate;
            else if (!found.alias.equals(candidate.alias)
                    || !found.storePassword.equals(candidate.storePassword)
                    || !found.keyPassword.equals(candidate.keyPassword)) return null;
        }
        return found;
    }

    private static void collectSigningKeys(File file, List<File> out, int depth) {
        if (file == null || depth > 8 || out.size() > 8) return;
        if (file.isFile()) {
            String name = file.getName().toLowerCase(Locale.US);
            if ((name.endsWith(".jks") || name.endsWith(".keystore")) && !name.equals("debug.keystore")) out.add(file);
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) collectSigningKeys(child, out, depth + 1);
    }


    private static final class SilentListener implements BuildListener {
        @Override public void onStarted() { }
        @Override public void onLog(String line) { }
        @Override public void onSuccess(String outputName) { }
        @Override public void onFailure(String message) { }
    }
}
