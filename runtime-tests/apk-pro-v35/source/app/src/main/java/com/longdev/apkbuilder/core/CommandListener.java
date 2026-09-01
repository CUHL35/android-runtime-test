package com.longdev.apkbuilder.core;

public interface CommandListener {
    void onCommandStarted();
    void onCommandLog(String line);
    void onCommandFinished(int exitCode);
    void onCommandFailure(String message);
}
