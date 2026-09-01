package com.longdev.apkbuilder.core;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

public final class ApkInstaller {
    private static final String PREFS = "apk_installer_state";
    private static final String KEY_PENDING_URI = "pending_apk_uri";

    private ApkInstaller() {}

    /**
     * Gọi ngay sau khi APK đã được lưu vào MediaStore/Download.
     * Nếu quyền "cài ứng dụng không rõ nguồn gốc" chưa bật, mở đúng trang quyền của APK PRO.
     * URI được giữ lại để khi người dùng quay về app, trình cài đặt APK tự mở tiếp.
     *
     * @return thông báo ngắn để ghi vào live log; lỗi mở installer không làm build bị đánh fail.
     */
    public static String requestInstall(Context context, Uri apkUri) {
        if (context == null || apkUri == null) return "Không có URI APK để mở trình cài đặt";
        rememberPending(context, apkUri);

        if (!canRequestPackageInstalls(context)) {
            try {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()));
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(settings);
                return "Cần bật 'Cho phép từ nguồn này'; quay lại APK PRO sẽ tự mở cài đặt APK";
            } catch (ActivityNotFoundException noSettings) {
                return "Không mở được trang cho phép cài APK: " + safeMessage(noSettings);
            } catch (Throwable error) {
                return "Không mở được trang cho phép cài APK: " + safeMessage(error);
            }
        }

        return launchInstaller(context, apkUri, true);
    }

    /** Gọi từ Activity.onResume() để tiếp tục sau màn hình cấp quyền nguồn không xác định. */
    public static boolean resumePendingInstall(Activity activity) {
        if (activity == null || !canRequestPackageInstalls(activity)) return false;
        String raw = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PENDING_URI, null);
        if (raw == null || raw.trim().isEmpty()) return false;
        Uri uri;
        try {
            uri = Uri.parse(raw);
        } catch (Throwable badUri) {
            clearPending(activity);
            return false;
        }
        String result = launchInstaller(activity, uri, false);
        return result.startsWith("Đã mở");
    }

    private static boolean canRequestPackageInstalls(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        try {
            return context.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String launchInstaller(Context context, Uri apkUri, boolean newTask) {
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (newTask || !(context instanceof Activity)) {
                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            clearPending(context);
            context.startActivity(install);
            return "Đã mở trình cài đặt APK";
        } catch (ActivityNotFoundException noInstaller) {
            rememberPending(context, apkUri);
            return "Không tìm thấy trình cài đặt APK: " + safeMessage(noInstaller);
        } catch (Throwable error) {
            rememberPending(context, apkUri);
            return "Không tự mở được trình cài đặt; chạm thông báo BUILD SUCCESS để thử lại: " + safeMessage(error);
        }
    }

    private static void rememberPending(Context context, Uri uri) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PENDING_URI, uri.toString()).apply();
    }

    private static void clearPending(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING_URI).apply();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error == null ? "unknown" : error.getClass().getSimpleName();
        }
        return message.trim();
    }
}
