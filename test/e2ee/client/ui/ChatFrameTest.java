package e2ee.client.ui;

import e2ee.common.TestSupport;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;

/**
 * Smoke test layout và trạng thái cơ bản của ChatFrame.
 */
public final class ChatFrameTest {

    private ChatFrameTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("UI-C1 - Sidebar, bubble va khoa nut gui",
                ChatFrameTest::testChatState);
        TestSupport.check("UI-C2 - Co gian ChatFrame (mac dinh, phong to, cuc tieu)",
                ChatFrameTest::testResponsiveLayout);
        TestSupport.summary();
    }

    private static void testChatState() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            ChatFrame frame = new ChatFrame("alice", new ChatFrame.Actions() {
                @Override
                public void onLookupPeer(String username) {
                }

                @Override
                public void onSendMessage(String username, String content) {
                }

                @Override
                public void onOpenSecurity(String username) {
                }

                @Override
                public void onClose() {
                }
            });
            frame.updatePresence("bob", true);
            frame.markPeerKeyAvailable("bob");
            frame.addMessage("bob", "Tin gui", 1_000L, true);
            frame.addMessage("bob", "Tin nhan", 2_000L, false);
            frame.addMessage("bob", "Tin tu huy nguoi gui",
                    System.currentTimeMillis(), true, 10);

            // Tin nhan den sau khi da het han: khong duoc them vao danh sach
            long now = System.currentTimeMillis();
            frame.addMessageWithExpiry("bob", "Tin nhan da het han", now - 5_000L, false, now - 1_000L);
            TestSupport.assertEquals(3, frame.getDisplayedMessageCount());

            // Tin nhan den con han: duoc them vao giao dien
            frame.addMessageWithExpiry("bob", "Tin nhan con han nguoi nhan", now, false, now + 10_000L);
            TestSupport.assertEquals(4, frame.getDisplayedMessageCount());

            TestSupport.assertEquals(1, frame.getFriendCount());
            TestSupport.assertTrue(frame.isSendEnabled(),
                    "Nut gui phai bat khi co peer hop le");

            frame.setPeerSendAllowed("bob", false);
            TestSupport.assertTrue(!frame.isSendEnabled(),
                    "Nut gui phai khoa khi peer khong an toan");
            frame.setPeerSecurityWarning("bob", true);
            TestSupport.assertTrue(frame.isSecurityWarningVisible(),
                    "Khung chat phai doi mau khi khoa peer thay doi");
            frame.setPeerSecurityWarning("bob", false);
            frame.setPeerSendAllowed("bob", true);
            TestSupport.assertTrue(!frame.isSecurityWarningVisible(),
                    "Canh bao phai duoc go sau khi doi soat");
            TestSupport.assertTrue(frame.isSendEnabled(),
                    "Nut gui phai mo lai sau khi doi soat");
            frame.selectTtlForTest(10);
            TestSupport.assertEquals(10,
                    frame.getSelectedTtlForTest());
            frame.expireMessagesForTest(Long.MAX_VALUE);
            TestSupport.assertEquals(2, frame.getDisplayedMessageCount());
            frame.updatePresence("dũng", true);
            frame.updatePresence("hoa", true);
            TestSupport.assertEquals(3, frame.getFriendCount());
            TestSupport.assertEquals(3, frame.getDisplayedFriendCount());

            frame.setFriendFilterForTest("dung");
            TestSupport.assertEquals(1, frame.getDisplayedFriendCount());

            frame.setFriendFilterForTest("HÒA");
            TestSupport.assertEquals(1, frame.getDisplayedFriendCount());

            frame.setFriendFilterForTest("");
            TestSupport.assertEquals(3, frame.getDisplayedFriendCount());
            TestSupport.assertEquals(3, frame.getFriendCount());

            TestSupport.assertTrue(frame.getMinimumSize().width >= 800,
                    "ChatFrame phai co layout co gian hop ly");
            frame.dispose();
        });
    }

    private static void testResponsiveLayout() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            ChatFrame frame = new ChatFrame("alice", new ChatFrame.Actions() {
                @Override
                public void onLookupPeer(String username) {}

                @Override
                public void onSendMessage(String username, String content) {}

                @Override
                public void onOpenSecurity(String username) {}

                @Override
                public void onClose() {}
            });
            frame.updatePresence("bob", true);
            frame.markPeerKeyAvailable("bob");
            frame.addMessage("bob", "Tin ngắn", 1_000L, true);
            frame.addMessage("bob",
                    "Một đoạn tin nhắn rất dài nhằm kiểm tra khả năng co giãn linh hoạt "
                    + "của bong bóng tin nhắn theo chiều rộng khung hội thoại, "
                    + "tự động ngắt dòng và giữ nguyên giao diện.", 2_000L, false);

            // 1. Kiểm tra kích thước mặc định
            frame.setSize(1040, 680);
            frame.doLayout();
            TestSupport.assertTrue(frame.getWidth() == 1040 && frame.getHeight() == 680,
                    "Kích thước mặc định phải là 1040x680");
            TestSupport.assertTrue(frame.isSendEnabled(),
                    "Khung soạn tin phải sẵn sàng ở kích thước mặc định");

            // 2. Kiểm tra kích thước phóng to
            frame.setSize(1400, 900);
            frame.doLayout();
            TestSupport.assertTrue(frame.getWidth() == 1400 && frame.getHeight() == 900,
                    "Kích thước phóng to phải là 1400x900");
            TestSupport.assertTrue(frame.isSendEnabled(),
                    "Nút gửi phải sẵn sàng khi phóng to");

            // 3. Kiểm tra kích thước thu nhỏ cực tiểu
            frame.setSize(820, 560);
            frame.doLayout();
            TestSupport.assertTrue(frame.getWidth() == 820 && frame.getHeight() == 560,
                    "Kích thước thu nhỏ phải là 820x560");
            TestSupport.assertTrue(frame.isSendEnabled(),
                    "Nút gửi và composer phải sẵn sàng khi thu nhỏ cực tiểu");

            frame.dispose();
        });
    }
}
