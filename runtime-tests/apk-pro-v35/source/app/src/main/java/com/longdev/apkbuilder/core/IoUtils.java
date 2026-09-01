package com.longdev.apkbuilder.core;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

public final class IoUtils {
    private IoUtils() {}

    public static void copyUri(ContentResolver resolver, Uri uri, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Không tạo được thư mục: " + parent);
        }
        try (InputStream input = resolver.openInputStream(uri);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("Không mở được file đã chọn");
            }
            copy(input, output);
        }
    }

    public static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Đã hủy bởi người dùng");
            output.write(buffer, 0, read);
        }
    }

    public static void deleteRecursively(File file) {
        if (file == null) return;
        try {
            if (Files.isSymbolicLink(file.toPath())) {
                Files.deleteIfExists(file.toPath());
                return;
            }
        } catch (Throwable ignored) {
            // Fall back to java.io below; never intentionally follow a known symlink.
        }
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
