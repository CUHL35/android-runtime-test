# Android 11/12 notes — APK PRO v36

- minSdk 30: Android 11 trở lên.
- targetSdk 33: mức tối thiểu để Release lint của toolchain hiện tại chấp nhận; minSdk 30 vẫn giữ hỗ trợ Android 11/12.
- compileSdk 37: chỉ là API compile của toolchain mới, không tự thêm behavior Android mới.
- MainActivity launcher exported=true: hợp yêu cầu Android 12 cho component có intent-filter.
- BuildService exported=false: service nội bộ.
- Notification PendingIntent: FLAG_IMMUTABLE trên API 23+.
- Foreground build service được khởi chạy từ thao tác người dùng trong UI.
- File output Download dùng MediaStore scoped storage.
- Không cần quyền storage legacy.
- POST_NOTIFICATIONS còn trong manifest để source vẫn compile/runtime an toàn trên Android mới nếu người dùng cài nhầm, nhưng API 30/31 không có runtime prompt cho quyền này.

Runtime API30/API31: NOT RUN trong môi trường hiện tại.
