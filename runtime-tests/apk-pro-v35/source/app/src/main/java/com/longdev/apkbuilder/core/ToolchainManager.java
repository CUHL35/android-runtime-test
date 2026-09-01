package com.longdev.apkbuilder.core;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * APK PRO v36 lightweight ARM64 bootstrap manager.
 *
 * Nothing large is embedded in APK PRO. A small official ARM64 bootstrap is downloaded once,
 * then Android-native JDK/build tools are resolved from the signed ARM64 package feed repository and
 * kept only in app-private cache. Android SDK platforms are downloaded from Google's live SDK
 * repository only when a project actually requires them.
 */
public final class ToolchainManager {
    private static final String ENGINE_SCRIPT = "engine/run-build.sh";
    private static final String KEYCHECK_SOURCE = "engine/SigningKeyCheck.java";
    private static final String PACKAGE_SCRIPT = "engine/provision-packages.sh";
    private static final String TOOLCHAIN_MANIFEST = "toolchain/arm64-core.properties";
    private static final String CORE_MARKER = "toolchain-core.properties";

    private static final String OLD_PREFIX = "/data/data/com.termux/files/usr";
    private static final String NEW_PREFIX = "/data/data/com.apkbld/files/usr";
    private static final String OLD_HOME = "/data/data/com.termux/files/home";
    private static final String NEW_HOME = "/data/data/com.apkbld/files/home";

    private final Context context;

    public ToolchainManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Ensures the small runtime bootstrap + core ARM64 build tools exist. */
    public File ensureReady(BuildListener listener) throws IOException {
        if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) {
            throw new IOException("APK PRO chỉ hỗ trợ toolchain Android ARM64 (arm64-v8a)");
        }

        CoreManifest manifest = loadCoreManifest();
        File root = context.getFilesDir();
        recoverInterruptedSwap(root);
        ensureDirectory(new File(root, "download-cache"), "download cache");
        ensureDirectory(new File(root, "gradle-home"), "Gradle/Maven cache");
        ensureDirectory(new File(root, "build-home/tmp"), "build tmp");
        copyEngineAssets(root);

        if (!bootstrapReady(root)) {
            if (listener != null) listener.onProgress(20, "Tải bootstrap ARM64");
            if (listener != null) listener.onLog("Fresh install: tải bootstrap ARM64 tối thiểu, SHA-256 pin cứng; không cần app Termux");
            installBootstrap(root, listener, manifest);
        } else if (listener != null) {
            listener.onLog("Bootstrap ARM64 đã có trong app-private cache");
        }

        prepareExecutables(root);
        validateBootstrap(root);

        if (!coreReady(root)) {
            if (listener != null) listener.onProgress(25, "Tải JDK / Android ARM64 tools");
            if (listener != null) listener.onLog("Provision ARM64 core vào staging: JDK17 + aapt/aapt2/aidl/zipalign");
            installPackagesAtomically(root, listener, manifest.corePackages);
        }

        prepareExecutables(root);
        validateCore(root);
        validateCoreRuntime(root, listener);
        writeCoreMarker(root, manifest);

        AndroidSdkRepository sdkRepository = new AndroidSdkRepository(root);
        sdkRepository.ensureBuildToolsFacade("36.0.0", listener);

