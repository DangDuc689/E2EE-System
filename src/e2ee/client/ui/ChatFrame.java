package e2ee.client.ui;

import e2ee.client.crypto.ClientCryptoService;
import e2ee.common.contract.MessageLimits;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Cửa sổ chat 1-1 chính của E2EE Client theo thiết kế Light UI hiện đại.
 * Hợp nhất danh sách bạn bè bên trái và khung chat bên phải.
 */
public final class ChatFrame extends JFrame {
    private static final Color PRIMARY = new Color(37, 99, 235);
    private static final Color PRIMARY_HOVER = new Color(29, 78, 216);
    private static final Color BG_WHITE = Color.WHITE;
    private static final Color BG_SIDEBAR = new Color(248, 250, 252);
    private static final Color BORDER_COLOR = new Color(226, 232, 240);
    private static final Color TEXT_DARK = new Color(15, 23, 42);
    private static final Color TEXT_MUTED = new Color(100, 116, 139);
    private static final Color ONLINE_COLOR = new Color(34, 197, 94);
    private static final Color OFFLINE_COLOR = new Color(148, 163, 184);
    public static final Color SERVER_CONNECTED_COLOR = new Color(34, 197, 94);
    public static final Color SERVER_DISCONNECTED_COLOR = new Color(239, 68, 68);
    public static final Color SERVER_RECONNECTING_COLOR = new Color(245, 158, 11);

