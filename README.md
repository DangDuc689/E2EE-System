# HỆ THỐNG CHAT E2EE QUA INTERNET (PURE JDK 17)

> **Học phần:** Lập trình mạng máy tính  
> **Lớp:** 23DTHA5 — **Mã nhóm:** 04  
> **Đề tài:** Hệ thống chat riêng 1–1 qua Internet với mã hóa đầu cuối (E2EE) và mở rộng Chat nhóm.  
> **Nền tảng:** Java Standard Edition (Pure JDK 17, Apache NetBeans Ant Project).  

---

## 👥 1. THÀNH VIÊN NHÓM & BẢNG ĐÓNG GÓP (CONTRIBUTIONS)

|      MSSV      | Họ và Tên                        | Công việc đã thực hiện                                                                                                                                                                                                               |         Merge Requests         |
| :------------: | :---------------------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :----------------------------: |
| `2380600525` | **Đặng Ngọc Đức**        | Khởi tạo cấu trúc dự án chuẩn JDK 17, Framing TCP & MiniJson; Phát triển hệ thống E2EE Group Chat hoàn chỉnh (Hạ tầng lưu trữ nhóm Server/Client Store, đổi khóa, giao diện tạo/quản lý nhóm Swing & TTL nhóm) | `!2`, `!4`, `!6`,`!25` |
| `2380600006` | **Lê Tấn Bình An**         | Xây dựng Server Socket Core (TCP ThreadPool 50 clients), SessionSnapshot, PublicKeyRegistry, xác thực AUTH Challenge-Response & Presence Dispatcher                                                                                    |        `!17`, `!18`        |
| `2380602440` | **Huỳnh Ngọc Anh Tuấn**    | Phát triển Client Crypto Engine (RSA-2048 OAEP, AES-256-GCM, SHA-256), bọc khóa bí mật bằng passphrase & quản lý danh tính cục bộ (ClientIdentityStore)                                                                        |        `!15`, `!16`        |
| `2380600293` | **Lê Quang Dũng**           | Phát triển module mã hóa nhóm độc lập `GroupCryptoService` (Multi-recipient AES wrap) & Hàng đợi lưu trữ tạm tin nhắn ngoại tuyến phía Server (OfflineMessageQueue, Router & ACK)                                      |        `!19`, `!20`        |
| `2380601447` | **Nguyễn Tài Tuấn Nghĩa** | Lập trình giao diện Java Swing (ChatFrame, LoginFrame), điều phối mạng Client Core (ChatClientController), Trust Store TOFU, đối soát Fingerprint & tin nhắn tự hủy TTL                                                       |        `!21`, `!23`        |


---

## 2. KIẾN TRÚC MÃ NGUỒN HIỆN TẠI

Dự án được tổ chức theo mô hình **Single NetBeans Ant Project (Tất cả trong 1)** với các package phân chia chức năng rõ ràng:

```text
E2EE-System/                       # NetBeans Java SE Application Project gốc
├── .gitignore                      # Cấu hình chặn file nhị phân, build, dist, IDE
├── README.md                       # Tài liệu dự án và theo dõi tiến độ đóng góp
├── build.xml                       # Kịch bản Ant build chuẩn cho NetBeans
├── manifest.mf                     # File thông tin manifest
├── nbproject/                      # Thư mục cấu hình NetBeans Project (JDK 17)
│   ├── project.xml
│   └── project.properties
└── src/                            # Toàn bộ mã nguồn Java của hệ thống
    └── e2ee/
        ├── client/                 # Module Client (Giao diện Java Swing)
        │    └── ClientMain.java
        ├── common/                 # Module Common (DTO, Giao thức, Tiện ích mã hóa)
        │    ├── contract/          # Envelope.java, Opcode.java, PacketChannel.java
        │    └── stub/              # LoopbackChannel.java
        └── server/                 # Module Server (Blind Relay & Quản lý kết nối)
             └── ServerMain.java
```

---

## 3. HƯỚNG DẪN MỞ & BUILD DỰ ÁN TRÊN NETBEANS

### Yêu cầu môi trường
- **JDK:** OpenJDK 17 trở lên.
- **IDE:** Apache NetBeans 17+ (khuyên dùng NetBeans 19, 21 hoặc 22).

### Các bước thực hiện
1. **Mở dự án:** Khởi động NetBeans, chọn **File > Open Project...**, điều hướng tới thư mục gốc `E2EE-System` và ấn **Open Project**. Chỉ hiển thị 1 project cốc cà phê duy nhất là `E2EE-System`.
2. **Khởi chạy Server:** Chuột phải vào file `ServerMain.java` > chọn **Run File (`Shift + F6`)** (hoặc bấm nút **Play (F6)**).
3. **Khởi chạy Client:** Chuột phải vào file `ClientMain.java` > chọn **Run File (`Shift + F6`)**. Có thể chạy nhiều lần để mở nhiều cửa sổ chat client đồng thời.
