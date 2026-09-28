package com.e2ee.server;

import com.e2ee.common.contract.Envelope;
import com.e2ee.common.contract.Opcode;

/**
 * Điểm khởi chạy của máy chủ E2EE Server (Blind Relay & Key Registry).
 * 
 * RÀNG BUỘC KIẾN TRÚC SỐNG CÒN (Issue 3 - Bình An phụ trách):
 * - Server đóng vai trò Blind Relay (Chuyển tiếp mù).
 * - Server chỉ đọc Metadata của Envelope để chuyển gói (`to`, `groupId`).
 * - TUYỆT ĐỐI KHÔNG import hoặc sử dụng `javax.crypto.Cipher` trong toàn bộ source Server.
 */
public class ServerMain {
    public static final int DEFAULT_PORT = 8888;

    public static void main(String[] args) {
        System.out.println("===============================================================");
        System.out.println("🚀 E2EE CHAT RELAY SERVER (Pure JDK 17)");
        System.out.println("Học phần: Lập trình mạng - Lớp: 23DTHA5 - Nhóm: 04");
        System.out.println("===============================================================");
        System.out.println("[INFO] Khởi tạo máy chủ thành công trên cổng mặc định: " + DEFAULT_PORT);

        // Kiểm tra tính toàn vẹn liên kết với thư viện E2EE-Common
        Envelope probe = Envelope.create(Opcode.PING, "server-core", "broadcast");
        System.out.println("[INFO] Kiểm tra liên kết E2EE-Common DTO: " + probe);
        System.out.println("[INFO] Sẵn sàng triển khai Issue 3 (Socket Thread Pool) & Issue 4 (Key Registry).");
        System.out.println("===============================================================");
    }
}
