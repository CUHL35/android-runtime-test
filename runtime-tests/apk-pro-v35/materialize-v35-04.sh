#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:?materialize root required}"
mkdir -p "$ROOT"
cat >> "$ROOT/app/src/main/java/com/longdev/apkbuilder/MainActivity.java" <<'APKPRO_92de930e4d9022c5'
            inspectManualBaseline(uri);
        } else if (requestCode == REQUEST_TOOLCHAIN_PACK) {
            importToolchainPack(uri);
        }
    }

    private void persistReadPermission(Uri uri, Intent data) {
        try {
            int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Throwable ignored) {
        }
    }

    private void inspectSelectedZip() {
        final Uri inspecting = selectedZipUri;
        if (inspecting == null) {
            resetSelectionUi();
            return;
        }

        sourceUri = null;
        patchUri = null;
        patchInfo = null;
        selectedResult = null;
        baselineUri = null;
        baselineVerified = false;
        baselineStatus = "";
        externalSigning = null;
        externalSigningName = "";
        noKeyMode = NoKeyMode.DEBUG;
        sourceBuildMode = BuildMode.DEBUG;

        zipButton.setText("✓ " + displayName(inspecting));
        zipMeta.setText("Đang scan ZIP...");
        hideBadge(zipBadge);
        hideBadge(verifyBadge);
        actionNote.setText("Đang nhận dạng SOURCE / PATCH / KEY...");
        showDisabledAction("ĐANG SCAN...");
        setVisibleLog("[SCAN] " + displayName(inspecting) + "\n[INFO] APK PRO đang tự nhận dạng ZIP...");

        sourceInspector.inspect(inspecting, result -> {
            if (selectedZipUri == null || !selectedZipUri.equals(inspecting)) return;
            selectedResult = result;
            zipButton.setText("✓ " + safeName(result.fileName, "selected.zip"));

            if (result.type == SourceInspector.Type.SOURCE) {
                sourceUri = inspecting;
                patchUri = null;
                patchInfo = null;
                zipMeta.setText("Package: " + safe(result.packageName, "không xác định"));
                hideBadge(verifyBadge);

                if (result.releaseReady) {
                    sourceBuildMode = BuildMode.RELEASE;
                    showBadge(zipBadge, "KEY READY", R.drawable.bg_badge_green, getColorCompat(R.color.green));
                    appendVisibleLog("[OK] FULL SOURCE · tìm thấy JKS + signing info");
                    appendVisibleLog("[CHECK] Release sẽ xác minh JKS bằng JDK trước Gradle");
                    appendVisibleLog("[READY] Mặc định Build Release · dùng mũi tên để chọn Debug");
                } else if (result.embeddedKeyCount > 0) {
                    sourceBuildMode = BuildMode.DEBUG;
                    showBadge(zipBadge, "KEY FOUND", R.drawable.bg_badge_orange, getColorCompat(R.color.orange));
                    appendVisibleLog("[WARN] Có key nhưng thiếu/không đồng nhất signing info; Release sẽ cần chọn key/info phù hợp");
                    appendVisibleLog("[READY] Mặc định Build Debug; dùng mũi tên để chọn Release");
                } else {
                    sourceBuildMode = BuildMode.DEBUG;
                    showBadge(zipBadge, "NO KEY", R.drawable.bg_badge_blue, getColorCompat(R.color.blue2));
                    appendVisibleLog("[INFO] FULL SOURCE · không có Release key");
                    appendVisibleLog("[READY] Mặc định Build Debug");
                }
                renderAction();
                return;
            }

            if (result.type == SourceInspector.Type.PATCH && result.patchInfo != null) {
                patchUri = inspecting;
                patchInfo = result.patchInfo;
                String base = safeVersion(patchInfo.baseVersion);
                String target = safeVersion(patchInfo.targetVersion);
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
        if (BuildStateStore.isRunning() || commandRunning || diagnosticsRunning || toolchainSetupRunning) return;
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
        if (selectedResult == null || BuildStateStore.isRunning() || commandRunning || diagnosticsRunning || toolchainSetupRunning) return;

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
APKPRO_92de930e4d9022c5
