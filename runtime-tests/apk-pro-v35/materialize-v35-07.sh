#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:?materialize root required}"
mkdir -p "$ROOT"
cat >> "$ROOT/app/src/main/java/com/longdev/apkbuilder/MainActivity.java" <<'APKPRO_3e9923967a86f7b6'
        renderAction();
    }

    private void exportToolchainPack() {
        if (BuildStateStore.isRunning() || commandRunning || toolchainSetupRunning) {
            actionNote.setText("Đang có tác vụ; chưa thể xuất Toolchain Pack");
            return;
        }
        actionNote.setText("Đang đóng gói APK PRO ARM64 Core...");
        new Thread(() -> {
            try {
                ToolchainPackManager.ExportResult result = ToolchainPackManager.exportToDownloads(this);
                runOnUiThread(() -> {
                    actionNote.setText("Đã xuất Download/" + result.savedName);
                    appendVisibleLog("[TOOLCHAIN] Export OK: " + result.savedName);
                    appendVisibleLog("[SHA-256] " + result.sha256);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> actionNote.setText("Xuất Toolchain Pack lỗi: " + safe(error.getMessage(), error.getClass().getSimpleName())));
            }
        }, "apkpro-toolchain-export").start();
    }

    private void importToolchainPack(Uri uri) {
        if (BuildStateStore.isRunning() || commandRunning || toolchainSetupRunning) {
            actionNote.setText("Đang có tác vụ; chưa thể nhập Toolchain Pack");
            return;
        }
        actionNote.setText("Đang xác minh và nhập APK PRO ARM64 Core...");
        new Thread(() -> {
            try {
                ToolchainPackManager.importFromUri(this, uri);
                runOnUiThread(() -> {
                    actionNote.setText("Đã nhập Toolchain Pack · core local sẵn sàng");
                    appendVisibleLog("[TOOLCHAIN] Import OK · APK PRO ARM64 Core v2");
                    appendVisibleLog("[TOOLCHAIN] Core chạy local · không cần live package repo/bootstrap");
                });
            } catch (Throwable error) {
                runOnUiThread(() -> actionNote.setText("Nhập Toolchain Pack lỗi: " + safe(error.getMessage(), error.getClass().getSimpleName())));
            }
        }, "apkpro-toolchain-import").start();
    }

    private void openApkDownloads() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Uri downloads = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download");
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloads);
            } catch (Throwable ignored) { }
        }
        try {
            startActivityForResult(intent, REQUEST_APK);
        } catch (ActivityNotFoundException noFiles) {
            actionNote.setText("Không có ứng dụng quản lý tệp để mở Download");
        }
    }

    private void openSelectedApk(Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, "application/vnd.android.package-archive");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(view); }
        catch (SecurityException denied) { actionNote.setText("Android đang chặn cài APK từ nguồn này"); }
        catch (ActivityNotFoundException unavailable) { actionNote.setText("Không có trình cài APK"); }
    }

    private void saveCurrentLog() {
        try {
            String name = RecoveryManager.saveCurrentLog(this);
            actionNote.setText("Đã lưu log: Download/" + name);
        } catch (Exception error) {
            actionNote.setText("Không lưu được log: " + error.getMessage());
        }
    }

    private void cancelCurrentWork() {
        if (BuildStateStore.isRunning()) {
            Intent cancel = new Intent(this, BuildService.class);
            cancel.setAction(BuildService.ACTION_CANCEL);
            startService(cancel);
            actionNote.setText("Đang yêu cầu hủy build...");
        } else if (commandRunning) {
            commandCoordinator.cancel();
            actionNote.setText("Đang hủy lệnh...");
        } else if (toolchainSetupRunning && toolchainSetupThread != null) {
            toolchainSetupThread.interrupt();
            actionNote.setText("Đang hủy tải toolchain...");
        }
    }

    private void resetBuildSession() {
        if (BuildStateStore.isRunning() || commandRunning || diagnosticsRunning || toolchainSetupRunning) {
            actionNote.setText("Không reset khi đang có tác vụ");
            return;
        }
        RecoveryManager.resetBuildSession(this);
        clearVisibleLog();
        updateProgress(0, "Chưa chạy");
        actionNote.setText("Đã reset phiên build; ZIP đang chọn vẫn giữ nguyên");
        renderAction();
    }

    private void confirmCleanTransient() {
        if (BuildStateStore.isRunning() || commandRunning || diagnosticsRunning || toolchainSetupRunning) {
            actionNote.setText("Không dọn cache khi đang có tác vụ");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Dọn build tạm?")
                .setMessage("Xóa build-session và thư mục TMP của lần build. Nhẹ và an toàn; giữ Gradle/Maven, JDK, SDK, toolchain và FULL SOURCE baseline.")
                .setNegativeButton("HỦY", null)
                .setPositiveButton("DỌN", (d, w) -> {
                    RecoveryManager.clearTransientBuild(this);
                    clearVisibleLog();
                    actionNote.setText("Đã dọn build tạm");
                }).show();
    }

    private void confirmCleanGradleMaven() {
        if (BuildStateStore.isRunning() || commandRunning || diagnosticsRunning || toolchainSetupRunning) {
            actionNote.setText("Không dọn cache khi đang có tác vụ");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Dọn Gradle / Maven cache?")
                .setMessage("Xóa dependency/artifact cache + Gradle daemon. Có thể giải phóng nhiều dung lượng nhưng project sẽ tải lại dependency khi build. Giữ Gradle distributions, JDK, SDK, toolchain và baseline PATCH.")
                .setNegativeButton("HỦY", null)
                .setPositiveButton("DỌN", (d, w) -> {
                    RecoveryManager.clearGradleMavenCaches(this);
                    actionNote.setText("Đã dọn Gradle/Maven cache");
                }).show();
    }

    private void confirmCleanDownloadCache() {
        if (BuildStateStore.isRunning() || commandRunning || diagnosticsRunning || toolchainSetupRunning) {
            actionNote.setText("Không dọn cache khi đang có tác vụ");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Dọn cache tải toolchain?")
                .setMessage("Xóa các archive tải trung gian trong download-cache. Giữ APK PRO ARM64 Core, SDK platform và Gradle đã cài; phần official cache thiếu sẽ tải lại khi project cần.")
                .setNegativeButton("HỦY", null)
                .setPositiveButton("DỌN", (d, w) -> {
                    RecoveryManager.clearToolchainDownloadCache(this);
                    actionNote.setText("Đã dọn cache tải toolchain");
                }).show();
    }

    private void runFullDiagnostics() {
        if (BuildStateStore.isRunning() || commandRunning || diagnosticsRunning || toolchainSetupRunning) {
            actionNote.setText("Chờ tác vụ hiện tại xong rồi TEST ALL");
            return;
        }
        diagnosticsRunning = true;
        zipButton.setEnabled(false);
        commandRunButton.setEnabled(false);
        showProgress();
        clearVisibleLog();
        updateProgress(1, "Chuẩn bị TEST ALL");
        actionNote.setText("Đang chẩn đoán toàn bộ · không thay đổi cache/source");

        final SourceInspector.Result source = selectedResult;
        final boolean baseVerified = baselineVerified;
        final String baseStatus = baselineStatus;

        new Thread(() -> {
            try {
                DiagnosticsRunner.Report report = DiagnosticsRunner.run(this, new DiagnosticsRunner.Listener() {
                    @Override public void onProgress(int percent, String stage) {
                        runOnUiThread(() -> updateProgress(percent, stage));
                    }

                    @Override public void onLine(String line) {
                        runOnUiThread(() -> appendVisibleLog(line));
                    }
                });

                StringBuilder sourceSummary = new StringBuilder();
                sourceSummary.append("=== SOURCE / SIGNING ===\n");
                if (source == null) {
                    sourceSummary.append("[WARN] Source · chưa chọn ZIP\n");
                } else {
                    sourceSummary.append("[PASS] Source · ").append(safeName(source.fileName, "ZIP"))
                            .append(" · ").append(source.type).append("\n");
                    sourceSummary.append("[PASS] Package · ").append(safe(source.packageName, "?")).append("\n");
                    sourceSummary.append("[PASS] Version · v").append(safeVersion(source.versionName))
                            .append(" · code ").append(source.versionCode).append("\n");
                    sourceSummary.append("[PASS] SHA-256 · ").append(safe(source.sha256, "?")).append("\n");
                    if (source.type == SourceInspector.Type.SOURCE) {
                        if (source.releaseReady) sourceSummary.append("[PASS] Signing · JKS + info có sẵn; JDK sẽ preflight khi Release\n");
                        else if (source.embeddedKeyCount > 0) sourceSummary.append("[WARN] Signing · có key nhưng chưa đủ điều kiện Release tự động\n");
                        else sourceSummary.append("[WARN] Signing · source không có release key\n");
                    }
                }
                if (baseVerified) sourceSummary.append("[PASS] Patch baseline · ").append(safe(baseStatus, "đã xác minh")).append("\n");
                else if (source != null && source.type == SourceInspector.Type.PATCH)
                    sourceSummary.append("[WARN] Patch baseline · chưa xác minh\n");

                runOnUiThread(() -> {
                    for (String line : sourceSummary.toString().split("\n")) {
                        if (!line.isEmpty()) appendVisibleLog(line);
                    }
                    diagnosticsRunning = false;
                    zipButton.setEnabled(true);
                    commandRunButton.setEnabled(true);
                    updateProgress(100, report.isPass() ? "TEST ALL hoàn tất" : "TEST ALL có lỗi");
                    actionNote.setText("TEST ALL · PASS " + report.pass + " · WARN " + report.warn + " · FAIL " + report.fail);
                    renderAction();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    diagnosticsRunning = false;
                    zipButton.setEnabled(true);
                    commandRunButton.setEnabled(true);
                    appendVisibleLog("[FAIL] TEST ALL · " + safe(error.getMessage(), error.getClass().getSimpleName()));
                    actionNote.setText("TEST ALL FAILED");
                    renderAction();
                });
            }
        }, "ApkProDiagnostics").start();
    }

    private void runQuickDiagnostics() {
        if (BuildStateStore.isRunning() || commandRunning || diagnosticsRunning || toolchainSetupRunning) {
            actionNote.setText("Chờ tác vụ hiện tại xong rồi chẩn đoán");
            return;
        }
        String command = "echo '=== JAVA ==='; java -version; "
                + "echo '=== JDK CACHE ==='; ls -1 \"$PREFIX/lib/jvm\" 2>/dev/null || true; "
                + "echo '=== GRADLE CACHE ==='; ls -1 \"$PREFIX/opt/gradle\" 2>/dev/null || true; "
                + "echo '=== SDK CACHE ==='; ls -1 \"$ANDROID_SDK_ROOT/platforms\" 2>/dev/null || true; "
                + "echo '=== STORAGE ==='; df -h \"$HOME\" 2>/dev/null || true; "
APKPRO_3e9923967a86f7b6
