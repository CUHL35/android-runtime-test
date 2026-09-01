# Android Runtime Test

Reusable GitHub Actions workflow for Android 11 (API 30) and Android 12 (API 31).

## What it verifies

Each API runs in an independent `ubuntu-22.04` matrix job. The workflow installs JDK 17, Android command-line tools, `platform-tools`/ADB, emulator, the requested Android platform, and the Google APIs x86_64 system image. It creates an AVD with `avdmanager`, boots it with KVM, prints `adb devices -l`, requires `sys.boot_completed=1`, and verifies the device API level.

If no APK input is supplied, the workflow is a boot-health test. Runtime logs are uploaded for 14 days even when a job fails.

## Reuse from another workflow

Upload the APK in the caller run, then call this workflow. The caller only supplies the artifact/path and optional runtime details; SDK and emulator setup remain inside the reusable workflow.

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: ./gradlew assembleRelease
      - uses: actions/upload-artifact@v4
        with:
          name: app-release
          path: app/build/outputs/apk/release/app-release.apk

  runtime-test:
    needs: build
    uses: CUHL35/android-runtime-test/.github/workflows/android-runtime-test.yml@codex/android-runtime-test
    with:
      apk_artifact_name: app-release
      apk_path: app-release.apk
      package_name: com.example.app
      # Optional exact activity; otherwise a launcher activity is started with monkey.
      launch_component: com.example.app/.MainActivity
      # Optional script stored in the caller repository.
      functional_test_script: runtime-tests/smoke.sh
```

The functional script receives these environment variables:

- `DEVICE_SERIAL`
- `API_LEVEL`
- `APK_PATH`
- `PACKAGE_NAME`

For an APK already committed in the repository, omit `apk_artifact_name` and set `apk_path` to its repo-relative path.

## Runtime behavior with an APK

For each API, the workflow:

1. Resolves the APK and prints SHA-256.
2. Infers the package with `apkanalyzer` unless `package_name` is supplied.
3. Runs `adb install -r -t` and verifies `pm path`.
4. Launches `launch_component`, or uses the package launcher through `monkey`.
5. Runs the optional functional script.
6. Captures logcat and fails on a matching fatal exception, ANR, or security exception.

GitHub-hosted runners are ephemeral. The reusable workflow owns and repeats the required SDK/emulator provisioning on each fresh runner; calling repositories do not duplicate those setup steps.
