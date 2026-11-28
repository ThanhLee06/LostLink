# Kết quả Kiểm thử Nghiệm thu Task 11 (ST48, ST49, ST50) — Báo cáo bài đăng

- **Ngày thực hiện**: 27/11/2026
- **Môi trường**: Firebase Rules Emulator (`demo-lostlink`, Auth `127.0.0.1:9099`, Firestore `127.0.0.1:8088`) & Android Unit/Instrumentation Tests
- **Lệnh thực thi**:
  ```powershell
  cd tools/rules-tests
  firebase emulators:exec --only auth,firestore --project demo-lostlink --config ../../firebase.emulators.json "npx jest --runInBand --verbose"
  ```

---

## 1. Kết quả chạy Firebase Rules Emulator (`tests/reports.test.js` & toàn bộ Suite)

```text
i  emulators: Starting emulators: auth, firestore
i  emulators: Detected demo project ID "demo-lostlink", emulated services will use a demo configuration and attempts to access non-emulated services for this project will fail.
+  firestore: Firestore Emulator was started in standard edition.
i  Running script: npx jest --runInBand --verbose

PASS tests/reports.test.js (6.628 s)
  reports security rules (B0-T15, ST48, Contract §3.8)
    √ allows verified student to report another user VISIBLE post once and read own report (996 ms)
    √ ST48: enforces verified @ut.edu.vn identity and blocks DELETING/DELETED accounts (382 ms)
    √ denies self-report, non-existent post, tombstoned post, and HIDDEN post (375 ms)
    √ denies spoofed reporterUid, processed status, publicNote, bad reason/ID/timestamp, and overlong description (533 ms)
    √ ST48: denies non-reporter read and all client update/delete/overwrite on PENDING or Admin-processed reports (448 ms)
    √ ST50: end-to-end integration flow — Android submits report -> Admin hides or keeps post -> Reporter & Author states (521 ms)

PASS tests/posts.test.js (30.272 s)
PASS tests/accounts.test.js (6.505 s)
PASS tests/events.test.js
PASS tests/collections.test.js

Test Suites: 5 passed, 5 total
Tests:       66 passed, 66 total
Snapshots:   0 total
Time:        48.719 s
Ran all test suites.
+  Script exited successfully (code 0)
```

---

## 2. Bảng đối chiếu nghiệm thu các kịch bản ST50

| STT | Kịch bản kiểm thử (ST50) | Kỳ vọng | Kết quả thực tế |
|---|---|---|---|
| 1 | Gửi báo cáo hợp lệ (`reports/{postId}_{reporterUid}`) | Tạo thành công với `status: PENDING`, `createdAt: request.time`, `reason` thuộc bộ enum chuẩn | **PASS** |
| 2 | Gửi báo cáo trùng lần hai cho cùng bài viết | Bị từ chối (`PERMISSION_DENIED`) do đụng quy tắc `allow update: if false` trên ID cố định `${postId}_${reporterUid}` | **PASS** |
| 3 | Giả danh `reporterUid` hoặc truyền lệch ID tài liệu | Bị từ chối (`PERMISSION_DENIED`) do bắt buộc `reporterUid == request.auth.uid` và `reportId == postId + '_' + request.auth.uid` | **PASS** |
| 4 | Gửi `status != 'PENDING'` (`RESOLVED_HIDDEN`, `REJECTED`) hoặc truyền `publicNote` | Bị từ chối (`PERMISSION_DENIED`) | **PASS** |
| 5 | Truyền sai `createdAt` (`createdAt != request.time`) hoặc `description > 500` ký tự | Bị từ chối (`PERMISSION_DENIED`) | **PASS** |
| 6 | Phía client tự sửa (`updateDoc`), ghi đè (`setDoc`) hoặc xóa (`deleteDoc`) báo cáo đã tạo / đã được Admin xử lý | Bị từ chối tuyệt đối (`allow update, delete: if false`) | **PASS** |
| 7 | Tài khoản đang ở trạng thái `DELETING` hoặc `DELETED` gửi báo cáo | Bị từ chối (`PERMISSION_DENIED`) bởi `isAccountActive()` | **PASS** |
| 8 | Tích hợp End-to-End: Android gửi báo cáo → Admin giữ bài (`REJECTED`) hoặc ẩn bài (`RESOLVED_HIDDEN` + `posts.moderationStatus = HIDDEN` + `post_moderations`) | Người báo cáo xem được trạng thái phản hồi & ghi chú của Admin; bài bị ẩn biến mất khỏi feed người khác; chủ bài vẫn xem được bài bị ẩn kèm lý do trong **Tin của tôi** nhưng không thể tự khôi phục hiển thị | **PASS** |

---

## 3. Kết quả chạy Unit Test phía Android (`PostDetailReportTest`)

- **Lệnh thực thi**: `.\gradlew.bat testDebugUnitTest`
- **Kết quả**: **BUILD SUCCESSFUL** (100% bài kiểm thử trong `PostDetailReportTest.kt` vượt qua, bao gồm kiểm tra enum lý do, giới hạn 500 ký tự, ẩn nút báo cáo với bài của chính mình, khóa chống bấm gửi nhiều lần, thông báo khi đã báo cáo trước đó, xử lý bài vừa bị xóa/ẩn khi đang thao tác, và hiển thị trạng thái phản hồi từ Admin).
