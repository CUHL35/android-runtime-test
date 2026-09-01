#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:?materialize root required}"
mkdir -p "$ROOT"
mkdir -p "$(dirname "$ROOT/app/src/main/java/com/longdev/apkbuilder/MainActivity.java")"
cat >> "$ROOT/app/src/main/java/com/longdev/apkbuilder/MainActivity.java" <<'APKPRO_ac1d646a66_1'
                zipMeta.setText("PATCH: v" + base + " → v" + target + " · Package: " + patchInfo.packageName);
                showBadge(zipBadge, "PATCH", R.drawable.bg_badge_orange, getColorCompat(R.color.orange));
                showBadge(verifyBadge, "CHƯA XÁC MINH", R.drawable.bg_badge_orange, getColorCompat(R.color.orange));
                appendVisibleLog("[OK] PATCH manifest hợp lệ");
                appendVisibleLog("[OK] Base-Version: " + base);
                appendVisibleLog("[OK] Target-Version: " + target);
                appendVisibleLog("[BASE] Tự tìm FULL SOURCE baseline...");
                renderAction();
                autoResolveBaseline(false);
                return;
            }

            zipMeta.setText("ZIP KHÔNG HỢP LỆ");
            showBadge(zipBadge, "INVALID", R.drawable.bg_badge_red, getColorCompat(R.color.red));
            hideBadge(verifyBadge);
            actionNote.setText(result.error == null ? "Không nhận ra FULL SOURCE hoặc PATCH" : result.error);
            appendVisibleLog("[FAIL] " + safe(result.error, "ZIP không hợp lệ"));
            showDisabledAction("BUILD START");
        });
    }

    private void resetSelectionUi() {
        selectedZipUri = null;
        sourceUri = null;
        patchUri = null;
        patchInfo = null;
        selectedResult = null;
        baselineUri = null;
        baselineVerified = false;
        sourceBuildMode = BuildMode.DEBUG;
        noKeyMode = NoKeyMode.DEBUG;
        zipButton.setText("CHỌN ZIP");
        zipMeta.setText("Package: chưa chọn source");
        hideBadge(zipBadge);
        hideBadge(verifyBadge);
        actionNote.setText("");
        showDisabledAction("BUILD START");
    }

    private void renderIdleAction() {
        if (selectedResult == null) showDisabledAction("BUILD START");
        else renderAction();
    }

    private void renderAction() {
        if (BuildStateStore.isRunning() || commandRunning) return;
        progressContainer.setVisibility(View.GONE);
        actionDisabled.setVisibility(View.GONE);
        actionSingle.setVisibility(View.GONE);
        actionSplit.setVisibility(View.GONE);
        actionMain.setEnabled(true);
        actionMain.setBackgroundResource(R.drawable.bg_primary);
        actionMain.setTextColor(Color.WHITE);

        if (selectedResult == null || selectedResult.type == SourceInspector.Type.INVALID) {
            showDisabledAction("BUILD START");
            return;
        }

        if (selectedResult.type == SourceInspector.Type.SOURCE) {
            actionSplit.setVisibility(View.VISIBLE);
            if (sourceBuildMode == BuildMode.DEBUG) {
                actionMain.setText("BUILD DEBUG");
                actionNote.setText("Build Debug · bấm mũi tên để chọn Release");
            } else if (selectedResult.releaseReady) {
                actionMain.setText("BUILD RELEASE");
                actionNote.setText("Key trong ZIP · JDK preflight trước Gradle · bấm mũi tên để chọn Debug");
            } else if (noKeyMode == NoKeyMode.RELEASE_EXTERNAL) {
                actionMain.setText("BUILD RELEASE · JKS");
                actionNote.setText(externalSigning == null
                        ? "Chưa chọn key hợp lệ · bấm mũi tên chọn lại"
                        : "Key ngoài: " + externalSigningName + " · sẽ preflight trước Gradle");
                if (externalSigning == null) disableActionMain();
            } else {
                actionMain.setText("BUILD RELEASE · KEY MỚI");
                actionNote.setText("Tạo JKS + SIGNING-KEY-INFO + RELEASE-KEY.zip vào Download");
            }
            return;
        }

        if (selectedResult.type == SourceInspector.Type.PATCH) {
            actionSplit.setVisibility(View.VISIBLE);
            actionMain.setText("BUILD UPDATE");
            if (baselineVerified) {
                actionNote.setText(baselineStatus);
            } else {
                actionNote.setText(baselineStatus.isEmpty()
                        ? "Baseline v" + safeVersion(patchInfo == null ? "" : patchInfo.baseVersion) + ": đang tìm / có thể chọn thủ công"
                        : baselineStatus);
            }
        }
    }

    private void disableActionMain() {
        actionMain.setEnabled(false);
        actionMain.setBackgroundResource(R.drawable.bg_disabled);
        actionMain.setTextColor(getColorCompat(R.color.disabled_text));
    }

    private void showDisabledAction(String text) {
        progressContainer.setVisibility(View.GONE);
        actionSingle.setVisibility(View.GONE);
        actionSplit.setVisibility(View.GONE);
        actionDisabled.setText(text);
        actionDisabled.setVisibility(View.VISIBLE);
    }

    private void showActionMenu() {
        if (selectedResult == null) return;
        PopupMenu menu = new PopupMenu(this, actionArrow);

        if (selectedResult.type == SourceInspector.Type.PATCH) {
            menu.getMenu().add("Tự tìm FULL SOURCE");
            menu.getMenu().add("Chọn FULL SOURCE thủ công");
            menu.setOnMenuItemClickListener(item -> {
                String title = item.getTitle().toString();
                if (title.startsWith("Tự tìm")) autoResolveBaseline(false);
                else chooseFile(REQUEST_PATCH_BASELINE, "application/zip");
                return true;
            });
            menu.show();
            return;
        }

        if (selectedResult.type == SourceInspector.Type.SOURCE) {
            if (sourceBuildMode == BuildMode.RELEASE) {
                menu.getMenu().add("Build Debug");
            } else if (selectedResult.releaseReady) {
                menu.getMenu().add("Build Release · Key trong ZIP");
            } else {
                menu.getMenu().add("Build Release · Chọn JKS / KEY ZIP");
                menu.getMenu().add("Build Release · Tạo key mới");
            }
            menu.setOnMenuItemClickListener(item -> {
                String title = item.getTitle().toString();
                if (title.equals("Build Debug")) {
                    sourceBuildMode = BuildMode.DEBUG;
                    noKeyMode = NoKeyMode.DEBUG;
                    externalSigning = null;
                    externalSigningName = "";
                    renderAction();
                } else if (title.contains("Key trong ZIP")) {
                    sourceBuildMode = BuildMode.RELEASE;
                    noKeyMode = NoKeyMode.DEBUG;
                    externalSigning = null;
                    externalSigningName = "";
                    renderAction();
                } else if (title.contains("Chọn JKS")) {
                    sourceBuildMode = BuildMode.RELEASE;
                    noKeyMode = NoKeyMode.RELEASE_EXTERNAL;
                    externalSigning = null;
                    externalSigningName = "";
                    renderAction();
                    chooseFile(REQUEST_EXTERNAL_SIGNING, "*/*");
                } else {
                    sourceBuildMode = BuildMode.RELEASE;
                    noKeyMode = NoKeyMode.RELEASE_NEW;
                    externalSigning = null;
                    externalSigningName = "";
                    renderAction();
                }
                return true;
            });
            menu.show();
        }
    }

    private void runPrimaryAction() {
        if (selectedResult == null || BuildStateStore.isRunning()) return;

        if (selectedResult.type == SourceInspector.Type.SOURCE) {
            if (sourceBuildMode == BuildMode.DEBUG) {
                startBuild(BuildMode.DEBUG, null, null, sourceUri);
                return;
            }
            if (selectedResult.releaseReady) {
                startBuild(BuildMode.RELEASE, SigningData.embeddedAuto("", "", ""), null, sourceUri);
                return;
            }
            if (noKeyMode == NoKeyMode.RELEASE_EXTERNAL) {
                if (externalSigning == null) {
                    chooseFile(REQUEST_EXTERNAL_SIGNING, "*/*");
                    return;
                }
                startBuild(BuildMode.RELEASE, externalSigning, null, sourceUri);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Tạo Release key mới?")
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
APKPRO_ac1d646a66_1
