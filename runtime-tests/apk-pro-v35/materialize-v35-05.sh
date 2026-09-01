#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:?materialize root required}"
mkdir -p "$ROOT"
cat >> "$ROOT/app/src/main/java/com/longdev/apkbuilder/MainActivity.java" <<'APKPRO_947ce1029acdb536'
                        .setMessage("Key mới không thể cài đè bản Release đã ký bằng key cũ. APK PRO sẽ lưu JKS + SIGNING-KEY-INFO.txt + RELEASE-KEY.zip vào Download. Chỉ tiếp tục nếu đây là app mới hoặc bạn chấp nhận key mới.")
                        .setNegativeButton("HỦY", null)
                        .setPositiveButton("TẠO KEY + BUILD", (d, w) -> startBuild(
                                BuildMode.RELEASE, SigningData.generateNew(), null, sourceUri))
                        .show();
            }
            return;
        }

        if (selectedResult.type == SourceInspector.Type.PATCH) {
            if (baselineVerified && baselineUri != null) {
                startBuild(BuildMode.UPDATE, null, patchUri, baselineUri);
            } else {
                autoResolveBaseline(true);
            }
        }
    }

    private void autoResolveBaseline(boolean buildWhenFound) {
        if (patchInfo == null || baselineLookupRunning) return;
        baselineLookupRunning = true;
        baselineVerified = false;
        baselineStatus = "Đang scan cache FULL SOURCE v" + safeVersion(patchInfo.baseVersion) + "...";
        showBadge(verifyBadge, "ĐANG TÌM", R.drawable.bg_badge_orange, getColorCompat(R.color.orange));
        renderAction();

        final PatchManager.PatchInfo expected = patchInfo;
        new Thread(() -> {
            try {
                BaselineStore.CachedSource cached = BaselineStore.findExact(
                        this, expected.baseSha256, expected.baseVersion, expected.packageName);
                runOnUiThread(() -> {
                    baselineLookupRunning = false;
                    if (patchInfo != expected) return;
                    if (cached == null) {
                        baselineVerified = false;
                        baselineUri = null;
                        baselineStatus = "Chưa có FULL SOURCE v" + safeVersion(expected.baseVersion) + " · cần chọn thủ công";
                        showBadge(verifyBadge, "CHƯA XÁC MINH", R.drawable.bg_badge_orange, getColorCompat(R.color.orange));
                        appendVisibleLog("[BASE] Không tìm thấy baseline SHA-256 trong source cache");
                        renderAction();
                        showMissingBaselineDialog();
                        return;
                    }

                    Uri original = BaselineStore.readableOriginalUri(this, cached);
                    if (original != null) {
                        baselineUri = original;
                        baselineVerified = true;
                        baselineStatus = "✓ FULL SOURCE v" + safeVersion(expected.baseVersion) + " · ZIP cũ đã có · SHA-256 khớp";
                        appendVisibleLog("[BASE] FULL SOURCE cũ còn truy cập được");
                    } else {
                        baselineUri = cached.uri;
                        baselineVerified = true;
                        baselineStatus = "✓ FULL SOURCE v" + safeVersion(expected.baseVersion) + " · cache đã có · SHA-256 khớp";
                        try {
                            String recreateName = "com.apkbld".equals(expected.packageName)
                                    ? "APK-PRO-v" + safeVersion(expected.baseVersion) + "-FULL-SOURCE.zip"
                                    : expected.packageName.replaceAll("[^A-Za-z0-9._-]", "_") + "-v" + safeVersion(expected.baseVersion) + "-FULL-SOURCE.zip";
                            BaselineStore.exportCachedToDownloads(this, cached, recreateName);
                            baselineStatus = "✓ FULL SOURCE v" + safeVersion(expected.baseVersion) + " · tạo lại từ cache · SHA-256 khớp";
                            appendVisibleLog("[EXPORT] Tạo lại baseline: Download/" + recreateName);
                        } catch (Throwable exportError) {
                            appendVisibleLog("[WARN] Cache baseline dùng được nhưng không tạo lại Download: " + exportError.getMessage());
                        }
                    }
                    showBadge(verifyBadge, "ĐÃ XÁC MINH", R.drawable.bg_badge_green, getColorCompat(R.color.green));
                    renderAction();
                    if (buildWhenFound) startBuild(BuildMode.UPDATE, null, patchUri, baselineUri);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    baselineLookupRunning = false;
                    baselineVerified = false;
                    baselineStatus = "Lỗi scan baseline: " + safe(error.getMessage(), error.getClass().getSimpleName());
                    showBadge(verifyBadge, "CHƯA XÁC MINH", R.drawable.bg_badge_orange, getColorCompat(R.color.orange));
                    appendVisibleLog("[BASE] " + baselineStatus);
                    renderAction();
                    showMissingBaselineDialog();
                });
            }
        }, "BaselineResolver").start();
    }

    private void showMissingBaselineDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Không tìm thấy FULL SOURCE v" + safeVersion(patchInfo == null ? "" : patchInfo.baseVersion))
                .setMessage("APK PRO đã tự scan source cache nhưng chưa có đúng baseline. Hãy chọn FULL SOURCE thủ công; app sẽ kiểm package + version + SHA-256 trước khi cho update.")
                .setNegativeButton("HỦY", null)
                .setPositiveButton("CHỌN SOURCE", (d, w) -> chooseFile(REQUEST_PATCH_BASELINE, "application/zip"))
                .show();
    }

    private void inspectManualBaseline(Uri uri) {
        if (patchInfo == null) return;
        final PatchManager.PatchInfo expected = patchInfo;
        baselineVerified = false;
        baselineUri = null;
        baselineStatus = "Đang scan FULL SOURCE thủ công...";
        showBadge(verifyBadge, "ĐANG KIỂM", R.drawable.bg_badge_orange, getColorCompat(R.color.orange));
        renderAction();
        appendVisibleLog("[BASE] Scan thủ công: " + displayName(uri));

        sourceInspector.inspect(uri, result -> {
            if (patchInfo != expected) return;
            if (result.type != SourceInspector.Type.SOURCE) {
                baselineMismatch("File đã chọn không phải FULL SOURCE");
                return;
            }
            if (!expected.packageName.equals(result.packageName)) {
                baselineMismatch("Sai package: cần " + expected.packageName + " nhưng source là " + safe(result.packageName, "?"));
                return;
            }
            if (!expected.baseSha256.equalsIgnoreCase(result.sha256)) {
                baselineMismatch("Sai SHA-256 baseline");
                return;
            }
            if (!expected.baseVersion.isEmpty() && !PatchManager.sameVersion(expected.baseVersion, result.versionName)) {
                baselineMismatch("Patch cần v" + expected.baseVersion + " nhưng source là v" + safeVersion(result.versionName));
                return;
            }

            baselineUri = uri;
            baselineVerified = true;
            baselineStatus = "✓ FULL SOURCE v" + safeVersion(result.versionName) + " · chọn thủ công · SHA-256 khớp";
            showBadge(verifyBadge, "ĐÃ XÁC MINH", R.drawable.bg_badge_green, getColorCompat(R.color.green));
            appendVisibleLog("[BASE] Package: MATCH");
            appendVisibleLog("[BASE] Version: MATCH");
            appendVisibleLog("[BASE] SHA-256: MATCH");
            renderAction();
        });
    }

    private void baselineMismatch(String reason) {
        baselineVerified = false;
        baselineUri = null;
        baselineStatus = "✕ KHÔNG TƯƠNG THÍCH · " + reason;
        showBadge(verifyBadge, "KHÔNG TƯƠNG THÍCH", R.drawable.bg_badge_red, getColorCompat(R.color.red));
        appendVisibleLog("[FAIL] " + reason);
        renderAction();
        new AlertDialog.Builder(this)
                .setTitle("FULL SOURCE không tương thích")
                .setMessage(reason + "\n\nAPK PRO không áp patch và không sửa source gốc.")
                .setNegativeButton("ĐÓNG", null)
                .setPositiveButton("CHỌN LẠI", (d, w) -> chooseFile(REQUEST_PATCH_BASELINE, "application/zip"))
                .show();
    }

    private void handleExternalSigningSelection(Uri uri) {
        String name = displayName(uri);
        String lower = name.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".zip")) {
            externalSigning = SigningData.externalBundle(uri);
            externalSigningName = name;
            noKeyMode = NoKeyMode.RELEASE_EXTERNAL;
            sourceBuildMode = BuildMode.RELEASE;
            appendVisibleLog("[KEY] Đã chọn RELEASE-KEY.zip: " + name);
            renderAction();
            return;
        }
        if (isKeyFileName(name)) {
            if (selectedResult != null && selectedResult.signingInfoPresent) {
                externalSigning = SigningData.external(uri, "", "", "");
                externalSigningName = name;
                noKeyMode = NoKeyMode.RELEASE_EXTERNAL;
                sourceBuildMode = BuildMode.RELEASE;
                appendVisibleLog("[KEY] JKS đã chọn; dùng SIGNING-KEY-INFO.txt trong source");
                renderAction();
            } else {
                pendingExternalKeyUri = uri;
                new AlertDialog.Builder(this)
                        .setTitle("JKS cần SIGNING-KEY-INFO.txt")
                        .setMessage("JKS không chứa mật khẩu để APK PRO tự đọc. Chọn SIGNING-KEY-INFO.txt tương ứng; alias/password sẽ được preflight trước Gradle.")
                        .setNegativeButton("HỦY", (d, w) -> {
                            pendingExternalKeyUri = null;
                            noKeyMode = NoKeyMode.DEBUG;
                            sourceBuildMode = BuildMode.DEBUG;
                            renderAction();
                        })
                        .setPositiveButton("CHỌN TXT", (d, w) -> chooseFile(REQUEST_INFO_AFTER_KEY, "text/plain"))
                        .show();
            }
            return;
        }
        if (lower.endsWith(".txt")) {
            parseSigningInfoThenChooseKey(uri);
            return;
        }
        onFailure("Chỉ nhận RELEASE-KEY.zip, .jks/.keystore hoặc SIGNING-KEY-INFO.txt");
        noKeyMode = NoKeyMode.DEBUG;
        sourceBuildMode = BuildMode.DEBUG;
        renderAction();
    }

    private void parseSigningInfoThenChooseKey(Uri infoUri) {
        appendVisibleLog("[KEY] Đang đọc SIGNING-KEY-INFO.txt...");
        new Thread(() -> {
            try {
                SigningInfoParser.Info info = SigningInfoParser.parseUri(this, infoUri);
                runOnUiThread(() -> {
                    pendingSigningInfo = info;
                    chooseFile(REQUEST_KEY_AFTER_INFO, "*/*");
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    onFailure("SIGNING-KEY-INFO.txt không hợp lệ: " + safe(error.getMessage(), error.getClass().getSimpleName()));
                    noKeyMode = NoKeyMode.DEBUG;
                    sourceBuildMode = BuildMode.DEBUG;
                    renderAction();
                });
            }
        }, "SigningInfoReader").start();
    }

    private void handleKeyAfterInfo(Uri keyUri) {
        SigningInfoParser.Info info = pendingSigningInfo;
        pendingSigningInfo = null;
        String name = displayName(keyUri);
        if (info == null || !isKeyFileName(name)) {
            onFailure("Cần chọn .jks/.keystore tương ứng với signing TXT");
            noKeyMode = NoKeyMode.DEBUG;
            sourceBuildMode = BuildMode.DEBUG;
            renderAction();
            return;
        }
        externalSigning = SigningData.external(keyUri, info.storePassword, info.alias, info.keyPassword);
        externalSigningName = name;
        noKeyMode = NoKeyMode.RELEASE_EXTERNAL;
        sourceBuildMode = BuildMode.RELEASE;
APKPRO_947ce1029acdb536
