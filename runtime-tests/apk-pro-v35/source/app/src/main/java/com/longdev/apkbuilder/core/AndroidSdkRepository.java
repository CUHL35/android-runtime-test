package com.longdev.apkbuilder.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

/** Installs platform packages from Google's live Android SDK repository metadata. */
public final class AndroidSdkRepository {
    private static final String REPOSITORY_URL = "https://dl.google.com/android/repository/repository2-3.xml";
    private static final String ARCHIVE_BASE = "https://dl.google.com/android/repository/";

    private final File runtime;

    public AndroidSdkRepository(File runtime) {
        this.runtime = runtime;
    }

    public File ensurePlatform(int api, BuildListener listener) throws IOException {
        if (api <= 0) throw new IOException("compileSdk không hợp lệ: " + api);
        File sdk = new File(runtime, "usr/opt/android-sdk");
        File platforms = new File(sdk, "platforms");

        // Android 37 introduced minor-versioned SDK package coordinates. Gradle still
        // declares compileSdk 37, while Google's repository publishes the base platform
        // as platforms;android-37.0 and installs it under platforms/android-37.0.
        // Prefer an existing exact cache first, then the minor .0 cache.
        File exactCached = new File(platforms, "android-" + api);
        if (new File(exactCached, "android.jar").isFile()) {
            if (listener != null) listener.onLog("SDK platform android-" + api + " đã có trong cache");
            return exactCached;
        }
        File minorZeroCached = new File(platforms, "android-" + api + ".0");
        if (new File(minorZeroCached, "android.jar").isFile()) {
            if (listener != null) listener.onLog("SDK platform android-" + api + ".0 đã có trong cache");
            return minorZeroCached;
        }

        if (listener != null) listener.onLog("Thiếu SDK cho compileSdk " + api
                + " — đọc repository chính thức của Google...");
        File cacheDir = new File(runtime, "download-cache/android-sdk");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) throw new IOException("Không tạo được Android SDK cache");
        File metadata = new File(cacheDir, "repository2-3.xml");
        NetworkFiles.download(REPOSITORY_URL, metadata, listener, "Android repository metadata");

        String coordinate = "platforms;android-" + api;
        PackageInfo info = findPackage(metadata, coordinate, null);
        if (info == null) {
            // Current Google repository uses this form for API 37: platforms;android-37.0.
            coordinate = "platforms;android-" + api + ".0";
            info = findPackage(metadata, coordinate, null);
        }
        if (info == null) {
            throw new IOException("Google SDK repository chưa có stable platform cho compileSdk " + api
                    + " (đã thử platforms;android-" + api + " và platforms;android-" + api + ".0)");
        }

        String platformDirName = coordinate.substring("platforms;".length());
        File platform = new File(platforms, platformDirName);
        if (listener != null) listener.onLog("SDK coordinate: " + coordinate);

        File archive = new File(cacheDir, info.url.substring(info.url.lastIndexOf('/') + 1));
        String archiveUrl = new java.net.URL(new java.net.URL(ARCHIVE_BASE), info.url).toString();
        if (!archive.isFile()) NetworkFiles.download(archiveUrl, archive, listener, "SDK " + platformDirName);
        try {
            NetworkFiles.verify(archive, info.algorithm, info.checksum);
        } catch (IOException stale) {
            archive.delete();
            NetworkFiles.download(archiveUrl, archive, listener, "SDK " + platformDirName);
            NetworkFiles.verify(archive, info.algorithm, info.checksum);
        }
        if (listener != null) listener.onLog("Checksum " + info.algorithm.toUpperCase(Locale.US)
                + " SDK " + platformDirName + ": OK");

