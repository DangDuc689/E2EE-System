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

            TestSupport.assertEquals(1, frame.getFriendCount());
            TestSupport.assertEquals(2, frame.getDisplayedMessageCount());
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
            TestSupport.assertTrue(frame.getMinimumSize().width >= 800,
                    "ChatFrame phai co layout co gian hop ly");
            frame.dispose();
        });
    }
}
