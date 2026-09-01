#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:?materialize root required}"
mkdir -p "$ROOT"
cat >> "$ROOT/app/src/main/java/com/longdev/apkbuilder/MainActivity.java" <<'APKPRO_51b37cbb3eda80ae'
        appendVisibleLog("[KEY] Đã ghép JKS + signing TXT; sẽ preflight key trước Gradle");
        renderAction();
    }

    private void handleInfoAfterKey(Uri infoUri) {
        Uri keyUri = pendingExternalKeyUri;
        pendingExternalKeyUri = null;
        if (keyUri == null) {
            onFailure("Mất JKS đang chờ ghép signing TXT");
            noKeyMode = NoKeyMode.DEBUG;
            sourceBuildMode = BuildMode.DEBUG;
            renderAction();
            return;
        }
        new Thread(() -> {
            try {
                SigningInfoParser.Info info = SigningInfoParser.parseUri(this, infoUri);
                runOnUiThread(() -> {
                    externalSigning = SigningData.external(keyUri, info.storePassword, info.alias, info.keyPassword);
                    externalSigningName = displayName(keyUri);
                    noKeyMode = NoKeyMode.RELEASE_EXTERNAL;
                    sourceBuildMode = BuildMode.RELEASE;
                    appendVisibleLog("[KEY] Đã ghép JKS + SIGNING-KEY-INFO.txt");
                    renderAction();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    onFailure("SIGNING-KEY-INFO.txt không hợp lệ: " + safe(error.getMessage(), error.getClass().getSimpleName()));
                    noKeyMode = NoKeyMode.DEBUG;
                    sourceBuildMode = BuildMode.DEBUG;
                    renderAction();
                });
            }
        }, "SigningInfoPairReader").start();
    }

    private void startBuild(BuildMode mode, SigningData signing, Uri buildPatchUri, Uri buildSourceUri) {
        if (buildSourceUri == null) {
            onFailure("Chưa có FULL SOURCE ZIP");
            return;
        }
        if (BuildStateStore.isRunning() || commandRunning || diagnosticsRunning || toolchainSetupRunning) return;
        if (mode == BuildMode.RELEASE && (signing == null || !signing.hasUsableKeySelection())) {
            onFailure("Build Release cần signing key");
            return;
        }
        if (mode == BuildMode.UPDATE && buildPatchUri == null) {
            onFailure("BUILD UPDATE cần PATCH ZIP");
            return;
        }

        Intent service = new Intent(this, BuildService.class);
        service.setAction(BuildService.ACTION_BUILD);
        service.putExtra(BuildService.EXTRA_SOURCE_URI, buildSourceUri.toString());
        service.putExtra(BuildService.EXTRA_MODE, mode.name());
        if (buildPatchUri != null) service.putExtra(BuildService.EXTRA_PATCH_URI, buildPatchUri.toString());
        if (signing != null) {
            service.putExtra(BuildService.EXTRA_KEY_SOURCE, signing.keySource.name());
            if (signing.keyUri != null) service.putExtra(BuildService.EXTRA_KEY_URI, signing.keyUri.toString());
            service.putExtra(BuildService.EXTRA_STORE_PASS, signing.storePassword);
            service.putExtra(BuildService.EXTRA_KEY_ALIAS, signing.alias);
            service.putExtra(BuildService.EXTRA_KEY_PASS, signing.keyPassword);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
    }

    private void runCommand() {
        if (BuildStateStore.isRunning() || diagnosticsRunning || toolchainSetupRunning) {
            actionNote.setText("Đang có tác vụ; chờ xong rồi chạy lệnh");
            return;
        }
        String command = commandInput.getText().toString().trim();
        commandCoordinator.run(command, this);
        // Intentionally keep commandInput unchanged after RUN.
    }

    private void showMainMenu() {
        PopupMenu menu = new PopupMenu(this, menuButton);
        menu.getMenu().add("Mở APK / Download");
        if (BuildStateStore.isRunning() || commandRunning || toolchainSetupRunning) menu.getMenu().add("DỪNG / HỦY");
        menu.getMenu().add("Tải toolchain");
        menu.getMenu().add("Chẩn đoán / TEST ALL");
        menu.getMenu().add("Chẩn đoán nhanh");
        menu.getMenu().add("Dọn build tạm");
        menu.getMenu().add("Dọn cache Gradle / Maven");
        menu.getMenu().add("Dọn cache tải toolchain");
        menu.getMenu().add("Xuất Toolchain Pack");
        menu.getMenu().add("Nhập Toolchain Pack");
        menu.getMenu().add("Reset phiên build");
        menu.getMenu().add("Prompt để xuất PATCH");
        menu.getMenu().add("Prompt để xuất RELEASE");
        menu.getMenu().add("Xóa log hiển thị");
        menu.getMenu().add("Thoát APK PRO");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.startsWith("Mở APK")) openApkDownloads();
            else if (title.startsWith("DỪNG")) cancelCurrentWork();
            else if (title.equals("Tải toolchain")) showToolchainDownloadDialog(false);
            else if (title.equals("Chẩn đoán / TEST ALL")) runFullDiagnostics();
            else if (title.equals("Chẩn đoán nhanh")) runQuickDiagnostics();
            else if (title.equals("Dọn build tạm")) confirmCleanTransient();
            else if (title.equals("Dọn cache Gradle / Maven")) confirmCleanGradleMaven();
            else if (title.equals("Dọn cache tải toolchain")) confirmCleanDownloadCache();
            else if (title.equals("Xuất Toolchain Pack")) exportToolchainPack();
            else if (title.equals("Nhập Toolchain Pack")) chooseFile(REQUEST_TOOLCHAIN_PACK, "application/zip");
            else if (title.startsWith("Reset")) resetBuildSession();
            else if (title.equals("Prompt để xuất PATCH")) showTextPrompt("PROMPT ĐỂ XUẤT PATCH", PATCH_SOURCE_PROMPT);
            else if (title.equals("Prompt để xuất RELEASE")) showTextPrompt("PROMPT ĐỂ XUẤT RELEASE", RELEASE_SOURCE_PROMPT);
            else if (title.startsWith("Xóa log")) {
                BuildStateStore.clearLog();
                clearVisibleLog();
                actionNote.setText("Đã xóa log hiển thị");
            } else if (title.startsWith("Thoát")) exitApp();
            return true;
        });
        menu.show();
    }

    private void maybeShowFirstToolchainPrompt() {
        if (ToolchainManager.hasCore(getFilesDir())) return;
        boolean shown = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_TOOLCHAIN_PROMPT_SHOWN, false);
        if (shown) return;
        menuButton.post(() -> showToolchainDownloadDialog(true));
    }

    private void showToolchainDownloadDialog(boolean firstLaunch) {
        if (BuildStateStore.isRunning() || commandRunning || diagnosticsRunning || toolchainSetupRunning) {
            actionNote.setText("Đang có tác vụ khác; chưa thể tải toolchain");
            return;
        }
        boolean coreReady = ToolchainManager.hasCore(getFilesDir());
        String status = coreReady
                ? "Bộ cơ bản đã có trong cache. Có thể kiểm tra lại hoặc tải thêm SDK phổ biến."
                : "Chưa có bộ ARM64 cơ bản. APK PRO vẫn có thể để sau và sẽ tải khi build cần.";
        String message = status + "\n\n"
                + "BỘ CƠ BẢN\n"
                + "• JDK 17 ARM64 + CA certificates\n"
                + "• aapt2 / aidl / zipalign + thư viện ARM64\n"
                + "• apksigner/core-lambda-stubs Build Tools 36 từ Google\n\n"
                + "SDK PHỔ BIẾN\n"
                + "• thêm Android Platform API 37 (package 37.0)\n\n"
                + "Gradle/AGP/Kotlin/dependency không tải cố định: APK PRO đọc project rồi cache đúng version khi build.";

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(firstLaunch ? "Chuẩn bị môi trường build?" : "Tải toolchain")
                .setMessage(message)
                .setNegativeButton("ĐỂ SAU", null)
                .setNeutralButton("BỘ CƠ BẢN", null)
                .setPositiveButton("CƠ BẢN + SDK 37", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(v -> {
                        dialog.dismiss();
                        startToolchainDownload(false);
                    });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        dialog.dismiss();
                        startToolchainDownload(true);
                    });
        });
        dialog.show();
        if (firstLaunch) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_TOOLCHAIN_PROMPT_SHOWN, true)
                    .apply();
        }
    }

    private void startToolchainDownload(boolean includeCommonSdk) {
        if (BuildStateStore.isRunning() || commandRunning || diagnosticsRunning || toolchainSetupRunning) {
            actionNote.setText("Đang có tác vụ khác; chưa thể tải toolchain");
            return;
        }
        toolchainSetupRunning = true;
        showProgress();
        zipButton.setEnabled(false);
        commandRunButton.setEnabled(false);
        updateProgress(1, "Chuẩn bị toolchain");
        actionNote.setText("Đang tải/kiểm tra toolchain · menu ⋯ có DỪNG / HỦY");
        appendVisibleLog("[TOOLCHAIN] Bắt đầu kiểm tra cache ARM64...");

        toolchainSetupThread = new Thread(() -> {
            BuildListener listener = new BuildListener() {
                @Override public void onStarted() { }
                @Override public void onLog(String line) {
                    runOnUiThread(() -> appendVisibleLog("[TOOLCHAIN] " + safe(line, "")));
                }
                @Override public void onProgress(int percent, String stage) {
                    runOnUiThread(() -> updateProgress(percent, stage));
                }
                @Override public void onSuccess(String outputName) { }
                @Override public void onFailure(String message) { }
            };
            try {
                ToolchainManager manager = new ToolchainManager(this);
                if (includeCommonSdk) manager.prefetchCommonSdk(listener);
                else manager.ensureReady(listener);
                if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("Đã hủy bởi người dùng");
                runOnUiThread(() -> finishToolchainDownloadSuccess(includeCommonSdk));
            } catch (Throwable error) {
                boolean cancelled = Thread.currentThread().isInterrupted()
                        || safe(error.getMessage(), "").contains("Đã hủy");
                runOnUiThread(() -> finishToolchainDownloadFailure(error, cancelled));
            }
        }, "apkpro-toolchain-download");
        toolchainSetupThread.start();
    }

    private void finishToolchainDownloadSuccess(boolean includeCommonSdk) {
        toolchainSetupRunning = false;
        toolchainSetupThread = null;
        zipButton.setEnabled(true);
        commandRunButton.setEnabled(true);
        updateProgress(100, "Toolchain sẵn sàng");
        actionNote.setText(includeCommonSdk
                ? "Toolchain cơ bản + SDK 37.0 đã sẵn sàng"
                : "Toolchain cơ bản đã sẵn sàng");
        appendVisibleLog(includeCommonSdk
                ? "[TOOLCHAIN] READY · Core ARM64 + API 37.0"
                : "[TOOLCHAIN] READY · Core ARM64");
        renderAction();
    }

    private void finishToolchainDownloadFailure(Throwable error, boolean cancelled) {
        toolchainSetupRunning = false;
        toolchainSetupThread = null;
        zipButton.setEnabled(true);
        commandRunButton.setEnabled(true);
        updateProgress(0, cancelled ? "Đã hủy" : "Toolchain lỗi");
        String detail = safe(error == null ? null : error.getMessage(),
                error == null ? "unknown" : error.getClass().getSimpleName());
        actionNote.setText(cancelled ? "Đã hủy tải toolchain" : "Tải toolchain lỗi: " + detail);
        appendVisibleLog(cancelled ? "[TOOLCHAIN] CANCELLED" : "[TOOLCHAIN][FAIL] " + detail);
APKPRO_51b37cbb3eda80ae
