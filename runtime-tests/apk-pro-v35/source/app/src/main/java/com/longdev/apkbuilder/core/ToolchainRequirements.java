package com.longdev.apkbuilder.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads project-declared toolchain requirements without changing the project. */
public final class ToolchainRequirements {
    private static final long MAX_BUILD_FILE_BYTES = 2L * 1024L * 1024L;
    private static final Pattern COMPILE_SDK = Pattern.compile(
            "(?m)\\bcompileSdk(?:Version)?\\s*(?:=\\s*)?\\(?\\s*[\\\"']?([0-9]{2,3})");
    private static final Pattern BUILD_TOOLS = Pattern.compile(
            "(?m)\\bbuildToolsVersion\\s*(?:=\\s*)?[\\\"']([0-9]+(?:\\.[0-9]+){1,2})[\\\"']");
    private static final Pattern WRAPPER_VERSION = Pattern.compile(
            "gradle-([0-9]+(?:\\.[0-9]+){1,3})-(?:bin|all)\\.zip");
    private static final Pattern JAVA_TOOLCHAIN_1 = Pattern.compile("JavaLanguageVersion\\.of\\(\\s*([0-9]{1,2})\\s*\\)");
    private static final Pattern JAVA_TOOLCHAIN_2 = Pattern.compile("\\bjvmToolchain\\(\\s*([0-9]{1,2})\\s*\\)");
    private static final Pattern JAVA_TOOLCHAIN_3 = Pattern.compile("JavaVersion\\.VERSION_([0-9]{1,2})");
    private static final Pattern JAVA_TOOLCHAIN_4 = Pattern.compile("\\blanguageVersion\\s*(?:=|\\.set\\()\\s*(?:JavaLanguageVersion\\.of\\()?\\s*([0-9]{1,2})");

    public final int compileSdk;
    public final String buildToolsVersion;
    public final String gradleVersion;
    public final int requestedJavaMajor;

    private ToolchainRequirements(int compileSdk, String buildToolsVersion,
                                  String gradleVersion, int requestedJavaMajor) {
        this.compileSdk = compileSdk;
        this.buildToolsVersion = buildToolsVersion == null ? "" : buildToolsVersion;
        if (gradleVersion == null || gradleVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("Gradle wrapper version is required");
        }
        this.gradleVersion = gradleVersion.trim();
        this.requestedJavaMajor = Math.max(17, requestedJavaMajor);
    }

    public static ToolchainRequirements inspect(File project) throws IOException {
        if (project == null || !project.isDirectory()) throw new IOException("Project không tồn tại");

        List<File> buildFiles = new ArrayList<>();
        collectBuildFiles(project, buildFiles, 0);
        int compileSdk = 0;
        String buildTools = "";
        int javaMajor = 17;

        for (File file : buildFiles) {
            if (file.length() <= 0 || file.length() > MAX_BUILD_FILE_BYTES) continue;
            String text = readText(file);
            if (compileSdk == 0) {
                Matcher m = COMPILE_SDK.matcher(text);
                if (m.find()) compileSdk = parseInt(m.group(1), 0);
            }
            if (buildTools.isEmpty()) {
                Matcher m = BUILD_TOOLS.matcher(text);
                if (m.find()) buildTools = m.group(1);
            }
            javaMajor = Math.max(javaMajor, highestJava(text));
        }

        if (compileSdk <= 0) {
            throw new IOException("Không đọc được compileSdk/compileSdkVersion từ project; không tự đoán SDK");
        }

        String gradle = readGradleWrapperVersion(project);
        if (gradle.isEmpty()) {
            throw new IOException("Không đọc được Gradle wrapper version; APK PRO không tự đoán/nâng Gradle");
        }
        return new ToolchainRequirements(compileSdk, buildTools, gradle, javaMajor);
    }

    public String effectiveBuildToolsVersion() {
        // APK PRO cannot execute Google's Linux/x86_64 host binaries directly on Android ARM64.
        // Expose the pinned façade revision backed by APK PRO ARM64 Core v2.
        // run-build.sh forces Android modules to use this façade without changing source.
        return "36.0.0";
    }

    public int jdkPackageMajor() {
        if (requestedJavaMajor <= 17) return 17;
        if (requestedJavaMajor <= 21) return 21;
        if (requestedJavaMajor <= 25) return 25;
        return requestedJavaMajor;
    }

    private static int highestJava(String text) {
        int value = 17;
        value = Math.max(value, highest(text, JAVA_TOOLCHAIN_1));
        value = Math.max(value, highest(text, JAVA_TOOLCHAIN_2));
        value = Math.max(value, highest(text, JAVA_TOOLCHAIN_3));
        value = Math.max(value, highest(text, JAVA_TOOLCHAIN_4));
        return value;
    }

    private static int highest(String text, Pattern pattern) {
        int value = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) value = Math.max(value, parseInt(matcher.group(1), 0));
        return value;
    }

    private static String readGradleWrapperVersion(File project) throws IOException {
        File wrapper = findFile(project, "gradle-wrapper.properties", 0, 7);
        if (wrapper == null) return "";
        try (BufferedReader reader = new BufferedReader(new FileReader(wrapper))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0 || !line.substring(0, eq).trim().equals("distributionUrl")) continue;
                String url = line.substring(eq + 1).trim().replace("\\:", ":").replace("\\=", "=");
                Matcher matcher = WRAPPER_VERSION.matcher(url);
                return matcher.find() ? matcher.group(1) : "";
            }
        }
        return "";
    }

    private static void collectBuildFiles(File dir, List<File> out, int depth) {
        if (dir == null || depth > 7 || out.size() > 160) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                String n = child.getName();
                if ("build".equals(n) || ".gradle".equals(n) || ".git".equals(n)) continue;
                collectBuildFiles(child, out, depth + 1);
            } else {
                String n = child.getName().toLowerCase(Locale.US);
                if (n.equals("build.gradle") || n.equals("build.gradle.kts") || n.equals("gradle.properties")) {
                    out.add(child);
                }
            }
        }
    }

    private static File findFile(File dir, String name, int depth, int maxDepth) {
        if (dir == null || depth > maxDepth) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) if (f.isFile() && name.equals(f.getName())) return f;
        for (File f : files) {
            if (!f.isDirectory()) continue;
            String n = f.getName();
            if ("build".equals(n) || ".gradle".equals(n) || ".git".equals(n)) continue;
            File found = findFile(f, name, depth + 1, maxDepth);
            if (found != null) return found;
        }
        return null;
    }

    private static String readText(File file) throws IOException {
        StringBuilder out = new StringBuilder((int)Math.min(file.length(), 128 * 1024));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) >= 0) out.append(buffer, 0, count);
        }
        return out.toString();
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    @Override public String toString() {
        return "compileSdk=" + compileSdk
                + ", buildTools=" + effectiveBuildToolsVersion()
                + ", Gradle=" + gradleVersion
                + ", Java=" + requestedJavaMajor;
    }
}
