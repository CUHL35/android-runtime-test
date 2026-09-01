package com.longdev.apkbuilder.core;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Portable backup/import for APK PRO's already-local ARM64 core. No network access. */
public final class ToolchainPackManager {
    public static final String DEFAULT_NAME = "APK-PRO-Toolchain-Core-v2.zip";
    private static final String META = "APK-PRO-TOOLCHAIN.properties";
    private static final String SYMLINKS = "APK-PRO-SYMLINKS.tsv";
    private static final String EXECUTABLES = "APK-PRO-EXECUTABLES.txt";
    private static final int BUFFER = 64 * 1024;

    private ToolchainPackManager() {}

    public static final class ExportResult {
        public final String savedName;
        public final String sha256;
        public final long bytes;

        ExportResult(String savedName, String sha256, long bytes) {
            this.savedName = savedName;
            this.sha256 = sha256;
            this.bytes = bytes;
        }
    }

    public static ExportResult exportToDownloads(Context context) throws IOException {
        File root = context.getFilesDir();
        if (!ToolchainManager.hasCore(root)) {
            throw new IOException("Chưa có APK PRO ARM64 Core để xuất");
        }
        File work = new File(context.getCacheDir(), "toolchain-pack-export");
        IoUtils.deleteRecursively(work);
        if (!work.mkdirs() && !work.isDirectory()) throw new IOException("Không tạo được pack staging");
        File zip = new File(work, DEFAULT_NAME);
        createPack(root, zip);
        String sha = sha256(zip);
        DownloadSaver.saveZip(context, zip, DEFAULT_NAME);
        long bytes = zip.length();
        IoUtils.deleteRecursively(work);
        return new ExportResult(DEFAULT_NAME, sha, bytes);
    }

