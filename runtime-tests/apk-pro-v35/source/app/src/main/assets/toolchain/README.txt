APK PRO v36 lightweight ARM64 toolchain.

No JDK/SDK/Gradle/native ARM64 payload is embedded in the APK source.
arm64-core.properties pins the first-use bootstrap URL/SHA-256 and required core packages.
Fresh install downloads into app-private staging/cache, validates, then activates Core v2 with rollback.
No Termux app installation is required.
Google SDK/Build Tools and Gradle use official upstream metadata/distributions with checksum validation.
