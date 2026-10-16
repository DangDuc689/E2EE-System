package e2ee.client.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Objects;

/**
 * Hộp thoại đối soát fingerprint qua một kênh ngoài đáng tin cậy.
 */
public final class FingerprintDialog extends JDialog {
    private static final Color SAFE = new Color(22, 101, 52);
    private static final Color WARNING = new Color(185, 28, 28);

    public FingerprintDialog(JFrame owner, String peerUsername,
            String myFingerprint, String peerFingerprint,
            boolean keyChanged, Runnable confirmAction) {
        super(owner, "Đối soát bảo mật - " + peerUsername, true);
        Objects.requireNonNull(confirmAction,
                "confirmAction cannot be null");
        initializeUi(peerUsername, myFingerprint, peerFingerprint,
                keyChanged, confirmAction);
    }

    private void initializeUi(String peerUsername, String myFingerprint,
            String peerFingerprint, boolean keyChanged,
            Runnable confirmAction) {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(600, 420));
        setSize(650, 460);
        setLocationRelativeTo(getOwner());

        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        JLabel heading = new JLabel(keyChanged
                ? "CẢNH BÁO: KHÓA BẢO MẬT ĐÃ THAY ĐỔI"
                : "Đối soát khóa mã hóa đầu cuối");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        heading.setForeground(keyChanged ? WARNING : SAFE);
        root.add(heading, BorderLayout.NORTH);

        JPanel fingerprints = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1.0;
        constraints.insets = new Insets(6, 0, 6, 0);

        constraints.gridy = 0;
        fingerprints.add(new JLabel("Vân tay khóa của tôi:"), constraints);
        constraints.gridy = 1;
        fingerprints.add(createFingerprintArea(myFingerprint), constraints);
        constraints.gridy = 2;
        constraints.insets = new Insets(18, 0, 6, 0);
        fingerprints.add(new JLabel("Vân tay khóa của "
                + peerUsername + ":"), constraints);
        constraints.gridy = 3;
        constraints.insets = new Insets(6, 0, 6, 0);
        fingerprints.add(createFingerprintArea(peerFingerprint), constraints);
        constraints.gridy = 4;
        constraints.insets = new Insets(16, 0, 6, 0);
        JLabel guidance = new JLabel(
                "Hãy đọc và so sánh hai vân tay qua cuộc gọi hoặc gặp trực tiếp.");
        guidance.setForeground(new Color(71, 85, 105));
        fingerprints.add(guidance, constraints);
        root.add(fingerprints, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new BorderLayout(8, 0));
        JButton cancelButton = new JButton("Đóng");
        JButton confirmButton = new JButton(
                "Đã đối soát an toàn qua kênh ngoài");
        confirmButton.setBackground(new Color(37, 99, 235));
        confirmButton.setForeground(Color.WHITE);
        cancelButton.addActionListener(event -> dispose());
        confirmButton.addActionListener(event -> {
            confirmAction.run();
            dispose();
        });
        buttons.add(cancelButton, BorderLayout.WEST);
        buttons.add(confirmButton, BorderLayout.EAST);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private static JScrollPane createFingerprintArea(String fingerprint) {
        JTextArea area = new JTextArea(
                fingerprint == null || fingerprint.isBlank()
                        ? "Chưa có khóa" : fingerprint);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setRows(2);
        area.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        area.setBackground(new Color(241, 245, 249));
        area.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JScrollPane scrollPane = new JScrollPane(area);
        scrollPane.setBorder(BorderFactory.createLineBorder(
                new Color(203, 213, 225)));
        return scrollPane;
    }
}