    public static void importFromUri(Context context, Uri uri) throws IOException {
        if (uri == null) throw new IOException("Chưa chọn Toolchain Pack");
        File base = new File(context.getCacheDir(), "toolchain-pack-import");
        IoUtils.deleteRecursively(base);
        if (!base.mkdirs() && !base.isDirectory()) throw new IOException("Không tạo được import staging");
        File archive = new File(base, "input.zip");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(archive))) {
            if (in == null) throw new IOException("Không mở được Toolchain Pack");
            IoUtils.copy(in, out);
        }

        File stage = new File(base, "stage");
        if (!stage.mkdirs() && !stage.isDirectory()) throw new IOException("Không tạo được unpack staging");
        unpack(archive, stage);
        validateMeta(stage);
        File stagedUsr = new File(stage, "usr");
        if (!stagedUsr.isDirectory()) throw new IOException("Toolchain Pack thiếu thư mục usr");

        File target = new File(context.getFilesDir(), "usr");
        File backup = new File(context.getFilesDir(), "usr.before-toolchain-import");
        IoUtils.deleteRecursively(backup);
        boolean hadTarget = target.exists();
        try {
            if (hadTarget) Files.move(target.toPath(), backup.toPath());
            Files.move(stagedUsr.toPath(), target.toPath());
            restoreLinksAndModes(stage, context.getFilesDir());
            if (!ToolchainManager.hasCore(context.getFilesDir())) {
                throw new IOException("Core sau import không vượt qua validation");
            }
            IoUtils.deleteRecursively(backup);
            ToolchainManager.coreMarker(context.getFilesDir()).delete();
        } catch (Throwable error) {
            IoUtils.deleteRecursively(target);
            if (backup.exists()) {
                try { Files.move(backup.toPath(), target.toPath()); } catch (Throwable ignored) { }
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Import Toolchain Pack thất bại", error);
        } finally {
            IoUtils.deleteRecursively(base);
        }
    }

    private static void createPack(File root, File out) throws IOException {
        Path usr = new File(root, "usr").toPath();
        List<String> symlinks = new ArrayList<>();
        List<String> executables = new ArrayList<>();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            putText(zip, META,
                    "format=1\nname=APK PRO ARM64 Core\ncoreVersion=2\nabi=arm64-v8a\n"
                            + "networkProvisioning=first-use-cache\n"
                            + "jdkBase=17\n");
            Files.walkFileTree(usr, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    String rel = relative(root.toPath(), dir);
                    if (shouldSkip(rel)) return FileVisitResult.SKIP_SUBTREE;
                    if (!rel.isEmpty()) putDirectory(zip, rel + "/");
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String rel = relative(root.toPath(), file);
                    if (shouldSkip(rel)) return FileVisitResult.CONTINUE;
                    if (Files.isSymbolicLink(file)) {
                        String rawTarget = Files.readSymbolicLink(file).toString();
                        symlinks.add(rel + "\t" + portableTarget(file, usr, rawTarget));
                        return FileVisitResult.CONTINUE;
                    }
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    if (Files.isExecutable(file)) executables.add(rel);
                    ZipEntry entry = new ZipEntry(rel);
                    entry.setTime(attrs.lastModifiedTime().toMillis());
                    zip.putNextEntry(entry);
                    try (InputStream in = new BufferedInputStream(new FileInputStream(file.toFile()))) {
                        byte[] buffer = new byte[BUFFER];
                        int n;
                        while ((n = in.read(buffer)) >= 0) if (n > 0) zip.write(buffer, 0, n);
                    }
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
            putText(zip, SYMLINKS, joinLines(symlinks));
            putText(zip, EXECUTABLES, joinLines(executables));
        }
    }

    private static boolean shouldSkip(String rel) {
        return rel.equals("usr/opt/android-sdk") || rel.startsWith("usr/opt/android-sdk/")
                || rel.equals("usr/opt/gradle") || rel.startsWith("usr/opt/gradle/")
                || rel.equals("usr/var/cache/apt") || rel.startsWith("usr/var/cache/apt/")
                || rel.equals("usr/var/lib/apt/lists") || rel.startsWith("usr/var/lib/apt/lists/")
                || rel.equals("usr/tmp") || rel.startsWith("usr/tmp/");
    }

    private static void unpack(File archive, File stage) throws IOException {
        String root = stage.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER];
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(stage, entry.getName());
                String canonical = target.getCanonicalPath();
                if (!canonical.startsWith(root)) throw new IOException("ZIP entry không an toàn: " + entry.getName());
                if (entry.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("Không tạo được " + target);
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Không tạo được " + parent);
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                        int n;
                        while ((n = zip.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void validateMeta(File stage) throws IOException {
        File meta = new File(stage, META);
        if (!meta.isFile()) throw new IOException("Không phải APK PRO Toolchain Pack: thiếu " + META);
        String text = new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8);
        if (!text.contains("format=1") || !text.contains("abi=arm64-v8a")) {
            throw new IOException("Toolchain Pack format/ABI không tương thích");
        }
    }

    private static void restoreLinksAndModes(File manifestRoot, File installRoot) throws IOException {
        File links = new File(manifestRoot, SYMLINKS);
        if (links.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(links))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    int tab = line.indexOf('\t');
                    if (tab <= 0) throw new IOException("Symlink manifest lỗi");
                    File link = safeChild(installRoot, line.substring(0, tab));
                    String target = line.substring(tab + 1);
                    File parent = link.getParentFile();
                    if (parent != null && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Không tạo được " + parent);
                    Files.deleteIfExists(link.toPath());
                    Files.createSymbolicLink(link.toPath(), new File(target).toPath());
                }
            }
        }
        File modes = new File(manifestRoot, EXECUTABLES);
        if (modes.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(modes))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    File file = safeChild(installRoot, line.trim());
                    if (file.isFile()) file.setExecutable(true, false);
                }
            }
        }
    }

    private static String portableTarget(Path link, Path usrRoot, String rawTarget) {
        if (rawTarget == null || rawTarget.isEmpty()) return rawTarget == null ? "" : rawTarget;
        Path raw = new File(rawTarget).toPath();
        if (!raw.isAbsolute()) return rawTarget;

        String[] knownPrefixes = {
                usrRoot.toAbsolutePath().toString(),
                "/data/data/com.apkbld/files/usr"
        };
        for (String prefix : knownPrefixes) {
            if (rawTarget.equals(prefix) || rawTarget.startsWith(prefix + "/")) {
                String suffix = rawTarget.substring(prefix.length());
                while (suffix.startsWith("/")) suffix = suffix.substring(1);
                Path target = suffix.isEmpty() ? usrRoot : usrRoot.resolve(suffix);
                return link.getParent().relativize(target).toString();
            }
        }
        return rawTarget;
    }

    private static File safeChild(File root, String rel) throws IOException {
        File child = new File(root, rel);
        String base = root.getCanonicalPath() + File.separator;
        if (!child.getCanonicalPath().startsWith(base)) throw new IOException("Pack path không an toàn: " + rel);
        return child;
    }

    private static String relative(Path root, Path child) {
        return root.relativize(child).toString().replace(File.separatorChar, '/');
    }

    private static void putDirectory(ZipOutputStream zip, String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.closeEntry();
    }

    private static void putText(ZipOutputStream zip, String name, String text) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String joinLines(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) out.append(line).append('\n');
        return out.toString();
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[BUFFER];
                int n;
                while ((n = in.read(buffer)) >= 0) if (n > 0) digest.update(buffer, 0, n);
            }
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Không tính được SHA-256 Toolchain Pack", e);
        }
    }
}
