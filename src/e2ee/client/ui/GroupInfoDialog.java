package e2ee.client.ui;

import e2ee.client.ChatClientController.MemberTrustState;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Hộp thoại hiển thị thông tin nhóm và danh sách thành viên kèm trạng thái bảo mật E2EE.
 */
public final class GroupInfoDialog extends JDialog {

    private static final Color PRIMARY = new Color(79, 70, 229);
    private static final Color BG_WHITE = Color.WHITE;
    private static final Color BG_LIGHT = new Color(248, 250, 252);
    private static final Color BORDER_COLOR = new Color(226, 232, 240);
    private static final Color TEXT_DARK = new Color(15, 23, 42);
    private static final Color TEXT_MUTED = new Color(100, 116, 139);

    public GroupInfoDialog(JFrame owner, String groupId, String groupName, List<String> members,
                           Function<String, MemberTrustState> trustStateProvider,
                           Consumer<String> openSecurityAction) {
        super(owner, "Thông tin nhóm - " + (groupName != null ? groupName : groupId), false);
        Objects.requireNonNull(groupId, "groupId cannot be null");
        String finalGroupName = groupName != null && !groupName.isBlank() ? groupName : groupId;
        List<String> memberList = members != null ? List.copyOf(members) : List.of();
        Function<String, MemberTrustState> trustProvider =
                trustStateProvider != null ? trustStateProvider : u -> MemberTrustState.UNKNOWN;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(440, 470);
        setLocationRelativeTo(owner);
        setResizable(false);

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(BG_LIGHT);
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        // 1. Thẻ thông tin nhóm
        JPanel headerCard = new JPanel(new BorderLayout(12, 0));
        headerCard.setBackground(BG_WHITE);
        headerCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)));

        headerCard.add(createAvatar(finalGroupName, 44, new Color(238, 242, 255), PRIMARY, 18), BorderLayout.WEST);

        JPanel groupDetails = new JPanel();
        groupDetails.setOpaque(false);
        groupDetails.setLayout(new BoxLayout(groupDetails, BoxLayout.Y_AXIS));

        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        nameRow.setOpaque(false);
        JLabel nameLabel = new JLabel(finalGroupName);
        nameLabel.setFont(new Font("Segoe UI", Font.BOLD, 15));
        nameLabel.setForeground(TEXT_DARK);
        nameRow.add(nameLabel);

        JLabel countBadge = new JLabel(memberList.size() + " thành viên");
        countBadge.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        countBadge.setForeground(new Color(71, 85, 105));
        countBadge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                BorderFactory.createEmptyBorder(1, 6, 1, 6)));
        nameRow.add(countBadge);

        JLabel idLabel = new JLabel("Mã nhóm: " + groupId);
        idLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        idLabel.setForeground(TEXT_MUTED);

        groupDetails.add(nameRow);
        groupDetails.add(Box.createVerticalStrut(3));
        groupDetails.add(idLabel);
        headerCard.add(groupDetails, BorderLayout.CENTER);
        root.add(headerCard, BorderLayout.NORTH);

        // 2. Danh sách thành viên
        JPanel centerPanel = new JPanel(new BorderLayout(0, 6));
        centerPanel.setOpaque(false);

        JLabel sectionTitle = new JLabel("DANH SÁCH THÀNH VIÊN VÀ BẢO MẬT E2EE");
        sectionTitle.setFont(new Font("Segoe UI", Font.BOLD, 11));
        sectionTitle.setForeground(TEXT_MUTED);
        centerPanel.add(sectionTitle, BorderLayout.NORTH);

        JPanel memberListPanel = new JPanel();
        memberListPanel.setLayout(new BoxLayout(memberListPanel, BoxLayout.Y_AXIS));
        memberListPanel.setBackground(BG_WHITE);

        for (int i = 0; i < memberList.size(); i++) {
            String member = memberList.get(i);
            MemberTrustState trustState = trustProvider.apply(member);
            memberListPanel.add(createMemberRow(member, trustState, openSecurityAction));
            if (i < memberList.size() - 1) {
                JPanel divider = new JPanel();
                divider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
                divider.setBackground(new Color(241, 245, 249));
                memberListPanel.add(divider);
            }
        }

        JScrollPane scrollPane = new JScrollPane(memberListPanel);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1));
        scrollPane.getViewport().setBackground(BG_WHITE);
        scrollPane.getVerticalScrollBar().setUnitIncrement(12);
        centerPanel.add(scrollPane, BorderLayout.CENTER);
        root.add(centerPanel, BorderLayout.CENTER);

        // 3. Nút Đóng
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        footer.setOpaque(false);
        JButton closeButton = createButton("Đóng", TEXT_DARK, BORDER_COLOR);
        closeButton.addActionListener(e -> dispose());
        footer.add(closeButton);
        root.add(footer, BorderLayout.SOUTH);

        setContentPane(root);
    }

    private JPanel createMemberRow(String username, MemberTrustState state, Consumer<String> openSecurity) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        row.add(createAvatar(username, 30, new Color(241, 245, 249), new Color(51, 65, 85), 12), BorderLayout.WEST);

        JLabel usernameLabel = new JLabel(username);
        usernameLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        usernameLabel.setForeground(TEXT_DARK);
        row.add(usernameLabel, BorderLayout.CENTER);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightPanel.setOpaque(false);
        rightPanel.add(createTrustBadge(state));

        if (state != MemberTrustState.MYSELF && openSecurity != null) {
            JButton verifyBtn = createButton("Đối soát", PRIMARY, new Color(199, 210, 254));
            verifyBtn.addActionListener(e -> openSecurity.accept(username));
            rightPanel.add(verifyBtn);
        }

        row.add(rightPanel, BorderLayout.EAST);
        return row;
    }

    private static JLabel createAvatar(String text, int size, Color bg, Color fg, int fontSize) {
        String initial = text.isBlank() ? "?" : text.substring(0, 1).toUpperCase();
        JLabel label = new JLabel(initial, SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillOval(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        label.setPreferredSize(new Dimension(size, size));
        label.setFont(new Font("Segoe UI", Font.BOLD, fontSize));
        label.setForeground(fg);
        return label;
    }

    private static JButton createButton(String text, Color fg, Color border) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btn.setForeground(fg);
        btn.setBackground(BG_WHITE);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, 1, true),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        return btn;
    }

    private static JLabel createTrustBadge(MemberTrustState state) {
        Color bg = switch (state) {
            case VERIFIED -> new Color(220, 252, 231);
            case KEY_CHANGED -> new Color(254, 226, 226);
            case TRUSTED_TOFU -> new Color(254, 249, 195);
            default -> new Color(241, 245, 249);
        };
        Color fg = switch (state) {
            case VERIFIED -> new Color(22, 101, 52);
            case KEY_CHANGED -> new Color(153, 27, 27);
            case TRUSTED_TOFU -> new Color(133, 77, 14);
            default -> new Color(71, 85, 105);
        };
        JLabel badge = new JLabel(state.getLabel());
        badge.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        badge.setForeground(fg);
        badge.setBackground(bg);
        badge.setOpaque(true);
        badge.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        return badge;
    }
}
