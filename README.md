# LostLink

Ứng dụng Android giúp sinh viên **báo mất đồ** và **báo nhặt được đồ** trong khuôn viên trường, kết nối người mất với người nhặt nhanh chóng.

> Đồ án môn Lập trình thiết bị di động.

## Giới thiệu

Mất đồ trong trường là chuyện thường gặp, nhưng thông tin thường bị rải rác ở nhiều nhóm chat và bảng tin. LostLink gom tất cả vào một nơi: đăng tin kèm ảnh, tìm kiếm theo khu vực, nhắn tin trực tiếp với người đăng để xác nhận và nhận lại đồ.

## Tính năng dự kiến

- Đăng ký, đăng nhập bằng email, quên mật khẩu
- Đăng tin **Mất đồ** hoặc **Tìm thấy** kèm ảnh, mô tả, địa điểm, thông tin liên hệ
- Trang chủ hiển thị tin mới đăng và mẹo tìm lại đồ
- Tìm kiếm theo từ khóa, danh mục, thời gian, khoảng cách
- Xem chi tiết tin, lưu tin, chia sẻ
- Nhắn tin giữa người đăng và người liên hệ
- Quản lý tin của tôi (đang mở, hoàn tất), sửa tin, đánh dấu đã trả đồ
- Hồ sơ cá nhân, thông báo, trợ giúp
- Báo cáo tin vi phạm, xóa tài khoản

> Dự án hiện ở giai đoạn khởi tạo giao diện Android; các tính năng trên đang được phát triển.

## Công nghệ sử dụng

| Thành phần | Công nghệ |
|---|---|
| Ngôn ngữ | Kotlin |
| Giao diện | Jetpack Compose, Material 3 |
| Build | Gradle Wrapper 9.6.0, Android Gradle Plugin 9.4.1 |
| Phiên bản Android | minSdk 24, compileSdk/targetSdk 37 |

## Thiết kế

- Figma: [LostLink](https://www.figma.com/design/6hJItuc71SoK6oubOe7NBH/LostLink)
- Ảnh thiết kế trong repository: `docs/Design/Screens/LostLink.png`

## Cài đặt và chạy

### Yêu cầu

- Android Studio có hỗ trợ Android Gradle Plugin 9.4.1
- JDK 17 trở lên
- Android SDK 37
- Máy ảo hoặc điện thoại Android API 24 trở lên

### Các bước thực hiện

1. Clone repository:

   ```bash
   git clone https://github.com/ThanhLee06/LostLink.git
   cd LostLink
   ```

2. Mở thư mục dự án bằng Android Studio.
3. Chờ Gradle đồng bộ và cài Android SDK 37 nếu Android Studio yêu cầu.
4. Chọn thiết bị, sau đó nhấn **Run**.

Có thể kiểm thử và tạo APK debug bằng dòng lệnh:

```powershell
# Windows
.\gradlew.bat test assembleDebug
```

```bash
# macOS/Linux
./gradlew test assembleDebug
```

APK debug được tạo tại `app/build/outputs/apk/debug/app-debug.apk`.

## Cấu trúc dự án

```text
LostLink/
├── app/                    # Mã nguồn ứng dụng Android
├── docs/                   # Tài liệu và thiết kế
├── gradle/                 # Gradle Wrapper và version catalog
├── build.gradle.kts        # Cấu hình build cấp dự án
└── settings.gradle.kts     # Khai báo module và repository
```

## Thành viên nhóm

| Họ tên | MSSV |
|---|---|
| Phan Ngọc Thiện | 56206009865 |
| Lê Xuân Thanh | 067206004491 |
| Phạm Quốc Thiện | 93206009681 |

Giảng viên hướng dẫn: Trương Quang Tuấn

## Giấy phép

Dự án phục vụ mục đích học tập.
