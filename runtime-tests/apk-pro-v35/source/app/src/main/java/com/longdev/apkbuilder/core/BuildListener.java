package com.longdev.apkbuilder.core;

public interface BuildListener {
    void onStarted();
    void onLog(String line);
    default void onProgress(int percent, String stage) { }
    void onSuccess(String outputName);
    void onFailure(String message);
    default void onCancelled() { onFailure("Đã hủy bởi người dùng"); }
}
