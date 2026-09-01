package com.longdev.apkbuilder.core;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Safe PATCH UPDATE support. A patch is always applied to the extracted temp project.
 * Export to Download happens only after BuildCoordinator has successfully validated the patched project.
 */
public final class PatchManager {
    private static final Pattern JSON_STRING = Pattern.compile("\\\"%s\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern JSON_NUMBER_OR_STRING = Pattern.compile("\\\"%s\\\"\\s*:\\s*(?:\\\"([^\\\"]*)\\\"|([0-9]+))");

    private PatchManager() { }

    public static final class PatchInfo {
        public final String packageName;
        public final String baseSha256;
        public final String baseVersion;
        public final String targetVersion;
        public final List<String> deletes;
        public final Map<String, String> fileSha256;
        public final boolean modernManifest;
        final File manifestFile;
        final File overlayRoot;

        PatchInfo(String packageName, String baseSha256, String baseVersion, String targetVersion,
                  List<String> deletes, Map<String, String> fileSha256, boolean modernManifest,
                  File manifestFile, File overlayRoot) {
            this.packageName = safe(packageName);
            this.baseSha256 = safe(baseSha256).toLowerCase(Locale.US);
            this.baseVersion = normalizeVersion(baseVersion);
            this.targetVersion = normalizeVersion(targetVersion);
            this.deletes = deletes;
            this.fileSha256 = fileSha256;
            this.modernManifest = modernManifest;
            this.manifestFile = manifestFile;
            this.overlayRoot = overlayRoot;
        }
    }

    public static final class AppliedPatch {
        public final PatchInfo info;
        public final String packageName;
        public final String sourceVersionBefore;
        public final String targetVersionAfter;

        AppliedPatch(PatchInfo info, String packageName, String sourceVersionBefore, String targetVersionAfter) {
            this.info = info;
            this.packageName = packageName;
            this.sourceVersionBefore = normalizeVersion(sourceVersionBefore);
            this.targetVersionAfter = normalizeVersion(targetVersionAfter);
        }
    }

    /** Copies and inspects a patch URI without touching a source project. */
    public static PatchInfo inspectUri(Context context, Uri patchUri, File workRoot) throws Exception {
        if (patchUri == null) throw new IOException("Thiếu PATCH ZIP");
        File patchZip = new File(workRoot, "inspect.patch.zip");
        IoUtils.copyUri(context.getContentResolver(), patchUri, patchZip);
        return inspectZip(patchZip, new File(workRoot, "inspect-patch"));
    }

    /** Inspects a local ZIP and returns null if no patch manifest exists. */
    public static PatchInfo tryInspectZip(File patchZip, File extracted) throws Exception {
        if (patchZip == null || !patchZip.isFile()) return null;
        IoUtils.deleteRecursively(extracted);
        if (!extracted.mkdirs() && !extracted.isDirectory()) throw new IOException("Không tạo được patch workspace");
        try (InputStream in = new FileInputStream(patchZip)) {
            ZipUtils.unzip(in, extracted);
        }
        File json = findFileIgnoreCase(extracted, "patch-manifest.json", 6);
        File txt = findFileIgnoreCase(extracted, "PATCH-MANIFEST.txt", 6);
        File manifest = json != null ? json : txt;
        if (manifest == null) return null;
        return parseManifest(manifest);
    }

    public static PatchInfo inspectZip(File patchZip, File extracted) throws Exception {
        PatchInfo info = tryInspectZip(patchZip, extracted);
        if (info == null) throw new IOException("PATCH thiếu patch-manifest.json hoặc PATCH-MANIFEST.txt");
        validateManifestIdentity(info);
        verifyPatchPayload(info);
        return info;
    }

