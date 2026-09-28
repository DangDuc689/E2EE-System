# 🛡️ HỆ THỐNG CHAT E2EE QUA INTERNET (PURE JDK 17)

> **Học phần:** Lập trình mạng máy tính  
> **Lớp:** 23DTHA5 — **Mã nhóm:** 04  
> **Đề tài:** Hệ thống chat riêng 1–1 qua Internet với mã hóa đầu cuối (E2EE) và mở rộng Chat nhóm.  
> **Nền tảng:** Java Standard Edition (Pure JDK 17, Apache NetBeans Ant Project).  

---

## 👥 1. BẢNG MA TRẬN ĐÓNG GÓP (CONTRIBUTIONS MATRIX)

> *(Cập nhật theo đúng **Mục 6 — Tài liệu hướng dẫn quy trình và ràng buộc thực hiện dự án trên Git/GitHub** phục vụ chấm thi cuối kỳ)*

| MSSV | Họ và Tên | Công việc đã thực hiện | Merge Requests |
| :---: | :--- | :--- | :---: |
| `2380600525` | **Đặng Ngọc Đức** *(Trưởng nhóm)* | Setup NetBeans Ant, Contract Day, Framing TCP & MiniJson parser thuần JDK | `!1`, `!2` |
| `2380600006` | **Lê Tấn Bình An** | Backend Socket Server, Thread Pool, Key Registry & AUTH Challenge-Response | `!3`, `!4` |
| `2380600293` | **Lê Quang Dũng** | Crypto Engine (RSA-OAEP, AES-GCM), Anti-Replay Engine & Key Fingerprint | `!5`, `!6` |
| `2380601447` | **Nguyễn Tài Tuấn Nghĩa** | Group Chat E2EE Engine (Multi-recipient), Store-and-Forward Offline Queue | `!7`, `!8` |
| `2380602440` | **Huỳnh Ngọc Anh Tuấn** | Java Swing UI Core, Security Dialog đối soát khóa & Bộ đếm tự hủy (TTL) xóa RAM | `!9`, `!10` |

---

## 🏗️ 2. KIẾN TRÚC HỆ THỐNG & CẤU TRÚC THƯ MỤC

Dự án được phân tách thành 3 module NetBeans Ant theo nguyên lý phụ thuộc một chiều:

```text
E2EE-System/
├── .gitignore                    # Chặn build/, dist/, *.jar, .idea, .vscode
├── README.md                     # Bảng đóng góp MSSV và quy trình kỹ thuật
│
├── E2EE-Common/                  # Java Class Library (DTO, Framing, MiniJson, Protocol)
│   ├── nbproject/
│   └── src/com/e2ee/common/
│       ├── contract/             # Opcode, Envelope, PacketChannel
│       └── stub/                 # LoopbackChannel (Stub test in-memory)
│
├── E2EE-Server/                  # Java Application (Blind Relay Server - không chứa Cipher)
│   ├── nbproject/                # Project Reference trỏ sang ../E2EE-Common
│   └── src/com/e2ee/server/
│
└── E2EE-Client/                  # Java Application (Client giao diện Java Swing)
    ├── nbproject/                # Project Reference trỏ sang ../E2EE-Common
    └── src/com/e2ee/client/
```

### Nguyên tắc liên kết thư viện (Project Reference)
- `E2EE-Server` và `E2EE-Client` liên kết trực tiếp với `E2EE-Common` qua đường dẫn tương đối `../E2EE-Common`.
- Khi Clean & Build Server hoặc Client trên NetBeans, hệ thống tự động build `E2EE-Common` sinh ra file `dist/E2EE-Common.jar`.
- Thư mục `dist/` nằm trong `.gitignore`, đảm bảo **100% không commit file `.jar` lên Git**.

---

## 📌 3. QUY TRÌNH & RÀNG BUỘC KỸ THUẬT TRÊN GIT/GITHUB

