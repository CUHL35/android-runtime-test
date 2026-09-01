#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:?materialize root required}"
mkdir -p "$ROOT"
mkdir -p "$(dirname "$ROOT/app/src/main/java/com/longdev/apkbuilder/MainActivity.java")"
cat > "$ROOT/app/src/main/java/com/longdev/apkbuilder/MainActivity.java" <<'APKPRO_ac1d646a66_0'
package com.longdev.apkbuilder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.longdev.apkbuilder.core.ApkInstaller;
import com.longdev.apkbuilder.core.BaselineStore;
import com.longdev.apkbuilder.core.BuildListener;
import com.longdev.apkbuilder.core.BuildMode;
import com.longdev.apkbuilder.core.BuildService;
import com.longdev.apkbuilder.core.BuildStateStore;
import com.longdev.apkbuilder.core.CommandCoordinator;
import com.longdev.apkbuilder.core.CommandListener;
import com.longdev.apkbuilder.core.DiagnosticsRunner;
import com.longdev.apkbuilder.core.PatchManager;
import com.longdev.apkbuilder.core.RecoveryManager;
import com.longdev.apkbuilder.core.SigningData;
import com.longdev.apkbuilder.core.SigningInfoParser;
import com.longdev.apkbuilder.core.SourceInspector;
import com.longdev.apkbuilder.core.ToolchainPackManager;

public final class MainActivity extends Activity implements BuildListener, CommandListener {
    private static final int REQUEST_ZIP = 1001;
    private static final int REQUEST_APK = 1002;
    private static final int REQUEST_EXTERNAL_SIGNING = 1003;
    private static final int REQUEST_KEY_AFTER_INFO = 1004;
    private static final int REQUEST_INFO_AFTER_KEY = 1005;
    private static final int REQUEST_PATCH_BASELINE = 1006;
    private static final int REQUEST_TOOLCHAIN_PACK = 1007;

    private static final int MAX_VISIBLE_LOG_CHARS = 60000;
    private static final int KEEP_VISIBLE_LOG_CHARS = 45000;
    private static final String STATE_SELECTED = "selectedZipUri";
    private static final String STATE_BASELINE = "baselineUri";

    private static final String RELEASE_SOURCE_PROMPT =
            "PROMPT ĐỂ XUẤT RELEASE\n\n"
            + "Dùng FULL SOURCE mới nhất làm baseline và đọc toàn bộ project trước khi sửa/build. "
            + "Giữ nguyên package/applicationId, signing key/certificate và các chức năng không liên quan. "
            + "Tự xác định compileSdk, targetSdk, minSdk, AGP, Gradle wrapper, buildTools và JDK mà project đang yêu cầu; "
            + "không tự hạ cấp project, không ép project cũ lên version mới nếu cấu hình đã pin. "
            + "Nếu thiếu toolchain thì JDK/native Android ARM64 lấy từ Termux signed upstream và giữ cache app-private; SDK/apksigner lấy Google chính thức, Gradle lấy Gradle chính thức, dependency theo repository của project; không đổi project chỉ để khớp máy build. "
            + "Phải kiểm tương thích runtime theo minSdk/targetSdk và test tối thiểu Android 11/API 30 + Android 12/API 31 khi app hỗ trợ; "
            + "với API khác phải chọn image/API nằm trong khoảng app hỗ trợ và không tuyên bố PASS nếu chưa chạy thật. "
            + "Release phải dùng đúng key lịch sử nếu là bản update; thiếu key cũ thì dừng, không tạo key mới. "
            + "Xuất FULL SOURCE ZIP sạch + APK Release đã ký/verify; ZIP không chứa build/, .gradle/, cache hay APK cũ. "
            + "Báo rõ versionName/versionCode, SHA-256, signing cert và kết quả build/runtime.";

    private static final String PATCH_SOURCE_PROMPT =
            "PROMPT ĐỂ XUẤT PATCH\n\n"
            + "Tạo PATCH ZIP v2 từ đúng FULL SOURCE baseline mới nhất. Bắt buộc có PATCH-MANIFEST.txt hoặc patch-manifest.json "
            + "với Package/applicationId, Base-SHA256, Base-Version, Target-Version; mọi file overlay phải có SHA-256, file xóa phải khai báo Delete/deletes. "
            + "Chỉ đưa file thật sự thay đổi vào files/ đúng đường dẫn project; không nhét JKS/key/password vào patch. "
            + "Trước khi tạo patch phải đọc compileSdk, targetSdk, minSdk, AGP, Gradle wrapper, buildTools và JDK của baseline/target; "
            + "không hạ cấp project, không tự đổi API để né lỗi build. Nếu project cần SDK/Gradle/API khác thì giữ nguyên yêu cầu đó; JDK/native Android ARM64 được provision từ Termux signed upstream và giữ cache; Toolchain Pack chỉ là backup tùy chọn, không hạ project để né. "
            + "Patch phải áp được trên bản temp, sai package/version/Base-SHA256/file hash phải dừng. "
            + "Sau khi apply phải build/test theo cấu hình project: có key hợp lệ trong FULL SOURCE thì Release + verify chữ ký; không có key thì Debug; "
            + "nếu có key nhưng signing info lỗi/không đồng nhất thì FAIL chứ không tự tụt xuống Debug. "
            + "Khi app hỗ trợ phải kiểm Android 11/API 30 + Android 12/API 31 và các API khác nằm trong minSdk..target/runtime support; không báo PASS nếu chưa chạy thật.";