        File ready = new File(root, ".apk-pro-ready-v33");
        touch(ready);
        if (listener != null) {
            listener.onLog("APK PRO ARM64 Core: cache local OK; lần sau không tải lại nếu còn đủ file");
            listener.onProgress(35, "Dynamic toolchain cache sẵn sàng");
        }
        return root;
    }

    /** Prefetches the common Android platform without pinning Gradle/AGP/dependencies. */
    public void prefetchCommonSdk(BuildListener listener) throws IOException {
        File root = ensureReady(listener);
        if (listener != null) listener.onProgress(70, "Tải SDK phổ biến API 37");
        AndroidSdkRepository sdk = new AndroidSdkRepository(root);
        sdk.ensurePlatform(37, listener);
        sdk.ensureBuildToolsFacade("36.0.0", listener);
        if (listener != null) {
            listener.onLog("Prefetch SDK phổ biến: platforms;android-37.0 + build-tools;36.0.0 READY");
            listener.onProgress(95, "SDK phổ biến đã cache");
        }
    }

    /** Ensures the exact project-specific SDK/JDK pieces are cached before Gradle starts. */
    public ToolchainRequirements ensureForProject(File root, File project, BuildListener listener) throws IOException {
        ToolchainRequirements requirements = ToolchainRequirements.inspect(project);
        listener.onLog("Project toolchain: " + requirements);

        // Gradle 7.3+ can run on JDK 17. Current Android/AGP generations use JDK 17+.
        // We refuse to silently run an old Gradle on the wrong JVM.
        if (compareVersions(requirements.gradleVersion, "7.3") < 0) {
            throw new IOException("Gradle " + requirements.gradleVersion
                    + " không chạy trên JDK 17. Project quá cũ; hãy dùng wrapper >= 7.3 hoặc cấu hình JDK cũ riêng.");
        }

        int jdkMajor = requirements.jdkPackageMajor();
        if (jdkMajor > 17) ensureOptionalJdk(root, jdkMajor, listener);

        AndroidSdkRepository sdk = new AndroidSdkRepository(root);
        listener.onProgress(38, "Kiểm tra SDK android-" + requirements.compileSdk);
        sdk.ensurePlatform(requirements.compileSdk, listener);
        sdk.ensureBuildToolsFacade(requirements.effectiveBuildToolsVersion(), listener);

        // Gradle itself stays on JDK17; additional JDKs are exposed as Java toolchains.
        File activeJava = new File(root, "active-java-home.txt");
        try (FileWriter writer = new FileWriter(activeJava, false)) {
            writer.write(new File(root, "usr/lib/jvm/java-17-openjdk").getAbsolutePath());
            writer.write('\n');
        }
        File activeBuildTools = new File(root, "active-build-tools.txt");
        try (FileWriter writer = new FileWriter(activeBuildTools, false)) {
            writer.write(requirements.effectiveBuildToolsVersion());
            writer.write('\n');
        }
        listener.onProgress(46, "Project toolchain đã đủ");
        return requirements;
    }

    private void ensureOptionalJdk(File root, int major, BuildListener listener) throws IOException {
        File home = new File(root, "usr/lib/jvm/java-" + major + "-openjdk");
        if (new File(home, "bin/javac").isFile()) {
            listener.onLog("JDK " + major + " toolchain đã có trong cache");
            return;
        }
        String packageName = "openjdk-" + major;
        listener.onLog("Project yêu cầu Java " + major + " — tải " + packageName + " từ ARM64 package feed...");
        try {
            installPackagesAtomically(root, listener, packageName);
        } catch (IOException exactMissing) {
            throw new IOException("Không cài được JDK " + major
                    + " từ repository hiện tại: " + exactMissing.getMessage(), exactMissing);
        }
        prepareExecutables(root);
        if (!new File(home, "bin/javac").isFile()) {
            throw new IOException("Repository tải xong nhưng không có JDK " + major + " tại " + home);
        }
    }

    private void installBootstrap(File root, BuildListener listener, CoreManifest manifest) throws IOException {
        File cache = new File(root, "download-cache/bootstrap-aarch64.zip");
        if (!cache.isFile()) NetworkFiles.download(manifest.bootstrapUrl, cache, listener, "ARM64 bootstrap");
        try {
            NetworkFiles.verify(cache, "SHA-256", manifest.bootstrapSha256);
        } catch (IOException badCached) {
            if (listener != null) listener.onLog("Bootstrap cache sai SHA-256 — tải lại...");
            cache.delete();
            NetworkFiles.download(manifest.bootstrapUrl, cache, listener, "ARM64 bootstrap");
            NetworkFiles.verify(cache, "SHA-256", manifest.bootstrapSha256);
        }
        if (listener != null) listener.onLog("Bootstrap SHA-256: OK");

        File stage = new File(root, "download-cache/bootstrap-install-stage");
        File stagePrefix = new File(stage, "usr");
        File livePrefix = new File(root, "usr");
        File backup = new File(root, "usr.before-bootstrap-install");
        IoUtils.deleteRecursively(stage);
        IoUtils.deleteRecursively(backup);
        ensureDirectory(stagePrefix, "bootstrap stage");
        try (InputStream input = new FileInputStream(cache)) {
            ZipUtils.unzip(input, stagePrefix);
        }

        // Fixed-length relocation is intentional: com.termux and com.apkbld prefixes are equal length.
        if (OLD_PREFIX.length() != NEW_PREFIX.length() || OLD_HOME.length() != NEW_HOME.length()) {
            throw new IOException("Prefix relocation length mismatch");
        }
        relocateTree(stagePrefix, OLD_PREFIX, NEW_PREFIX);
        relocateTree(stagePrefix, OLD_HOME, NEW_HOME);
        restoreBootstrapSymlinks(stagePrefix);
        validateBootstrapPrefix(stagePrefix);

        try {
            if (livePrefix.exists()) Files.move(livePrefix.toPath(), backup.toPath());
            try {
                Files.move(stagePrefix.toPath(), livePrefix.toPath());
                prepareExecutables(root);
                validateBootstrap(root);
            } catch (Throwable broken) {
                IoUtils.deleteRecursively(livePrefix);
                if (backup.exists()) Files.move(backup.toPath(), livePrefix.toPath());
                if (broken instanceof IOException) {
                    throw new IOException("Bootstrap mới không vượt qua activation; đã rollback: "
                            + broken.getMessage(), broken);
                }
                throw new IOException("Bootstrap activation thất bại; đã rollback", broken);
            }
            IoUtils.deleteRecursively(backup);
        } finally {
            IoUtils.deleteRecursively(stage);
        }
    }

    private void downloadPackagesInto(File root, File destinationPrefix, BuildListener listener, String... packages) throws IOException {
        if (packages == null || packages.length == 0) return;
        if (destinationPrefix == null) throw new IOException("Thiếu destination prefix");
        File script = new File(root, "provision-packages.sh");
        copyAsset(PACKAGE_SCRIPT, script);
        script.setExecutable(true, false);

        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("/system/bin/sh");
        command.add(script.getAbsolutePath());
        command.add(root.getAbsolutePath());
        command.addAll(Arrays.asList(packages));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        File prefix = new File(root, "usr");
        java.util.Map<String, String> env = builder.environment();
        env.put("PREFIX", prefix.getAbsolutePath());
        env.put("HOME", new File(root, "build-home").getAbsolutePath());
        env.put("TMPDIR", new File(root, "build-home/tmp").getAbsolutePath());
        env.put("PATH", prefix.getAbsolutePath() + "/bin:/system/bin:/system/xbin");
        env.remove("LD_LIBRARY_PATH");

        Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    process.destroyForcibly();
                    throw new IOException("Đã hủy bởi người dùng");
                }
                listener.onLog(line);
            }
        }
        try {
            int code = process.waitFor();
            if (code != 0) throw new IOException("ARM64 package downloader thoát mã " + code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Đã hủy bởi người dùng", e);
        }

        File stage = new File(root, "download-cache/pkg-stage");
        File stagedPrefix = new File(stage, "data/data/com.termux/files/usr");
        if (!stagedPrefix.isDirectory()) {
            throw new IOException("Package stage không có Termux prefix: " + stagedPrefix);
        }
        relocateTree(stagedPrefix, OLD_PREFIX, NEW_PREFIX);
        relocateTree(stagedPrefix, OLD_HOME, NEW_HOME);
        AndroidSdkRepository.copyTree(stagedPrefix.toPath(), destinationPrefix.toPath());
        writeDownloadedPackageLock(root);
        IoUtils.deleteRecursively(stage);
    }

    private void installPackagesAtomically(File root, BuildListener listener, String... packages) throws IOException {
        File livePrefix = new File(root, "usr");
        if (!livePrefix.isDirectory()) throw new IOException("Bootstrap prefix chưa sẵn sàng");

        File stageRoot = new File(root, "core-install-stage");
        File stagedPrefix = new File(stageRoot, "usr");
        File backup = new File(root, "usr.before-core-install");
        IoUtils.deleteRecursively(stageRoot);
        IoUtils.deleteRecursively(backup);
        if (!stageRoot.mkdirs() && !stageRoot.isDirectory()) throw new IOException("Không tạo được core staging");

        try {
            AndroidSdkRepository.copyTree(livePrefix.toPath(), stagedPrefix.toPath());
            downloadPackagesInto(root, stagedPrefix, listener, packages);
            validateCorePayload(stagedPrefix, packages);

            if (livePrefix.exists()) Files.move(livePrefix.toPath(), backup.toPath());
            try {
                Files.move(stagedPrefix.toPath(), livePrefix.toPath());
                prepareExecutables(root);
                validateBootstrap(root);
                if (containsPackage(packages, "openjdk-17") || coreReady(root)) validateCore(root);
            } catch (Throwable broken) {
                IoUtils.deleteRecursively(livePrefix);
                if (backup.exists()) Files.move(backup.toPath(), livePrefix.toPath());
                if (broken instanceof IOException) {
                    throw new IOException("Core mới không vượt qua activation; đã rollback: "
                            + broken.getMessage(), broken);
                }
                throw new IOException("Core activation thất bại; đã rollback", broken);
            }
            IoUtils.deleteRecursively(backup);
        } finally {
            IoUtils.deleteRecursively(stageRoot);
        }
    }

    private static void validateCorePayload(File prefix, String... packages) throws IOException {
        if (containsPackage(packages, "openjdk-17")) {
            requirePathNoFollow(new File(prefix, "lib/jvm/java-17-openjdk/bin/java"), "JDK17 java");
            requirePathNoFollow(new File(prefix, "lib/jvm/java-17-openjdk/bin/javac"), "JDK17 javac");
            requirePathNoFollow(new File(prefix, "lib/jvm/java-17-openjdk/bin/keytool"), "JDK17 keytool");
        }
        if (containsPackage(packages, "ca-certificates"))
            requirePathNoFollow(new File(prefix, "etc/tls/cert.pem"), "CA certificates");
        if (containsPackage(packages, "ca-certificates-java"))
            requirePathNoFollow(new File(prefix, "lib/jvm/java-17-openjdk/lib/security/jssecacerts"), "Java CA keystore");
        if (containsPackage(packages, "aapt2")) requirePathNoFollow(new File(prefix, "bin/aapt2"), "aapt2");
        if (containsPackage(packages, "aidl")) requirePathNoFollow(new File(prefix, "bin/aidl"), "aidl");
        if (containsPackage(packages, "aapt")) {
            File zipalign = new File(prefix, "bin/zipalign");
            if (!Files.exists(zipalign.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("ARM64 package 'aapt' không còn cung cấp zipalign; core chưa được kích hoạt");
            }
        }
    }

    private static boolean containsPackage(String[] packages, String wanted) {
        if (packages == null) return false;
        for (String item : packages) if (wanted.equals(item)) return true;
        return false;
    }

    private static void requirePathNoFollow(File file, String label) throws IOException {
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Staging thiếu " + label + ": " + file);
        }
    }

    private static void writeDownloadedPackageLock(File root) throws IOException {
        File archives = new File(root, "download-cache/apt/archives");
        File[] debs = archives.listFiles((dir, name) -> name.endsWith(".deb"));
        if (debs == null || debs.length == 0) return;
        java.util.Arrays.sort(debs, java.util.Comparator.comparing(File::getName));
        StringBuilder lock = new StringBuilder();
        for (File deb : debs) {
            lock.append(NetworkFiles.digest(deb, "SHA-256")).append("  ").append(deb.getName()).append('\n');
        }
        try (FileOutputStream out = new FileOutputStream(new File(root, "toolchain-package-lock.sha256"), false)) {
            out.write(lock.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    public static boolean hasCore(File root) {
        return coreReady(root);
    }

    public static File coreMarker(File root) {
        return new File(root, CORE_MARKER);
    }

    private static void writeCoreMarker(File root, CoreManifest manifest) throws IOException {
        String content = "format=2\n"
                + "name=APK PRO ARM64 Core\n"
                + "coreVersion=" + manifest.coreVersion + "\n"
                + "runtimeAbi=" + manifest.abi + "\n"
                + "bootstrapUrl=" + manifest.bootstrapUrl + "\n"
                + "bootstrapSha256=" + manifest.bootstrapSha256 + "\n"
                + "networkProvisioning=first-use-cache\n"
                + "packageVerification=apt-signed-metadata+sha256-lock\n"
                + "jdkBase=17\n"
                + "jdkSigning=17\n";
        try (FileOutputStream out = new FileOutputStream(coreMarker(root), false)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }


    private CoreManifest loadCoreManifest() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = context.getAssets().open(TOOLCHAIN_MANIFEST)) {
            properties.load(input);
        }
        String format = properties.getProperty("format", "").trim();
        String abi = properties.getProperty("abi", "").trim();
        String version = properties.getProperty("coreVersion", "").trim();
        String bootstrapUrl = properties.getProperty("bootstrapUrl", "").trim();
        String bootstrapSha = properties.getProperty("bootstrapSha256", "").trim().toLowerCase(java.util.Locale.US);
        String packageText = properties.getProperty("corePackages", "").trim();
        if (!"1".equals(format)) throw new IOException("Toolchain manifest format không hỗ trợ: " + format);
        if (!"arm64-v8a".equals(abi)) throw new IOException("Toolchain manifest ABI không hợp lệ: " + abi);
        if (version.isEmpty()) throw new IOException("Toolchain manifest thiếu coreVersion");
        if (!bootstrapUrl.startsWith("https://github.com/termux/termux-packages/releases/download/")) {
            throw new IOException("Toolchain manifest bootstrap URL không thuộc upstream Termux release");
        }
        if (!bootstrapSha.matches("[0-9a-f]{64}")) throw new IOException("Toolchain manifest bootstrap SHA-256 không hợp lệ");
        String[] packages = packageText.split("[,\\s]+");
        java.util.List<String> clean = new java.util.ArrayList<>();
        for (String item : packages) if (!item.trim().isEmpty()) clean.add(item.trim());
        String[] required = {"ca-certificates", "ca-certificates-java", "resolv-conf",
                "openjdk-17", "aapt", "aapt2", "aidl"};
        for (String item : required) if (!clean.contains(item)) throw new IOException("Toolchain manifest thiếu package: " + item);
        return new CoreManifest(version, abi, bootstrapUrl, bootstrapSha, clean.toArray(new String[0]));
    }

    private static final class CoreManifest {
        final String coreVersion;
        final String abi;
        final String bootstrapUrl;
        final String bootstrapSha256;
        final String[] corePackages;

        CoreManifest(String coreVersion, String abi, String bootstrapUrl, String bootstrapSha256, String[] corePackages) {
            this.coreVersion = coreVersion;
            this.abi = abi;
            this.bootstrapUrl = bootstrapUrl;
            this.bootstrapSha256 = bootstrapSha256;
            this.corePackages = corePackages;
        }
    }

    private static boolean bootstrapReady(File root) {
        return new File(root, "usr/bin/sh").isFile()
                && new File(root, "usr/bin/apt-get").isFile()
                && new File(root, "usr/bin/dpkg-deb").isFile()
                && new File(root, "usr/bin/curl").isFile()
                && new File(root, "usr/bin/unzip").isFile();
    }

    private static boolean coreReady(File root) {
        return new File(root, "usr/lib/jvm/java-17-openjdk/bin/java").isFile()
                && new File(root, "usr/lib/jvm/java-17-openjdk/bin/javac").isFile()
                && new File(root, "usr/lib/jvm/java-17-openjdk/bin/keytool").isFile()
                && new File(root, "usr/lib/jvm/java-17-openjdk/lib/jspawnhelper").isFile()
                && new File(root, "usr/lib/jvm/java-17-openjdk/lib/security/jssecacerts").isFile()
                && new File(root, "usr/etc/tls/cert.pem").isFile()
                && new File(root, "usr/bin/aapt2").isFile()
                && new File(root, "usr/bin/aidl").isFile()
                && new File(root, "usr/bin/zipalign").isFile();
    }


    private static void recoverInterruptedSwap(File root) throws IOException {
        File live = new File(root, "usr");
        if (live.exists()) return;
        File[] backups = {
                new File(root, "usr.before-core-install"),
                new File(root, "usr.before-bootstrap-install"),
                new File(root, "usr.before-toolchain-import")
        };
        for (File backup : backups) {
            if (!backup.exists()) continue;
            Files.move(backup.toPath(), live.toPath());
            return;
        }
    }

    private static void validateBootstrapPrefix(File prefix) throws IOException {
        requireFile(new File(prefix, "bin/sh"), "bootstrap sh");
        requireFile(new File(prefix, "bin/apt-get"), "bootstrap apt-get");
        requireFile(new File(prefix, "bin/dpkg-deb"), "bootstrap dpkg-deb");
        requireFile(new File(prefix, "bin/curl"), "bootstrap curl");
        requireFile(new File(prefix, "bin/unzip"), "bootstrap unzip");
    }

    private static void validateBootstrap(File root) throws IOException {
        requireFile(new File(root, "usr/bin/sh"), "Termux sh");
        requireFile(new File(root, "usr/bin/apt-get"), "Termux apt-get");
        requireFile(new File(root, "usr/bin/dpkg-deb"), "Termux dpkg-deb");
        requireFile(new File(root, "usr/bin/curl"), "curl");
        requireFile(new File(root, "usr/bin/unzip"), "unzip");
    }

    private static void validateCore(File root) throws IOException {
        requireFile(new File(root, "usr/lib/jvm/java-17-openjdk/bin/java"), "JDK17 java");
        requireFile(new File(root, "usr/lib/jvm/java-17-openjdk/bin/javac"), "JDK17 javac");
        requireFile(new File(root, "usr/lib/jvm/java-17-openjdk/bin/keytool"), "JDK17 keytool");
        requireFile(new File(root, "usr/lib/jvm/java-17-openjdk/lib/jspawnhelper"), "JDK17 jspawnhelper");
        requireFile(new File(root, "usr/lib/jvm/java-17-openjdk/lib/security/jssecacerts"), "Java CA keystore");
        requireFile(new File(root, "usr/etc/tls/cert.pem"), "CA certificates");
        requireFile(new File(root, "usr/bin/aapt2"), "ARM64 aapt2");
        requireFile(new File(root, "usr/bin/aidl"), "ARM64 aidl");
        requireFile(new File(root, "usr/bin/zipalign"), "ARM64 zipalign");
        ensureDirectory(new File(root, "usr/opt/android-sdk/platforms"), "SDK platforms cache");
        ensureDirectory(new File(root, "usr/opt/android-sdk/build-tools"), "SDK build-tools cache");
        ensureDirectory(new File(root, "usr/opt/gradle"), "Gradle distributions cache");
    }

    private static void validateCoreRuntime(File root, BuildListener listener) throws IOException {
        File prefix = new File(root, "usr");
        File javaHome = new File(prefix, "lib/jvm/java-17-openjdk");
        probeExecutable(root, new File(javaHome, "bin/java"), true, "JDK17 java", "-version");
        probeExecutable(root, new File(javaHome, "bin/javac"), true, "JDK17 javac", "-version");
        probeExecutable(root, new File(javaHome, "bin/keytool"), false, "JDK17 keytool", "-help");
        probeExecutable(root, new File(prefix, "bin/aapt2"), true, "ARM64 aapt2", "version");
        probeExecutable(root, new File(prefix, "bin/aidl"), false, "ARM64 aidl", "--version");
        probeExecutable(root, new File(prefix, "bin/zipalign"), false, "ARM64 zipalign", "-h");
        if (listener != null) listener.onLog("Runtime smoke: java/javac/keytool/aapt2/aidl/zipalign khởi chạy OK");
    }

    private static void probeExecutable(
            File root, File executable, boolean requireZero, String label, String... args) throws IOException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.addAll(java.util.Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        File prefix = new File(root, "usr");
        java.util.Map<String, String> env = builder.environment();
        env.put("PREFIX", prefix.getAbsolutePath());
        env.put("HOME", new File(root, "build-home").getAbsolutePath());
        env.put("TMPDIR", new File(root, "build-home/tmp").getAbsolutePath());
        env.put("JAVA_HOME", new File(prefix, "lib/jvm/java-17-openjdk").getAbsolutePath());
        env.put("PATH", prefix.getAbsolutePath() + "/bin:/system/bin:/system/xbin");
        env.remove("LD_LIBRARY_PATH");

        Process process;
        try {
            process = builder.start();
        } catch (IOException startFailure) {
            throw new IOException(label + " không khởi chạy được: " + startFailure.getMessage(), startFailure);
        }
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null && output.length() < 32768) {
                    if (output.length() > 0) output.append('\n');
                    output.append(line);
                }
            } catch (IOException ignored) { }
        }, "apkpro-probe-reader");
        reader.start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(label + " smoke test timeout");
            }
            try { reader.join(1500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("Đã hủy bởi người dùng", e);
            }
            int code = process.exitValue();
            if (requireZero && code != 0) {
                throw new IOException(label + " smoke test exit=" + code + " output=" + trimProbe(output));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Đã hủy bởi người dùng", e);
        }
    }

    private static String trimProbe(StringBuilder output) {
        String value = output == null ? "" : output.toString().trim().replace('\n', ' ');
        if (value.length() > 240) value = value.substring(0, 240) + "…";
        return value.isEmpty() ? "(empty)" : value;
    }

    private void copyEngineAssets(File root) throws IOException {
        File engine = new File(root, "run-build.sh");
        copyAsset(ENGINE_SCRIPT, engine);
        engine.setExecutable(true, false);
        copyAsset(KEYCHECK_SOURCE, new File(root, "SigningKeyCheck.java"));
        File packageScript = new File(root, "provision-packages.sh");
        copyAsset(PACKAGE_SCRIPT, packageScript);
        packageScript.setExecutable(true, false);
    }

    private void copyAsset(String assetPath, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Không tạo được " + parent);
        try (InputStream input = context.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(destination)) {
            IoUtils.copy(input, output);
        }
    }

    private static void restoreBootstrapSymlinks(File prefix) throws IOException {
        File manifest = new File(prefix, "SYMLINKS.txt");
        if (!manifest.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(manifest), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int arrow = line.indexOf('\u2190');
                if (arrow <= 0 || arrow >= line.length() - 1) continue;
                String target = line.substring(0, arrow);
                String relative = line.substring(arrow + 1);
                while (relative.startsWith("./")) relative = relative.substring(2);
                target = target.replace(OLD_PREFIX, NEW_PREFIX).replace(OLD_HOME, NEW_HOME);
                Path link = new File(prefix, relative).toPath();
                Files.createDirectories(link.getParent());
                Files.deleteIfExists(link);
                Files.createSymbolicLink(link, new File(target).toPath());
            }
        } catch (UnsupportedOperationException e) {
            throw new IOException("Android filesystem không tạo được bootstrap symlink", e);
        }
        manifest.delete();
    }

    /** Replace an equal-length absolute prefix inside regular files and symlink targets. */
    private static void relocateTree(File root, String from, String to) throws IOException {
        if (from.length() != to.length()) throw new IOException("Relocation phải cùng độ dài");
        byte[] needle = from.getBytes(StandardCharsets.UTF_8);
        byte[] replacement = to.getBytes(StandardCharsets.UTF_8);
        Files.walkFileTree(root.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) {
                    Path target = Files.readSymbolicLink(file);
                    String value = target.toString();
                    if (value.contains(from)) {
                        Files.delete(file);
                        Files.createSymbolicLink(file, new File(value.replace(from, to)).toPath());
                    }
                } else if (attrs.isRegularFile()) {
                    replaceFixedLength(file.toFile(), needle, replacement);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void replaceFixedLength(File file, byte[] needle, byte[] replacement) throws IOException {
        if (file.length() < needle.length) return;
        final int block = 1024 * 1024;
        byte[] buffer = new byte[block + needle.length - 1];
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long length = raf.length();
            long offset = 0L;
            while (offset < length) {
                raf.seek(offset);
                int want = (int)Math.min(buffer.length, length - offset);
                int count = raf.read(buffer, 0, want);
                if (count <= 0) break;
                int scanLimit = count - needle.length;
                for (int i = 0; i <= scanLimit; i++) {
                    if (!matches(buffer, i, needle)) continue;
                    raf.seek(offset + i);
                    raf.write(replacement);
                    System.arraycopy(replacement, 0, buffer, i, replacement.length);
                }
                if (count <= needle.length) break;
                offset += count - (needle.length - 1);
            }
        }
    }

    private static boolean matches(byte[] data, int offset, byte[] needle) {
        for (int i = 0; i < needle.length; i++) if (data[offset + i] != needle[i]) return false;
        return true;
    }

    private static void prepareExecutables(File root) {
        makeTreeExecutable(new File(root, "usr/bin"));
        makeTreeExecutable(new File(root, "usr/libexec"));
        makeTreeExecutable(new File(root, "usr/lib/apt/methods"));
        makeTreeExecutable(new File(root, "usr/lib/dpkg"));
        File jvmRoot = new File(root, "usr/lib/jvm");
        File[] jvms = jvmRoot.listFiles();
        if (jvms != null) {
            for (File jvm : jvms) {
                if (!jvm.isDirectory() || !jvm.getName().startsWith("java-")) continue;
                makeTreeExecutable(new File(jvm, "bin"));
                makeNamedExecutable(new File(jvm, "lib"), "jspawnhelper");
            }
        }
    }

    private static void makeTreeExecutable(File file) {
        if (file == null || !file.exists()) return;
        if (file.isFile()) {
            file.setExecutable(true, false);
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) makeTreeExecutable(child);
    }

    private static void makeNamedExecutable(File dir, String name) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) makeNamedExecutable(child, name);
            else if (name.equals(child.getName())) child.setExecutable(true, false);
        }
    }

    private static void requireFile(File file, String label) throws IOException {
        if (!file.isFile()) throw new IOException("Toolchain thiếu " + label + ": " + file.getAbsolutePath());
        file.setReadable(true, false);
        if (file.getParentFile() != null && (file.getParent().contains("/bin") || file.getName().equals("jspawnhelper"))) {
            file.setExecutable(true, false);
        }
    }

    private static void ensureDirectory(File dir, String label) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Không tạo được " + label + ": " + dir);
        if (!dir.isDirectory()) throw new IOException(label + " không phải thư mục: " + dir);
    }

    private static void touch(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Không tạo được marker dir");
        if (!file.exists()) {
            try (FileOutputStream out = new FileOutputStream(file)) { out.write('1'); }
        }
        file.setLastModified(System.currentTimeMillis());
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max; i++) {
            int x = i < a.length ? parseLeadingInt(a[i]) : 0;
            int y = i < b.length ? parseLeadingInt(b[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static int parseLeadingInt(String text) {
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') break;
            value = value * 10 + (c - '0');
        }
        return value;
    }
}
