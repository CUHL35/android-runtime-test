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
import com.longdev.apkbuilder.core.ToolchainManager;

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
    private static final String PREFS = "apkpro_ui";
    private static final String PREF_TOOLCHAIN_PROMPT_SHOWN = "toolchain_prompt_shown_v33";

    private static final String RELEASE_SOURCE_PROMPT =
            "PROMPT ĐỂ XUẤT RELEASE\n\n"
            + "Dùng FULL SOURCE mới nhất làm baseline và đọc toàn bộ project trước khi sửa/build. "
            + "Giữ nguyên package/applicationId, signing key/certificate và các chức năng không liên quan. "
            + "Tự xác định compileSdk, targetSdk, minSdk, AGP, Gradle wrapper, buildTools và JDK mà project đang yêu cầu; "
            + "không tự hạ cấp project, không ép project cũ lên version mới nếu cấu hình đã pin. "
            + "Nếu thiếu SDK/Gradle thì lấy đúng version từ upstream chính thức; JDK/native Android ARM64 phải dùng core/Toolchain Pack đã xác minh, không đổi project chỉ để khớp máy build. "
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
            + "không hạ cấp project, không tự đổi API để né lỗi build. Nếu project cần SDK/Gradle/API khác thì giữ nguyên yêu cầu đó; JDK/native Android ARM64 phải có trong Toolchain Pack tương thích, không hạ project để né. "
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
    private boolean toolchainSetupRunning;
    private Thread toolchainSetupThread;

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

        maybeShowFirstToolchainPrompt();
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