    private enum NoKeyMode { DEBUG, RELEASE_EXTERNAL, RELEASE_NEW }

    private Uri selectedZipUri;
    private Uri sourceUri;
    private Uri patchUri;
    private Uri baselineUri;
    private SourceInspector.Result selectedResult;
    private PatchManager.PatchInfo patchInfo;
    private boolean baselineVerified;
    private String baselineStatus = "";
    private NoKeyMode noKeyMode = NoKeyMode.DEBUG;
    private BuildMode sourceBuildMode = BuildMode.DEBUG;
    private SigningData externalSigning;
    private String externalSigningName = "";
    private Uri pendingExternalKeyUri;
    private SigningInfoParser.Info pendingSigningInfo;
    private boolean commandRunning;
    private int currentProgress;
    private boolean baselineLookupRunning;
    private boolean diagnosticsRunning;

    private EditText commandInput;
    private ImageButton commandRunButton;
    private ImageButton menuButton;
    private Button saveLogButton;
    private Button zipButton;
    private TextView zipMeta;
    private TextView zipBadge;
    private TextView verifyBadge;
    private Button actionDisabled;
    private Button actionSingle;
    private LinearLayout actionSplit;
    private Button actionMain;
    private ImageButton actionArrow;
    private LinearLayout progressContainer;
    private ProgressBar progress;
    private TextView progressPercent;
    private TextView progressStage;
    private TextView actionNote;
    private TextView log;
    private ScrollView logScroll;

    private CommandCoordinator commandCoordinator;
    private SourceInspector sourceInspector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        commandInput = findViewById(R.id.inputCommand);
        commandRunButton = findViewById(R.id.buttonRunCommand);
        menuButton = findViewById(R.id.buttonMenu);
        saveLogButton = findViewById(R.id.buttonSaveLog);
        zipButton = findViewById(R.id.buttonZip);
        zipMeta = findViewById(R.id.textZipMeta);
        zipBadge = findViewById(R.id.textZipBadge);
        verifyBadge = findViewById(R.id.textVerifyBadge);
        actionDisabled = findViewById(R.id.buttonActionDisabled);
        actionSingle = findViewById(R.id.buttonActionSingle);
        actionSplit = findViewById(R.id.actionSplit);
        actionMain = findViewById(R.id.buttonActionMain);
        actionArrow = findViewById(R.id.buttonActionArrow);
        progressContainer = findViewById(R.id.progressContainer);
        progress = findViewById(R.id.progress);
        progressPercent = findViewById(R.id.textProgressPercent);
        progressStage = findViewById(R.id.textProgressStage);
        actionNote = findViewById(R.id.textActionNote);
        log = findViewById(R.id.textLog);
        logScroll = findViewById(R.id.logScroll);

        commandCoordinator = new CommandCoordinator(this);
        sourceInspector = new SourceInspector(this);

        menuButton.setOnClickListener(v -> showMainMenu());
        saveLogButton.setOnClickListener(v -> saveCurrentLog());
        zipButton.setOnClickListener(v -> chooseFile(REQUEST_ZIP, "application/zip"));
        commandRunButton.setOnClickListener(v -> runCommand());
        actionSingle.setOnClickListener(v -> runPrimaryAction());
        actionMain.setOnClickListener(v -> runPrimaryAction());
        actionArrow.setOnClickListener(v -> showActionMenu());

        renderIdleAction();

        if (savedInstanceState != null) {
            String selected = savedInstanceState.getString(STATE_SELECTED);
            String baseline = savedInstanceState.getString(STATE_BASELINE);
            if (baseline != null && !baseline.trim().isEmpty()) baselineUri = Uri.parse(baseline);
            if (selected != null && !selected.trim().isEmpty()) {
                selectedZipUri = Uri.parse(selected);
                inspectSelectedZip();
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2201); } catch (Throwable ignored) { }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ApkInstaller.resumePendingInstall(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        clearVisibleLog();
        BuildStateStore.register(this);
    }

    @Override
    protected void onStop() {
        BuildStateStore.unregister(this);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (selectedZipUri != null) outState.putString(STATE_SELECTED, selectedZipUri.toString());
        if (baselineUri != null) outState.putString(STATE_BASELINE, baselineUri.toString());
    }

    private void chooseFile(int requestCode, String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.setType(mime);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQUEST_EXTERNAL_SIGNING || requestCode == REQUEST_KEY_AFTER_INFO || requestCode == REQUEST_INFO_AFTER_KEY) {
                noKeyMode = NoKeyMode.DEBUG;
                sourceBuildMode = BuildMode.DEBUG;
                externalSigning = null;
                externalSigningName = "";
                pendingExternalKeyUri = null;
                pendingSigningInfo = null;
                renderAction();
            }
            return;
        }

        Uri uri = data.getData();
        persistReadPermission(uri, data);

        if (requestCode == REQUEST_ZIP) {
            selectedZipUri = uri;
            inspectSelectedZip();
        } else if (requestCode == REQUEST_APK) {
            openSelectedApk(uri);
        } else if (requestCode == REQUEST_EXTERNAL_SIGNING) {
            handleExternalSigningSelection(uri);
        } else if (requestCode == REQUEST_KEY_AFTER_INFO) {
            handleKeyAfterInfo(uri);
        } else if (requestCode == REQUEST_INFO_AFTER_KEY) {
            handleInfoAfterKey(uri);
        } else if (requestCode == REQUEST_PATCH_BASELINE) {
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
APKPRO_ac1d646a66_0