        File stage = new File(cacheDir, "platform-stage-" + platformDirName.replace('.', '_'));
        IoUtils.deleteRecursively(stage);
        if (!stage.mkdirs()) throw new IOException("Không tạo được SDK stage");
        try {
            try (FileInputStream input = new FileInputStream(archive)) {
                ZipUtils.unzip(input, stage);
            }

            File extracted = findAndroidJarParent(stage, 0);
            if (extracted == null) throw new IOException("SDK platform ZIP không có android.jar");
            if (!platforms.exists() && !platforms.mkdirs()) throw new IOException("Không tạo được platforms cache");
            IoUtils.deleteRecursively(platform);
            moveTree(extracted, platform);
        } finally {
            IoUtils.deleteRecursively(stage);
        }
        if (!new File(platform, "android.jar").isFile()) {
            throw new IOException("Cài SDK " + platformDirName + " chưa hoàn tất");
        }
        if (listener != null) listener.onLog("Đã cache " + coordinate);
        return platform;
    }

    /**
     * Creates an SDK Build Tools façade: Android-native ARM64 binaries come from the
     * APK PRO core, while architecture-neutral apksigner/core-lambda-stubs come from
     * Google's official Build Tools archive for the exact requested revision.
     */
    public File ensureBuildToolsFacade(String revision, BuildListener listener) throws IOException {
        if (revision == null || !revision.matches("[0-9]+(?:\\.[0-9]+){1,2}")) revision = "36.0.0";
        File prefix = new File(runtime, "usr");
        File sdk = new File(prefix, "opt/android-sdk");
        File target = new File(sdk, "build-tools/" + revision);
        File aapt2 = new File(prefix, "bin/aapt2");
        File aapt = new File(prefix, "bin/aapt");
        File aidl = new File(prefix, "bin/aidl");
        File zipalign = new File(prefix, "bin/zipalign");

        if (!aapt2.isFile()) throw new IOException("APK PRO ARM64 Core thiếu aapt2");
        if (!aidl.isFile()) throw new IOException("APK PRO ARM64 Core thiếu aidl");
        if (!zipalign.isFile()) throw new IOException("APK PRO ARM64 Core thiếu zipalign");

        ensureOfficialBuildToolsJavaArtifacts(revision, target, listener);
        File apksignerJar = new File(target, "lib/apksigner.jar");
        File coreLambda = new File(target, "core-lambda-stubs.jar");
        if (!apksignerJar.isFile()) throw new IOException("Google Build Tools thiếu lib/apksigner.jar");
        if (!coreLambda.isFile()) throw new IOException("Google Build Tools thiếu core-lambda-stubs.jar");

        if (!target.exists() && !target.mkdirs()) throw new IOException("Không tạo được build-tools façade");
        linkOrCopy(aapt2, new File(target, "aapt2"));
        if (aapt.isFile()) linkOrCopy(aapt, new File(target, "aapt"));
        linkOrCopy(aidl, new File(target, "aidl"));
        linkOrCopy(zipalign, new File(target, "zipalign"));

        // sdklib validates a few legacy Build Tools paths even when a project never uses them.
        // These launchers fail loudly if an imported project really requires unsupported host tools.
        writeUnsupportedLauncher(new File(target, "dexdump"), "dexdump");
        writeUnsupportedLauncher(new File(target, "split-select"), "split-select");

        File sourceProperties = new File(target, "source.properties");
        try (FileOutputStream output = new FileOutputStream(sourceProperties)) {
            output.write(("Pkg.Desc=Android SDK Build-Tools (APK PRO ARM64 façade)\n"
                    + "Pkg.Revision=" + revision + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (listener != null) listener.onLog("Build Tools " + revision
                + " façade: ARM64 aapt2/aidl/zipalign + Google apksigner/core-lambda-stubs");
        return target;
    }

    private void ensureOfficialBuildToolsJavaArtifacts(
            String revision, File target, BuildListener listener) throws IOException {
        File apksigner = new File(target, "lib/apksigner.jar");
        File lambda = new File(target, "core-lambda-stubs.jar");
        if (apksigner.isFile() && apksigner.length() > 1024
                && lambda.isFile() && lambda.length() > 1024) {
            if (listener != null) listener.onLog("Google Build Tools " + revision + " Java artifacts đã cache");
            return;
        }

        File cacheDir = new File(runtime, "download-cache/android-sdk");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) throw new IOException("Không tạo được Android SDK cache");
        File metadata = new File(cacheDir, "repository2-3.xml");
        NetworkFiles.download(REPOSITORY_URL, metadata, listener, "Android repository metadata");

        PackageInfo info = findPackage(metadata, "build-tools;" + revision, "linux");
        if (info == null) throw new IOException("Google SDK repository chưa có stable build-tools;" + revision + " cho Linux");

        File archive = new File(cacheDir, info.url.substring(info.url.lastIndexOf('/') + 1));
        String archiveUrl = new java.net.URL(new java.net.URL(ARCHIVE_BASE), info.url).toString();
        if (!archive.isFile()) NetworkFiles.download(archiveUrl, archive, listener, "Google Build Tools " + revision);
        try {
            NetworkFiles.verify(archive, info.algorithm, info.checksum);
        } catch (IOException stale) {
            archive.delete();
            NetworkFiles.download(archiveUrl, archive, listener, "Google Build Tools " + revision);
            NetworkFiles.verify(archive, info.algorithm, info.checksum);
        }
        if (listener != null) listener.onLog("Checksum " + info.algorithm.toUpperCase(Locale.US)
                + " Google Build Tools " + revision + ": OK");

        File stage = new File(cacheDir, "build-tools-stage-" + revision.replace('.', '_'));
        IoUtils.deleteRecursively(stage);
        if (!stage.mkdirs() && !stage.isDirectory()) throw new IOException("Không tạo được Build Tools stage");
        try {
            try (FileInputStream input = new FileInputStream(archive)) {
                ZipUtils.unzip(input, stage);
            }
            File extracted = findBuildToolsRoot(stage, 0);
            if (extracted == null) throw new IOException("Build Tools ZIP thiếu apksigner/core-lambda-stubs");

            File lib = new File(target, "lib");
            if (!lib.exists() && !lib.mkdirs()) throw new IOException("Không tạo được build-tools lib cache");
            copyAtomic(new File(extracted, "lib/apksigner.jar"), apksigner);
            copyAtomic(new File(extracted, "core-lambda-stubs.jar"), lambda);
        } finally {
            IoUtils.deleteRecursively(stage);
        }
        if (!apksigner.isFile() || apksigner.length() <= 1024) throw new IOException("Cache apksigner.jar chưa hoàn tất");
        if (!lambda.isFile() || lambda.length() <= 1024) throw new IOException("Cache core-lambda-stubs.jar chưa hoàn tất");
    }


    private static void copyAtomic(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Không tạo được " + parent);
        File part = new File(destination.getAbsolutePath() + ".part");
        Files.deleteIfExists(part.toPath());
        Files.copy(source.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(part.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
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