    /**
     * Apply patch only to the already-extracted temp project. Does NOT export anything.
     */
    public static AppliedPatch apply(Context context, Uri patchUri, File sourceZip, File project,
                                     File session, BuildListener listener) throws Exception {
        File patchZip = new File(session, "update.patch.zip");
        IoUtils.copyUri(context.getContentResolver(), patchUri, patchZip);
        File extracted = new File(session, "patch-extracted");
        PatchInfo info = inspectZip(patchZip, extracted);

        String actualPackage = SourceInspector.readPackageName(project);
        if (!info.packageName.equals(actualPackage)) {
            throw new IOException("PATCH sai package: patch=" + info.packageName + ", source=" + actualPackage);
        }

        String actualSha = sha256(sourceZip);
        if (!info.baseSha256.equalsIgnoreCase(actualSha)) {
            throw new IOException("PATCH sai baseline SHA-256. Cần " + info.baseSha256 + " nhưng source là " + actualSha);
        }
        listener.onLog("PATCH baseline SHA-256 OK: " + actualSha);

        String beforeVersion = SourceInspector.readVersionName(project);
        if (!blank(info.baseVersion) && !sameVersion(info.baseVersion, beforeVersion)) {
            throw new IOException("PATCH sai Base-Version. Cần v" + info.baseVersion + " nhưng source là v" + normalizeVersion(beforeVersion));
        }
        if (!blank(info.baseVersion)) listener.onLog("PATCH Base-Version OK: " + info.baseVersion);

        for (String rel : info.deletes) {
            File target = safeTarget(project, rel);
            IoUtils.deleteRecursively(target);
            listener.onLog("PATCH delete: " + rel);
        }

        copyOverlay(info.overlayRoot, project, info.overlayRoot, info.manifestFile);
        listener.onLog("PATCH overlay applied to temp project");

        if (ProjectLocator.findProjectRoot(project) == null && !new File(project, "settings.gradle").isFile()
                && !new File(project, "settings.gradle.kts").isFile()) {
            throw new IOException("PATCH làm mất cấu trúc project Gradle");
        }

        String afterPackage = SourceInspector.readPackageName(project);
        if (!info.packageName.equals(afterPackage)) {
            throw new IOException("PATCH làm đổi applicationId/package: " + afterPackage);
        }

        String afterVersion = SourceInspector.readVersionName(project);
        if (!blank(info.targetVersion) && !sameVersion(info.targetVersion, afterVersion)) {
            throw new IOException("PATCH Target-Version không khớp source sau patch. Manifest=v" + info.targetVersion
                    + ", project=v" + normalizeVersion(afterVersion));
        }
        if (!blank(info.targetVersion)) listener.onLog("PATCH Target-Version OK: " + info.targetVersion);

        return new AppliedPatch(info, afterPackage, beforeVersion, afterVersion);
    }

    /**
     * Export patched FULL SOURCE only after compile/build validation has succeeded.
     */
    public static String exportValidatedSource(Context context, File project, File session,
                                               AppliedPatch applied, BuildListener listener) throws Exception {
        File outZip = new File(session, "validated-patched-full-source.zip");
        zipProject(project, outZip);
        if (!outZip.isFile() || outZip.length() <= 0) throw new IOException("Không tạo được FULL SOURCE sau patch");

        String target = !blank(applied.info.targetVersion)
                ? applied.info.targetVersion
                : normalizeVersion(applied.targetVersionAfter);
        String name;
        if ("com.apkbld".equals(applied.packageName)) {
            name = "APK-PRO-v" + (blank(target) ? "next" : target) + ".zip";
        } else {
            String safePackage = applied.packageName.replaceAll("[^A-Za-z0-9._-]", "_");
            name = safePackage + "-v" + (blank(target) ? "next" : target) + ".zip";
        }
        DownloadSaver.saveZip(context, outZip, name);
        try {
            BaselineStore.cacheBuiltSource(context, outZip, target, applied.packageName);
            listener.onLog("FULL SOURCE cache updated for next PATCH baseline");
        } catch (Throwable cacheError) {
            listener.onLog("WARN: Không cache được FULL SOURCE mới: " + cacheError.getMessage());
        }
        listener.onLog("FULL SOURCE validated saved: Download/" + name);
        return name;
    }

