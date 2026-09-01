package com.longdev.apkbuilder.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ZipUtils {
    private ZipUtils() {}

    public interface ProgressCallback {
        void onProgress(long bytesRead, long totalBytes);
    }

    public static void unzip(InputStream input, File destination) throws IOException {
        unzip(input, destination, -1L, null);
    }

    public static void unzip(InputStream input, File destination, long totalBytes, ProgressCallback callback) throws IOException {
        String root = destination.getCanonicalPath() + File.separator;
        InputStream tracked = callback == null
                ? input
                : new CountingInputStream(input, totalBytes, callback);
        try (ZipInputStream zip = new ZipInputStream(tracked)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(destination, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(root)) {
                    throw new IOException("ZIP chứa đường dẫn không an toàn: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) {
                        throw new IOException("Không tạo được: " + out);
                    }
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Không tạo được: " + parent);
                    }
                    try (FileOutputStream file = new FileOutputStream(out)) {
                        IoUtils.copy(zip, file);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final long total;
        private final ProgressCallback callback;
        private long count;
        private long lastReported;

        private CountingInputStream(InputStream input, long total, ProgressCallback callback) {
            super(input);
            this.total = total;
            this.callback = callback;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) report(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) report(read);
            return read;
        }

        private void report(int read) {
            count += read;
            // Throttle callback to roughly every 2 MiB, plus final chunk.
            if (count - lastReported >= 2L * 1024L * 1024L || (total > 0 && count >= total)) {
                lastReported = count;
                callback.onProgress(count, total);
            }
        }
    }
}
