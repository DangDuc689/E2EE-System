package com.e2ee.client;

import com.e2ee.common.contract.Envelope;
import com.e2ee.common.contract.Opcode;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Điểm khởi chạy của ứng dụng giao diện E2EE Client (Java Swing).
 * 
 * NGUYÊN TẮC LUỒNG GIAO DIỆN (Issue 9 - Tuấn phụ trách):
 * - Toàn bộ tác vụ I/O mạng và giải mã Crypto chạy ngoài EDT (Event Dispatch Thread).
 * - Cập nhật giao diện Swing bắt buộc thông qua SwingUtilities.invokeLater.
 */
public class ClientMain {
    public static void main(String[] args) {
        System.out.println("===============================================================");
        System.out.println("💬 E2EE CHAT CLIENT (Java Swing - Pure JDK 17)");
        System.out.println("Học phần: Lập trình mạng - Lớp: 23DTHA5 - Nhóm: 04");
        System.out.println("===============================================================");

        // Đảm bảo khởi tạo giao diện trên luồng EDT
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }

            // Kiểm tra tính toàn vẹn liên kết với thư viện E2EE-Common
            Envelope initEnv = Envelope.create(Opcode.AUTH, "client-init", "server");
            System.out.println("[INFO] Kiểm tra liên kết E2EE-Common DTO: " + initEnv);
            System.out.println("[INFO] Sẵn sàng triển khai Issue 9 (Swing UI Core) & Issue 10 (Security Dialog).");
            System.out.println("===============================================================");
        });
    }
}
