package e2ee.client.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Hộp thoại tạo nhóm chat mới với mã hóa E2EE (AES-256-GCM + RSA-2048 OAEP).
 * Cho phép đặt tên nhóm, chọn thành viên và kích hoạt phân phối khóa nhóm.
 */
public final class CreateGroupDialog extends JDialog {
    private static final Color PRIMARY = new Color(37, 99, 235);
    private static final Color PRIMARY_HOVER = new Color(29, 78, 216);
    private static final Color BG_WHITE = Color.WHITE;
    private static final Color BORDER_COLOR = new Color(226, 232, 240);
    private static final Color TEXT_DARK = new Color(15, 23, 42);
    private static final Color TEXT_MUTED = new Color(100, 116, 139);
    private static final Color ONLINE_COLOR = new Color(34, 197, 94);
    private static final Color OFFLINE_COLOR = new Color(148, 163, 184);

    public interface GroupCreateCallback {
        void onGroupCreated(String groupName, List<String> memberUsernames);
    }

    private final JTextField groupNameField = new JTextField() {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(TEXT_MUTED);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                Insets insets = getInsets();
                int x = insets != null ? insets.left : 6;
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString("Nhập tên nhóm chat...", x, y);
                g2.dispose();
            }
        }
    };

    private final JLabel selectedCountLabel = new JLabel("Đã chọn 0 thành viên");
    private final List<MemberCheckRow> memberRows = new ArrayList<>();
    private final GroupCreateCallback callback;

    public CreateGroupDialog(JFrame owner, List<ChatFrame.FriendItem> availableFriends,
                             GroupCreateCallback callback) {
        super(owner, "Tạo nhóm chat mới (E2EE Group)", true);
        this.callback = Objects.requireNonNull(callback, "callback cannot be null");
        initializeUi(availableFriends != null ? availableFriends : Collections.emptyList());
    }

    private void initializeUi(List<ChatFrame.FriendItem> availableFriends) {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(520, 560));
        setSize(560, 600);
        setLocationRelativeTo(getOwner());

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(BG_WHITE);
        root.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        // 1. Header: Icon nhóm vector + Tiêu đề
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        headerPanel.setOpaque(false);
        JLabel iconLabel = new JLabel(new GroupIcon(PRIMARY, 22));
        JLabel titleLabel = new JLabel("Tạo nhóm chat mới (E2EE Group)");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 17f));
        titleLabel.setForeground(TEXT_DARK);
        headerPanel.add(iconLabel);
        headerPanel.add(titleLabel);
        root.add(headerPanel, BorderLayout.NORTH);

        // 2. Nội dung chính: Tên nhóm + Danh sách thành viên + Hộp thông tin E2EE
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setOpaque(false);

        // 2.1. Ô nhập tên nhóm
        JLabel nameLabel = new JLabel("Tên nhóm chat:");
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));
        nameLabel.setForeground(TEXT_DARK);
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel nameInputBox = new RoundedBorderPanel(10, BG_WHITE, BORDER_COLOR);
        nameInputBox.setLayout(new BorderLayout());
        nameInputBox.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        nameInputBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        nameInputBox.setPreferredSize(new Dimension(500, 42));
        nameInputBox.setAlignmentX(Component.LEFT_ALIGNMENT);

        groupNameField.setBorder(BorderFactory.createEmptyBorder());
        groupNameField.setOpaque(false);
        groupNameField.setFont(groupNameField.getFont().deriveFont(Font.PLAIN, 14f));
        groupNameField.setForeground(TEXT_DARK);
        nameInputBox.add(groupNameField, BorderLayout.CENTER);

        contentPanel.add(nameLabel);
        contentPanel.add(javax.swing.Box.createVerticalStrut(6));
        contentPanel.add(nameInputBox);
        contentPanel.add(javax.swing.Box.createVerticalStrut(14));

        // 2.2. Hàng tiêu đề chọn thành viên: "Chọn thành viên tham gia:" + "Đã chọn X thành viên"
        JPanel memberHeader = new JPanel(new BorderLayout());
        memberHeader.setOpaque(false);
        memberHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        memberHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

        JLabel selectLabel = new JLabel("Chọn thành viên tham gia:");
        selectLabel.setFont(selectLabel.getFont().deriveFont(Font.BOLD, 13f));
        selectLabel.setForeground(TEXT_DARK);

        selectedCountLabel.setFont(selectedCountLabel.getFont().deriveFont(Font.BOLD, 12.5f));
        selectedCountLabel.setForeground(PRIMARY);

        memberHeader.add(selectLabel, BorderLayout.WEST);
        memberHeader.add(selectedCountLabel, BorderLayout.EAST);

        contentPanel.add(memberHeader);
        contentPanel.add(javax.swing.Box.createVerticalStrut(8));

        // 2.3. Danh sách bạn bè kèm checkbox
        JPanel memberListPanel = new JPanel();
        memberListPanel.setLayout(new BoxLayout(memberListPanel, BoxLayout.Y_AXIS));
        memberListPanel.setBackground(BG_WHITE);

        for (ChatFrame.FriendItem friend : availableFriends) {
            if (friend != null && !friend.isGroup()) {
                MemberCheckRow row = new MemberCheckRow(friend, this::updateSelectionCount);
                memberRows.add(row);
                memberListPanel.add(row);
                memberListPanel.add(javax.swing.Box.createVerticalStrut(2));
            }
        }

        if (memberRows.isEmpty()) {
            JLabel emptyLabel = new JLabel("Chưa có bạn bè nào trong danh bạ để thêm.", SwingConstants.CENTER);
            emptyLabel.setFont(emptyLabel.getFont().deriveFont(Font.PLAIN, 13f));
            emptyLabel.setForeground(TEXT_MUTED);
            emptyLabel.setBorder(BorderFactory.createEmptyBorder(20, 10, 20, 10));
            emptyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            memberListPanel.add(emptyLabel);
        }

        // Thêm glue để các dòng luôn nằm gọn ở trên cùng, không bị giãn chiều cao
        memberListPanel.add(javax.swing.Box.createVerticalGlue());

        JScrollPane memberScroll = new JScrollPane(memberListPanel);
        memberScroll.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        memberScroll.setBackground(BG_WHITE);
        memberScroll.getViewport().setBackground(BG_WHITE);
        memberScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        memberScroll.setPreferredSize(new Dimension(500, 180));
        memberScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));

        contentPanel.add(memberScroll);
        contentPanel.add(javax.swing.Box.createVerticalStrut(12));

        // 2.4. Khung thông tin bảo mật E2EE (Light Blue)
        JPanel e2eeInfoBox = new RoundedBorderPanel(12, new Color(239, 246, 255), new Color(191, 219, 254));
        e2eeInfoBox.setLayout(new BorderLayout(10, 0));
        e2eeInfoBox.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        e2eeInfoBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        e2eeInfoBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        e2eeInfoBox.setPreferredSize(new Dimension(500, 75));

        JLabel infoIconLabel = new JLabel(new InfoIcon(PRIMARY, 18));
        JLabel infoTextLabel = new JLabel("<html><body style='width: 440px;'>"
                + "<b>Cơ chế E2EE:</b> Client sẽ tự sinh khóa nhóm <b>AES-256-GCM</b>, sau đó dùng "
                + "Public Key RSA của từng bạn được chọn để mã hóa và gửi riêng qua server Blind Relay."
                + "</body></html>");
        infoTextLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        infoTextLabel.setForeground(new Color(30, 64, 175));

        e2eeInfoBox.add(infoIconLabel, BorderLayout.WEST);
        e2eeInfoBox.add(infoTextLabel, BorderLayout.CENTER);

        contentPanel.add(e2eeInfoBox);
        root.add(contentPanel, BorderLayout.CENTER);

        // 3. Footer: Nút Hủy bỏ và Tạo nhóm & Phân phối khóa
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        footerPanel.setOpaque(false);

        JButton cancelButton = new JButton("Hủy bỏ");
        cancelButton.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        cancelButton.setForeground(new Color(51, 65, 85));
        cancelButton.setBackground(BG_WHITE);
        cancelButton.setFocusPainted(false);
        cancelButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cancelButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                BorderFactory.createEmptyBorder(8, 18, 8, 18)));
        cancelButton.addActionListener(e -> dispose());

        JButton createButton = new JButton("Tạo nhóm & Phân phối khóa") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? PRIMARY_HOVER : PRIMARY);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        createButton.setFont(new Font("Segoe UI", Font.BOLD, 13));
        createButton.setForeground(Color.WHITE);
        createButton.setContentAreaFilled(false);
        createButton.setFocusPainted(false);
        createButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        createButton.setBorder(BorderFactory.createEmptyBorder(9, 20, 9, 20));
        createButton.addActionListener(e -> submitCreateGroup());

        footerPanel.add(cancelButton);
        footerPanel.add(createButton);
        root.add(footerPanel, BorderLayout.SOUTH);

        setContentPane(root);
        updateSelectionCount();
    }

    private void updateSelectionCount() {
        int count = 0;
        for (MemberCheckRow row : memberRows) {
            if (row.isSelected()) {
                count++;
            }
        }
        selectedCountLabel.setText("Đã chọn " + count + " thành viên");
    }

    private void submitCreateGroup() {
        String groupName = groupNameField.getText().trim();
        if (groupName.isEmpty()) {
            groupNameField.requestFocus();
            groupNameField.setBorder(BorderFactory.createLineBorder(new Color(239, 68, 68), 1));
            return;
        }

        List<String> selectedMembers = new ArrayList<>();
        for (MemberCheckRow row : memberRows) {
            if (row.isSelected()) {
                selectedMembers.add(row.getUsername());
            }
        }

        if (selectedMembers.isEmpty()) {
            selectedCountLabel.setForeground(new Color(239, 68, 68));
            selectedCountLabel.setText("Cần chọn ít nhất 1 thành viên!");
            return;
        }

        dispose();
        callback.onGroupCreated(groupName, selectedMembers);
    }

    /**
     * Mỗi dòng trong danh sách chọn thành viên: Checkbox + Avatar + Username + Chấm trạng thái.
     */
    private static final class MemberCheckRow extends JPanel {
        private final JCheckBox checkBox = new JCheckBox();
        private final String username;

        private MemberCheckRow(ChatFrame.FriendItem friend, Runnable onToggle) {
            super(new BorderLayout(10, 0));
            this.username = friend.getUsername();
            setOpaque(true);
            setBackground(BG_WHITE);
            setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            setPreferredSize(new Dimension(0, 44));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            checkBox.setOpaque(false);
            checkBox.setFocusPainted(false);
            checkBox.addActionListener(e -> onToggle.run());

            // Avatar 2 chữ cái đầu
            SmallAvatarPanel avatar = new SmallAvatarPanel(username, 30);

            JLabel nameLabel = new JLabel(username);
            nameLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
            nameLabel.setForeground(TEXT_DARK);

            JPanel leftBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            leftBox.setOpaque(false);
            leftBox.add(checkBox);
            leftBox.add(avatar);
            leftBox.add(nameLabel);

            // Trạng thái Trực tuyến / Ngoại tuyến
            JLabel statusLabel = new JLabel(friend.isOnline() ? "● Trực tuyến" : "○ Ngoại tuyến");
            statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            statusLabel.setForeground(friend.isOnline() ? ONLINE_COLOR : OFFLINE_COLOR);

            add(leftBox, BorderLayout.WEST);
            add(statusLabel, BorderLayout.EAST);

            // Bấm vào bất kỳ đâu trên dòng để check/uncheck
            MouseAdapter clickListener = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    checkBox.setSelected(!checkBox.isSelected());
                    onToggle.run();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    setBackground(new Color(248, 250, 252));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    setBackground(BG_WHITE);
                }
            };
            addMouseListener(clickListener);
            leftBox.addMouseListener(clickListener);
            avatar.addMouseListener(clickListener);
            nameLabel.addMouseListener(clickListener);
            statusLabel.addMouseListener(clickListener);
        }

        public boolean isSelected() {
            return checkBox.isSelected();
        }

        public String getUsername() {
            return username;
        }
    }

    /**
     * Panel avatar nhỏ vẽ chữ cái đầu.
     */
    private static final class SmallAvatarPanel extends JPanel {
        private final String username;
        private final int size;

        private SmallAvatarPanel(String username, int size) {
            this.username = username != null ? username : "";
            this.size = size;
            setPreferredSize(new Dimension(size, size));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (username.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int hash = Math.abs(username.hashCode());
            Color[] colors = {
                new Color(59, 130, 246), new Color(16, 185, 129),
                new Color(245, 158, 11), new Color(139, 92, 246),
                new Color(236, 72, 153), new Color(14, 165, 233)
            };
            g2.setColor(colors[hash % colors.length]);
            g2.fillOval(0, 0, size, size);

            String initial = username.length() >= 2
                    ? username.substring(0, 2).toUpperCase()
                    : username.substring(0, 1).toUpperCase();
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Segoe UI", Font.BOLD, (int) (size * 0.42)));
            FontMetrics fm = g2.getFontMetrics();
            int tx = (size - fm.stringWidth(initial)) / 2;
            int ty = (size - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(initial, tx, ty);
            g2.dispose();
        }
    }

    /**
     * Panel viền bo tròn tùy chỉnh.
     */
    private static class RoundedBorderPanel extends JPanel {
        private final int radius;
        private final Color bgColor;
        private final Color borderColor;

        private RoundedBorderPanel(int radius, Color bgColor, Color borderColor) {
            this.radius = radius;
            this.bgColor = bgColor;
            this.borderColor = borderColor;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bgColor);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            if (borderColor != null) {
                g2.setColor(borderColor);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * Icon nhóm vector vẽ bằng Java2D.
     */
    private static final class GroupIcon implements javax.swing.Icon {
        private final Color color;
        private final int size;

        private GroupIcon(Color color, int size) {
            this.color = color;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            // Người chính (ở giữa/phải)
            g2.drawOval(x + 4, y + 2, 8, 8);
            g2.drawArc(x + 1, y + 11, 14, 10, 0, 180);

            // Người phụ (ở bên cạnh)
            g2.drawOval(x + 12, y + 3, 6, 6);
            g2.drawArc(x + 10, y + 11, 10, 8, 0, 160);
            g2.dispose();
        }

        @Override
        public int getIconWidth() { return size; }
        @Override
        public int getIconHeight() { return size; }
    }

    /**
     * Icon chữ (i) thông tin.
     */
    private static final class InfoIcon implements javax.swing.Icon {
        private final Color color;
        private final int size;

        private InfoIcon(Color color, int size) {
            this.color = color;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawOval(x + 1, y + 1, size - 2, size - 2);
            g2.fillOval(x + size / 2 - 1, y + 4, 3, 3);
            g2.drawLine(x + size / 2, y + 9, x + size / 2, y + size - 5);
            g2.dispose();
        }

        @Override
        public int getIconWidth() { return size; }
        @Override
        public int getIconHeight() { return size; }
    }
}
