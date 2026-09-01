# Recovery notes — APK PRO v35

- Fresh install ARM64 tự setup core khi build/RUN lần đầu; cần mạng.
- Download dùng `.part`; file chưa hoàn tất không được coi là cache hợp lệ.
- Core/package install dùng staging + validation + backup/rollback.
- Khi app bị kill giữa swap và `files/usr` bị thiếu, lần chạy sau thử phục hồi `usr.before-core-install`, `usr.before-bootstrap-install` hoặc `usr.before-toolchain-import` trước khi provision.
- Toolchain Pack export/import vẫn dùng được để backup/migrate core.
- Xóa download cache không xóa `files/usr`; Gradle/SDK/dependency cache có thể tải lại.
- Release signing source key/certificate giữ nguyên baseline v33; không tự tạo key thay thế.
