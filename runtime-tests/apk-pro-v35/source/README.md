# APK PRO v36

APK builder chạy trực tiếp trên Android ARM64, tập trung Android 11 (API 30) và Android 12 (API 31).

## App build baseline v36
- JDK runtime cho Gradle: 17
- Android Gradle Plugin: 9.3.2
- Gradle Wrapper: 9.5.0
- Build Tools façade: 36.0.0
- compileSdk: 37
- minSdk: 30
- targetSdk: 33
- versionName/versionCode: `36` / `36`
- applicationId: `com.apkbld`
- namespace: `com.longdev.apkbuilder`

Toolchain baseline này dùng bản stable đã được đối chiếu: AGP 9.3.2 hỗ trợ API 37, yêu cầu/default Gradle 9.5.0, Build Tools 36.0.0 và JDK 17.

## Toolchain nhẹ, tải lần đầu rồi giữ cache
APK/source không nhúng JDK, Android SDK, Gradle hay native ARM64 payload lớn. Manifest `assets/toolchain/arm64-core.properties` pin ABI, bootstrap URL, SHA-256 và core packages.

Fresh install ARM64:
1. tải bootstrap aarch64 nhỏ từ upstream Termux release đã pin SHA-256;
2. bootstrap chỉ làm seed/downloader, không yêu cầu cài app Termux;
3. CA certificates + JDK17 + aapt/aapt2/aidl/zipalign ARM64 được tải vào staging riêng, repack/relocate sang prefix app-private của `com.apkbld`, validate rồi swap có rollback;
4. Google Android SDK platform + phần Java của Build Tools (`apksigner.jar`, `core-lambda-stubs.jar`) tải từ repository Google và kiểm checksum metadata;
5. Gradle tải đúng `distributionUrl` của source từ endpoint Gradle chính thức và bắt buộc SHA-256;
6. AGP/dependency tiếp tục do Gradle của project lấy từ repository project khai báo.

Cache chính nằm trong app-private storage: `files/usr`, `files/download-cache`, `files/gradle-home`. Lần sau dùng lại file đã đủ; xóa cache tải không xóa ARM64 core đang cài.

> Mô hình này không còn fresh-install offline self-contained. Máy mới cần mạng cho lần setup/build đầu. Sau khi toolchain/dependencies cần thiết đã cache, các project tương ứng có thể build offline.


## Tải toolchain chủ động
- Lần đầu mở app, nếu chưa có ARM64 Core, APK PRO hỏi một lần: **Bộ cơ bản**, **Cơ bản + SDK 37**, hoặc **Để sau**.
- Nếu chọn **Để sau**, popup không làm phiền lại; menu `⋯ > Tải toolchain` luôn mở lại cùng lựa chọn.
- Bộ cơ bản gồm JDK17 ARM64, CA certificates, aapt2/aidl/zipalign + thư viện ARM64, và phần Java Build Tools 36 cần cho ký/build.
- Chế độ đầy đủ phổ biến tải thêm `platforms;android-37.0`.
- Gradle/AGP/Kotlin/dependency vẫn không pin sẵn: APK PRO đọc đúng project rồi tải/cache đúng version khi cần.
- Download có retry + resume `.part`; checksum vẫn quyết định PASS, file lỗi không được active.
- Trước khi Core được đánh dấu READY, APK PRO smoke-test runtime `java`, `javac`, `keytool`, `aapt2`, `aidl`, `zipalign`.

## Không đổi behavior source import
- Không auto-upgrade Gradle/AGP/JDK/SDK của project import.
- Project bắt buộc có `gradle-wrapper.properties`; không đọc được wrapper thì dừng.
- Giữ package/signing/UI/build/PATCH workflow cũ ngoài phần provisioning toolchain.
- Toolchain Pack export/import vẫn giữ để backup/migrate core.

## Test status
Static preflight được chạy trước khi đóng ZIP. Android build/runtime/API30/API31/FUNCTIONAL TEST ALL chỉ được ghi PASS khi thực sự chạy; nếu môi trường không có Android SDK/device thì giữ `NOT RUN`.
