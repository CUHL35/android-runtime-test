package com.longdev.apkbuilder.core;

import java.io.File;

public final class ProjectLocator {
    private static final int MAX_DEPTH = 12;

    private ProjectLocator() {}

    public static File findProjectRoot(File root) {
        return find(root, 0);
    }

    private static File find(File dir, int depth) {
        if (dir == null || depth > MAX_DEPTH || !dir.isDirectory()) return null;

        if (isGradleProject(dir)) {
            return dir;
        }

        File[] children = dir.listFiles(File::isDirectory);
        if (children == null) return null;

        for (File child : children) {
            String name = child.getName();
            if ("build".equals(name) || ".gradle".equals(name) || ".git".equals(name)) {
                continue;
            }
            File found = find(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isGradleProject(File dir) {
        return new File(dir, "gradlew").isFile()
                || new File(dir, "settings.gradle").isFile()
                || new File(dir, "settings.gradle.kts").isFile();
    }
}
