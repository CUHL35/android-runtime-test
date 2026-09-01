package com.longdev.apkbuilder.core;

import android.net.Uri;

public final class SigningData {
    public enum KeySource {
        EXTERNAL_URI,
        EXTERNAL_BUNDLE,
        EMBEDDED_AUTO,
        GENERATE_NEW
    }

    public final KeySource keySource;
    public final Uri keyUri;
    public final String storePassword;
    public final String alias;
    public final String keyPassword;

    private SigningData(KeySource keySource, Uri keyUri, String storePassword, String alias, String keyPassword) {
        this.keySource = keySource;
        this.keyUri = keyUri;
        this.storePassword = storePassword == null ? "" : storePassword;
        this.alias = alias == null ? "" : alias;
        this.keyPassword = keyPassword == null ? "" : keyPassword;
    }

    public static SigningData external(Uri keyUri, String storePassword, String alias, String keyPassword) {
        return new SigningData(KeySource.EXTERNAL_URI, keyUri, storePassword, alias, keyPassword);
    }

    public static SigningData externalBundle(Uri bundleUri) {
        return new SigningData(KeySource.EXTERNAL_BUNDLE, bundleUri, "", "", "");
    }

    public static SigningData embeddedAuto(String storePassword, String alias, String keyPassword) {
        return new SigningData(KeySource.EMBEDDED_AUTO, null, storePassword, alias, keyPassword);
    }

    public static SigningData generateNew() {
        return new SigningData(KeySource.GENERATE_NEW, null, "", "", "");
    }

    public boolean usesEmbeddedKey() {
        return keySource == KeySource.EMBEDDED_AUTO;
    }

    public boolean usesBundle() {
        return keySource == KeySource.EXTERNAL_BUNDLE;
    }

    public boolean generatesNewKey() {
        return keySource == KeySource.GENERATE_NEW;
    }

    public boolean hasUsableKeySelection() {
        return usesEmbeddedKey() || generatesNewKey() || keyUri != null;
    }
}
