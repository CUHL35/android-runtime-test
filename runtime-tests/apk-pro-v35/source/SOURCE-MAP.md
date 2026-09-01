# Source map — APK PRO v35

- `app/src/main/java/com/longdev/apkbuilder/MainActivity.java` — UI, source/PATCH flow, first-launch toolchain prompt, menu download, Toolchain Pack import/export.
- `app/src/main/java/com/longdev/apkbuilder/core/ToolchainManager.java` — Core v2 manifest, ARM64 first-use provisioning, staging/validation/rollback/cache.
- `app/src/main/assets/toolchain/arm64-core.properties` — pinned ARM64 bootstrap/core manifest.
- `app/src/main/assets/engine/provision-packages.sh` — signed APT download-only into private cache/staging; never dpkg-installs into Termux app path.
- `app/src/main/java/com/longdev/apkbuilder/core/NetworkFiles.java` — retry/resume/checksum-safe atomic downloads.
- `app/src/main/java/com/longdev/apkbuilder/core/AndroidSdkRepository.java` — Google SDK metadata/platform + Google Java Build Tools artifacts + ARM64 façade.
- `app/src/main/java/com/longdev/apkbuilder/core/ToolchainPackManager.java` — portable core pack, symlink/mode preservation, staged import.
- `app/src/main/java/com/longdev/apkbuilder/core/ToolchainRequirements.java` — inspect compileSdk/Gradle/JDK without guessing.
- `app/src/main/assets/engine/run-build.sh` — exact-wrapper Gradle build, Build Tools façade, debug/release signing verification.
- `app/src/main/java/com/longdev/apkbuilder/core/DiagnosticsRunner.java` — Chẩn đoán / TEST ALL including core/cache/network endpoints.
- `gradle/wrapper/` — Gradle 9.5.0 wrapper + pinned distribution SHA-256 + wrapper source/JAR.
- `release-signing/` — historical signing material preserved unchanged.
