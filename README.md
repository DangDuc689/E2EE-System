# HỆ THỐNG CHAT E2EE QUA INTERNET (PURE JDK 17)

> **Học phần:** Lập trình mạng máy tính  
> **Lớp:** 23DTHA5 — **Mã nhóm:** 04  
> **Đề tài:** Hệ thống chat riêng 1–1 qua Internet với mã hóa đầu cuối (E2EE) và mở rộng Chat nhóm.  
> **Nền tảng:** Java Standard Edition (Pure JDK 17, Apache NetBeans Ant Project).  

---

## 👥 1. THÀNH VIÊN NHÓM & BẢNG ĐÓNG GÓP (CONTRIBUTIONS)

| STT | MSSV | Họ và Tên | Vai trò | Công việc phụ trách | Pull Requests (PRs) đã merge |
| :---: | :---: | :--- | :---: | :---: | :---: |
| 1 | `2380600525` | **Đặng Ngọc Đức** | Trưởng nhóm | Khởi tạo khung dự án NetBeans Ant & Git repo | `commit: abe30bc` |
| 2 | `2380600006` | **Lê Tấn Bình An** | Thành viên | - | - |
| 3 | `2380600293` | **Lê Quang Dũng** | Thành viên | - | - |
| 4 | `2380601447` | **Nguyễn Tài Tuấn Nghĩa** | Thành viên | - | - |
| 5 | `2380602440` | **Huỳnh Ngọc Anh Tuấn** | Thành viên | - | - |

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