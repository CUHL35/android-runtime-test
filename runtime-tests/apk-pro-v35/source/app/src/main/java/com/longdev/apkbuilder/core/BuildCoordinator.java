package com.longdev.apkbuilder.core;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BuildCoordinator {
    private static final String PROGRESS_PREFIX = "@@PROGRESS|";

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private volatile Process activeProcess;
    private volatile Future<?> activeTask;
    private volatile Thread activeThread;
    private int taskLines;

    public BuildCoordinator(Context context) {
        this.context = context.getApplicationContext();
    }

    public static File lastBuildLogFile(Context context) {
        return new File(new File(context.getFilesDir(), "logs"), "last-build.log");
    }

    public void build(Uri sourceUri, Uri patchUri, BuildMode mode, SigningData signing, BuildListener listener) {
        if (sourceUri == null) {
            listener.onFailure("Chưa chọn source ZIP");
            return;
        }
        if (mode == BuildMode.RELEASE && (signing == null || !signing.hasUsableKeySelection())) {
            listener.onFailure("Build Release cần key bên ngoài hoặc key trong source ZIP");
            return;
        }
        if (mode == BuildMode.UPDATE && patchUri == null) {
            listener.onFailure("BUILD UPDATE cần PATCH ZIP");
            return;
        }
        cancelRequested.set(false);
        listener.onStarted();
        activeTask = executor.submit(() -> executeBuild(sourceUri, patchUri, mode, signing, listener));
    }

    public void cancel() {
        cancelRequested.set(true);
        Process process = activeProcess;
        if (process != null) {
            try { process.destroy(); } catch (Throwable ignored) { }
            try { process.destroyForcibly(); } catch (Throwable ignored) { }
        }
        Thread thread = activeThread;
        if (thread != null) thread.interrupt();
    }

    private void checkCancelled() throws BuildCancelledException {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new BuildCancelledException();
        }
    }

    private void executeBuild(Uri sourceUri, Uri patchUri, BuildMode mode, SigningData signing, BuildListener listener) {
        activeThread = Thread.currentThread();
        File session = new File(context.getCacheDir(), "build-session");
        File logFile = lastBuildLogFile(context);
        BuildLogWriter logWriter = null;
        taskLines = 0;
        try {
            logWriter = new BuildLogWriter(logFile);
            checkCancelled();
            postProgress(listener, 2, "Chuẩn bị workspace");
            IoUtils.deleteRecursively(session);
            if (!session.mkdirs()) throw new IOException("Không tạo được build workspace");

            postProgress(listener, 5, "Copy source ZIP");
            postLog(listener, logWriter, "Copy source ZIP...");
            File sourceZip = new File(session, "source.zip");
            IoUtils.copyUri(context.getContentResolver(), sourceUri, sourceZip);
            checkCancelled();

            postProgress(listener, 10, "Giải nén / tìm project");
            File project = SourceProjectPreparer.prepare(
                    sourceZip,
                    session,
                    new PostingListener(listener, logWriter));
            if (project == null) {
                throw new IOException("Không tìm thấy project Gradle trong ZIP hoặc ZIP source bên trong");
            }
            checkCancelled();
            saveLastProjectPath(project);
            postLog(listener, logWriter, "Project: " + project.getName());
            postProgress(listener, 15, "Project đã sẵn sàng");

            PatchManager.AppliedPatch appliedPatch = null;
            if (patchUri != null) {
                if (mode != BuildMode.UPDATE) throw new IOException("PATCH ZIP chỉ được dùng với BUILD UPDATE");
                postProgress(listener, 16, "Xác minh PATCH baseline");
                appliedPatch = PatchManager.apply(context, patchUri, sourceZip, project, session,
                        new PostingListener(listener, logWriter));
                postProgress(listener, 19, "PATCH đã áp dụng vào temp; chưa xuất FULL SOURCE");
            } else if (mode == BuildMode.UPDATE) {
                throw new IOException("BUILD UPDATE thiếu PATCH ZIP");
            }

            // BUILD UPDATE follows the verified FULL SOURCE baseline:
            // - exactly one embedded key + valid signing info => Release + signature verify
            // - no embedded key => Debug
            // - key exists but signing info is missing/ambiguous => fail instead of silently downgrading
            BuildMode effectiveBuildMode = mode;
            SigningData effectiveSigning = signing;
            if (mode == BuildMode.UPDATE) {
                java.util.List<File> updateKeys = new java.util.ArrayList<>();
                collectSigningKeys(project, updateKeys, 0);
                if (updateKeys.isEmpty()) {
                    effectiveBuildMode = BuildMode.DEBUG;
                    effectiveSigning = null;
                    postLog(listener, logWriter, "UPDATE AUTO MODE: FULL SOURCE không có release key -> Build Debug");
                } else if (updateKeys.size() == 1) {
                    SigningInfoParser.Info updateInfo;
                    try {
                        updateInfo = findEmbeddedSigningInfo(project);
                    } catch (IOException signingError) {
                        throw new IOException("FULL SOURCE có key nhưng signing info không hợp lệ/không đồng nhất: "
                                + signingError.getMessage());
                    }
                    if (updateInfo == null || !updateInfo.isComplete()) {
                        throw new IOException("FULL SOURCE có key nhưng thiếu signing info hợp lệ; không tự hạ xuống Debug");
                    }
                    effectiveBuildMode = BuildMode.RELEASE;
                    effectiveSigning = SigningData.embeddedAuto(
                            updateInfo.storePassword, updateInfo.alias, updateInfo.keyPassword);
                    postLog(listener, logWriter, "UPDATE AUTO MODE: FULL SOURCE có key + signing info -> Build Release");
                } else {
                    throw new IOException("FULL SOURCE có nhiều release key; không thể tự chọn key cho UPDATE");
                }
            }
            boolean releaseLike = effectiveBuildMode == BuildMode.RELEASE;

            File keyFile = null;
            String storePassword = effectiveSigning == null ? "" : effectiveSigning.storePassword;
            String keyAlias = effectiveSigning == null ? "" : effectiveSigning.alias;
            String keyPassword = effectiveSigning == null ? "" : effectiveSigning.keyPassword;

            if (releaseLike && !effectiveSigning.generatesNewKey()) {
                if (effectiveSigning.usesEmbeddedKey()) {
                    postProgress(listener, 18, "Tìm key trong source ZIP");
                    keyFile = findEmbeddedSigningKey(project);
                    postLog(listener, logWriter, "Key trong source: " + keyFile.getName());
                    if (isBlank(storePassword)) {
                        postLog(listener, logWriter, "Đang tìm SIGNING-KEY-INFO.txt trong source ZIP...");
                        SigningInfoParser.Info info = findEmbeddedSigningInfo(project);
                        storePassword = info.storePassword;
                        if (isBlank(keyAlias)) keyAlias = info.alias;
                        if (isBlank(keyPassword)) keyPassword = info.keyPassword;
                        postLog(listener, logWriter, "Đã đọc signing info trong ZIP (không ghi mật khẩu vào log)");
                    }
                } else if (effectiveSigning.usesBundle()) {
                    postProgress(listener, 18, "Đọc RELEASE-KEY.zip");
                    ReleaseKeyManager.ResolvedKey resolved = ReleaseKeyManager.fromBundle(context, effectiveSigning.keyUri, session);
                    keyFile = resolved.keyFile;
                    storePassword = resolved.storePassword;
                    keyAlias = resolved.alias;
                    keyPassword = resolved.keyPassword;
                    postLog(listener, logWriter, "Đã đọc RELEASE-KEY.zip (không ghi mật khẩu vào log)");
                } else {
                    postProgress(listener, 18, "Copy signing key");
                    keyFile = new File(session, "signing-key.jks");
                    IoUtils.copyUri(context.getContentResolver(), effectiveSigning.keyUri, keyFile);
                    if (isBlank(storePassword)) {
                        postLog(listener, logWriter, "JKS ngoài: tự tìm SIGNING-KEY-INFO.txt trong source ZIP...");
                        try {
                            SigningInfoParser.Info info = findEmbeddedSigningInfo(project);
                            storePassword = info.storePassword;
                            if (isBlank(keyAlias)) keyAlias = info.alias;
                            if (isBlank(keyPassword)) keyPassword = info.keyPassword;
                            postLog(listener, logWriter, "Đã ghép JKS ngoài với signing info trong source ZIP");
                        } catch (IOException noInfo) {
                            throw new IOException("JKS đơn lẻ không chứa mật khẩu. Hãy chọn RELEASE-KEY.zip hoặc để SIGNING-KEY-INFO.txt trong source ZIP.");
                        }
                    }
                }
                if (isBlank(storePassword)) throw new IOException("Không tìm thấy thông tin ký tự động");
                if (isBlank(keyPassword)) keyPassword = storePassword;
            }

            checkCancelled();
            postProgress(listener, 20, "Chuẩn bị JDK/SDK standalone");
            ToolchainManager toolchainManager = new ToolchainManager(context);
            File runtime = toolchainManager.ensureReady(new PostingListener(listener, logWriter));
            checkCancelled();

            postProgress(listener, 36, "Đọc yêu cầu toolchain của project");
            ToolchainRequirements requirements = toolchainManager.ensureForProject(
                    runtime, project, new PostingListener(listener, logWriter));
            postLog(listener, logWriter, "Toolchain ready: " + requirements);
            File engine = new File(runtime, "run-build.sh");
            checkCancelled();

            if (effectiveBuildMode == BuildMode.RELEASE && effectiveSigning != null && effectiveSigning.generatesNewKey()) {
                postProgress(listener, 34, "Tạo Release key mới");
                String packageName = SourceInspector.readPackageName(project);
                ReleaseKeyManager.ResolvedKey generated = ReleaseKeyManager.generateAndExport(
                        context, runtime, session, packageName);
                keyFile = generated.keyFile;
                storePassword = generated.storePassword;
                keyAlias = generated.alias;
                keyPassword = generated.keyPassword;
                postLog(listener, logWriter, "Đã tạo Release key mới");
                if (generated.bundleSavedName != null) {
                    postLog(listener, logWriter, "RELEASE KEY saved: Download/" + generated.bundleSavedName);
                    postLog(listener, logWriter, "JKS + SIGNING-KEY-INFO.txt cũng đã lưu riêng trong Download.");
                    postLog(listener, logWriter, "Giữ RELEASE-KEY.zip/JKS này để ký các bản cập nhật sau.");
                }
            }

            checkCancelled();
            File resultFile = new File(session, "result-apk.txt");
            ProcessBuilder builder = new ProcessBuilder(
                    "/system/bin/sh",
                    engine.getAbsolutePath(),
                    project.getAbsolutePath(),
                    effectiveBuildMode == BuildMode.DEBUG ? "debug" : "release",
                    resultFile.getAbsolutePath());
            builder.redirectErrorStream(true);
            Map<String, String> env = builder.environment();
            env.put("BUILDER_RUNTIME", runtime.getAbsolutePath());
            if (keyFile != null) {
                env.put("SIGNING_KEY", keyFile.getAbsolutePath());
                env.put("STORE_PASS", storePassword);
                env.put("KEY_ALIAS", keyAlias);
                env.put("KEY_PASS", keyPassword);
            }

            postProgress(listener, 35, "Khởi động build engine");
            Process process = builder.start();
            activeProcess = process;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    checkCancelled();
                    handleEngineLine(listener, logWriter, line);
                }
            }
            int code = process.waitFor();
            activeProcess = null;
            checkCancelled();
            if (code != 0) throw new IOException("Build engine thoát với mã " + code);
            postProgress(listener, 94, "Đã build xong, lấy APK");
            if (!resultFile.isFile()) throw new IOException("Build xong nhưng không có result-apk.txt");

            String apkPath;
            try (BufferedReader reader = new BufferedReader(new FileReader(resultFile))) {
                apkPath = reader.readLine();
            }
            if (apkPath == null || apkPath.trim().isEmpty()) throw new IOException("Không tìm thấy APK output");
            File apk = new File(apkPath.trim());
            if (!apk.isFile()) throw new IOException("APK output không tồn tại: " + apk);

            if (mode == BuildMode.UPDATE) {
                if (appliedPatch == null) throw new IOException("Mất trạng thái PATCH sau validation build");

                String updateVariant = effectiveBuildMode == BuildMode.RELEASE ? "Release" : "Debug";
                postProgress(listener, 96, updateVariant + " build PASS; đóng FULL SOURCE");
                String sourceName = PatchManager.exportValidatedSource(
                        context, project, session, appliedPatch, new PostingListener(listener, logWriter));

                postProgress(listener, 98, "Lưu APK UPDATE " + updateVariant + " vào Download");
                String apkName = outputApkName(project, effectiveBuildMode);
                DownloadSaver.SavedApk saved = DownloadSaver.saveApk(context, apk, apkName);
                postLog(listener, logWriter, "UPDATE MODE: " + updateVariant.toUpperCase(java.util.Locale.US));
                postLog(listener, logWriter, "UPDATE APK saved: Download/" + saved.name);
                postLog(listener, logWriter, "UPDATE FULL SOURCE saved: Download/" + sourceName);

                postProgress(listener, 99, "Mở trình cài đặt APK UPDATE");
                String installResult = ApkInstaller.requestInstall(context, saved.uri);
                postLog(listener, logWriter, installResult);

                logWriter.close();
                logWriter = null;
                postProgress(listener, 100, "BUILD UPDATE hoàn tất — APK + FULL SOURCE đã xuất");
                main.post(() -> listener.onSuccess(saved.name));
                return;
            }

            postProgress(listener, 97, "Lưu APK vào Download");
            String name = outputApkName(project, mode);
            DownloadSaver.SavedApk saved = DownloadSaver.saveApk(context, apk, name);
            postLog(listener, logWriter, "APK saved: Download/" + saved.name);

            // Sau khi APK thực sự lưu xong, mở Android Package Installer.
            postProgress(listener, 99, "Mở trình cài đặt APK");
            String installResult = ApkInstaller.requestInstall(context, saved.uri);
            postLog(listener, logWriter, installResult);

            logWriter.close();
            logWriter = null;
            postProgress(listener, 100, "Hoàn tất — đã gọi trình cài đặt");
            main.post(() -> listener.onSuccess(saved.name));
        } catch (Throwable error) {
            activeProcess = null;
            if (cancelRequested.get() || Thread.currentThread().isInterrupted() || error instanceof BuildCancelledException) {
                try {
                    if (logWriter != null) {
                        logWriter.writeLine("BUILD CANCELED BY USER");
                        logWriter.close();
                        logWriter = null;
                    }
                } catch (Throwable ignored) { }
                main.post(listener::onCancelled);
                return;
            }
            String message = error.getMessage();
            if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
            String logName = null;
            try {
                if (logWriter != null) {
                    logWriter.writeLine("BUILD FAILED: " + message);
                    logWriter.close();
                    logWriter = null;
                }
                if (logFile.isFile()) {
                    logName = DownloadSaver.saveLog(context, logFile, "APK-PRO-build-error.log");
                }
            } catch (Throwable ignored) {
            }
            String finalMessage = logName == null
                    ? message
                    : message + " — log: Download/" + logName;
            main.post(() -> listener.onFailure(finalMessage));
        } finally {
            activeProcess = null;
            activeTask = null;
            activeThread = null;
            if (logWriter != null) {
                try {
                    logWriter.close();
                } catch (IOException ignored) {
                }
            }
        }
    }


    private static File findEmbeddedSigningKey(File session) throws IOException {
        java.util.List<File> keys = new java.util.ArrayList<>();
        collectSigningKeys(session, keys, 0);
        if (keys.isEmpty()) {
            throw new IOException("Không tìm thấy .jks/.keystore trong source ZIP");
        }
        if (keys.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) names.append(", ");
                names.append(keys.get(i).getName());
            }
            throw new IOException("Có nhiều key trong source ZIP: " + names + " — hãy dùng Chọn file key");
        }
        return keys.get(0);
    }


    private static SigningInfoParser.Info findEmbeddedSigningInfo(File session) throws IOException {
        java.util.List<File> candidates = new java.util.ArrayList<>();
        collectSigningInfoTextFiles(session, candidates, 0);
        SigningInfoParser.Info found = null;
        for (File candidate : candidates) {
            SigningInfoParser.Info info;
            try {
                info = SigningInfoParser.parseFile(candidate);
            } catch (IOException notSigningInfo) {
                continue;
            }
            if (found != null && (!found.alias.equals(info.alias)
                    || !found.storePassword.equals(info.storePassword)
                    || !found.keyPassword.equals(info.keyPassword))) {
                throw new IOException("Có nhiều SIGNING-KEY-INFO.txt khác nhau trong source ZIP — hãy chọn key ngoài");
            }
            found = info;
        }
        if (found == null) {
            throw new IOException("Không tìm thấy SIGNING-KEY-INFO.txt hợp lệ trong source ZIP; cần Alias / Store password / Key password");
        }
        return found;
    }

    private static void collectSigningInfoTextFiles(File file, java.util.List<File> out, int depth) {
        if (file == null || depth > 8 || out.size() > 30) return;
        if (file.isFile()) {
            String name = file.getName().toLowerCase(java.util.Locale.US);
            if (name.endsWith(".txt") && file.length() <= 128 * 1024) out.add(file);
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) collectSigningInfoTextFiles(child, out, depth + 1);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void collectSigningKeys(File file, java.util.List<File> out, int depth) {
        if (file == null || depth > 8 || out.size() > 8) return;
        if (file.isFile()) {
            String name = file.getName().toLowerCase(java.util.Locale.US);
            if ((name.endsWith(".jks") || name.endsWith(".keystore")) && !name.equals("debug.keystore")) {
                out.add(file);
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) collectSigningKeys(child, out, depth + 1);
    }
    private static String outputApkName(File project, BuildMode mode) {
        String packageName = SourceInspector.readPackageName(project);
        String version = PatchManager.normalizeVersion(SourceInspector.readVersionName(project));
        String suffix = mode == BuildMode.DEBUG ? "debug.apk" : "release.apk";
        if ("com.apkbld".equals(packageName)) {
            return "APK-PRO-v" + (version.isEmpty() ? "unknown" : version) + "-" + suffix;
        }
        String base = packageName == null || packageName.trim().isEmpty()
                ? project.getName()
                : packageName.replaceAll("[^A-Za-z0-9._-]", "_");
        return base + (version.isEmpty() ? "" : "-v" + version) + "-" + suffix;
    }

    private void handleEngineLine(BuildListener listener, BuildLogWriter writer, String line) {
        if (line != null && line.startsWith(PROGRESS_PREFIX)) {
            String[] parts = line.split("\\|", 3);
            if (parts.length == 3) {
                try {
                    int percent = Integer.parseInt(parts[1]);
                    postProgress(listener, percent, parts[2]);
                    return;
                } catch (NumberFormatException ignored) {
                }
            }
        }

        postLog(listener, writer, line);

        // Gradle không cung cấp phần trăm task ổn định trong console plain. Bump có giới hạn
        // để thanh tiến trình vẫn phản ánh task đang chạy mà không giả 100% trước khi hoàn tất.
        if (line != null && line.startsWith("> Task ")) {
            taskLines++;
            int percent = Math.min(89, 60 + taskLines);
            postProgress(listener, percent, "Gradle task " + taskLines);
        }
    }

    private void saveLastProjectPath(File project) {
        File marker = new File(context.getFilesDir(), "last-project-path.txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(marker, false))) {
            writer.write(project.getAbsolutePath());
            writer.newLine();
        } catch (IOException ignored) {
        }
    }

    private void postLog(BuildListener listener, BuildLogWriter writer, String line) {
        if (writer != null) {
            try {
                writer.writeLine(line);
            } catch (IOException ignored) {
            }
        }
        main.post(() -> listener.onLog(line));
    }

    private void postProgress(BuildListener listener, int percent, String stage) {
        int safe = Math.max(0, Math.min(100, percent));
        main.post(() -> listener.onProgress(safe, stage));
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private final class PostingListener implements BuildListener {
        private final BuildListener delegate;
        private final BuildLogWriter writer;

        private PostingListener(BuildListener delegate, BuildLogWriter writer) {
            this.delegate = delegate;
            this.writer = writer;
        }

        @Override public void onStarted() { }
        @Override public void onLog(String line) { postLog(delegate, writer, line); }
        @Override public void onProgress(int percent, String stage) { postProgress(delegate, percent, stage); }
        @Override public void onSuccess(String outputName) { }
        @Override public void onFailure(String message) { }
        @Override public void onCancelled() { }
    }

    private static final class BuildCancelledException extends IOException {
        BuildCancelledException() { super("Đã hủy bởi người dùng"); }
    }

    private static final class BuildLogWriter implements AutoCloseable {
        private final BufferedWriter writer;

        private BuildLogWriter(File file) throws IOException {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Không tạo được thư mục log");
            }
            writer = new BufferedWriter(new FileWriter(file, false));
        }

        private synchronized void writeLine(String line) throws IOException {
            writer.write(line == null ? "" : line);
            writer.newLine();
            writer.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            writer.close();
        }
    }
}
