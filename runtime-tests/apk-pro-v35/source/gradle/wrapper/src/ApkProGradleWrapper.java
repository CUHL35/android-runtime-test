import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/**
 * Small source-included Gradle bootstrap used by APK PRO's source ZIP.
 * It honors gradle-wrapper.properties, verifies distributionSha256Sum,
 * extracts the requested official Gradle distribution, then launches it.
 */
public final class ApkProGradleWrapper {
    private static final int BUFFER = 64 * 1024;

    public static void main(String[] args) throws Exception {
        File project = new File(System.getProperty("user.dir")).getCanonicalFile();
        File propsFile = new File(project, "gradle/wrapper/gradle-wrapper.properties");
        if (!propsFile.isFile()) fail("Missing " + propsFile);

        Properties p = new Properties();
        try (InputStream in = new FileInputStream(propsFile)) { p.load(in); }
        String urlText = required(p, "distributionUrl");
        String expectedSha = required(p, "distributionSha256Sum").toLowerCase(Locale.US);
        if (!urlText.startsWith("https://services.gradle.org/distributions/")
                && !urlText.startsWith("https://downloads.gradle.org/distributions/")) {
            fail("APK PRO wrapper only accepts official Gradle distribution URLs: " + urlText);
        }

        String fileName = urlText.substring(urlText.lastIndexOf('/') + 1);
        String version = fileName.replaceFirst("^gradle-", "").replaceFirst("-bin\\.zip$", "");
        String gradleHome = System.getenv("GRADLE_USER_HOME");
        if (gradleHome == null || gradleHome.trim().isEmpty()) {
            gradleHome = new File(System.getProperty("user.home"), ".gradle").getAbsolutePath();
        }
        File cache = new File(gradleHome, "wrapper/dists/apkpro/gradle-" + version);
        File zip = new File(cache, fileName);
        File dist = new File(cache, "gradle-" + version);
        File executable = new File(dist, isWindows() ? "bin/gradle.bat" : "bin/gradle");

        if (!executable.isFile()) {
            if (!cache.isDirectory() && !cache.mkdirs()) fail("Cannot create " + cache);
            if (!zip.isFile() || !sha256(zip).equals(expectedSha)) {
                Files.deleteIfExists(zip.toPath());
                download(new URL(urlText), zip);
            }
            String actual = sha256(zip);
            if (!actual.equals(expectedSha)) {
                Files.deleteIfExists(zip.toPath());
                fail("Gradle SHA-256 mismatch. expected=" + expectedSha + " actual=" + actual);
            }
            File stage = new File(cache, "stage-" + System.nanoTime());
            unzip(zip, stage);
            File extracted = new File(stage, "gradle-" + version);
            if (!new File(extracted, "bin/gradle").isFile() && !new File(extracted, "bin/gradle.bat").isFile()) {
                deleteTree(stage.toPath());
                fail("Invalid Gradle distribution: missing bin/gradle");
            }
            if (dist.exists()) deleteTree(dist.toPath());
            Files.move(extracted.toPath(), dist.toPath(), StandardCopyOption.REPLACE_EXISTING);
            deleteTree(stage.toPath());
        }
        executable.setExecutable(true, false);

        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(project);
        pb.inheritIO();
        int rc = pb.start().waitFor();
        System.exit(rc);
    }

    private static void download(URL url, File out) throws Exception {
        URL current = url;
        for (int redirect = 0; redirect < 8; redirect++) {
            URLConnection connection = current.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "APK-PRO-Gradle-Wrapper/33");
            if (connection instanceof HttpURLConnection) {
                HttpURLConnection http = (HttpURLConnection) connection;
                http.setInstanceFollowRedirects(false);
                int code = http.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = http.getHeaderField("Location");
                    if (location == null) fail("Gradle redirect without Location");
                    current = new URL(current, location);
                    http.disconnect();
                    continue;
                }
                if (code < 200 || code >= 300) fail("Gradle download HTTP " + code);
            }
            File tmp = new File(out.getParentFile(), out.getName() + ".part");
            try (InputStream in = connection.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[BUFFER];
                int n;
                while ((n = in.read(buffer)) >= 0) if (n > 0) os.write(buffer, 0, n);
            }
            if (!tmp.renameTo(out)) {
                Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        fail("Too many Gradle download redirects");
    }

    private static void unzip(File zip, File destination) throws Exception {
        if (!destination.mkdirs() && !destination.isDirectory()) fail("Cannot create " + destination);
        String root = destination.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER];
            while ((entry = zin.getNextEntry()) != null) {
                File target = new File(destination, entry.getName());
                String canonical = target.getCanonicalPath();
                if (!canonical.startsWith(root)) fail("Unsafe ZIP entry: " + entry.getName());
                if (entry.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) fail("Cannot create " + target);
                } else {
                    File parent = target.getParentFile();
                    if (!parent.mkdirs() && !parent.isDirectory()) fail("Cannot create " + parent);
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                        int n;
                        while ((n = zin.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) md.update(buffer, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.trim().isEmpty()) fail("Missing wrapper property " + key);
        return value.trim();
    }

    private static boolean isWindows() { return File.separatorChar == '\\'; }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(path -> {
            try { Files.deleteIfExists(path); } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    private static void fail(String message) {
        System.err.println("APK PRO Gradle wrapper: " + message);
        System.exit(1);
        throw new AssertionError(message);
    }
}
