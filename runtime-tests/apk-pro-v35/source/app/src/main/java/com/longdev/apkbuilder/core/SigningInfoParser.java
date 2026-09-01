package com.longdev.apkbuilder.core;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Parses the signing-info text format used by APK PRO without logging secrets. */
public final class SigningInfoParser {
    private static final int MAX_BYTES = 128 * 1024;

    private SigningInfoParser() { }

    public static final class Info {
        public final String alias;
        public final String storePassword;
        public final String keyPassword;

        public Info(String alias, String storePassword, String keyPassword) {
            this.alias = safe(alias);
            this.storePassword = safe(storePassword);
            this.keyPassword = safe(keyPassword);
        }

        public boolean isComplete() {
            return !storePassword.isEmpty();
        }
    }

    public static Info parseUri(Context context, Uri uri) throws IOException {
        if (context == null || uri == null) throw new IOException("Thiếu file SIGNING-KEY-INFO.txt");
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Không mở được file TXT");
            return parse(in);
        }
    }

    public static Info parseFile(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("Không tìm thấy file signing info");
        if (file.length() > MAX_BYTES) throw new IOException("File signing info quá lớn");
        try (InputStream in = new FileInputStream(file)) {
            return parse(in);
        }
    }

    private static Info parse(InputStream in) throws IOException {
        String alias = "";
        String storePass = "";
        String keyPass = "";
        int bytes = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                bytes += line.length() + 1;
                if (bytes > MAX_BYTES) throw new IOException("File signing info quá lớn");
                line = stripBom(line).trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                int split = firstSeparator(line);
                if (split <= 0) continue;
                String key = normalizeKey(line.substring(0, split));
                String value = line.substring(split + 1).trim();
                if (value.isEmpty()) continue;

                if (isAliasKey(key)) alias = value;
                else if (isStorePasswordKey(key)) storePass = value;
                else if (isKeyPasswordKey(key)) keyPass = value;
            }
        }

        if (storePass.trim().isEmpty()) {
            throw new IOException("Thiếu Store password trong TXT");
        }
        if (keyPass.trim().isEmpty()) keyPass = storePass;
        return new Info(alias, storePass, keyPass);
    }


    private static int firstSeparator(String line) {
        int colon = line.indexOf(':');
        int equals = line.indexOf('=');
        if (colon < 0) return equals;
        if (equals < 0) return colon;
        return Math.min(colon, equals);
    }

    private static String normalizeKey(String value) {
        return value.toLowerCase(Locale.US)
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "")
                .trim();
    }

    private static boolean isAliasKey(String key) {
        return "alias".equals(key) || "keyalias".equals(key) || "ksalias".equals(key);
    }

    private static boolean isStorePasswordKey(String key) {
        return "storepassword".equals(key) || "storepass".equals(key)
                || "keystorepassword".equals(key) || "kspass".equals(key);
    }

    private static boolean isKeyPasswordKey(String key) {
        return "keypassword".equals(key) || "keypass".equals(key);
    }

    private static String stripBom(String value) {
        return value != null && !value.isEmpty() && value.charAt(0) == '\ufeff' ? value.substring(1) : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