    private static final Color SECURITY_WARNING_BACKGROUND =
            new Color(254, 226, 226);
    private static final Color SECURITY_WARNING_FOREGROUND =
            new Color(153, 27, 27);

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern DIACRITICAL_MARKS =
            Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    private final String myUsername;
    private final Actions actions;
    private final DefaultListModel<FriendItem> friendModel =
            new DefaultListModel<>();
    private final DefaultListModel<FriendItem> displayedFriendModel =
            new DefaultListModel<>();
    private final JList<FriendItem> friendList = new JList<>(displayedFriendModel);
    private boolean filteringFriends;
    private final JTextField addFriendField = new JTextField() {
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
                int x = insets != null ? insets.left : 0;
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString("Tìm kiếm...", x, y);
                g2.dispose();
            }
        }
    };
    private final JPanel messagesPanel = new ScrollableMessagesPanel();
    private final JScrollPane messagesScroll;
    private final JPanel conversationPanel = new JPanel(new BorderLayout());
    private final JPanel composerPanel = new JPanel(new BorderLayout(8, 8));
    private final JTextArea messageInput = new JTextArea(2, 24);
    private final JButton sendButton = new JButton("Gửi");
    private final JLabel serverStatusLabel = new JLabel("Máy chủ: Đã kết nối");
    private final JComboBox<TtlOption> ttlSelector = new JComboBox<>(
            new TtlOption[]{
                new TtlOption("Không tự hủy", 0),
                new TtlOption("Tự hủy: 10s", 10),
                new TtlOption("Tự hủy: 30s", 30)
            });
    private final JButton securityButton = new JButton("Đối soát") {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getModel().isRollover() && isEnabled()
                    ? new Color(241, 245, 249) : BG_WHITE);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.setColor(BORDER_COLOR);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    };
    private final JButton groupInfoButton = new JButton() {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getModel().isRollover() && isEnabled()
                    ? new Color(241, 245, 249) : BG_WHITE);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            g2.setColor(BORDER_COLOR);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            g2.dispose();
            super.paintComponent(g);
        }
    };
    private final JButton rotateKeyButton = new JButton() {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getModel().isRollover() && isEnabled()
                    ? new Color(241, 245, 249) : BG_WHITE);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.setColor(BORDER_COLOR);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    };
    private JDialog myKeyDialog;
    private JTextArea myFingerprintArea;
    private JButton rotateButtonInDialog;
    private final JPanel chatHeader = new JPanel(new BorderLayout());
    private final AvatarBadgePanel peerHeaderAvatar =
            new AvatarBadgePanel("", false, 38, null, false);
    private final JLabel conversationTitle = new JLabel("Chọn một người bạn");
    private final JLabel conversationSubtitle = new JLabel("");
    private final JLabel connectionBanner = new JLabel(
            "Đã kết nối an toàn", SwingConstants.CENTER);

    private enum ConversationTab {
        ALL,
        FRIENDS,
        GROUPS
    }

    private ConversationTab activeTab = ConversationTab.ALL;
    private final JButton tabAll = new JButton("Tất cả");
    private final JButton tabFriends = new JButton("Bạn bè");
    private final JButton tabGroups = new JButton("Nhóm");

    private final Map<String, FriendItem> friends = new HashMap<>();
    private final Map<String, List<ChatMessage>> conversations =
            new HashMap<>();
    private final Map<String, Boolean> sendAllowed = new HashMap<>();
    private final Map<String, Boolean> securityWarnings = new HashMap<>();
    private final Timer expiryTimer = new Timer(
            1_000, event -> expireMessages(System.currentTimeMillis()));
    private final Timer bannerTimer = new Timer(
            4_000, event -> connectionBanner.setVisible(false));

    private String selectedPeer;

    public ChatFrame(String myUsername, Actions actions) {
        super("E2EE Chat - " + myUsername);
        this.myUsername = Objects.requireNonNull(
                myUsername, "myUsername cannot be null");
        this.actions = Objects.requireNonNull(actions,
                "actions cannot be null");
        messagesScroll = new JScrollPane(messagesPanel);
        initializeUi();
        bindActions();
        expiryTimer.start();
        bannerTimer.setRepeats(false);
    }

    private void initializeUi() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(820, 560));
        setSize(1040, 680);
        setLocationByPlatform(true);

        // Banner thông báo kết nối mỏng phía trên
        connectionBanner.setOpaque(true);
        connectionBanner.setBackground(new Color(220, 252, 231));
        connectionBanner.setForeground(new Color(22, 101, 52));
        connectionBanner.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        connectionBanner.setVisible(false); // Ẩn mặc định để giao diện thoáng, hiển thị khi có sự kiện

        JSplitPane splitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                createSidebar(), createConversationPanel());
        splitPane.setDividerLocation(300);
        splitPane.setDividerSize(1);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setResizeWeight(0.0);
        splitPane.setContinuousLayout(true);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG_WHITE);
        root.add(connectionBanner, BorderLayout.NORTH);
        root.add(splitPane, BorderLayout.CENTER);
        setContentPane(root);
    }

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 8));
        sidebar.setBackground(BG_SIDEBAR);
        sidebar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER_COLOR),
                BorderFactory.createEmptyBorder(14, 14, 14, 14)));
        sidebar.setPreferredSize(new Dimension(300, 0));
        sidebar.setMinimumSize(new Dimension(220, 0));

        // 1. Thẻ hồ sơ của tôi (Profile Card)
        JPanel profileCard = new RoundedPanel(14, BG_WHITE, BORDER_COLOR);
        profileCard.setLayout(new BorderLayout(10, 0));
        profileCard.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        AvatarBadgePanel myAvatar = new AvatarBadgePanel(myUsername, true, 42);
        JPanel myInfoPanel = new JPanel();
        myInfoPanel.setOpaque(false);
        myInfoPanel.setLayout(new BoxLayout(myInfoPanel, BoxLayout.Y_AXIS));

        JLabel myNameLabel = new JLabel(myUsername);
        myNameLabel.setFont(myNameLabel.getFont().deriveFont(Font.BOLD, 15f));
        myNameLabel.setForeground(TEXT_DARK);

        myInfoPanel.add(javax.swing.Box.createVerticalGlue());
        myInfoPanel.add(myNameLabel);
        myInfoPanel.add(javax.swing.Box.createVerticalGlue());

        profileCard.add(myAvatar, BorderLayout.WEST);
        profileCard.add(myInfoPanel, BorderLayout.CENTER);

        rotateKeyButton.setIcon(new KeyIcon(new Color(51, 65, 85)));
        rotateKeyButton.setToolTipText("Khóa bảo mật của tôi");
        rotateKeyButton.setContentAreaFilled(false);
        rotateKeyButton.setFocusPainted(false);
        rotateKeyButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        rotateKeyButton.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        rotateKeyButton.setPreferredSize(new Dimension(36, 36));

        JPanel profileRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        profileRight.setOpaque(false);
        profileRight.add(rotateKeyButton);
        profileCard.add(profileRight, BorderLayout.EAST);

        // 2. Ô tìm kiếm và nút "Tạo nhóm"
        JPanel searchAndAddPanel = new JPanel(new BorderLayout(8, 0));
        searchAndAddPanel.setOpaque(false);

        JPanel searchBox = new RoundedPanel(20, BG_WHITE, BORDER_COLOR);
        searchBox.setLayout(new BorderLayout(8, 0));
        searchBox.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        searchBox.setPreferredSize(new Dimension(0, 38));

        JLabel searchIconLabel = new JLabel(new SearchIcon(TEXT_MUTED));
        searchBox.add(searchIconLabel, BorderLayout.WEST);

        addFriendField.setBorder(BorderFactory.createEmptyBorder());
        addFriendField.setOpaque(false);
        addFriendField.setFont(addFriendField.getFont().deriveFont(Font.PLAIN, 13f));
        addFriendField.setForeground(TEXT_DARK);
        addFriendField.setToolTipText("Tìm kiếm...");
        addFriendField.addActionListener(event -> submitFriendLookup());
        searchBox.add(addFriendField, BorderLayout.CENTER);
        searchAndAddPanel.add(searchBox, BorderLayout.CENTER);

        JButton createGroupBtn = new JButton("Tạo nhóm") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? new Color(239, 246, 255) : BG_WHITE);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g2.setColor(getModel().isRollover() ? PRIMARY : BORDER_COLOR);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        createGroupBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        createGroupBtn.setForeground(PRIMARY);
        createGroupBtn.setContentAreaFilled(false);
        createGroupBtn.setFocusPainted(false);
        createGroupBtn.setMargin(new Insets(0, 0, 0, 0));
        createGroupBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        createGroupBtn.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        createGroupBtn.addActionListener(event -> openCreateGroupDialog());
        searchAndAddPanel.add(createGroupBtn, BorderLayout.EAST);

        // 3. Thanh tab lọc: Tất cả | Bạn bè | Nhóm
        JPanel tabBar = new JPanel(new GridLayout(1, 3, 4, 0));
        tabBar.setOpaque(false);
        tabBar.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

        tabAll.setFocusPainted(false);
        tabAll.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tabAll.addActionListener(e -> setConversationTab(ConversationTab.ALL));

        tabFriends.setFocusPainted(false);
        tabFriends.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tabFriends.addActionListener(e -> setConversationTab(ConversationTab.FRIENDS));

        tabGroups.setFocusPainted(false);
        tabGroups.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tabGroups.addActionListener(e -> setConversationTab(ConversationTab.GROUPS));

        updateTabStyles();
        tabBar.add(tabAll);
        tabBar.add(tabFriends);
        tabBar.add(tabGroups);

        // Header trên của Sidebar (Profile Card + Search & Add + Tab Bar)
        JPanel sidebarTop = new JPanel(new BorderLayout(0, 10));
        sidebarTop.setOpaque(false);
        sidebarTop.add(profileCard, BorderLayout.NORTH);

        JPanel searchAndTabs = new JPanel(new BorderLayout(0, 6));
        searchAndTabs.setOpaque(false);
        searchAndTabs.add(searchAndAddPanel, BorderLayout.NORTH);
        searchAndTabs.add(tabBar, BorderLayout.SOUTH);

        sidebarTop.add(searchAndTabs, BorderLayout.CENTER);

        // 3. Danh sách bạn bè
        friendList.setBackground(BG_SIDEBAR);
        friendList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        friendList.setCellRenderer(new ModernFriendCellRenderer());
        friendList.setBorder(BorderFactory.createEmptyBorder());

        JScrollPane friendScroll = new JScrollPane(friendList);
        friendScroll.setBorder(BorderFactory.createEmptyBorder());
        friendScroll.setBackground(BG_SIDEBAR);
        friendScroll.getViewport().setBackground(BG_SIDEBAR);
        friendScroll.setHorizontalScrollBarPolicy(
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        // 4. Thanh footer trạng thái máy chủ dưới đáy
        JPanel sidebarFooter = new JPanel(new BorderLayout());
        sidebarFooter.setOpaque(false);
        sidebarFooter.setBorder(BorderFactory.createEmptyBorder(8, 2, 2, 2));

        serverStatusLabel.setIcon(new DotIcon(SERVER_CONNECTED_COLOR, 8));
        serverStatusLabel.setIconTextGap(6);
        serverStatusLabel.setFont(serverStatusLabel.getFont().deriveFont(Font.PLAIN, 12f));
        serverStatusLabel.setForeground(TEXT_DARK);

        JLabel versionLabel = new JLabel("v1.0");
        versionLabel.setFont(versionLabel.getFont().deriveFont(Font.PLAIN, 12f));
        versionLabel.setForeground(TEXT_MUTED);

        sidebarFooter.add(serverStatusLabel, BorderLayout.WEST);
        sidebarFooter.add(versionLabel, BorderLayout.EAST);

        sidebar.add(sidebarTop, BorderLayout.NORTH);
        sidebar.add(friendScroll, BorderLayout.CENTER);
        sidebar.add(sidebarFooter, BorderLayout.SOUTH);
        return sidebar;
    }

    private JPanel createConversationPanel() {
        conversationPanel.setBackground(BG_WHITE);
        conversationPanel.setMinimumSize(new Dimension(380, 0));

        // Header cuộc trò chuyện: Avatar, Tên, Trạng thái hoạt động, và nút Đối soát
        chatHeader.setBackground(BG_WHITE);
        chatHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR),
                BorderFactory.createEmptyBorder(12, 20, 12, 20)));

        JPanel headerLeft = new JPanel(new BorderLayout(10, 0));
        headerLeft.setOpaque(false);

        peerHeaderAvatar.setVisible(false);
        headerLeft.add(peerHeaderAvatar, BorderLayout.WEST);

        JPanel textInfoPanel = new JPanel();
        textInfoPanel.setOpaque(false);
        textInfoPanel.setLayout(new BoxLayout(textInfoPanel, BoxLayout.Y_AXIS));

        conversationTitle.setFont(
                conversationTitle.getFont().deriveFont(Font.BOLD, 17f));
        conversationTitle.setForeground(TEXT_DARK);

        conversationSubtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        conversationSubtitle.setForeground(TEXT_MUTED);
        conversationSubtitle.setIconTextGap(5);
        conversationSubtitle.setVisible(false);

        textInfoPanel.add(javax.swing.Box.createVerticalGlue());
        textInfoPanel.add(conversationTitle);
        textInfoPanel.add(javax.swing.Box.createVerticalStrut(2));
        textInfoPanel.add(conversationSubtitle);
        textInfoPanel.add(javax.swing.Box.createVerticalGlue());

        headerLeft.add(textInfoPanel, BorderLayout.CENTER);

        // Nút Đối soát ở góc phải (icon vector ổ khóa)
        securityButton.setIcon(new LockIcon(new Color(51, 65, 85)));
        securityButton.setIconTextGap(6);
        securityButton.setFont(securityButton.getFont().deriveFont(Font.PLAIN, 13f));
        securityButton.setForeground(new Color(51, 65, 85));
        securityButton.setContentAreaFilled(false);
        securityButton.setFocusPainted(false);
        securityButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        securityButton.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        securityButton.setEnabled(false);
        securityButton.setVisible(false);

        // Nút Thông tin nhóm ở góc phải (icon vector vòng tròn chấm tâm [⊙])
        groupInfoButton.setIcon(new InfoCircleIcon(new Color(51, 65, 85), 18));
        groupInfoButton.setToolTipText("Xem thông tin và thành viên nhóm");
        groupInfoButton.setContentAreaFilled(false);
        groupInfoButton.setFocusPainted(false);
        groupInfoButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        groupInfoButton.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        groupInfoButton.setEnabled(false);
        groupInfoButton.setVisible(false);

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        headerRight.setOpaque(false);
        headerRight.add(securityButton);
        headerRight.add(groupInfoButton);

        chatHeader.add(headerLeft, BorderLayout.CENTER);
        chatHeader.add(headerRight, BorderLayout.EAST);

        // Vùng hiển thị tin nhắn
        messagesPanel.setLayout(
                new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(BG_WHITE);
        messagesPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        messagesScroll.setBorder(BorderFactory.createEmptyBorder());
        messagesScroll.setBackground(BG_WHITE);
        messagesScroll.getViewport().setBackground(BG_WHITE);
        messagesScroll.getVerticalScrollBar().setUnitIncrement(16);
        messagesScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                messagesPanel.revalidate();
                messagesPanel.repaint();
            }
        });

        conversationPanel.add(chatHeader, BorderLayout.NORTH);
        conversationPanel.add(messagesScroll, BorderLayout.CENTER);
        conversationPanel.add(createComposer(), BorderLayout.SOUTH);
        return conversationPanel;
    }

    private JPanel createComposer() {
        composerPanel.setBackground(BG_WHITE);
        composerPanel.setBorder(BorderFactory.createEmptyBorder(10, 16, 14, 16));

        // Thanh soạn thảo bo tròn hiện đại 2 tầng: vùng nhập full-width ở trên, công cụ ở dưới
        JPanel composerBar = new RoundedPanel(16, new Color(248, 250, 252), BORDER_COLOR);
        composerBar.setLayout(new BorderLayout(0, 6));
        composerBar.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 12));
        composerBar.setMinimumSize(new Dimension(320, 100));

        messageInput.setLineWrap(true);
        messageInput.setWrapStyleWord(true);
        messageInput.setEnabled(false);
        messageInput.setOpaque(false);
        messageInput.setFont(messageInput.getFont().deriveFont(Font.PLAIN, 14f));
        messageInput.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        JScrollPane inputScroll = new JScrollPane(messageInput);
        inputScroll.setBorder(BorderFactory.createEmptyBorder());
        inputScroll.setOpaque(false);
        inputScroll.getViewport().setOpaque(false);
        inputScroll.setHorizontalScrollBarPolicy(
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        inputScroll.setVerticalScrollBarPolicy(
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        inputScroll.getVerticalScrollBar().setUnitIncrement(14);
        inputScroll.setPreferredSize(new Dimension(0, 68));
        inputScroll.setMinimumSize(new Dimension(100, 48));

        // Hàng công cụ bên dưới: gợi ý phím tắt bên trái, chọn TTL + nút Gửi bên phải
        JPanel bottomBar = new JPanel(new BorderLayout(10, 0));
        bottomBar.setOpaque(false);
        bottomBar.setMinimumSize(new Dimension(240, 36));

        JPanel actionButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actionButtons.setOpaque(false);

        ttlSelector.setFont(ttlSelector.getFont().deriveFont(Font.PLAIN, 12.5f));
        ttlSelector.setToolTipText("Thời gian tồn tại của tin nhắn");

        sendButton.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        sendButton.setFont(sendButton.getFont().deriveFont(Font.BOLD, 13f));
        sendButton.setBackground(new Color(226, 232, 240));
        sendButton.setForeground(new Color(148, 163, 184));
        sendButton.setFocusPainted(false);
        sendButton.setBorder(BorderFactory.createEmptyBorder(7, 20, 7, 20));
        sendButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendButton.setEnabled(false);

        actionButtons.add(ttlSelector);
        actionButtons.add(sendButton);

        bottomBar.add(actionButtons, BorderLayout.EAST);

        composerBar.add(inputScroll, BorderLayout.CENTER);
        composerBar.add(bottomBar, BorderLayout.SOUTH);

        composerPanel.add(composerBar, BorderLayout.CENTER);
        return composerPanel;
    }

    private void bindActions() {
        friendList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !filteringFriends) {
                FriendItem item = friendList.getSelectedValue();
                if (item != null && !Objects.equals(item.username, selectedPeer)) {
                    selectedPeer = item.username;
                    renderSelectedConversation();
                    if (!Boolean.TRUE.equals(sendAllowed.get(selectedPeer))
                            && !Boolean.TRUE.equals(securityWarnings.get(selectedPeer))) {
                        actions.onLookupPeer(selectedPeer);
                    }
                }
            }
        });
        addFriendField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyFriendFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyFriendFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyFriendFilter();
            }
        });
        sendButton.addActionListener(event -> submitMessage());
        securityButton.addActionListener(event -> {
            if (selectedPeer != null) {
                actions.onOpenSecurity(selectedPeer);
            }
        });
        groupInfoButton.addActionListener(event -> openGroupInfoDialog());
        rotateKeyButton.addActionListener(event -> openMyKeyDialog());
        messageInput.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ENTER
                        && !event.isShiftDown()) {
                    event.consume();
                    submitMessage();
                }
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                actions.onClose();
            }
        });
    }

    private void submitFriendLookup() {
        String username = addFriendField.getText().trim();
        if (!username.isBlank() && !username.equals(myUsername)) {
            actions.onLookupPeer(username);
            addFriendField.setText("");
        }
    }

    private void submitMessage() {
        String content = messageInput.getText().trim();
        if (selectedPeer == null || content.isEmpty()
                || !sendButton.isEnabled()) {
            return;
        }
        // Kiểm tra giới hạn 64 KiB UTF-8 trước khi gọi gửi tin
        if (!MessageLimits.isWithinLimit(content)) {
            showBanner(String.format(Locale.GERMANY,
                    "Tin nhắn quá dài (%,d byte; giới hạn %,d byte)",
                    MessageLimits.utf8ByteLength(content),
                    MessageLimits.MAX_TEXT_UTF8_BYTES),
                    BannerType.ERROR);
            return;
        }
        TtlOption option = (TtlOption) ttlSelector.getSelectedItem();
        int ttlSeconds = option != null ? option.seconds : 0;
        FriendItem item = friends.get(selectedPeer);
        if (item != null && item.isGroup()) {
            actions.onSendGroupMessage(selectedPeer, content, ttlSeconds);
        } else {
            actions.onSendMessage(selectedPeer, content, ttlSeconds);
        }
        messageInput.setText("");
    }

    public void updatePresence(String username, boolean online) {
        requireEdt();
        FriendItem friend = friends.get(username);
        if (friend == null) {
            friend = new FriendItem(username, online);
            friends.put(username, friend);
            friendModel.addElement(friend);
            applyFriendFilter();
        } else {
            friend.online = online;
        }
        if (Objects.equals(username, selectedPeer)) {
            peerHeaderAvatar.update(username, online);
            if (!friend.isGroup()) {
                conversationSubtitle.setIcon(new DotIcon(online ? ONLINE_COLOR : OFFLINE_COLOR, 7));
                conversationSubtitle.setText(online ? "Trực tuyến" : "Ngoại tuyến");
                conversationSubtitle.setForeground(online ? ONLINE_COLOR : TEXT_MUTED);
            }
        }
        friendList.repaint();
    }

    public void markPeerKeyAvailable(String username) {
        requireEdt();
        if (!friends.containsKey(username)) {
            updatePresence(username, false);
        }
        sendAllowed.putIfAbsent(username, true);
        selectPeer(username);
        if (Objects.equals(username, selectedPeer)) {
            updateComposerState();
        }
    }

    public void selectPeer(String username) {
        requireEdt();
        FriendItem item = friends.get(username);
        if (item != null) {
            if (!displayedFriendModel.contains(item)) {
                addFriendField.setText("");
            }
            friendList.setSelectedValue(item, true);
        }
    }

    public void addMessage(String peerUsername, String content,
            long timestamp, boolean sentByMe) {
        addMessage(peerUsername, content, timestamp, sentByMe, 0);
    }

    public void addMessage(String peerUsername, String content,
            long timestamp, boolean sentByMe, int ttlSeconds) {
        if (ttlSeconds < 0 || ttlSeconds > 86_400) {
            throw new IllegalArgumentException("TTL khong hop le");
        }
        long expiresAt = ttlSeconds == 0 ? 0L : timestamp + ttlSeconds * 1_000L;
        addMessageWithExpiry(peerUsername, content, timestamp, sentByMe, expiresAt);
    }

    public void addMessageWithExpiry(String peerUsername, String content,
            long timestamp, boolean sentByMe, long expiresAt) {
        requireEdt();
        if (expiresAt > 0 && expiresAt <= System.currentTimeMillis()) {
            return;
        }
        conversations.computeIfAbsent(peerUsername,
                ignored -> new ArrayList<>())
                .add(new ChatMessage(content, timestamp, sentByMe, expiresAt));
        if (peerUsername.equals(selectedPeer)) {
            renderSelectedConversation();
        }
        friendList.repaint();
    }

    public void addGroup(String groupId, String groupName, List<String> members) {
        requireEdt();
        if (friends.containsKey(groupId)) {
            return;
        }
        FriendItem group = new FriendItem(groupId, groupName, true, true, members);
        friends.put(groupId, group);
        friendModel.addElement(group);
        sendAllowed.put(groupId, true);
        applyFriendFilter();
    }

    public void addGroupMessage(String groupId, String senderUsername, String content,
                                long timestamp, boolean sentByMe, int ttlSeconds) {
        if (ttlSeconds < 0 || ttlSeconds > 86_400) {
            throw new IllegalArgumentException("TTL khong hop le");
        }
        long expiresAt = ttlSeconds == 0 ? 0L : timestamp + ttlSeconds * 1_000L;
        addGroupMessageWithExpiry(groupId, senderUsername, content, timestamp, sentByMe, expiresAt);
    }

    public void addGroupMessageWithExpiry(String groupId, String senderUsername, String content,
                                          long timestamp, boolean sentByMe, long expiresAt) {
        requireEdt();
        if (expiresAt > 0 && expiresAt <= System.currentTimeMillis()) {
            return;
        }
        conversations.computeIfAbsent(groupId, ignored -> new ArrayList<>())
                .add(new ChatMessage(senderUsername, content, timestamp, sentByMe, expiresAt));
        if (groupId.equals(selectedPeer)) {
            renderSelectedConversation();
        }
        friendList.repaint();
    }

    private void openCreateGroupDialog() {
        List<FriendItem> availableFriends = new ArrayList<>();
        for (int i = 0; i < friendModel.size(); i++) {
            FriendItem item = friendModel.get(i);
            if (!item.isGroup()) {
                availableFriends.add(item);
            }
        }
        CreateGroupDialog dialog = new CreateGroupDialog(this, availableFriends,
                (groupName, memberUsernames) -> {
                    String groupId = "group_" + java.util.UUID.randomUUID().toString();
                    List<String> allMembers = new ArrayList<>(memberUsernames);
                    if (!allMembers.contains(myUsername)) {
                        allMembers.add(myUsername);
                    }
                    showBanner("Đang gửi yêu cầu tạo nhóm '" + groupName + "'...", BannerType.WARNING);
                    actions.onCreateGroup(groupId, groupName, allMembers);
                });
        dialog.setVisible(true);
    }

    private void openMyKeyDialog() {
        if (myKeyDialog != null && myKeyDialog.isDisplayable()) {
            myKeyDialog.toFront();
            myKeyDialog.requestFocus();
            return;
        }
        myKeyDialog = new JDialog(this, "Khóa bảo mật của tôi - " + myUsername, true);
        myKeyDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        myKeyDialog.setSize(520, 370);
        myKeyDialog.setLocationRelativeTo(this);
        myKeyDialog.setResizable(false);

        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBackground(new Color(248, 250, 252));
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));

        // 1. Thông tin Username và thuật toán
        JPanel infoCard = new RoundedPanel(12, BG_WHITE, BORDER_COLOR);
        infoCard.setLayout(new GridLayout(2, 1, 0, 4));
        infoCard.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

        JLabel userLabel = new JLabel("Tài khoản: " + myUsername);
        userLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        userLabel.setForeground(TEXT_DARK);

        JLabel algoLabel = new JLabel("Thuật toán: RSA-2048 / OAEP-SHA256 (Mã hóa đầu cuối)");
        algoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        algoLabel.setForeground(TEXT_MUTED);

        infoCard.add(userLabel);
        infoCard.add(algoLabel);

        // 2. Ô hiển thị vân tay Fingerprint hiện tại
        JPanel centerPanel = new JPanel(new BorderLayout(0, 6));
        centerPanel.setOpaque(false);

        JLabel fpTitle = new JLabel("VÂN TAY KHÓA HIỆN TẠI (FINGERPRINT)");
        fpTitle.setFont(new Font("Segoe UI", Font.BOLD, 11));
        fpTitle.setForeground(TEXT_MUTED);

        String currentFp = actions.getMyFingerprint();
        myFingerprintArea = new JTextArea(currentFp != null && !currentFp.isBlank() ? currentFp : "Chưa có khóa");
        myFingerprintArea.setEditable(false);
        myFingerprintArea.setFocusable(false);
        myFingerprintArea.setLineWrap(true);
        myFingerprintArea.setWrapStyleWord(true);
        myFingerprintArea.setRows(3);
        myFingerprintArea.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        myFingerprintArea.setBackground(new Color(241, 245, 249));
        myFingerprintArea.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JScrollPane fpScroll = new JScrollPane(myFingerprintArea);
        fpScroll.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));

        JLabel fpHint = new JLabel("Đọc mã này cho bạn bè đối soát qua kênh ngoài (gặp trực tiếp, gọi điện).");
        fpHint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        fpHint.setForeground(TEXT_MUTED);

        centerPanel.add(fpTitle, BorderLayout.NORTH);
        centerPanel.add(fpScroll, BorderLayout.CENTER);
        centerPanel.add(fpHint, BorderLayout.SOUTH);

        // 3. Thanh nút điều khiển dưới đáy
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);

        JButton closeBtn = new JButton("Đóng") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? new Color(241, 245, 249) : BG_WHITE);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.setColor(BORDER_COLOR);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        closeBtn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        closeBtn.setForeground(new Color(51, 65, 85));
        closeBtn.setContentAreaFilled(false);
        closeBtn.setFocusPainted(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
        closeBtn.addActionListener(e -> myKeyDialog.dispose());

        rotateButtonInDialog = new JButton("Đổi khóa mới") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() && isEnabled() ? PRIMARY_HOVER : PRIMARY);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        rotateButtonInDialog.setFont(new Font("Segoe UI", Font.BOLD, 12));
        rotateButtonInDialog.setForeground(Color.WHITE);
        rotateButtonInDialog.setContentAreaFilled(false);
        rotateButtonInDialog.setFocusPainted(false);
        rotateButtonInDialog.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        rotateButtonInDialog.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
        rotateButtonInDialog.addActionListener(e -> {
            rotateButtonInDialog.setEnabled(false);
            rotateButtonInDialog.setText("Đang đổi khóa...");
            actions.onRotateKey();
        });

        footer.add(closeBtn, BorderLayout.WEST);
        footer.add(rotateButtonInDialog, BorderLayout.EAST);

        root.add(infoCard, BorderLayout.NORTH);
        root.add(centerPanel, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);

        myKeyDialog.setContentPane(root);
        myKeyDialog.setVisible(true);
    }

    public void updateMyFingerprint(String newFingerprint) {
        requireEdt();
        if (myFingerprintArea != null) {
            myFingerprintArea.setText(newFingerprint);
        }
        if (rotateButtonInDialog != null) {
            rotateButtonInDialog.setEnabled(true);
            rotateButtonInDialog.setText("Đổi khóa mới");
        }
    }

    private void openGroupInfoDialog() {
        if (selectedPeer == null) {
            return;
        }
        FriendItem item = friends.get(selectedPeer);
        if (item == null || !item.isGroup()) {
            return;
        }
        GroupInfoDialog dialog = new GroupInfoDialog(
                this,
                item.getUsername(),
                item.getDisplayName(),
                item.getMembers(),
                actions::getMemberTrustState,
                actions::onOpenSecurity);
        dialog.setVisible(true);
    }

    public void setPeerSendAllowed(String username, boolean allowed) {
        requireEdt();
        sendAllowed.put(username, allowed);
        updateComposerState();
    }

    public void setPeerSecurityWarning(String username, boolean warning) {
        requireEdt();
        securityWarnings.put(username, warning);
        if (warning) {
            sendAllowed.put(username, false);
        }
        if (Objects.equals(selectedPeer, username)) {
            updateSecurityAppearance();
            updateComposerState();
        }
    }

    public void showBanner(String message, BannerType type) {
        requireEdt();
        connectionBanner.setText(message);
        connectionBanner.setVisible(true);
        bannerTimer.restart();
        switch (type) {
            case CONNECTED -> {
                connectionBanner.setBackground(new Color(220, 252, 231));
                connectionBanner.setForeground(new Color(22, 101, 52));
            }
            case WARNING -> {
                connectionBanner.setBackground(new Color(254, 249, 195));
                connectionBanner.setForeground(new Color(133, 77, 14));
            }
            case ERROR -> {
                connectionBanner.setBackground(new Color(254, 226, 226));
                connectionBanner.setForeground(new Color(153, 27, 27));
            }
        }
    }

    public void setServerStatus(String text, Color dotColor) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setServerStatus(text, dotColor));
            return;
        }
        serverStatusLabel.setText(text);
        serverStatusLabel.setIcon(new DotIcon(dotColor, 8));
    }

    public String getServerStatusText() {
        return serverStatusLabel.getText();
    }

    private void renderSelectedConversation() {
        requireEdt();
        messagesPanel.removeAll();
        if (selectedPeer == null) {
            peerHeaderAvatar.setVisible(false);
            conversationTitle.setText("Chọn một cuộc trò chuyện");
            conversationSubtitle.setIcon(null);
            conversationSubtitle.setVisible(false);
        } else {
            FriendItem item = friends.get(selectedPeer);
            boolean isGroup = item != null && item.isGroup();
            if (isGroup) {
                peerHeaderAvatar.updateGroup(item.getDisplayName());
                peerHeaderAvatar.setVisible(true);
                conversationTitle.setText(item.getDisplayName());

                int memberCount = item.getMembers().size();
                conversationSubtitle.setIcon(null);
                conversationSubtitle.setText(memberCount + " thành viên");
                conversationSubtitle.setForeground(TEXT_MUTED);
                conversationSubtitle.setVisible(true);
            } else {
                boolean isOnline = item != null && item.isOnline();
                peerHeaderAvatar.update(selectedPeer, isOnline);
                peerHeaderAvatar.setVisible(true);
                conversationTitle.setText(selectedPeer);
                conversationSubtitle.setIcon(new DotIcon(isOnline ? ONLINE_COLOR : OFFLINE_COLOR, 7));
                conversationSubtitle.setText(isOnline ? "Trực tuyến" : "Ngoại tuyến");
                conversationSubtitle.setForeground(isOnline ? ONLINE_COLOR : TEXT_MUTED);
                conversationSubtitle.setVisible(true);
            }

            for (ChatMessage message : conversations.getOrDefault(
                    selectedPeer, List.of())) {
                String senderDisplay = message.sender() != null ? message.sender() : selectedPeer;
                messagesPanel.add(new MessageRowPanel(
                        senderDisplay,
                        message.content(),
                        message.timestamp,
                        message.sentByMe,
                        remainingSeconds(message,
                                System.currentTimeMillis()),
                        isGroup));
                messagesPanel.add(javax.swing.Box.createVerticalStrut(12));
            }
        }
        updateComposerState();
        updateSecurityAppearance();
        messagesPanel.revalidate();
        messagesPanel.repaint();
        SwingUtilities.invokeLater(() -> messagesScroll
                .getVerticalScrollBar().setValue(
                        messagesScroll.getVerticalScrollBar().getMaximum()));
    }

    private void updateComposerState() {
        boolean hasPeer = selectedPeer != null;
        FriendItem item = hasPeer ? friends.get(selectedPeer) : null;
        boolean isGroup = item != null && item.isGroup();
        boolean allowed = hasPeer
                && sendAllowed.getOrDefault(selectedPeer, false);
        messageInput.setEnabled(allowed);
        sendButton.setEnabled(allowed);
        sendButton.setBackground(allowed ? PRIMARY : new Color(226, 232, 240));
        sendButton.setForeground(allowed ? Color.WHITE : new Color(148, 163, 184));
        securityButton.setEnabled(hasPeer && !isGroup);
        securityButton.setVisible(hasPeer && !isGroup);
        groupInfoButton.setEnabled(hasPeer && isGroup);
        groupInfoButton.setVisible(hasPeer && isGroup);
        ttlSelector.setEnabled(allowed);
    }

    private void expireMessages(long now) {
        requireEdt();
        boolean selectedConversationChanged = false;
        boolean anyExpired = false;
        for (Map.Entry<String, List<ChatMessage>> entry
                : conversations.entrySet()) {
            List<ChatMessage> messages = entry.getValue();
            for (int index = messages.size() - 1; index >= 0; index--) {
                ChatMessage message = messages.get(index);
                if (message.expiresAt > 0 && message.expiresAt <= now) {
                    message.wipe();
                    messages.remove(index);
                    anyExpired = true;
                    if (entry.getKey().equals(selectedPeer)) {
                        selectedConversationChanged = true;
                    }
                }
            }
        }
        if (selectedConversationChanged) {
            renderSelectedConversation();
        } else if (selectedPeer != null) {
            boolean hasExpiringMessage = conversations.getOrDefault(
                    selectedPeer, List.of()).stream()
                    .anyMatch(message -> message.expiresAt > 0);
            if (hasExpiringMessage) {
                renderSelectedConversation();
            }
        }
        if (anyExpired) {
            friendList.repaint();
        }
    }

    private static int remainingSeconds(ChatMessage message, long now) {
        if (message.expiresAt == 0) {
            return 0;
        }
        long remainingMs = Math.max(0L, message.expiresAt - now);
        return (int) Math.max(1L, (remainingMs + 999L) / 1_000L);
    }

    private void updateSecurityAppearance() {
        boolean warning = selectedPeer != null
                && securityWarnings.getOrDefault(selectedPeer, false);
        Color background = warning
                ? SECURITY_WARNING_BACKGROUND : BG_WHITE;
        chatHeader.setBackground(background);
        messagesPanel.setBackground(background);
        messagesScroll.getViewport().setBackground(background);
        composerPanel.setBackground(background);
        conversationTitle.setOpaque(warning);
        conversationTitle.setBackground(background);
        conversationTitle.setForeground(warning
                ? SECURITY_WARNING_FOREGROUND : TEXT_DARK);
        conversationPanel.setBorder(warning
                ? BorderFactory.createLineBorder(
                        SECURITY_WARNING_FOREGROUND, 3)
                : BorderFactory.createEmptyBorder());
    }

    private void applyFriendFilter() {
        requireEdt();
        String rawQuery = addFriendField.getText();
        String query = removeAccents(rawQuery).trim().toLowerCase(Locale.ROOT);
        filteringFriends = true;
        try {
            displayedFriendModel.clear();
            for (int i = 0; i < friendModel.size(); i++) {
                FriendItem item = friendModel.get(i);
                if (activeTab == ConversationTab.FRIENDS && item.isGroup()) {
                    continue;
                }
                if (activeTab == ConversationTab.GROUPS && !item.isGroup()) {
                    continue;
                }
                if (query.isEmpty()
                        || removeAccents(item.getDisplayName()).toLowerCase(Locale.ROOT).contains(query)
                        || removeAccents(item.getUsername()).toLowerCase(Locale.ROOT).contains(query)) {
                    displayedFriendModel.addElement(item);
                }
            }
            if (selectedPeer != null) {
                FriendItem peerItem = friends.get(selectedPeer);
                if (peerItem != null && displayedFriendModel.contains(peerItem)) {
                    friendList.setSelectedValue(peerItem, false);
                }
            }
        } finally {
            filteringFriends = false;
        }
    }

    private void setConversationTab(ConversationTab tab) {
        this.activeTab = tab;
        updateTabStyles();
        applyFriendFilter();
    }

    private void updateTabStyles() {
        styleTabButton(tabAll, activeTab == ConversationTab.ALL);
        styleTabButton(tabFriends, activeTab == ConversationTab.FRIENDS);
        styleTabButton(tabGroups, activeTab == ConversationTab.GROUPS);
    }

    private void styleTabButton(JButton btn, boolean active) {
        btn.setFont(new Font("Segoe UI", active ? Font.BOLD : Font.PLAIN, 12));
        btn.setForeground(active ? PRIMARY : TEXT_MUTED);
        btn.setBackground(active ? new Color(239, 246, 255) : BG_WHITE);
        btn.setContentAreaFilled(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(active ? PRIMARY : BORDER_COLOR, 1, true),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
    }

    static String removeAccents(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        String withoutMarks = DIACRITICAL_MARKS.matcher(normalized).replaceAll("");
        return withoutMarks.replace('đ', 'd').replace('Đ', 'D');
    }

    int getFriendCount() {
        return friendModel.size();
    }

    int getDisplayedFriendCount() {
        return displayedFriendModel.size();
    }

    void setFriendFilterForTest(String query) {
        addFriendField.setText(query);
    }

    int getDisplayedMessageCount() {
        // Đếm số lượng MessageRowPanel được thêm vào messagesPanel
        int count = 0;
        for (Component comp : messagesPanel.getComponents()) {
            if (comp instanceof MessageRowPanel) {
                count++;
            }
        }
        return count;
    }

    boolean isSendEnabled() {
        return sendButton.isEnabled();
    }

    boolean isSecurityWarningVisible() {
        return selectedPeer != null
                && securityWarnings.getOrDefault(selectedPeer, false)
                && SECURITY_WARNING_BACKGROUND.equals(
                        messagesPanel.getBackground());
    }

    boolean isBannerVisible() {
        return connectionBanner.isVisible();
    }

    void expireMessagesForTest(long now) {
        expireMessages(now);
    }

    void selectTtlForTest(int ttlSeconds) {
        for (int index = 0; index < ttlSelector.getItemCount(); index++) {
            TtlOption option = ttlSelector.getItemAt(index);
            if (option.seconds == ttlSeconds) {
                ttlSelector.setSelectedIndex(index);
                return;
            }
        }
        throw new IllegalArgumentException("TTL khong co trong giao dien");
    }

    int getSelectedTtlForTest() {
        TtlOption option = (TtlOption) ttlSelector.getSelectedItem();
        return option != null ? option.seconds : 0;
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "Cap nhat Swing bat buoc chay tren EDT");
        }
    }

    public enum BannerType {
        CONNECTED,
        WARNING,
        ERROR
    }

    public interface Actions {
        void onLookupPeer(String username);

        void onSendMessage(String username, String content);

        default void onSendMessage(String username, String content,
                int ttlSeconds) {
            onSendMessage(username, content);
        }

        default void onSendGroupMessage(String groupId, String content) {
        }

        default void onSendGroupMessage(String groupId, String content,
                int ttlSeconds) {
            onSendGroupMessage(groupId, content);
        }

        void onOpenSecurity(String username);

        default String getMyFingerprint() {
            return "";
        }

        default void onRotateKey() {
        }

        void onClose();

        default void onCreateGroup(String groupName, List<String> memberUsernames) {
        }

        default void onCreateGroup(String groupId, String groupName, List<String> memberUsernames) {
            onCreateGroup(groupName, memberUsernames);
        }

        default e2ee.client.ChatClientController.MemberTrustState getMemberTrustState(String username) {
            return e2ee.client.ChatClientController.MemberTrustState.UNKNOWN;
        }
    }

    public static final class FriendItem {
        private final String username;
        private final String displayName;
        private boolean online;
        private final boolean group;
        private final List<String> members;

        public FriendItem(String username, boolean online) {
            this(username, username, online, false, List.of());
        }

        public FriendItem(String groupId, String displayName, boolean online,
                          boolean group, List<String> members) {
            this.username = Objects.requireNonNull(groupId, "username cannot be null");
            this.displayName = displayName != null ? displayName : groupId;
            this.online = online;
            this.group = group;
            this.members = members != null ? new ArrayList<>(members) : new ArrayList<>();
        }

        public String getUsername() {
            return username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean isOnline() {
            return online;
        }

        public boolean isGroup() {
            return group;
        }

        public List<String> getMembers() {
            return List.copyOf(members);
        }

        @Override
        public String toString() {
            return displayName != null ? displayName : username;
        }
    }

    private static final class ChatMessage {
        private final String sender;
        private final byte[] contentBytes;
        private final long timestamp;
        private final boolean sentByMe;
        private final long expiresAt;

        private ChatMessage(String content, long timestamp,
                boolean sentByMe, long expiresAt) {
            this(null, content, timestamp, sentByMe, expiresAt);
        }

        private ChatMessage(String sender, String content, long timestamp,
                boolean sentByMe, long expiresAt) {
            this.sender = sender;
            this.contentBytes = (content == null ? "" : content)
                    .getBytes(StandardCharsets.UTF_8);
            this.timestamp = timestamp;
            this.sentByMe = sentByMe;
            this.expiresAt = expiresAt;
        }

        private String sender() {
            return sender;
        }

        private String content() {
            return new String(contentBytes, StandardCharsets.UTF_8);
        }

        private void wipe() {
            ClientCryptoService.wipeMemory(contentBytes);
        }
    }

    private static final class TtlOption {
        private final String label;
        private final int seconds;

        private TtlOption(String label, int seconds) {
            this.label = label;
            this.seconds = seconds;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Component vẽ Avatar tròn kèm chấm trạng thái online/offline góc dưới.
     */
    private static final class AvatarBadgePanel extends JPanel {
        private String username;
        private boolean online;
        private final int size;
        private final Color customBgColor;
        private final boolean showStatusDot;

        private boolean isGroup;

        private AvatarBadgePanel(String username, boolean online, int size) {
            this(username, online, size, null, true);
        }

        private AvatarBadgePanel(String username, boolean online, int size,
                Color customBgColor) {
            this(username, online, size, customBgColor, true);
        }

        private AvatarBadgePanel(String username, boolean online, int size,
                Color customBgColor, boolean showStatusDot) {
            this.username = username != null ? username : "";
            this.online = online;
            this.size = size;
            this.customBgColor = customBgColor;
            this.showStatusDot = showStatusDot;
            setPreferredSize(new Dimension(size + 4, size + 4));
            setOpaque(false);
        }

        private void updateGroup(String groupName) {
            this.username = groupName != null ? groupName : "";
            this.online = true;
            this.isGroup = true;
            repaint();
        }

        private void update(String username, boolean online) {
            this.username = username != null ? username : "";
            this.online = online;
            this.isGroup = false;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (username.isEmpty()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            if (isGroup) {
                g2.setColor(PRIMARY);
                g2.fillRoundRect(0, 0, size, size, 12, 12);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int ox = (size - 18) / 2;
                int oy = (size - 18) / 2;
                g2.drawOval(ox + 3, oy + 2, 7, 7);
                g2.drawArc(ox + 0, oy + 10, 13, 9, 0, 180);
                g2.drawOval(ox + 10, oy + 3, 5, 5);
                g2.drawArc(ox + 8, oy + 10, 9, 7, 0, 160);
                g2.dispose();
                return;
            }

            // Vòng tròn avatar với màu nền cố định hoặc theo hash username
            Color bgColor = customBgColor;
            if (bgColor == null) {
                int hash = Math.abs(username.hashCode());
                Color[] colors = {
                    new Color(59, 130, 246),
                    new Color(16, 185, 129),
                    new Color(245, 158, 11),
                    new Color(139, 92, 246),
                    new Color(236, 72, 153),
                    new Color(14, 165, 233),
                    new Color(249, 115, 22),
                    new Color(168, 85, 247)
                };
                bgColor = colors[hash % colors.length];
            }

            g2.setColor(bgColor);
            g2.fillOval(0, 0, size, size);

            // 2 chữ cái đầu viết hoa nếu có
            String initial = username.length() >= 2
                    ? username.substring(0, 2).toUpperCase()
                    : username.substring(0, 1).toUpperCase();
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Segoe UI", Font.BOLD, (int) (size * 0.40)));
            FontMetrics fm = g2.getFontMetrics();
            int tx = (size - fm.stringWidth(initial)) / 2;
            int ty = (size - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(initial, tx, ty);

            // Chấm trạng thái góc dưới phải (Online: Xanh, Offline: Xám) nếu được bật
            if (showStatusDot) {
                int dotSize = (int) Math.max(8, size * 0.28);
                int dx = size - dotSize;
                int dy = size - dotSize;

                g2.setColor(Color.WHITE);
                g2.fillOval(dx - 1, dy - 1, dotSize + 2, dotSize + 2); // Viền trắng
                g2.setColor(online ? ONLINE_COLOR : OFFLINE_COLOR);
                g2.fillOval(dx, dy, dotSize, dotSize);
            }

            g2.dispose();
        }
    }

    /**
     * Icon kính lúp vẽ vector bằng Java2D (không dùng emoji).
     */
    private static final class SearchIcon implements Icon {
        private final Color color;

        private SearchIcon(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawOval(x + 1, y + 1, 9, 9);
            g2.drawLine(x + 8, y + 8, x + 13, y + 13);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }

    /**
     * Icon ổ khóa vẽ vector bằng Java2D cho nút Đối soát (không dùng emoji).
     */
    private static final class LockIcon implements Icon {
        private final Color color;

        private LockIcon(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            // Quai khóa
            g2.drawRoundRect(x + 3, y + 1, 8, 7, 5, 5);
            // Thân khóa
            g2.drawRoundRect(x + 1, y + 5, 12, 9, 3, 3);
            // Lỗ khóa
            g2.fillOval(x + 6, y + 8, 2, 3);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 15;
        }

        @Override
        public int getIconHeight() {
            return 15;
        }
    }

    /**
     * Icon vòng tròn chấm tâm vẽ vector bằng Java2D cho nút Thông tin nhóm (không dùng emoji).
     */
    private static final class InfoCircleIcon implements Icon {
        private final Color color;
        private final int size;

        private InfoCircleIcon(Color color, int size) {
            this.color = color;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int pad = 2;
            int diameter = size - pad * 2;
            g2.drawOval(x + pad, y + pad, diameter, diameter);
            int dot = 4;
            g2.fillOval(x + (size - dot) / 2, y + (size - dot) / 2, dot, dot);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    /**
     * Icon chìa khóa vẽ vector bằng Java2D cho nút Đổi khóa (không dùng emoji).
     */
    private static final class KeyIcon implements Icon {
        private final Color color;

        private KeyIcon(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            // Vòng đầu chìa khóa góc trên phải
            g2.drawOval(x + 8, y + 2, 7, 7);

            // Thân chìa chéo xuống góc dưới trái
            g2.drawLine(x + 9, y + 8, x + 2, y + 15);

            // Răng chìa chéo vuông góc sang dưới phải
            g2.drawLine(x + 4, y + 13, x + 6, y + 15);
            g2.drawLine(x + 6, y + 11, x + 8, y + 13);

            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }
    }

    /**
     * Icon chấm tròn trạng thái vẽ vector bằng Java2D.
     */
    private static final class DotIcon implements Icon {
        private final Color color;
        private final int size;

        private DotIcon(Color color, int size) {
            this.color = color;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x, y, size, size);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    private String getLastMessagePreview(String username) {
        List<ChatMessage> list = conversations.get(username);
        if (list == null || list.isEmpty()) {
            return "";
        }
        long now = System.currentTimeMillis();
        for (int i = list.size() - 1; i >= 0; i--) {
            ChatMessage message = list.get(i);
            if (message.expiresAt == 0 || message.expiresAt > now) {
                String text = message.content();
                if (text == null || text.isBlank()) {
                    continue;
                }
                return message.sentByMe ? "Bạn: " + text.trim() : text.trim();
            }
        }
        return "";
    }

    private static String formatMessagePreview(String rawText, FontMetrics fm, int maxWidth) {
        if (rawText == null || rawText.isBlank()) {
            return "Chưa có tin nhắn";
        }
        String singleLine = rawText.replaceAll("\\s+", " ").trim();
        if (singleLine.isEmpty()) {
            return "Chưa có tin nhắn";
        }
        if (fm == null || maxWidth <= 0) {
            return singleLine.length() > 25 ? singleLine.substring(0, 24) + "…" : singleLine;
        }
        if (fm.stringWidth(singleLine) <= maxWidth) {
            return singleLine;
        }
        String ellipsis = "…";
        int ellipsisWidth = fm.stringWidth(ellipsis);
        if (ellipsisWidth >= maxWidth) {
            return ellipsis;
        }
        int low = 0;
        int high = singleLine.length();
        int best = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (fm.stringWidth(singleLine.substring(0, mid)) + ellipsisWidth <= maxWidth) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return singleLine.substring(0, best) + ellipsis;
    }

    /**
     * Renderer hiện đại cho danh sách bạn bè (Item thẻ bo góc, avatar chữ cái, preview tin nhắn).
     */
    private final class ModernFriendCellRenderer
            extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list,
                Object value, int index, boolean selected,
                boolean cellHasFocus) {
            JPanel card = new JPanel(new BorderLayout(10, 0)) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    if (selected) {
                        g2.setColor(PRIMARY);
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                    } else {
                        g2.setColor(BG_SIDEBAR);
                        g2.fillRect(0, 0, getWidth(), getHeight());
                    }
                    g2.dispose();
                }
            };
            card.setOpaque(false);
            card.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

            if (value instanceof FriendItem friend) {
                AvatarBadgePanel avatar = new AvatarBadgePanel(
                        friend.isGroup() ? friend.getDisplayName() : friend.getUsername(),
                        friend.isOnline(), 38, null, !friend.isGroup());
                if (friend.isGroup()) {
                    avatar.updateGroup(friend.getDisplayName());
                }

                JPanel textPanel = new JPanel();
                textPanel.setOpaque(false);
                textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));

                JLabel nameLabel = new JLabel(friend.getDisplayName());
                nameLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
                nameLabel.setForeground(selected ? Color.WHITE : TEXT_DARK);

                Font previewFont = new Font("Segoe UI", Font.PLAIN, 12);
                FontMetrics fm = list != null ? list.getFontMetrics(previewFont) : null;
                int listWidth = list != null ? list.getWidth() : 0;
                int maxTextWidth = listWidth > 0 ? listWidth - 78 : 170;

                String rawPreview = getLastMessagePreview(friend.getUsername());
                if (rawPreview.isEmpty() && friend.isGroup()) {
                    rawPreview = friend.getMembers().size() + " thành viên";
                }
                String displayPreview = formatMessagePreview(rawPreview, fm, maxTextWidth);

                JLabel previewLabel = new JLabel(displayPreview);
                previewLabel.setFont(previewFont);
                previewLabel.setForeground(selected
                        ? new Color(224, 231, 255)
                        : TEXT_MUTED);

                textPanel.add(nameLabel);
                textPanel.add(javax.swing.Box.createVerticalStrut(2));
                textPanel.add(previewLabel);

                card.add(avatar, BorderLayout.WEST);
                card.add(textPanel, BorderLayout.CENTER);
            }
            return card;
        }
    }

    /**
     * Component hiển thị từng dòng tin nhắn kèm avatar, bong bóng bo tròn và trạng thái gửi.
     */
    private static final class MessageRowPanel extends JPanel {
        private MessageRowPanel(String peerUsername, String content,
                long timestamp, boolean sentByMe, int remainingSeconds) {
            this(peerUsername, content, timestamp, sentByMe, remainingSeconds, false);
        }

        private MessageRowPanel(String senderUsername, String content,
                long timestamp, boolean sentByMe, int remainingSeconds, boolean isGroup) {
            super(new BorderLayout());
            setOpaque(false);

            String timeStr = TIME_FORMAT.format(
                    Instant.ofEpochMilli(timestamp)
                            .atZone(ZoneId.systemDefault()));

            if (sentByMe) {
                // Tin nhắn của tôi: Căn phải, nền xanh, không avatar
                JTextArea msgText = createTextArea(content, Color.WHITE);
                msgText.setAlignmentX(Component.LEFT_ALIGNMENT);

                JLabel metaLabel = new JLabel(
                        (remainingSeconds > 0
                                ? "TTL " + remainingSeconds + "s   " : "")
                                + timeStr);
                metaLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                metaLabel.setForeground(new Color(191, 219, 254));
                metaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

                BubblePanel bubble = new BubblePanel(
                        18, PRIMARY, PRIMARY, msgText, metaLabel, content, false);

                add(bubble, BorderLayout.EAST);
            } else {
                // Tin nhắn đối phương: Căn trái, có avatar nhỏ bên cạnh, nền xám nhạt
                JPanel leftContainer = new JPanel(new BorderLayout(8, 0));
                leftContainer.setOpaque(false);

                AvatarBadgePanel avatar = new AvatarBadgePanel(senderUsername, false, 30, null, false);

                JPanel bubbleWrapper = new JPanel();
                bubbleWrapper.setLayout(new BoxLayout(bubbleWrapper, BoxLayout.Y_AXIS));
                bubbleWrapper.setOpaque(false);

                if (isGroup) {
                    JLabel senderLabel = new JLabel(senderUsername);
                    senderLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
                    senderLabel.setForeground(new Color(51, 65, 85));
                    senderLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 3, 0));
                    bubbleWrapper.add(senderLabel);
                }

                JTextArea msgText = createTextArea(content, TEXT_DARK);
                msgText.setAlignmentX(Component.LEFT_ALIGNMENT);

                JLabel metaLabel = new JLabel(
                        (remainingSeconds > 0
                                ? "Tự hủy: " + remainingSeconds + "s   " : "")
                                + timeStr);
                metaLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                metaLabel.setForeground(TEXT_MUTED);
                metaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

                BubblePanel bubble = new BubblePanel(
                        18, new Color(241, 245, 249), BORDER_COLOR,
                        msgText, metaLabel, content, true);
                bubbleWrapper.add(bubble);

                leftContainer.add(avatar, BorderLayout.WEST);
                leftContainer.add(bubbleWrapper, BorderLayout.CENTER);

                add(leftContainer, BorderLayout.WEST);
            }
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        private static JTextArea createTextArea(String content, Color fgColor) {
            JTextArea ta = new JTextArea(content == null ? "" : content);
            ta.setEditable(false);
            ta.setFocusable(false);
            ta.setLineWrap(true);
            ta.setWrapStyleWord(true);
            ta.setOpaque(false);
            ta.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            ta.setForeground(fgColor);
            ta.setBorder(BorderFactory.createEmptyBorder());
            return ta;
        }
    }

    /**
     * Bong bóng tin nhắn tự động co giãn theo chiều rộng khung hội thoại.
     */
    private static final class BubblePanel extends RoundedPanel {
        private final JTextArea msgText;
        private final JLabel metaLabel;
        private final String content;
        private final boolean hasAvatarOffset;

        private BubblePanel(int radius, Color bgColor, Color borderColor,
                JTextArea msgText, JLabel metaLabel, String content, boolean hasAvatarOffset) {
            super(radius, bgColor, borderColor);
            this.msgText = msgText;
            this.metaLabel = metaLabel;
            this.content = content != null ? content : "";
            this.hasAvatarOffset = hasAvatarOffset;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(10, 16, 8, 16));
            add(msgText);
            add(javax.swing.Box.createVerticalStrut(4));
            add(metaLabel);
        }

        @Override
        public Dimension getPreferredSize() {
            int containerWidth = getParentContainerWidth();
            int available = Math.max(260, containerWidth - (hasAvatarOffset ? 72 : 32));
            int maxBubbleWidth = Math.max(220, (int) (available * 0.68));
            int maxTextWidth = Math.max(160, maxBubbleWidth - 32);

            FontMetrics fm = msgText.getFontMetrics(msgText.getFont());
            String[] lines = content.split("\n", -1);
            int naturalWidth = 0;
            for (String line : lines) {
                int lineW = fm.stringWidth(line) + 6;
                if (lineW > naturalWidth) {
                    naturalWidth = lineW;
                }
            }
            int metaWidth = metaLabel.getPreferredSize().width;
            int unconstrainedWidth = Math.max(naturalWidth, metaWidth);

            int targetTextWidth = Math.min(unconstrainedWidth, maxTextWidth);
            msgText.setSize(targetTextWidth, Short.MAX_VALUE);
            int targetTextHeight = msgText.getPreferredSize().height;

            msgText.setPreferredSize(new Dimension(targetTextWidth, targetTextHeight));
            int bubbleW = targetTextWidth + 32;
            int bubbleH = targetTextHeight + 4 + metaLabel.getPreferredSize().height + 18;
            return new Dimension(bubbleW, bubbleH);
        }

        private int getParentContainerWidth() {
            Component c = getParent();
            while (c != null) {
                if (c instanceof JViewport) {
                    int w = c.getWidth();
                    if (w > 0) {
                        return w;
                    }
                }
                if (c instanceof JScrollPane) {
                    int w = ((JScrollPane) c).getViewport().getWidth();
                    if (w > 0) {
                        return w;
                    }
                }
                c = c.getParent();
            }
            return 600;
        }
    }

    /**
     * Vùng chứa tin nhắn có khả năng bám sát độ rộng của viewport.
     */
    private static final class ScrollableMessagesPanel extends JPanel implements Scrollable {
        private ScrollableMessagesPanel() {
            super();
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /**
     * Panel tiện ích hỗ trợ vẽ nền bo góc mượt mà và đường viền.
     */
    private static class RoundedPanel extends JPanel {
        private final int cornerRadius;
        private final Color bgColor;
        private final Color borderColor;

        private RoundedPanel(int radius, Color bgColor, Color borderColor) {
            super();
            this.cornerRadius = radius;
            this.bgColor = bgColor;
            this.borderColor = borderColor;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(bgColor != null ? bgColor : getBackground());
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1,
                    cornerRadius, cornerRadius);

            if (borderColor != null) {
                g2.setColor(borderColor);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1,
                        cornerRadius, cornerRadius);
            }
            g2.dispose();
        }
    }

    @Override
    public void dispose() {
        expiryTimer.stop();
        bannerTimer.stop();
        for (List<ChatMessage> messages : conversations.values()) {
            for (ChatMessage message : messages) {
                message.wipe();
            }
        }
        conversations.clear();
        super.dispose();
    }
}
