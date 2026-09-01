#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:?materialize root required}"
mkdir -p "$ROOT"
cat >> "$ROOT/app/src/main/java/com/longdev/apkbuilder/MainActivity.java" <<'APKPRO_d2480b2ace546c2f'
                + "echo '=== LAST LOG ==='; [ -f \"$LAST_BUILD_LOG\" ] && tail -n 40 \"$LAST_BUILD_LOG\" || echo 'No build log'";
        commandInput.setText(command);
        commandCoordinator.run(command, this);
    }

    private void showTextPrompt(String title, String text) {
        TextView prompt = new TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        prompt.setPadding(pad, pad, pad, pad);
        prompt.setText(text);
        prompt.setTextColor(getColorCompat(R.color.text));
        prompt.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(prompt);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton("ĐÓNG", null)
                .setPositiveButton("COPY", (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(title, text));
                    actionNote.setText("Đã copy prompt");
                }).show();
    }

    private void exitApp() {
        if (BuildStateStore.isRunning()) {
            new AlertDialog.Builder(this)
                    .setTitle("Thoát APK PRO?")
                    .setMessage("Build vẫn chạy nền. Muốn dừng, chọn DỪNG / HỦY trong menu.")
                    .setNegativeButton("Ở LẠI", null)
                    .setPositiveButton("THOÁT", (d, w) -> finishAndRemoveTask())
                    .show();
            return;
        }
        if (commandRunning) commandCoordinator.cancel();
        if (toolchainSetupRunning && toolchainSetupThread != null) toolchainSetupThread.interrupt();
        finishAndRemoveTask();
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) return value.trim();
                }
            }
        } catch (Throwable ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    private static boolean isKeyFileName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".jks") || lower.endsWith(".keystore");
    }

    private void showBadge(TextView view, String text, int background, int color) {
        view.setText(text);
        view.setTextColor(color);
        view.setBackgroundResource(background);
        view.setVisibility(View.VISIBLE);
    }

    private static void hideBadge(TextView view) {
        view.setVisibility(View.GONE);
        view.setText("");
        view.setBackground((Drawable) null);
    }

    private int getColorCompat(int id) {
        return Build.VERSION.SDK_INT >= 23 ? getColor(id) : getResources().getColor(id);
    }

    private void clearVisibleLog() {
        log.setText("");
        logScroll.post(() -> logScroll.scrollTo(0, 0));
    }

    private void setVisibleLog(String text) {
        log.setText(text == null ? "" : text);
        logScroll.post(() -> logScroll.scrollTo(0, 0));
    }

    private void appendVisibleLog(String line) {
        log.append((line == null ? "" : line) + "\n");
        if (log.length() > MAX_VISIBLE_LOG_CHARS) {
            CharSequence text = log.getText();
            int start = Math.max(0, text.length() - KEEP_VISIBLE_LOG_CHARS);
            log.setText("… log dài, đang hiển thị phần cuối …\n" + text.subSequence(start, text.length()));
        }
        logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void updateProgress(int percent, String stage) {
        int safe = Math.max(0, Math.min(100, percent));
        currentProgress = safe;
        progress.setProgress(safe);
        progressPercent.setText(safe + "%");
        if (stage != null && !stage.trim().isEmpty()) progressStage.setText(stage);
    }

    private void showProgress() {
        actionDisabled.setVisibility(View.GONE);
        actionSingle.setVisibility(View.GONE);
        actionSplit.setVisibility(View.GONE);
        progressContainer.setVisibility(View.VISIBLE);
    }

    @Override
    public void onStarted() {
        commandRunning = false;
        showProgress();
        zipButton.setEnabled(false);
        commandRunButton.setEnabled(false);
        updateProgress(0, "Bắt đầu");
        actionNote.setText("Đang chạy nền · menu ⋯ có DỪNG / HỦY");
        clearVisibleLog();
    }

    @Override public void onLog(String line) { appendVisibleLog(line); }
    @Override public void onProgress(int percent, String stage) { updateProgress(percent, stage); }

    @Override
    public void onSuccess(String outputName) {
        zipButton.setEnabled(true);
        commandRunButton.setEnabled(true);
        updateProgress(100, "Hoàn tất");
        if (selectedResult != null && selectedResult.type == SourceInspector.Type.PATCH) {
            actionNote.setText("UPDATE SUCCESS · APK + FULL SOURCE trong Download");
            if (outputName != null && !outputName.trim().isEmpty()) {
                appendVisibleLog("[EXPORT] UPDATE APK: Download/" + outputName);
            }
        } else if (outputName != null && outputName.toLowerCase(java.util.Locale.US).endsWith(".zip")) {
            actionNote.setText("UPDATE SUCCESS · Download/" + outputName);
            appendVisibleLog("[EXPORT] FULL SOURCE: Download/" + outputName);
        } else {
            actionNote.setText("BUILD SUCCESS · Download/" + outputName);
        }
        renderAction();
    }

    @Override
    public void onFailure(String message) {
        zipButton.setEnabled(true);
        commandRunButton.setEnabled(true);
        progressStage.setText("Dừng tại " + currentProgress + "%");
        actionNote.setText("FAILED · " + safe(message, "Lỗi không xác định"));
        appendVisibleLog("[FAIL] " + safe(message, "Lỗi không xác định"));
        renderAction();
    }

    @Override
    public void onCancelled() {
        zipButton.setEnabled(true);
        commandRunButton.setEnabled(true);
        actionNote.setText("ĐÃ HỦY");
        appendVisibleLog("[CANCEL] Đã hủy bởi người dùng");
        renderAction();
    }

    @Override
    public void onCommandStarted() {
        commandRunning = true;
        showProgress();
        zipButton.setEnabled(false);
        commandRunButton.setEnabled(false);
        updateProgress(0, "RUN lệnh");
        clearVisibleLog();
        appendVisibleLog("$ " + commandInput.getText().toString().trim());
        actionNote.setText("Đang chạy lệnh...");
    }

    @Override
    public void onCommandLog(String line) {
        appendVisibleLog(line);
        if (currentProgress < 90) updateProgress(Math.min(90, currentProgress + 2), "RUN đang chạy");
    }

    @Override
    public void onCommandFinished(int exitCode) {
        commandRunning = false;
        zipButton.setEnabled(true);
        commandRunButton.setEnabled(true);
        updateProgress(100, exitCode == 0 ? "Lệnh hoàn tất" : "Lệnh lỗi");
        actionNote.setText(exitCode == 0 ? "COMMAND OK" : "COMMAND FAILED · exit " + exitCode);
        renderAction();
    }

    @Override
    public void onCommandFailure(String message) {
        commandRunning = false;
        zipButton.setEnabled(true);
        commandRunButton.setEnabled(true);
        actionNote.setText("COMMAND FAILED · " + message);
        appendVisibleLog("[CMD FAIL] " + message);
        renderAction();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String safeName(String value, String fallback) { return safe(value, fallback); }
    private static String safeVersion(String value) {
        String v = PatchManager.normalizeVersion(value);
        return v.isEmpty() ? "?" : v;
    }
}
APKPRO_d2480b2ace546c2f