### 3.1. Thiết lập định danh cá nhân (Bắt buộc)
Mỗi sinh viên bắt buộc phải cấu hình Git trên máy cá nhân khớp với danh sách lớp:
```bash
git config --global user.name "MSSV_HoVaTen"
git config --global user.email "email_sinh_vien@domain"
```

### 3.2. Quản lý công việc bằng Issue
- Mọi đầu việc (code, fix bug, tài liệu) đều phải tạo Issue tương ứng trên GitHub trước khi viết code.
- Mỗi Issue chỉ gán (Assign) cho **ĐÚNG 1 NGƯỜI** chịu trách nhiệm chính và phải gắn Label + Milestone tuần tương ứng.

### 3.3. Chiến lược phân nhánh (Branching Strategy)
- Nhánh `main` được bảo vệ (Protected). Nghiêm cấm push trực tiếp lên `main`.
- Khi giải quyết Issue, sinh viên checkout nhánh mới từ `main` theo cú pháp chuẩn:
  ```bash
  feature/<MSSV>-issue-<Số_Issue>-<Tên_ngắn_gọn>
  ```
  *Ví dụ:* `feature/2380600525-issue-1-project-setup`

### 3.4. Tiêu chuẩn Commit (Conventional Commits)
- Cấu trúc thông điệp commit: `<loại>: <mô tả ngắn gọn>`
- Các loại hợp lệ: `feat:` (tính năng mới), `fix:` (sửa lỗi), `refactor:` (tối ưu code), `docs:` (tài liệu).
- *Ví dụ:* `feat: khởi tạo khung giao thức envelope và opcode v1`

### 3.5. Quy trình Merge Request / Pull Request (MR/PR)
- Mô tả MR/PR phải nêu rõ: Đã làm gì? Ảnh hưởng module nào? Kèm ảnh chụp màn hình chứng minh code chạy thành công.
- **Peer Review bắt buộc:** Phải có ít nhất 1 thành viên khác trong nhóm review và Approve. Nghiêm cấm tự tạo MR và tự merge.

---

## ⚙️ 4. HƯỚNG DẪN BUILD & CHẠY DỰ ÁN TRÊN NETBEANS

### Yêu cầu môi trường
- **JDK:** OpenJDK 17 trở lên (đã kiểm tra tương thích JDK 17, JDK 21, JDK 22).
- **IDE:** Apache NetBeans 17+ (khuyên dùng NetBeans 19, 21 hoặc 22).

### Các bước mở và build dự án
1. Khởi động Apache NetBeans.
2. Chọn **File > Open Project...**
3. Điều hướng tới thư mục `E2EE-System/`, chọn cả 3 dự án:
   - `E2EE-Common`
   - `E2EE-Server`
   - `E2EE-Client`
4. Click chuột phải vào `E2EE-Common` > chọn **Clean and Build**.
5. Click chuột phải vào `E2EE-Server` > chọn **Clean and Build** (hoặc **Run**).
6. Click chuột phải vào `E2EE-Client` > chọn **Clean and Build** (hoặc **Run**).

---

## 📅 5. BỐN CỘT MỐC ĐỒ ÁN (MILESTONES)
- **Milestone 1 (Tuần 1–2):** Nền móng dự án, NetBeans Ant Setup, Contract Day v1, Framing TCP 4 bytes & Parser MiniJson thuần JDK.
- **Milestone 2 (Tuần 3–5):** AUTH Challenge-Response bằng chữ ký số, Tra cứu Public Key, Chat 1-1 E2EE qua Internet, Anti-Replay & Fingerprint.
- **Milestone 3 (Tuần 6–7):** Chat nhóm E2EE Multi-recipient RSA, Hàng đợi Store-and-Forward (Offline sync), Tin nhắn tự hủy xóa RAM (TTL).
- **Milestone 4 (Tuần 8–9):** Kiểm thử chịu tải 20 clients đồng thời, kịch bản Wireshark demo bảo vệ, đóng băng mã nguồn `v1.0`.
