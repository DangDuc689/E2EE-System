package e2ee.client.ui;

import e2ee.common.TestSupport;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;

/**
 * Smoke test hộp thoại fingerprint.
 */
public final class FingerprintDialogTest {

    private FingerprintDialogTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("UI-F1 - Khoi tao hop thoai fingerprint tren EDT",
                FingerprintDialogTest::testCreateDialog);
        TestSupport.summary();
    }

    private static void testCreateDialog() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        SwingUtilities.invokeAndWait(() -> {
            JFrame owner = new JFrame();
            FingerprintDialog dialog = new FingerprintDialog(
                    owner, "bob",
                    "AAAA BBBB CCCC DDDD",
                    "1111 2222 3333 4444",
                    true, () -> {
                    });
            TestSupport.assertTrue(dialog.isModal(),
                    "Hop thoai doi soat phai modal");
            TestSupport.assertTrue(dialog.getMinimumSize().width >= 600,
                    "Fingerprint dai phai duoc hien thi ro rang");
            dialog.dispose();
            owner.dispose();
        });
    }
}
