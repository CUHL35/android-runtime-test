package com.longdev.apkbuilder.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-local build state shared by the foreground BuildService and Activity.
 * The foreground service keeps the process alive while a build is active on Android 11/12.
 */
public final class BuildStateStore {
    private static final int MAX_LOG_CHARS = 60000;
    private static final int KEEP_LOG_CHARS = 45000;
    private static final CopyOnWriteArrayList<BuildListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Object LOCK = new Object();

    private static boolean running;
    private static int progress;
    private static String stage = "Chưa chạy";
    private static String terminalStatus = "Sẵn sàng";
    private static String successOutput;
    private static String failureMessage;
    private static boolean cancelled;
    private static final StringBuilder log = new StringBuilder();

    private BuildStateStore() {}

    public static boolean isRunning() {
        synchronized (LOCK) {
            return running;
        }
    }

    public static void register(BuildListener listener) {
        if (listener == null) return;
        LISTENERS.addIfAbsent(listener);

        Snapshot snapshot;
        synchronized (LOCK) {
            snapshot = new Snapshot(running, progress, stage, terminalStatus,
                    successOutput, failureMessage, cancelled, splitLog(log.toString()));
        }

        if (snapshot.running) listener.onStarted();
        for (String line : snapshot.lines) listener.onLog(line);
        listener.onProgress(snapshot.progress, snapshot.stage);
        if (!snapshot.running) {
            if (snapshot.successOutput != null) listener.onSuccess(snapshot.successOutput);
            else if (snapshot.cancelled) listener.onCancelled();
            else if (snapshot.failureMessage != null) listener.onFailure(snapshot.failureMessage);
        }
    }

    public static void unregister(BuildListener listener) {
        LISTENERS.remove(listener);
    }

    public static void started() {
        synchronized (LOCK) {
            running = true;
            progress = 0;
            stage = "Bắt đầu";
            terminalStatus = "Đang build...";
            successOutput = null;
            failureMessage = null;
            cancelled = false;
            log.setLength(0);
        }
        for (BuildListener listener : LISTENERS) listener.onStarted();
    }

    public static void log(String line) {
        synchronized (LOCK) {
            log.append(line == null ? "" : line).append('\n');
            if (log.length() > MAX_LOG_CHARS) {
                int start = Math.max(0, log.length() - KEEP_LOG_CHARS);
                String tail = log.substring(start);
                log.setLength(0);
                log.append("… log dài, đang giữ phần cuối …\n").append(tail);
            }
        }
        for (BuildListener listener : LISTENERS) listener.onLog(line);
    }

    public static void progress(int percent, String newStage) {
        int safe = Math.max(0, Math.min(100, percent));
        synchronized (LOCK) {
            // Các bước download/retry có thang phần trăm nội bộ thấp hơn; UI không được chạy lùi.
            safe = Math.max(progress, safe);
            progress = safe;
            if (newStage != null && !newStage.trim().isEmpty()) stage = newStage;
        }
        for (BuildListener listener : LISTENERS) listener.onProgress(safe, newStage);
    }

    public static void success(String outputName) {
        synchronized (LOCK) {
            running = false;
            progress = 100;
            stage = "Hoàn tất";
            terminalStatus = "BUILD SUCCESS";
            successOutput = outputName;
            failureMessage = null;
            cancelled = false;
        }
        for (BuildListener listener : LISTENERS) listener.onSuccess(outputName);
    }

    public static void failure(String message) {
        synchronized (LOCK) {
            running = false;
            terminalStatus = "BUILD FAILED";
            successOutput = null;
            failureMessage = message;
            cancelled = false;
        }
        for (BuildListener listener : LISTENERS) listener.onFailure(message);
    }


    public static void cancelled() {
        synchronized (LOCK) {
            running = false;
            stage = "Đã hủy";
            terminalStatus = "BUILD CANCELED";
            successOutput = null;
            failureMessage = null;
            cancelled = true;
        }
        for (BuildListener listener : LISTENERS) listener.onCancelled();
    }

    public static void reset() {
        synchronized (LOCK) {
            running = false;
            progress = 0;
            stage = "Chưa chạy";
            terminalStatus = "Sẵn sàng";
            successOutput = null;
            failureMessage = null;
            cancelled = false;
            log.setLength(0);
        }
        for (BuildListener listener : LISTENERS) {
            listener.onProgress(0, "Chưa chạy");
        }
    }

    public static void clearLog() {
        synchronized (LOCK) {
            log.setLength(0);
        }
    }
    private static List<String> splitLog(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        String[] parts = text.split("\\n", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i == parts.length - 1 && parts[i].isEmpty()) break;
            lines.add(parts[i]);
        }
        return lines;
    }

    private static final class Snapshot {
        final boolean running;
        final int progress;
        final String stage;
        final String terminalStatus;
        final String successOutput;
        final String failureMessage;
        final boolean cancelled;
        final List<String> lines;

        Snapshot(boolean running, int progress, String stage, String terminalStatus,
                 String successOutput, String failureMessage, boolean cancelled, List<String> lines) {
            this.running = running;
            this.progress = progress;
            this.stage = stage;
            this.terminalStatus = terminalStatus;
            this.successOutput = successOutput;
            this.failureMessage = failureMessage;
            this.cancelled = cancelled;
            this.lines = lines;
        }
    }
}
