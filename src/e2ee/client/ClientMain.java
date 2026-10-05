package e2ee.client;

import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Điểm khởi chạy của ứng dụng giao diện E2EE Client (Java Swing).
 * 
 * NGUYÊN TẮC LUỒNG GIAO DIỆN (Issue 9 - Nghĩa phụ trách):
 * - Toàn bộ tác vụ I/O mạng và giải mã Crypto chạy ngoài EDT (Event Dispatch Thread).
 * - Cập nhật giao diện Swing bắt buộc thông qua SwingUtilities.invokeLater.
 */
public class ClientMain {
    public static void main(String[] args) {
        System.out.println("===============================================================");
        System.out.println("[E2EE-CLIENT] KHOI CHAY CHAT CLIENT (Java Swing - Pure JDK 17)");
        System.out.println("Hoc phan: Lap trinh mang - Lop: 23DTHA5 - Nhom: 04");
        System.out.println("===============================================================");

        // Dam bao khoi tao giao dien tren luong EDT
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }

            // Kiem tra tinh toan ven lien ket voi thu vien E2EE-Common
            Envelope initEnv = Envelope.create(Opcode.AUTH, "client-init", "server");
            System.out.println("[INFO] Kiem tra lien ket E2EE-Common DTO: " + initEnv);
            System.out.println("[INFO] San sang trien khai Issue 9 (Swing UI Core) & Issue 10 (Security Dialog).");
            System.out.println("===============================================================");
        });
    }
}
