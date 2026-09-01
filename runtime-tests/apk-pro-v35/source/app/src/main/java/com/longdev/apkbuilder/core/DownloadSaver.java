package com.longdev.apkbuilder.core;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class DownloadSaver {
    private DownloadSaver() {}

    public static final class SavedApk {
        public final String name;
        public final Uri uri;

        SavedApk(String name, Uri uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    public static SavedApk saveApk(Context context, File apk, String outputName) throws IOException {
        Uri uri = saveFile(context, apk, outputName, "application/vnd.android.package-archive");
        return new SavedApk(outputName, uri);
    }

    public static String saveLog(Context context, File log, String outputName) throws IOException {
        saveFile(context, log, outputName, "text/plain");
        return outputName;
    }

    public static String saveZip(Context context, File zip, String outputName) throws IOException {
        saveFile(context, zip, outputName, "application/zip");
        return outputName;
    }

    public static String saveKey(Context context, File key, String outputName) throws IOException {
        saveFile(context, key, outputName, "application/octet-stream");
        return outputName;
    }

    public static String saveText(Context context, File text, String outputName) throws IOException {
        saveFile(context, text, outputName, "text/plain");
        return outputName;
    }

    public static Uri saveFile(Context context, File source, String outputName, String mimeType) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, outputName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Không tạo được file trong Download");
        try {
            try (FileInputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("Không mở được output Download");
                IoUtils.copy(input, output);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        } catch (Throwable error) {
            resolver.delete(uri, null, null);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException(error);
        }
        return uri;
    }
}
