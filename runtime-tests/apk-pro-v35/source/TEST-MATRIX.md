# TEST MATRIX — APK PRO v36

Coverage mapping: **47/47 inventory items = 100% mapped**. `NOT RUN` means no claim of runtime/build PASS.

| ID | Test / expected final state | Verification in this delivery | Execution |
|---|---|---|---|
| F01 | SAF selects readable ZIP URI | MainActivity callsite/static | NOT RUN |
| F02 | FULL source root/package/version/key detected | SourceInspector static | NOT RUN |
| F03 | Invalid PATCH manifest/hash fails | PatchManager/SourceInspector static | NOT RUN |
| F04 | Baseline mismatch cannot proceed | BaselineStore/MainActivity static | NOT RUN |
| F05 | PATCH export occurs after apply/verify path | production patch path static | NOT RUN |
| F06 | assembleDebug exit 0 + APK exists | run-build production path | NOT RUN |
| F07 | embedded-key Release path | BuildCoordinator static | NOT RUN |
| F08 | external-key Release path | MainActivity/BuildCoordinator static | NOT RUN |
| F09 | key creation only explicit | ReleaseKeyManager static | NOT RUN |
| F10 | bad key/password/alias fails preflight | SigningKeyCheck path | NOT RUN |
| F11 | Release signature verify required | run-build path | NOT RUN |
| F12 | MediaStore output finishes | DownloadSaver static | NOT RUN |
| F13 | installer intent/unknown-source handling | ApkInstaller static | NOT RUN |
| F14 | foreground/cancel/immutable PendingIntent | BuildService static | NOT RUN |
| F15 | console calls ToolchainManager.ensureReady | CommandCoordinator static | NOT RUN |
| F16 | Core v2 required files + marker consistent | ToolchainManager static | NOT RUN |
| F17 | exported pack metadata/symlink/mode/SHA | ToolchainPackManager static | NOT RUN |
| F18 | import stage/validate/swap/rollback | ToolchainPackManager static | NOT RUN |
| F19 | missing/invalid project toolchain config fails, no guessing | ToolchainRequirements static | NOT RUN |
| F20 | Google platform archive checksum required | AndroidSdkRepository static | NOT RUN |
| F21 | façade maps ARM64 tools; unsupported tools exit 127 | AndroidSdkRepository static | NOT RUN |
| F22 | wrapper URL official + SHA-256 required | run-build static | NOT RUN |
| F23 | exact declared Gradle executes, source not upgraded | run-build static | NOT RUN |
| F24 | TEST ALL checks actual cache/storage/network/build state | DiagnosticsRunner static | NOT RUN |
| F25 | quick diagnostic uses production state | MainActivity static | NOT RUN |
| F26 | transient cleanup isolated | cleanup callsite static | NOT RUN |
| F27 | Gradle/Maven cache cleanup isolated | cleanup callsite static | NOT RUN |
| F28 | download cache cleanup does not delete `files/usr` | cleanup callsite static | NOT RUN |
| F29 | reset does not rewrite project | BuildState/MainActivity static | NOT RUN |
| F30 | PATCH/RELEASE prompt remains | MainActivity static | NOT RUN |
| F31 | log persistence/export path remains | RecoveryManager static | NOT RUN |
| F32 | API30/31 manifest/service/MediaStore preflight | manifest/source static | NOT RUN |
| F33 | package + JKS/signing info unchanged from baseline | final hash/config comparison | STATIC CHECK |
| F34 | wrapper files present/nonzero/JAR integrity | final gate | STATIC CHECK |
| F35 | manifest format/ABI/HTTPS upstream/SHA/core packages validated | ToolchainManager + asset static | STATIC CHECK |
| F36 | bootstrap `.part` download, SHA verify, staging, validation/rollback | NetworkFiles/ToolchainManager static | NOT RUN |
| F37 | APT download-only, no dpkg install, cache retained, staging relocation + package lock | provision script/ToolchainManager static | NOT RUN |
| F38 | swap backup rollback + startup recovery paths exist | ToolchainManager static | NOT RUN |
| F39 | Google Build Tools archive checksum + `.part` atomic Java-artifact copy | AndroidSdkRepository static | NOT RUN |
| F40 | ready core skips download; exact missing SDK/Gradle/JDK provisioned on demand | ToolchainManager/run-build static | NOT RUN |
| F41 | fresh app data + no Core => prompt once; `ĐỂ SAU` does not install | MainActivity preference/callsite static | NOT RUN |
| F42 | menu always exposes `Tải toolchain`; opens same provisioning dialog after first-launch defer | MainActivity menu/callsite static | NOT RUN |
| F43 | basic mode calls `ensureReady`; full mode adds `platforms;android-37.0` without pinning Gradle/AGP | MainActivity/ToolchainManager static | NOT RUN |
| F44 | Core is not marked READY unless required binaries exist and runtime probes start successfully | ToolchainManager static + Java17 core compile | NOT RUN |
| F45 | interrupted/failed HTTP keeps `.part`, retries max 3, validates resumed offset, atomically finalizes | NetworkFiles static + Java17 core compile | NOT RUN |
| F46 | compileSdk 37 resolves Google stable `platforms;android-37.0`, installs under `platforms/android-37.0`, and build preflight accepts the minor `.0` directory | AndroidSdkRepository/run-build static regression | STATIC PASS |

| F47 | app baseline uses published stable AGP 9.3.2 + Gradle 9.5.0 SHA, with no stale 9.4.0/9.6.0 final references | build.gradle/wrapper/verify-source static regression | STATIC PASS |
