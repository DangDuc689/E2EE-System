package e2ee.server;

import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;

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
        System.out.println("[E2EE-SERVER] KHOI CHAY CHAT RELAY SERVER (Pure JDK 17)");
        System.out.println("Hoc phan: Lap trinh mang - Lop: 23DTHA5 - Nhom: 04");
        System.out.println("===============================================================");
        System.out.println("[INFO] Khoi tao may chu thanh cong tren cong mac dinh: " + DEFAULT_PORT);

        // Kiem tra tinh toan ven lien ket voi thu vien E2EE-Common
        Envelope probe = Envelope.create(Opcode.PING, "server-core", "broadcast");
        System.out.println("[INFO] Kiem tra lien ket E2EE-Common DTO: " + probe);
        System.out.println("[INFO] San sang trien khai Issue 3 (Socket Thread Pool) & Issue 4 (Key Registry).");
        System.out.println("===============================================================");
    }
}
