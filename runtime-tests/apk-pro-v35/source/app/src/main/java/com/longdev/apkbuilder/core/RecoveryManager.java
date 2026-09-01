package com.longdev.apkbuilder.core;

import android.content.Context;

import java.io.File;
import java.io.IOException;

public final class RecoveryManager {
    private RecoveryManager() {}

    public static void resetBuildSession(Context context) {
        IoUtils.deleteRecursively(new File(context.getCacheDir(), "build-session"));
        IoUtils.deleteRecursively(new File(context.getFilesDir(), "last-project-path.txt"));
        BuildStateStore.reset();
    }

    /** Light cleanup: only current build workspace and temp files. */
    public static void clearTransientBuild(Context context) {
        IoUtils.deleteRecursively(new File(context.getCacheDir(), "build-session"));
        File tmp = new File(context.getFilesDir(), "build-home/tmp");
        IoUtils.deleteRecursively(tmp);
        tmp.mkdirs();
        BuildStateStore.reset();
    }

    /** Clears Gradle/Maven artifact caches and daemon state; keeps installed Gradle distributions. */
    public static void clearGradleMavenCaches(Context context) {
        File files = context.getFilesDir();
        IoUtils.deleteRecursively(new File(files, "gradle-home/caches"));
        IoUtils.deleteRecursively(new File(files, "gradle-home/daemon"));
        new File(files, "gradle-home").mkdirs();
        BuildStateStore.reset();
    }

    /** Clears downloaded archives/staging only; installed JDK/SDK/Gradle/toolchain remain intact. */
    public static void clearToolchainDownloadCache(Context context) {
        File cache = new File(context.getFilesDir(), "download-cache");
        IoUtils.deleteRecursively(cache);
        cache.mkdirs();
    }

    /** Backward-compatible combined cleanup used by older callers. */
    public static void clearBuildCaches(Context context) {
        clearTransientBuild(context);
        clearGradleMavenCaches(context);
    }

    public static String saveCurrentLog(Context context) throws IOException {
        File log = BuildCoordinator.lastBuildLogFile(context);
        if (!log.isFile()) throw new IOException("Chưa có build log để lưu");
        return DownloadSaver.saveLog(context, log, "APK-Builder-manual-log.txt");
    }
}
