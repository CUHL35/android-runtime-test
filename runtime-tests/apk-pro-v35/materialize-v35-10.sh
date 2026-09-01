#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:?materialize root required}"
mkdir -p "$ROOT"
cat >> "$ROOT/app/src/main/java/com/longdev/apkbuilder/core/AndroidSdkRepository.java" <<'APKPRO_7349d47b5e829b73'
            Files.deleteIfExists(part.toPath());
        }
    }

    private static void writeUnsupportedLauncher(File file, String tool) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Không tạo được " + parent);
        String script = "#!/system/bin/sh\n"
                + "echo 'APK PRO: " + tool + " không có Android ARM64 implementation trong Core v2' >&2\n"
                + "exit 127\n";
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        file.setExecutable(true, false);
    }

    private static PackageInfo findPackage(File xml, String wanted, String requiredHostOs) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Throwable ignored) { }
            Document document = factory.newDocumentBuilder().parse(xml);
            NodeList packages = document.getElementsByTagNameNS("*", "remotePackage");
            for (int i = 0; i < packages.getLength(); i++) {
                Element remote = (Element) packages.item(i);
                if (!wanted.equals(remote.getAttribute("path"))) continue;
                String channel = childRef(remote, "channelRef");
                if (!channel.isEmpty() && !"channel-0".equals(channel)) continue;

                NodeList archives = remote.getElementsByTagNameNS("*", "archive");
                for (int a = 0; a < archives.getLength(); a++) {
                    Element archive = (Element) archives.item(a);
                    String hostOs = directChildText(archive, "host-os");
                    if (requiredHostOs != null && !requiredHostOs.equalsIgnoreCase(hostOs)) continue;
                    if (requiredHostOs == null && !hostOs.isEmpty()) continue;

                    NodeList complete = archive.getElementsByTagNameNS("*", "complete");
                    for (int j = 0; j < complete.getLength(); j++) {
                        Element c = (Element) complete.item(j);
                        String url = childText(c, "url");
                        NodeList checksums = c.getElementsByTagNameNS("*", "checksum");
                        if (url.isEmpty() || checksums.getLength() == 0) continue;
                        Element checksum = (Element) checksums.item(0);
                        String type = checksum.getAttribute("type").trim().toUpperCase(Locale.US);
                        String algorithm = "SHA-1";
                        if (type.equals("SHA256") || type.equals("SHA-256")) algorithm = "SHA-256";
                        else if (!type.equals("SHA1") && !type.equals("SHA-1")) continue;
                        return new PackageInfo(url, algorithm, checksum.getTextContent().trim());
                    }
                }
            }
            return null;
        } catch (Exception e) {
            throw new IOException("Không đọc được Android repository metadata: " + e.getMessage(), e);
        }
    }

    private static String directChildText(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) continue;
            Element element = (Element) node;
            String name = element.getLocalName();
            if (localName.equals(name)) {
                String value = element.getTextContent();
                return value == null ? "" : value.trim();
            }
        }
        return "";
    }

    private static String childText(Element parent, String localName) {
        NodeList list = parent.getElementsByTagNameNS("*", localName);
        if (list.getLength() == 0) return "";
        String value = list.item(0).getTextContent();
        return value == null ? "" : value.trim();
    }

    private static String childRef(Element parent, String localName) {
        NodeList list = parent.getElementsByTagNameNS("*", localName);
        if (list.getLength() == 0) return "";
        Node node = list.item(0);
        if (!(node instanceof Element)) return "";
        return ((Element) node).getAttribute("ref").trim();
    }

    private static File findBuildToolsRoot(File file, int depth) {
        if (file == null || depth > 5) return null;
        if (file.isDirectory()
                && new File(file, "lib/apksigner.jar").isFile()
                && new File(file, "core-lambda-stubs.jar").isFile()) return file;
        File[] children = file.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            File found = findBuildToolsRoot(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static File findAndroidJarParent(File file, int depth) {
        if (file == null || depth > 4) return null;
        if (file.isDirectory() && new File(file, "android.jar").isFile()) return file;
        File[] children = file.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            File found = findAndroidJarParent(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static void moveTree(File source, File destination) throws IOException {
        if (source.renameTo(destination)) return;
        copyTree(source.toPath(), destination.toPath());
        IoUtils.deleteRecursively(source);
    }

    static void copyTree(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                Path target = destination.resolve(relative);
                Files.createDirectories(target);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                Path target = destination.resolve(relative);
                Files.createDirectories(target.getParent());
                if (Files.isSymbolicLink(file)) {
                    Files.deleteIfExists(target);
                    Files.createSymbolicLink(target, Files.readSymbolicLink(file));
                } else {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    File src = file.toFile();
                    File dst = target.toFile();
                    if (src.canExecute()) dst.setExecutable(true, false);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static void linkOrCopy(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Không tạo được " + parent);
        try {
            Files.deleteIfExists(destination.toPath());
            Files.createSymbolicLink(destination.toPath(), source.toPath());
        } catch (Throwable noSymlink) {
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (source.canExecute()) destination.setExecutable(true, false);
        }
    }

    private static final class PackageInfo {
        final String url;
        final String algorithm;
        final String checksum;
        PackageInfo(String url, String algorithm, String checksum) {
            this.url = url;
            this.algorithm = algorithm;
            this.checksum = checksum;
        }
    }
}
APKPRO_7349d47b5e829b73
mkdir -p "$(dirname "$ROOT/app/src/main/java/com/longdev/apkbuilder/core/ApkInstaller.java")"
cat > "$ROOT/app/src/main/java/com/longdev/apkbuilder/core/ApkInstaller.java" <<'APKPRO_602df1a132a10f60'
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
APKPRO_602df1a132a10f60
