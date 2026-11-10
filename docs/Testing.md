# Kiểm thử LostLink

## Build, unit test và lint

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Unit test bao gồm giữ loại tin khi mở form, chặn gửi lặp, chờ xác nhận máy chủ, thử lại cùng mã bài, giữ bản nháp khi lỗi, bỏ callback của tài khoản cũ, hợp đồng dữ liệu và giới hạn đọc ảnh.

## Kiểm thử giao diện trên Android Emulator

Khởi động máy ảo rồi chạy:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.lostlink.PostFormSubmissionUiTest"
```

Các test này dùng nguồn dữ liệu giả để kiểm tra tương tác UI: lần đầu mở tin nhặt được, không điều hướng trước khi lưu xong, lỗi lưu/thử lại, tái sử dụng ảnh đã tải và lỗi upload. Không gửi bài hoặc ảnh lên hệ thống thật.

## Kiểm thử SDK Firebase và Security Rules

Yêu cầu Firebase CLI, Node.js và JDK tương thích với CLI (Java 21 trở lên). Dùng project **demo-lostlink**; không thay tên bằng project thật của nhóm.

Trong terminal thứ nhất, tại thư mục repository:

```powershell
firebase emulators:start --only auth,firestore --project demo-lostlink --config firebase.emulators.json
```

Trong terminal thứ hai, với Android Emulator đang chạy:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.lostlink.PostRepositoryEmulatorTest" "-Pandroid.testInstrumentationRunnerArguments.firebaseEmulatorTests=true"
```

Test kết nối Auth tại `10.0.2.2:9099` và Firestore tại `10.0.2.2:8088` qua một FirebaseApp riêng tên `post-test-*`. Chỉ bản debug cho phép HTTP tới địa chỉ emulator. Test tạo tài khoản giả, xác minh trong Auth Emulator, rồi kiểm tra:

- Tạo bài thật bằng SDK và đọc lại theo model HomePost, có timestamp máy chủ.
- Thử lại không tạo thêm document hoặc ghi đè bài đã lưu.
- Email không được lưu vào bài khi tác giả tắt quyền liên hệ.
- Tài khoản chưa xác minh bị cả repository và Firestore Rules từ chối.
- Tài khoản khác không được sửa bài của tác giả.

Các test Firebase được bỏ qua khi không bật `firebaseEmulatorTests=true`, để bộ UI test thông thường không phụ thuộc dịch vụ cục bộ. Dừng emulators bằng Ctrl+C sau khi kiểm tra. Không dùng token, mật khẩu hoặc email thật trong test.

Nếu máy ảo không truy cập được host qua `10.0.2.2`, dùng `adb reverse tcp:9099 tcp:9099` và `adb reverse tcp:8088 tcp:8088` trên thiết bị kiểm thử, rồi thêm `"-Pandroid.testInstrumentationRunnerArguments.firebaseEmulatorHost=127.0.0.1"` vào lệnh Gradle. Khi có nhiều thiết bị, luôn truyền `adb -s <serial>` để chỉ cấu hình đúng máy kiểm thử.
