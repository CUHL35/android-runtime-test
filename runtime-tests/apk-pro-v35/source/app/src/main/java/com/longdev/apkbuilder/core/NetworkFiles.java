package com.longdev.apkbuilder.core;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;

final class NetworkFiles {
    private static final int MAX_ATTEMPTS = 3;
    private NetworkFiles() {}

    static void download(String url, File destination, BuildListener listener, String label) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Không tạo được download cache: " + parent);
        }
        File part = new File(destination.getAbsolutePath() + ".part");
        IOException last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Đã hủy bởi người dùng");
            try {
                downloadAttempt(url, destination, part, listener, label);
                return;
            } catch (IOException error) {
                last = error;
                if (Thread.currentThread().isInterrupted()
                        || error.getMessage() != null && error.getMessage().contains("Đã hủy")) {
                    throw error;
                }
                if (attempt >= MAX_ATTEMPTS) break;
                if (listener != null) {
                    listener.onLog(label + " lỗi lần " + attempt + "/" + MAX_ATTEMPTS
                            + " — giữ file .part để resume: " + safeMessage(error));
                }
                try {
                    Thread.sleep(1200L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Đã hủy bởi người dùng", interrupted);
                }
            }
        }
        throw new IOException(label + " tải thất bại sau " + MAX_ATTEMPTS + " lần: "
                + safeMessage(last), last);
    }

    private static void downloadAttempt(
            String url, File destination, File part, BuildListener listener, String label) throws IOException {
        long resumeFrom = part.isFile() ? part.length() : 0L;
        HttpURLConnection connection = null;
        try {
            URL current = new URL(url);
            int responseCode = -1;
            for (int redirect = 0; redirect < 6; redirect++) {
                connection = (HttpURLConnection) current.openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(90000);
                connection.setRequestProperty("User-Agent", "APK-PRO/33 Android");
                if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
                connection.setInstanceFollowRedirects(false);
                responseCode = connection.getResponseCode();
                if (responseCode >= 300 && responseCode < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (location == null || location.trim().isEmpty()) {
                        throw new IOException("Redirect không có Location: HTTP " + responseCode);
                    }
                    current = new URL(current, location);
                    continue;
                }
                if (responseCode == 416 && resumeFrom > 0) {
                    connection.disconnect();
                    connection = null;
                    if (!part.delete()) throw new IOException("Server từ chối resume và không xóa được .part");
                    resumeFrom = 0L;
                    current = new URL(url);
                    continue;
                }
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException("HTTP " + responseCode + " khi tải " + url);
                }
                break;
            }
            if (connection == null) throw new IOException("Quá nhiều redirect: " + url);

            boolean append = resumeFrom > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL;
            if (append) validateContentRangeStart(connection, resumeFrom);
            if (resumeFrom > 0 && !append) {
                // Server ignored Range. Restart cleanly instead of appending corrupt bytes.
                resumeFrom = 0L;
                if (part.exists() && !part.delete()) throw new IOException("Không reset được file .part");
            }

            long responseLength = connection.getContentLengthLong();
            long expectedTotal = expectedTotal(connection, responseLength, resumeFrom, append);
            long copied = resumeFrom;
            long lastReport = copied;
            if (listener != null && append) {
                listener.onLog(label + " resume từ " + String.format(Locale.US, "%.0f MiB", resumeFrom / 1048576.0));
            }

            try (InputStream input = new BufferedInputStream(connection.getInputStream(), 64 * 1024);
                 FileOutputStream output = new FileOutputStream(part, append)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Đã hủy bởi người dùng");
                    if (count == 0) continue;
                    output.write(buffer, 0, count);
                    copied += count;
                    if (listener != null && copied - lastReport >= 8L * 1024L * 1024L) {
                        lastReport = copied;
                        String detail = expectedTotal > 0
                                ? String.format(Locale.US, "%s %.0f/%.0f MiB", label,
                                copied / 1048576.0, expectedTotal / 1048576.0)
                                : String.format(Locale.US, "%s %.0f MiB", label, copied / 1048576.0);
                        listener.onLog(detail);
                    }
                }
                output.getFD().sync();
            }

            if (expectedTotal > 0 && part.length() != expectedTotal) {
                throw new IOException("Download chưa đủ byte: expected=" + expectedTotal + " actual=" + part.length());
            }
            finalizeDownload(part, destination);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void validateContentRangeStart(HttpURLConnection connection, long expectedStart) throws IOException {
        String range = connection.getHeaderField("Content-Range");
        if (range == null || !range.startsWith("bytes ")) {
            throw new IOException("Server trả 206 nhưng thiếu Content-Range");
        }
        int dash = range.indexOf('-', 6);
        if (dash <= 6) throw new IOException("Content-Range không hợp lệ: " + range);
        try {
            long actualStart = Long.parseLong(range.substring(6, dash).trim());
            if (actualStart != expectedStart) {
                throw new IOException("Resume lệch offset: expected=" + expectedStart + " actual=" + actualStart);
            }
        } catch (NumberFormatException badRange) {
            throw new IOException("Content-Range không hợp lệ: " + range, badRange);
        }
    }

    private static long expectedTotal(
            HttpURLConnection connection, long responseLength, long resumeFrom, boolean append) {
        if (append) {
            String range = connection.getHeaderField("Content-Range");
            if (range != null) {
                int slash = range.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < range.length()) {
                    try { return Long.parseLong(range.substring(slash + 1).trim()); }
                    catch (NumberFormatException ignored) { }
                }
            }
            return responseLength > 0 ? resumeFrom + responseLength : -1L;
        }
        return responseLength > 0 ? responseLength : -1L;
    }

    private static void finalizeDownload(File part, File destination) throws IOException {
        if (!part.isFile() || part.length() <= 0) throw new IOException("File tải rỗng: " + part);
        Files.deleteIfExists(destination.toPath());
        try {
            Files.move(part.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value.trim();
    }

    static String digest(File file, String algorithm) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            try (InputStream input = new BufferedInputStream(new FileInputStream(file), 64 * 1024)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) md.update(buffer, 0, count);
            }
            StringBuilder out = new StringBuilder();
            for (byte b : md.digest()) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("Không có thuật toán hash: " + algorithm, e);
        }
    }

    static void verify(File file, String algorithm, String expected) throws IOException {
        if (expected == null || expected.trim().isEmpty()) throw new IOException("Metadata thiếu checksum");
        String actual = digest(file, algorithm);
        if (!actual.equalsIgnoreCase(expected.trim())) {
            file.delete();
            throw new IOException("Checksum sai: expected=" + expected + " actual=" + actual);
        }
    }
}
