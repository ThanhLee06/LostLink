# LostLink

Ứng dụng Android giúp sinh viên **báo mất đồ** và **báo nhặt được đồ** trong khuôn viên trường, kết nối người mất với người nhặt nhanh chóng.

> Đồ án môn Lập trình thiết bị di động.

## Giới thiệu

Mất đồ trong trường là chuyện thường gặp, nhưng thông tin thường bị rải rác ở nhiều nhóm chat và bảng tin. LostLink gom tất cả vào một nơi: đăng tin kèm ảnh, tìm kiếm theo khu vực, nhắn tin trực tiếp với người đăng để xác nhận và nhận lại đồ.

## Tính năng

- Đăng ký, đăng nhập bằng email, quên mật khẩu
- Đăng tin **Mất đồ** hoặc **Tìm thấy** kèm ảnh, mô tả, địa điểm, thông tin liên hệ
- Trang chủ hiển thị tin mới đăng và mẹo tìm lại đồ
- Tìm kiếm theo từ khóa, danh mục, thời gian, khoảng cách
- Xem chi tiết tin, lưu tin, chia sẻ
- Nhắn tin giữa người đăng và người liên hệ
- Quản lý tin của tôi (đang mở, hoàn tất), sửa tin, đánh dấu đã trả đồ
- Hồ sơ cá nhân, thông báo, trợ giúp
- Báo cáo tin vi phạm, xóa tài khoản

> Một số tính năng đang trong quá trình phát triển, xem mục [Tiến độ](#tiến-độ).

## Công nghệ sử dụng

| Thành phần | Công nghệ |
|---|---|
| Ngôn ngữ | Kotlin |
| Giao diện | Jetpack Compose, Material 3 |
| Điều hướng | Navigation Compose |
| Xác thực | Firebase Authentication |
| Cơ sở dữ liệu | Cloud Firestore |
| Lưu ảnh | Firebase Storage |
| Thiết kế | Figma |
| Quản lý mã nguồn | Git, GitHub |

## Thiết kế

Figma: [LostLink](https://www.figma.com/design/6hJItuc71SoK6oubOe7NBH/LostLink)

*(Nếu chưa mở được, hãy xin quyền xem từ nhóm.)*

## Cấu trúc dự án

```
LostLink/
├── docs/ # Tài liệu, thiết kế, báo cáo
└── README.md
```

## Hướng dẫn chạy dự án

### Yêu cầu

- Android Studio bản mới nhất
- JDK 17 trở lên
- Máy ảo (emulator) hoặc điện thoại Android bật USB debugging


## Thành viên nhóm

| Họ tên | MSSV | Vai trò |
|---|---|---|
| Phan Ngọc Thiện | 56206009865 | 
| Lê Xuân Thanh | 067206004491 | 
| Phạm Quốc Thiện | 93206009681 |

Giảng viên hướng dẫn: Trương Quang Tuấn

## Giấy phép

Dự án phục vụ mục đích học tập.
