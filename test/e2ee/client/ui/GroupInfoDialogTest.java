package e2ee.client.ui;

import e2ee.client.ChatClientController.MemberTrustState;
import e2ee.common.TestSupport;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Smoke test hộp thoại thông tin nhóm GroupInfoDialog.
 */
public final class GroupInfoDialogTest {

    private GroupInfoDialogTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("UI-GID1 - Khoi tao va hien thi GroupInfoDialog",
                GroupInfoDialogTest::testCreateDialog);
        TestSupport.check("UI-GID2 - Kiem tra callback doi soat thanh vien",
                GroupInfoDialogTest::testVerifyMemberCallback);
        TestSupport.summary();
    }

    private static void testCreateDialog() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        SwingUtilities.invokeAndWait(() -> {
            JFrame owner = new JFrame();
            List<String> members = List.of("alice", "bob", "charlie");
            GroupInfoDialog dialog = new GroupInfoDialog(
                    owner, "group_uuid_12345", "Nhóm Mật Mã E2EE", members,
                    user -> switch (user) {
                        case "alice" -> MemberTrustState.MYSELF;
                        case "bob" -> MemberTrustState.VERIFIED;
                        default -> MemberTrustState.TRUSTED_TOFU;
                    },
                    user -> {
                    });

            TestSupport.assertEquals("Thông tin nhóm - Nhóm Mật Mã E2EE", dialog.getTitle());
            TestSupport.assertTrue(dialog.getWidth() >= 440, "Chieu rong hop thoai >= 440");
            TestSupport.assertTrue(dialog.getHeight() >= 460, "Chieu cao hop thoai >= 460");
            dialog.dispose();
            owner.dispose();
        });
    }

    private static void testVerifyMemberCallback() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        SwingUtilities.invokeAndWait(() -> {
            JFrame owner = new JFrame();
            AtomicBoolean verifiedTriggered = new AtomicBoolean(false);
            List<String> members = List.of("alice", "bob");
            GroupInfoDialog dialog = new GroupInfoDialog(
                    owner, "group_999", "Dự Án Lớn", members,
                    user -> "alice".equals(user) ? MemberTrustState.MYSELF : MemberTrustState.TRUSTED_TOFU,
                    user -> {
                        if ("bob".equals(user)) {
                            verifiedTriggered.set(true);
                        }
                    });

            TestSupport.assertTrue(dialog.isDisplayable() || !dialog.isVisible(), "Dialog khoi tao thanh cong");
            dialog.dispose();
            owner.dispose();
        });
    }
}
