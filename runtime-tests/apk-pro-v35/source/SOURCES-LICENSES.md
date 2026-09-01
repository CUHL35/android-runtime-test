# Sources / licenses — APK PRO v36

Source ZIP này không nhúng JDK, Android SDK platform, Gradle distribution hoặc native ARM64 toolchain binary payload lớn.

Downstream/upstream được APK PRO tải khi cần:
- Termux packages/bootstrap: upstream `termux/termux-packages`; từng package giữ license/notice upstream của nó. APK PRO dùng aarch64 bootstrap/package làm nguồn ARM64 rồi repack/relocate vào app-private prefix; không phụ thuộc app Termux.
- Android SDK / Build Tools: Google Android SDK / AOSP licensing theo artifact Google phân phối.
- Android Gradle Plugin / Google artifacts: license đi cùng Google Maven artifacts.
- Gradle distributions: Gradle upstream license.
- Maven dependencies: license của dependency do project import khai báo.

APK PRO không được xóa/thay notice/license upstream trong package/artifact tải về. Toolchain Pack là backup runtime local do người dùng xuất, không nằm sẵn trong source ZIP.
