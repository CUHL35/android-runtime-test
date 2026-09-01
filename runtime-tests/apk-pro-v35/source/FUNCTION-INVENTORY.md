# FUNCTION INVENTORY — APK PRO v35

Every production function below has a matching row in `TEST-MATRIX.md`.

| ID | Production function/path |
|---|---|
| F01 | Select ZIP with Storage Access Framework |
| F02 | Inspect FULL SOURCE ZIP: project root/package/version/key state |
| F03 | Inspect PATCH ZIP + manifest/hash/base version |
| F04 | Resolve cached/manual FULL SOURCE baseline for PATCH |
| F05 | Apply PATCH to temporary source and export patched FULL SOURCE |
| F06 | Build Debug from production source path |
| F07 | Build Release with key embedded in source |
| F08 | Build Release with external JKS/signing info |
| F09 | Create/export new release key only when explicitly selected |
| F10 | Preflight keystore/password/alias before Release build |
| F11 | Verify final Release APK signature |
| F12 | Save APK/source ZIP/key/log/text to Download via MediaStore |
| F13 | Open/install APK through Android package installer |
| F14 | Foreground BuildService progress/cancel/lifecycle |
| F15 | Live command console using same local runtime core |
| F16 | ARM64 Core v2 validation + marker |
| F17 | Export portable Toolchain Core Pack |
| F18 | Import Toolchain Core Pack with staging/validation/rollback |
| F19 | Inspect project compileSdk/Gradle wrapper/Java requirement without changing source |
| F20 | Download/verify required Android SDK Platform from Google repository |
| F21 | Create exact-revision ARM64 Build Tools façade |
| F22 | Download/verify exact project Gradle distribution from official Gradle endpoint |
| F23 | Gradle/AGP/Maven build using imported project's dependency declarations |
| F24 | Chẩn đoán / TEST ALL |
| F25 | Chẩn đoán nhanh/source state checks |
| F26 | Clean transient build files |
| F27 | Clean Gradle/Maven cache |
| F28 | Clean toolchain download cache without deleting active ARM64 core |
| F29 | Reset build session/state |
| F30 | PATCH/RELEASE prompt display/copy workflow |
| F31 | Build log persistence/recovery |
| F32 | Android 11/12 launcher/service/exported/PendingIntent/Download compatibility |
| F33 | Historical package/applicationId/signing material preservation |
| F34 | Gradle wrapper completeness: scripts + properties + JAR + source |
| F35 | Parse/validate embedded ARM64 core manifest |
| F36 | First-use bootstrap download + pinned SHA-256 + private staging activation |
| F37 | ARM64 package download-only cache + signed metadata + staging relocation/repack |
| F38 | Atomic core swap/rollback and interrupted-swap recovery |
| F39 | Download/cache Google apksigner/core-lambda-stubs with repository checksum + atomic copy |
| F40 | Reuse cached core/SDK/Gradle/dependencies; optional newer JDK only when project requires it |
| F41 | First-launch one-time prompt to pre-download toolchain or defer |
| F42 | Main menu `Tải toolchain` reopens manual toolchain download flow at any time |
| F43 | Toolchain prefetch modes: Core ARM64 only, or Core + common Android Platform API 37 |
| F44 | Runtime smoke validation for java/javac/keytool/aapt2/aidl/zipalign before Core READY |
| F45 | HTTP download retry + resume with Range/Content-Range validation + atomic finalize |
| F46 | Resolve compileSdk 37 to published Google platform coordinate `platforms;android-37.0` and accept installed `android-37.0` path |


| F47 | Validate APK PRO app baseline uses currently published stable AGP/Gradle coordinates and pinned Gradle checksum |
