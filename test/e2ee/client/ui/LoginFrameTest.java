package e2ee.client.ui;

import e2ee.common.TestSupport;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;

/**
 * Smoke test cấu trúc cửa sổ đăng nhập Swing.
 */
public final class LoginFrameTest {

    private LoginFrameTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("UI-L1 - Khoi tao LoginFrame tren EDT",
                LoginFrameTest::testCreateOnEdt);
        TestSupport.summary();
    }

    private static void testCreateOnEdt() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            LoginFrame frame = new LoginFrame(new LoginFrame.Actions() {
                @Override
                public void onLogin(String host, int port,
                        String username, char[] passphrase) {
                }

                @Override
                public void onRegister(String host, int port,
                        String username, char[] passphrase) {
                }

                @Override
                public void onClose() {
                }
            });
            TestSupport.assertTrue(frame.getMinimumSize().width >= 400,
                    "LoginFrame phai co kich thuoc toi thieu hop ly");
            TestSupport.assertTrue(frame.getContentPane().getLayout()
                    instanceof java.awt.BorderLayout,
                    "LoginFrame phai dung layout co gian");
            frame.dispose();
        });
    }
}
