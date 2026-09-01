# APK PRO v36 — Lightweight ARM64 toolchain

## Core v2
Core chạy trong app-private `files/usr` và gồm JDK17 + native ARM64 tools cần cho Android build. Không nhúng payload lớn vào APK.

`assets/toolchain/arm64-core.properties` là manifest tin cậy đi cùng APK, hiện pin:
- ABI `arm64-v8a`;
- upstream Termux aarch64 bootstrap release 2026-08-30;
- SHA-256 bootstrap;
- packages `ca-certificates,ca-certificates-java,resolv-conf,openjdk-17,aapt,aapt2,aidl`.

APK PRO không cài/khởi chạy app Termux. Bootstrap/package chỉ được dùng làm upstream ARM64, được tải vào cache/staging và chuyển sang prefix riêng của APK PRO trước khi active.

## First-use provisioning
- Download dùng `.part`, tối đa 3 lần retry, HTTP Range resume có kiểm Content-Range, fsync + atomic finalize.
- Bootstrap được SHA-256 verify trước giải nén.
- ARM64 package download dùng signed APT metadata/package hashes; `.deb` giữ trong app-private download cache.
- Package không `dpkg install` vào `/data/data/com.termux`; chỉ extract vào staging, fixed-length relocate `com.termux` → `com.apkbld`, validate file bắt buộc rồi swap `files/usr` với backup/rollback.
- Nếu tiến trình bị ngắt giữa swap, lần sau có recovery cho các thư mục backup trước khi provision tiếp.
- `toolchain-package-lock.sha256` ghi SHA-256 các package archive đã cache.

## Android SDK / Build Tools
- Platform `android.jar`: Google `repository2-3.xml` + archive checksum.
- Build Tools revision: metadata Google đúng revision.
- `apksigner.jar` + `core-lambda-stubs.jar`: lấy từ Google Build Tools Linux archive vì là Java/architecture-neutral; copy qua `.part` và atomic rename.
- `aapt2`, `aidl`, `zipalign`: ARM64 Android-native từ Core v2.
- Unsupported host-only launcher không giả PASS; gọi vào sẽ exit 127.

## Gradle / dependencies
- Gradle: đúng wrapper của project, chỉ chấp nhận official Gradle distribution URL, bắt buộc SHA-256.
- AGP: Google Maven theo project.
- Java/Kotlin dependencies: Maven Central/repository project.
- Không tự nâng toolchain project sau lỗi build.

## Offline behavior
Fresh install cần mạng. Sau khi core/SDK/Gradle/dependency đã cache, APK PRO tái sử dụng cache và không tự tải lại core chỉ vì có bản mới upstream.
## UI provisioning
- Fresh app data + Core chưa có: hỏi một lần ngay khi mở app.
- `ĐỂ SAU` chỉ bỏ qua setup; build vẫn tự provision khi thật sự cần.
- Menu `Tải toolchain` luôn cho gọi lại thủ công.
- Hai mức: Core ARM64 cơ bản hoặc Core + Android Platform API 37.
- Các thao tác dọn cache/reset/diagnostics/console bị chặn trong lúc provisioning để tránh race với staging/swap.

## Activation gate
Core chỉ được đánh dấu READY sau khi file bắt buộc tồn tại và runtime smoke khởi chạy được: JDK17 `java/javac/keytool`, `aapt2`, `aidl`, `zipalign`. Nếu upstream `aapt` không còn cung cấp `zipalign`, staging fail và live Core cũ được giữ/rollback; không ghi PASS giả.
