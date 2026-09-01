package com.longdev.apkbuilder.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CommandCoordinator {
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Process activeProcess;
    private volatile Future<?> activeTask;
    private volatile Thread activeThread;

    public CommandCoordinator(Context context) {
        this.context = context.getApplicationContext();
    }

    public void run(String command, CommandListener listener) {
        if (command == null || command.trim().isEmpty()) {
            listener.onCommandFailure("Chưa nhập lệnh");
            return;
        }
        cancelled.set(false);
        listener.onCommandStarted();
        activeTask = executor.submit(() -> execute(command.trim(), listener));
    }

    public void cancel() {
        cancelled.set(true);
        Process process = activeProcess;
        if (process != null) {
            try { process.destroy(); } catch (Throwable ignored) { }
            try { process.destroyForcibly(); } catch (Throwable ignored) { }
        }
        Thread thread = activeThread;
        if (thread != null) thread.interrupt();
    }

    private void execute(String command, CommandListener listener) {
        activeThread = Thread.currentThread();
        try {
            ToolchainManager manager = new ToolchainManager(context);
            File runtime = manager.ensureReady(new SilentBuildListener(listener));
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();

            File workDir = resolveWorkingDirectory(runtime);
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", command);
            builder.directory(workDir);
            builder.redirectErrorStream(true);

            Map<String, String> env = builder.environment();
            File prefix = new File(runtime, "usr");
            File javaHome = new File(prefix, "lib/jvm/java-17-openjdk");
            File sdk = new File(prefix, "opt/android-sdk");
            File gradleHome = new File(runtime, "gradle-home");
            File buildHome = new File(runtime, "build-home");
            File lastLog = BuildCoordinator.lastBuildLogFile(context);

            env.put("PREFIX", prefix.getAbsolutePath());
            env.put("JAVA_HOME", javaHome.getAbsolutePath());
            env.put("ANDROID_HOME", sdk.getAbsolutePath());
            env.put("ANDROID_SDK_ROOT", sdk.getAbsolutePath());
            env.put("GRADLE_USER_HOME", gradleHome.getAbsolutePath());
            env.put("HOME", buildHome.getAbsolutePath());
            env.put("TMPDIR", new File(buildHome, "tmp").getAbsolutePath());
            env.put("LAST_BUILD_LOG", lastLog.getAbsolutePath());
            env.put("PATH", javaHome.getAbsolutePath() + "/bin:" + prefix.getAbsolutePath() + "/bin:/system/bin:/system/xbin");

            Process process = builder.start();
            activeProcess = process;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    postLog(listener, line);
                }
            }
            int code = process.waitFor();
            activeProcess = null;
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            main.post(() -> listener.onCommandFinished(code));
        } catch (InterruptedException cancelledError) {
            main.post(() -> listener.onCommandFailure("Đã hủy lệnh"));
        } catch (Throwable error) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                main.post(() -> listener.onCommandFailure("Đã hủy lệnh"));
                return;
            }
            String message = error.getMessage();
            if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
            String finalMessage = message;
            main.post(() -> listener.onCommandFailure(finalMessage));
        } finally {
            activeProcess = null;
            activeTask = null;
            activeThread = null;
        }
    }

    private File resolveWorkingDirectory(File runtime) {
        File marker = new File(context.getFilesDir(), "last-project-path.txt");
        if (marker.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(marker))) {
                String path = reader.readLine();
                if (path != null) {
                    File project = new File(path.trim());
                    if (project.isDirectory()) return project;
                }
            } catch (Exception ignored) {
            }
        }
        File buildHome = new File(runtime, "build-home");
        if (!buildHome.exists()) buildHome.mkdirs();
        return buildHome;
    }

    private void postLog(CommandListener listener, String line) {
        main.post(() -> listener.onCommandLog(line));
    }

    private final class SilentBuildListener implements BuildListener {
        private final CommandListener delegate;
        private SilentBuildListener(CommandListener delegate) { this.delegate = delegate; }
        @Override public void onStarted() { }
        @Override public void onLog(String line) { postLog(delegate, line); }
        @Override public void onSuccess(String outputName) { }
        @Override public void onFailure(String message) { }
        @Override public void onCancelled() { }
    }
}