    public static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[128 * 1024];
            int n;
            while ((n = in.read(b)) >= 0) md.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest()) s.append(String.format(Locale.US, "%02x", x));
        return s.toString();
    }

    private static PatchInfo parseManifest(File file) throws Exception {
        String lower = file.getName().toLowerCase(Locale.US);
        PatchInfo info = lower.endsWith(".json") ? parseJsonManifest(file) : parseTextManifest(file);
        validateManifestIdentity(info);
        File parent = file.getParentFile();
        File files = new File(parent, "files");
        File overlayRoot = files.isDirectory() ? files : parent;
        return new PatchInfo(info.packageName, info.baseSha256, info.baseVersion, info.targetVersion,
                info.deletes, info.fileSha256, info.modernManifest, file, overlayRoot);
    }

    private static PatchInfo parseTextManifest(File file) throws IOException {
        String packageName = "";
        String baseSha = "";
        String baseVersion = "";
        String targetVersion = "";
        boolean modern = false;
        List<String> deletes = new ArrayList<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("Manifest-Version:")) {
                    modern = t.substring(17).trim().startsWith("2");
                } else if (t.startsWith("Package:")) {
                    packageName = t.substring(8).trim();
                } else if (t.startsWith("Base-SHA256:")) {
                    baseSha = t.substring(12).trim();
                } else if (t.startsWith("Base-Version:")) {
                    baseVersion = t.substring(13).trim();
                } else if (t.startsWith("Target-Version:")) {
                    targetVersion = t.substring(15).trim();
                } else if (t.startsWith("Delete:")) {
                    deletes.add(normalizeRel(t.substring(7).trim()));
                } else if (t.startsWith("File-SHA256:")) {
                    String value = t.substring(12).trim();
                    int pipe = value.indexOf('|');
                    String path;
                    String sha;
                    if (pipe >= 0) {
                        path = value.substring(0, pipe).trim();
                        sha = value.substring(pipe + 1).trim();
                    } else {
                        String[] parts = value.split("\\s+", 2);
                        if (parts.length != 2) throw new IOException("File-SHA256 sai format: " + value);
                        sha = parts[0].trim();
                        path = parts[1].trim();
                    }
                    hashes.put(normalizeRel(path), sha.toLowerCase(Locale.US));
                }
            }
        }
        return new PatchInfo(packageName, baseSha, baseVersion, targetVersion, deletes, hashes,
                modern, file, file.getParentFile());
    }

    /** Minimal JSON parser for the documented flat patch manifest fields. */
    private static PatchInfo parseJsonManifest(File file) throws IOException {
        String text = readSmallText(file, 1024 * 1024);
        String packageName = jsonString(text, "package");
        if (blank(packageName)) packageName = jsonString(text, "applicationId");
        String baseSha = jsonString(text, "baseSha256");
        String baseVersion = jsonNumberOrString(text, "baseVersion");
        String targetVersion = jsonNumberOrString(text, "targetVersion");
        List<String> deletes = jsonStringArray(text, "delete");
        if (deletes.isEmpty()) deletes = jsonStringArray(text, "deletes");
        Map<String, String> hashes = jsonStringMap(text, "fileSha256");
        return new PatchInfo(packageName, baseSha, baseVersion, targetVersion, deletes, hashes,
                true, file, file.getParentFile());
    }

    private static void validateManifestIdentity(PatchInfo info) throws IOException {
        if (info == null) throw new IOException("PATCH manifest không hợp lệ");
        if (blank(info.packageName) || blank(info.baseSha256)) {
            throw new IOException("PATCH manifest bắt buộc có package + baseSha256/Base-SHA256");
        }
        if (!info.baseSha256.matches("(?i)[0-9a-f]{64}")) throw new IOException("Base-SHA256 không hợp lệ");
        if (info.modernManifest && (blank(info.baseVersion) || blank(info.targetVersion))) {
            throw new IOException("PATCH manifest v2 bắt buộc có Base-Version + Target-Version");
        }
    }

    private static void verifyPatchPayload(PatchInfo info) throws Exception {
        List<File> payload = new ArrayList<>();
        collectPayloadFiles(info.overlayRoot, info.overlayRoot, info.manifestFile, payload);

        if (info.modernManifest && payload.size() != info.fileSha256.size()) {
            throw new IOException("PATCH manifest v2 phải có File-SHA256 cho mọi file overlay. files="
                    + payload.size() + ", hashes=" + info.fileSha256.size());
        }
        for (File file : payload) {
            String rel = info.overlayRoot.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
            String expected = info.fileSha256.get(rel);
            if (expected == null) {
                if (info.modernManifest) throw new IOException("PATCH thiếu File-SHA256: " + rel);
                continue;
            }
            String actual = sha256(file);
            if (!expected.equalsIgnoreCase(actual)) throw new IOException("PATCH file SHA-256 sai: " + rel);
        }
    }

    private static void collectPayloadFiles(File root, File file, File manifest, List<File> out) throws IOException {
        if (file == null || !file.exists()) return;
        if (file.equals(manifest)) return;
        String rel = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        if (!rel.isEmpty() && rel.startsWith("META-INF/")) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) collectPayloadFiles(root, child, manifest, out);
        } else {
            safeRel(rel);
            out.add(file);
        }
    }

    private static void copyOverlay(File file, File project, File base, File manifest) throws IOException {
        if (file.equals(manifest)) return;
        String rel = base.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        if (rel.equalsIgnoreCase("PATCH-MANIFEST.txt") || rel.equalsIgnoreCase("patch-manifest.json") || rel.startsWith("META-INF/")) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) copyOverlay(child, project, base, manifest);
            return;
        }
        File target = safeTarget(project, rel);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Không tạo được patch dir: " + parent);
        try (FileInputStream in = new FileInputStream(file); FileOutputStream out = new FileOutputStream(target)) {
            IoUtils.copy(in, out);
        }
    }

    private static File safeTarget(File project, String rel) throws IOException {
        safeRel(rel);
        File out = new File(project, rel);
        String root = project.getCanonicalPath() + File.separator;
        String canonical = out.getCanonicalPath();
        if (!canonical.startsWith(root)) throw new IOException("PATCH path thoát project: " + rel);
        return out;
    }

    private static void safeRel(String rel) throws IOException {
        if (rel == null || rel.trim().isEmpty() || rel.startsWith("/") || rel.startsWith("\\")
                || rel.contains("../") || rel.contains("..\\") || rel.equals("..")) {
            throw new IOException("PATCH path không an toàn: " + rel);
        }
    }

    private static String normalizeRel(String rel) throws IOException {
        String value = rel == null ? "" : rel.trim().replace('\\', '/');
        safeRel(value);
        return value;
    }

    private static File findFileIgnoreCase(File dir, String name, int depth) {
        if (dir == null || depth < 0 || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) if (f.isFile() && name.equalsIgnoreCase(f.getName())) return f;
        for (File f : files) if (f.isDirectory()) {
            File found = findFileIgnoreCase(f, name, depth - 1);
            if (found != null) return found;
        }
        return null;
    }

    private static void zipProject(File project, File out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            zipTree(project, project, zip);
        }
    }

    private static void zipTree(File root, File file, ZipOutputStream zip) throws IOException {
        String rel = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        if (!rel.isEmpty() && shouldSkip(rel, file)) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) zipTree(root, child, zip);
            return;
        }
        if (rel.isEmpty()) return;
        ZipEntry e = new ZipEntry(rel);
        zip.putNextEntry(e);
        try (FileInputStream in = new FileInputStream(file)) { IoUtils.copy(in, zip); }
        zip.closeEntry();
    }

    private static boolean shouldSkip(String rel, File file) {
        String n = "/" + rel.replace('\\','/') + "/";
        if (n.contains("/build/") || n.contains("/.gradle/") || n.contains("/.idea/") || n.contains("/cache/")) return true;
        String low = file.getName().toLowerCase(Locale.US);
        return low.endsWith(".apk") || low.endsWith(".aab") || low.equals("local.properties");
    }

    private static String readSmallText(File file, int max) throws IOException {
        if (!file.isFile() || file.length() > max) throw new IOException("Manifest quá lớn");
        StringBuilder out = new StringBuilder((int) file.length());
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String jsonString(String text, String key) {
        Pattern p = Pattern.compile(String.format(Locale.US, JSON_STRING.pattern(), Pattern.quote(key)));
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String jsonNumberOrString(String text, String key) {
        Pattern p = Pattern.compile(String.format(Locale.US, JSON_NUMBER_OR_STRING.pattern(), Pattern.quote(key)));
        Matcher m = p.matcher(text);
        if (!m.find()) return "";
        return safe(m.group(1) != null ? m.group(1) : m.group(2));
    }

    private static List<String> jsonStringArray(String text, String key) throws IOException {
        List<String> out = new ArrayList<>();
        Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        if (!m.find()) return out;
        Matcher strings = Pattern.compile("\\\"([^\\\"]+)\\\"").matcher(m.group(1));
        while (strings.find()) out.add(normalizeRel(strings.group(1)));
        return out;
    }

    private static Map<String, String> jsonStringMap(String text, String key) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        if (!m.find()) return out;
        Matcher entry = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([0-9a-fA-F]{64})\\\"").matcher(m.group(1));
        while (entry.find()) out.put(normalizeRel(entry.group(1)), entry.group(2).toLowerCase(Locale.US));
        return out;
    }

    public static boolean sameVersion(String a, String b) {
        String x = normalizeVersion(a);
        String y = normalizeVersion(b);
        return !blank(x) && x.equals(y);
    }

    public static String normalizeVersion(String value) {
        String v = safe(value);
        while (v.startsWith("v") || v.startsWith("V")) v = v.substring(1).trim();
        return v;
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
