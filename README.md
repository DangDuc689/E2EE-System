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

## 🏗️ 2. KIẾN TRÚC MÃ NGUỒN HIỆN TẠI

Dự án hiện tại gồm 3 module NetBeans Ant cơ bản được cấu hình liên kết một chiều:

```text
E2EE-System/
├── .gitignore                    # Cấu hình chặn file nhị phân, build, dist, IDE
├── README.md                     # Tài liệu dự án và theo dõi tiến độ đóng góp
│
├── E2EE-Common/                  # Java Class Library (Dùng chung giữa Client & Server)
│   ├── nbproject/
│   └── src/
│
├── E2EE-Server/                  # Java Application (Phần mềm máy chủ Relay)
│   ├── nbproject/                # Project Reference trỏ sang ../E2EE-Common
│   └── src/
│
└── E2EE-Client/                  # Java Application (Phần mềm giao diện Swing)
    ├── nbproject/                # Project Reference trỏ sang ../E2EE-Common
    └── src/
```

---

## 3. HƯỚNG DẪN MỞ & BUILD DỰ ÁN TRÊN NETBEANS

### Yêu cầu môi trường
- **JDK:** OpenJDK 17 trở lên.
- **IDE:** Apache NetBeans 17+ (khuyên dùng NetBeans 19, 21 hoặc 22).

### Các bước thực hiện
1. **Mở dự án:** Khởi động NetBeans, chọn **File > Open Project...**, điều hướng tới thư mục gốc và mở đồng thời cả 3 module: `E2EE-Common`, `E2EE-Server`, `E2EE-Client`.
2. **Build thư viện chung:** Chuột phải vào `E2EE-Common` > chọn **Clean and Build** *(bắt buộc thực hiện trước để sinh file thư viện phụ thuộc)*.
3. **Khởi chạy ứng dụng:** Chuột phải vào `E2EE-Server` hoặc `E2EE-Client` > chọn **Run** (hoặc **Clean and Build**).