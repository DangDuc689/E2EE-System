package e2ee.client;

import e2ee.client.identity.ClientIdentityStore;
import e2ee.client.security.LocalTrustStore;
import e2ee.client.ui.ChatFrame;
import e2ee.client.ui.LoginFrame;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.IOException;

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

        ClientIdentityStore identityStore = new ClientIdentityStore();
        LocalTrustStore trustStore;
        try {
            // Đọc file trust store ngoài EDT để không làm treo giao diện.
            trustStore = new LocalTrustStore();
        } catch (IOException e) {
            System.err.println("[E2EE-CLIENT] Khong the mo LocalTrustStore: "
                    + e.getMessage());
            return;
        }
        ChatClientController controller = new ChatClientController(
                identityStore, trustStore);

        // Dam bao khoi tao giao dien tren luong EDT
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new ClientApplication(controller).start();
        });
    }

    /**
     * Nối callback của Controller với hai cửa sổ Swing. Lớp này không thực hiện
     * I/O hay crypto; mọi tác vụ nền được giao cho ChatClientController.
     */
    private static final class ClientApplication
            implements ChatClientController.Listener,
            LoginFrame.Actions, ChatFrame.Actions {
        private final ChatClientController controller;
        private LoginFrame loginFrame;
        private ChatFrame chatFrame;
        private boolean shuttingDown;

        private ClientApplication(ChatClientController controller) {
            this.controller = controller;
        }

        private void start() {
            controller.setListener(this);
            loginFrame = new LoginFrame(this);
            loginFrame.setLocationRelativeTo(null);
            loginFrame.setVisible(true);
        }

        @Override
        public void onLogin(String host, int port, String username,
                char[] passphrase) {
            controller.login(host, port, username, passphrase);
        }

        @Override
        public void onRegister(String host, int port, String username,
                char[] passphrase) {
            controller.register(host, port, username, passphrase);
        }

        @Override
        public void onLookupPeer(String username) {
            controller.lookupPeer(username);
        }

        @Override
        public void onSendMessage(String username, String content) {
            controller.sendMessage(username, content);
        }

        @Override
        public void onOpenSecurity(String username) {
            JOptionPane.showMessageDialog(chatFrame,
                    "Đối soát fingerprint của " + username
                    + " sẽ được hoàn thiện trong Issue #14.",
                    "Đối soát bảo mật",
                    JOptionPane.INFORMATION_MESSAGE);
        }

        @Override
        public void onClose() {
            shutdown();
        }

        @Override
        public void onStateChanged(ChatClientController.State state) {
            if (loginFrame != null && loginFrame.isDisplayable()) {
                boolean busy = state == ChatClientController.State.CONNECTING
                        || state == ChatClientController.State.AUTHENTICATING;
                loginFrame.setBusy(busy);
            }
        }

        @Override
        public void onAuthenticated(String username) {
            if (loginFrame != null) {
                loginFrame.setVisible(false);
                loginFrame.dispose();
            }
            chatFrame = new ChatFrame(username, this);
            chatFrame.setLocationRelativeTo(null);
            chatFrame.setVisible(true);
        }

        @Override
        public void onConnectionLost(String message) {
            if (chatFrame != null) {
                chatFrame.showBanner("Mất kết nối: " + message,
                        ChatFrame.BannerType.ERROR);
            }
        }

        @Override
        public void onReconnectScheduled(int attempt, long delayMs,
                String message) {
            if (chatFrame != null) {
                chatFrame.showBanner("Đang kết nối lại lần " + attempt
                        + " sau " + (delayMs / 1000.0) + " giây...",
                        ChatFrame.BannerType.WARNING);
            }
        }

        @Override
        public void onReconnected(String username) {
            if (chatFrame != null) {
                chatFrame.showBanner("Đã kết nối lại an toàn",
                        ChatFrame.BannerType.CONNECTED);
            }
        }

        @Override
        public void onError(String code, String message) {
            String text = code + ": " + message;
            if (loginFrame != null && loginFrame.isDisplayable()) {
                loginFrame.setBusy(false);
                loginFrame.setStatus(text, true);
            } else if (chatFrame != null) {
                chatFrame.showBanner(text, ChatFrame.BannerType.ERROR);
            }
        }

        @Override
        public void onPeerKeyAvailable(String username,
                String fingerprint, boolean verified) {
            if (chatFrame != null) {
                chatFrame.markPeerKeyAvailable(username);
            }
        }

        @Override
        public void onPeerKeyMismatch(String username,
                String newPublicKeyBase64) {
            if (chatFrame != null) {
                chatFrame.updatePresence(username,
                        controller.isPeerOnline(username));
                chatFrame.selectPeer(username);
                chatFrame.setPeerSendAllowed(username, false);
                chatFrame.showBanner(
                        "CẢNH BÁO: Khóa của " + username
                        + " đã thay đổi. Đã khóa gửi tin.",
                        ChatFrame.BannerType.ERROR);
            }
        }

        @Override
        public void onPresenceChanged(String username, boolean online) {
            if (chatFrame != null) {
                chatFrame.updatePresence(username, online);
            }
        }

        @Override
        public void onMessageSent(String recipientUsername, String content,
                String messageId, long timestamp) {
            if (chatFrame != null) {
                chatFrame.addMessage(recipientUsername,
                        content, timestamp, true);
            }
        }

        @Override
        public void onMessageReceived(String senderUsername, String content,
                String messageId, long timestamp) {
            if (chatFrame != null) {
                chatFrame.updatePresence(senderUsername,
                        controller.isPeerOnline(senderUsername));
                chatFrame.addMessage(senderUsername,
                        content, timestamp, false);
            }
        }

        @Override
        public void onSecurityWarning(String username, String message) {
            if (chatFrame != null) {
                chatFrame.showBanner("Cảnh báo bảo mật từ "
                        + username + ": " + message,
                        ChatFrame.BannerType.ERROR);
            }
        }

        private void shutdown() {
            if (shuttingDown) {
                return;
            }
            shuttingDown = true;
            controller.close();
            if (loginFrame != null) {
                loginFrame.dispose();
            }
            if (chatFrame != null) {
                chatFrame.dispose();
            }
        }
    }
}
