package com.longdev.apkbuilder.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.longdev.apkbuilder.MainActivity;

public final class BuildService extends Service implements BuildListener {
    public static final String ACTION_BUILD = "com.apkbld.action.BUILD";
    public static final String ACTION_CANCEL = "com.apkbld.action.CANCEL_BUILD";
    public static final String EXTRA_SOURCE_URI = "sourceUri";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_PATCH_URI = "patchUri";
    public static final String EXTRA_KEY_URI = "keyUri";
    public static final String EXTRA_KEY_SOURCE = "keySource";
    public static final String EXTRA_STORE_PASS = "storePass";
    public static final String EXTRA_KEY_ALIAS = "keyAlias";
    public static final String EXTRA_KEY_PASS = "keyPass";

    private static final String CHANNEL_ID = "apk_builder_build_v22"; // giữ channel để không tạo trùng notification channel
    private static final int NOTIFICATION_ID = 1501;
    private static final long WAKELOCK_TIMEOUT_MS = 3L * 60L * 60L * 1000L;

    private BuildCoordinator coordinator;
    private PowerManager.WakeLock wakeLock;
    private boolean running;
    private boolean cancelling;
    private int lastNotificationProgress = -1;
    private BuildMode currentMode;

    @Override
    public void onCreate() {
        super.onCreate();
        coordinator = new BuildCoordinator(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            if (running && !cancelling) {
                cancelling = true;
                BuildStateStore.log("Yêu cầu DỪNG / HỦY từ người dùng...");
                updateNotification(Math.max(0, lastNotificationProgress), "Đang hủy build...");
                coordinator.cancel();
            }
            return START_NOT_STICKY;
        }

        if (intent == null || !ACTION_BUILD.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (running || BuildStateStore.isRunning()) return START_NOT_STICKY;

        String source = intent.getStringExtra(EXTRA_SOURCE_URI);
        String modeName = intent.getStringExtra(EXTRA_MODE);
        if (source == null || modeName == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        BuildMode mode;
        try {
            mode = BuildMode.valueOf(modeName);
        } catch (IllegalArgumentException badMode) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        SigningData signing = null;
        if (mode == BuildMode.RELEASE) {
            String keySource = safeExtra(intent, EXTRA_KEY_SOURCE);
            String storePass = safeExtra(intent, EXTRA_STORE_PASS);
            String alias = safeExtra(intent, EXTRA_KEY_ALIAS);
            String keyPass = safeExtra(intent, EXTRA_KEY_PASS);
            if (SigningData.KeySource.EMBEDDED_AUTO.name().equals(keySource)) {
                signing = SigningData.embeddedAuto(storePass, alias, keyPass);
            } else if (SigningData.KeySource.GENERATE_NEW.name().equals(keySource)) {
                signing = SigningData.generateNew();
            } else {
                String key = intent.getStringExtra(EXTRA_KEY_URI);
                if (key != null) {
                    Uri keyUri = Uri.parse(key);
                    signing = SigningData.KeySource.EXTERNAL_BUNDLE.name().equals(keySource)
                            ? SigningData.externalBundle(keyUri)
                            : SigningData.external(keyUri, storePass, alias, keyPass);
                }
            }
        }

        currentMode = mode;
        running = true;
        cancelling = false;
        lastNotificationProgress = 0;
        startForeground(NOTIFICATION_ID, buildNotification(0, "Bắt đầu build...", true, true));
        acquireWakeLock();
        String patch = intent.getStringExtra(EXTRA_PATCH_URI);
        coordinator.build(Uri.parse(source), patch == null ? null : Uri.parse(patch), mode, signing, this);
        return START_NOT_STICKY;
    }

    private static String safeExtra(Intent intent, String name) {
        String value = intent.getStringExtra(name);
        return value == null ? "" : value;
    }

    @Override
    public void onStarted() {
        BuildStateStore.started();
        updateNotification(0, "Đang build...");
    }

    @Override
    public void onLog(String line) {
        BuildStateStore.log(line);
    }

    @Override
    public void onProgress(int percent, String stage) {
        int safe = Math.max(lastNotificationProgress, Math.max(0, Math.min(100, percent)));
        BuildStateStore.progress(safe, stage);
        if (safe != lastNotificationProgress || stage != null) {
            lastNotificationProgress = safe;
            updateNotification(safe, stage == null ? "Đang build..." : stage);
        }
    }

    @Override
    public void onSuccess(String outputName) {
        BuildStateStore.success(outputName);
        if (currentMode == BuildMode.UPDATE) {
            finishService(100, "UPDATE SUCCESS — APK: Download/" + outputName + " · FULL SOURCE cũng đã xuất");
        } else {
            finishService(100, "BUILD SUCCESS — chạm để cài Download/" + outputName);
        }
    }

    @Override
    public void onFailure(String message) {
        BuildStateStore.failure(message);
        finishService(Math.max(0, lastNotificationProgress), "BUILD FAILED — " + message);
    }

    @Override
    public void onCancelled() {
        BuildStateStore.cancelled();
        finishService(Math.max(0, lastNotificationProgress), "BUILD ĐÃ HỦY");
    }

    private void finishService(int percent, String text) {
        running = false;
        cancelling = false;
        releaseWakeLock();
        coordinator.shutdown();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        stopForeground(true);
        if (manager != null) {
            // Final notification is not silent: user gets a visible completion/failure/cancel alert.
            manager.notify(NOTIFICATION_ID, buildNotification(percent, text, false, false));
        }
        stopSelf();
    }

    private void acquireWakeLock() {
        PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (manager == null) return;
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "APKBuilder:Build");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "APK PRO build",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Tiến trình build và kết quả APK PRO");
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(int percent, String text, boolean ongoing, boolean onlyAlertOnce) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, pendingFlags);

        Intent cancel = new Intent(this, BuildService.class);
        cancel.setAction(ACTION_CANCEL);
        PendingIntent cancelPending = PendingIntent.getService(this, 1502, cancel, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("APK PRO")
                .setContentText(text)
                .setContentIntent(pending)
                .setOnlyAlertOnce(onlyAlertOnce)
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
                .setProgress(100, Math.max(0, Math.min(100, percent)), ongoing && percent <= 0);
        if (ongoing) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "DỪNG / HỦY", cancelPending);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && !onlyAlertOnce) {
            builder.setDefaults(Notification.DEFAULT_ALL);
        }
        return builder.build();
    }

    private void updateNotification(int percent, String stage) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(percent, stage, true, true));
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        if (running) {
            running = false;
            if (cancelling) BuildStateStore.cancelled();
            else BuildStateStore.failure("Build service bị hệ thống dừng trước khi hoàn tất");
            if (coordinator != null) coordinator.cancel();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
