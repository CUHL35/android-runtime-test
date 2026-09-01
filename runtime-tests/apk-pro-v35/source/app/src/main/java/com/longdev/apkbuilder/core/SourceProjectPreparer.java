package com.longdev.apkbuilder.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Extracts a selected ZIP and resolves the contained Gradle project.
 * Also accepts a FULL-PACKAGE ZIP that contains a nested FULL-SOURCE ZIP.
 */
public final class SourceProjectPreparer {
    private static final int MAX_NESTED_ZIPS = 8;

    private SourceProjectPreparer() {}

    public static File prepare(File sourceZip, File sessionRoot, BuildListener listener) throws IOException {
        File extracted = new File(sessionRoot, "source");
        if (!extracted.mkdirs()) {
            throw new IOException("Không tạo được source directory");
        }

        unzipFile(sourceZip, extracted);

        File project = ProjectLocator.findProjectRoot(extracted);
        if (project != null) {
            return project;
        }

        List<File> nested = findNestedZips(extracted);
        if (nested.isEmpty()) {
            return null;
        }

        nested.sort(Comparator.comparingInt(SourceProjectPreparer::priority));
        int count = Math.min(nested.size(), MAX_NESTED_ZIPS);
        for (int i = 0; i < count; i++) {
            File nestedZip = nested.get(i);
            listener.onLog("ZIP ngoài không có Gradle; thử ZIP bên trong: " + nestedZip.getName());

            File nestedRoot = new File(sessionRoot, "nested-source-" + i);
            if (!nestedRoot.mkdirs()) {
                throw new IOException("Không tạo được nested source directory");
            }

            try {
                unzipFile(nestedZip, nestedRoot);
            } catch (IOException badZip) {
                IoUtils.deleteRecursively(nestedRoot);
                continue;
            }

            project = ProjectLocator.findProjectRoot(nestedRoot);
            if (project != null) {
                return project;
            }
        }
        return null;
    }

    private static void unzipFile(File zipFile, File destination) throws IOException {
        try (InputStream input = new FileInputStream(zipFile)) {
            ZipUtils.unzip(input, destination);
        }
    }

    private static List<File> findNestedZips(File root) {
        List<File> result = new ArrayList<>();
        collectZips(root, result, 0);
        return result;
    }

    private static void collectZips(File dir, List<File> result, int depth) {
        if (dir == null || depth > 5 || result.size() >= MAX_NESTED_ZIPS * 2) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                collectZips(file, result, depth + 1);
            } else if (file.getName().toLowerCase().endsWith(".zip")) {
                result.add(file);
            }
        }
    }

    private static int priority(File file) {
        String name = file.getName().toLowerCase();
        if (name.contains("full-source")) return 0;
        if (name.contains("source")) return 1;
        return 2;
    }
}
