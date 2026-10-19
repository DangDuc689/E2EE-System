package e2ee.client.ui;

import e2ee.client.crypto.ClientCryptoService;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    private static final Color SECURITY_WARNING_BACKGROUND =
            new Color(254, 226, 226);
    private static final Color SECURITY_WARNING_FOREGROUND =
            new Color(153, 27, 27);

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");

    private final String myUsername;
    private final Actions actions;
    private final DefaultListModel<FriendItem> friendModel =
            new DefaultListModel<>();
    private final JList<FriendItem> friendList = new JList<>(friendModel);
    private final JTextField addFriendField = new JTextField();
    private final JPanel messagesPanel = new JPanel();
    private final JScrollPane messagesScroll;
    private final JPanel conversationPanel = new JPanel(new BorderLayout());
    private final JPanel composerPanel = new JPanel(new BorderLayout(8, 8));
    private final JTextArea messageInput = new JTextArea(2, 24);
    private final JButton sendButton = new JButton("Gửi");
    private final JComboBox<TtlOption> ttlSelector = new JComboBox<>(
            new TtlOption[]{
                new TtlOption("Không tự hủy", 0),
                new TtlOption("Tự hủy: 10s", 10),
                new TtlOption("Tự hủy: 30s", 30)
            });
    private final JButton securityButton = new JButton("🔒 Đối soát");
    private final JLabel conversationTitle = new JLabel("Chọn một người bạn");
    private final JLabel connectionBanner = new JLabel(
            "Đã kết nối an toàn", SwingConstants.CENTER);

    private final Map<String, FriendItem> friends = new HashMap<>();
    private final Map<String, List<ChatMessage>> conversations =
            new HashMap<>();
    private final Map<String, Boolean> sendAllowed = new HashMap<>();
    private final Map<String, Boolean> securityWarnings = new HashMap<>();
    private final Timer expiryTimer = new Timer(
            1_000, event -> expireMessages(System.currentTimeMillis()));

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
        splitPane.setDividerLocation(280);
        splitPane.setDividerSize(1);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setResizeWeight(0.25);
        splitPane.setContinuousLayout(true);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG_WHITE);
        root.add(connectionBanner, BorderLayout.NORTH);
        root.add(splitPane, BorderLayout.CENTER);
        setContentPane(root);
    }

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 12));
        sidebar.setBackground(BG_SIDEBAR);
        sidebar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER_COLOR),
                BorderFactory.createEmptyBorder(14, 14, 14, 14)));
        sidebar.setPreferredSize(new Dimension(280, 0));

        // 1. Thẻ hồ sơ của tôi (Profile Card)
        JPanel profileCard = new RoundedPanel(12, BG_WHITE, BORDER_COLOR);
        profileCard.setLayout(new BorderLayout(10, 0));
        profileCard.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        AvatarBadgePanel myAvatar = new AvatarBadgePanel(myUsername, true, 42);
        JPanel myInfoPanel = new JPanel();
        myInfoPanel.setOpaque(false);
        myInfoPanel.setLayout(new BoxLayout(myInfoPanel, BoxLayout.Y_AXIS));

        JLabel myNameLabel = new JLabel(myUsername);
        myNameLabel.setFont(myNameLabel.getFont().deriveFont(Font.BOLD, 15f));
        myNameLabel.setForeground(TEXT_DARK);

        JLabel myStatusLabel = new JLabel("Online");
        myStatusLabel.setFont(myStatusLabel.getFont().deriveFont(Font.PLAIN, 12.5f));
        myStatusLabel.setForeground(new Color(22, 163, 74));

        myInfoPanel.add(myNameLabel);
        myInfoPanel.add(javax.swing.Box.createVerticalStrut(2));
        myInfoPanel.add(myStatusLabel);

        profileCard.add(myAvatar, BorderLayout.WEST);
        profileCard.add(myInfoPanel, BorderLayout.CENTER);

        // 2. Ô tìm kiếm và thêm bạn bè
        JPanel searchBox = new RoundedPanel(10, BG_WHITE, BORDER_COLOR);
        searchBox.setLayout(new BorderLayout(6, 0));
        searchBox.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 8));

        addFriendField.setBorder(BorderFactory.createEmptyBorder());
        addFriendField.setFont(addFriendField.getFont().deriveFont(Font.PLAIN, 13f));
        addFriendField.setToolTipText("Tìm kiếm hoặc thêm bạn...");

        JButton addButton = new JButton("Thêm");
        addButton.setFont(addButton.getFont().deriveFont(Font.BOLD, 12f));
        addButton.setBackground(BG_SIDEBAR);
        addButton.setForeground(PRIMARY);
        addButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        addButton.setFocusPainted(false);
        addButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addButton.addActionListener(event -> submitFriendLookup());
        addFriendField.addActionListener(event -> submitFriendLookup());

        searchBox.add(addFriendField, BorderLayout.CENTER);
        searchBox.add(addButton, BorderLayout.EAST);

        // Header trên của Sidebar
        JPanel sidebarTop = new JPanel(new BorderLayout(0, 10));
        sidebarTop.setOpaque(false);
        sidebarTop.add(profileCard, BorderLayout.NORTH);
        sidebarTop.add(searchBox, BorderLayout.SOUTH);

        // 3. Danh sách bạn bè
        friendList.setBackground(BG_SIDEBAR);
        friendList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        friendList.setCellRenderer(new ModernFriendCellRenderer());
        friendList.setBorder(BorderFactory.createEmptyBorder());

        JScrollPane friendScroll = new JScrollPane(friendList);
        friendScroll.setBorder(BorderFactory.createEmptyBorder());
        friendScroll.setBackground(BG_SIDEBAR);
        friendScroll.getViewport().setBackground(BG_SIDEBAR);

        sidebar.add(sidebarTop, BorderLayout.NORTH);
        sidebar.add(friendScroll, BorderLayout.CENTER);
        return sidebar;
    }

    private JPanel createConversationPanel() {
        conversationPanel.setBackground(BG_WHITE);

        // Header cuộc trò chuyện: Hiển thị tên người bạn đang chat
        JPanel chatHeader = new JPanel(new BorderLayout());
        chatHeader.setBackground(BG_WHITE);
        chatHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR),
                BorderFactory.createEmptyBorder(16, 24, 16, 24)));

        conversationTitle.setFont(
                conversationTitle.getFont().deriveFont(Font.BOLD, 20f));
        conversationTitle.setForeground(TEXT_DARK);
        chatHeader.add(conversationTitle, BorderLayout.WEST);

        // Vùng hiển thị tin nhắn
        messagesPanel.setLayout(
                new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(BG_WHITE);
        messagesPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        messagesScroll.setBorder(BorderFactory.createEmptyBorder());
        messagesScroll.setBackground(BG_WHITE);
        messagesScroll.getViewport().setBackground(BG_WHITE);
        messagesScroll.getVerticalScrollBar().setUnitIncrement(16);

        conversationPanel.add(chatHeader, BorderLayout.NORTH);
        conversationPanel.add(messagesScroll, BorderLayout.CENTER);
        conversationPanel.add(createComposer(), BorderLayout.SOUTH);
        return conversationPanel;
    }

    private JPanel createComposer() {
        composerPanel.setBackground(BG_WHITE);
        composerPanel.setBorder(BorderFactory.createEmptyBorder(12, 24, 18, 24));

        // Thanh soạn thảo bo tròn hiện đại
        JPanel composerBar = new RoundedPanel(26, new Color(248, 250, 252), BORDER_COLOR);
        composerBar.setLayout(new BorderLayout(10, 0));
        composerBar.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 8));

        messageInput.setLineWrap(true);
        messageInput.setWrapStyleWord(true);
        messageInput.setEnabled(false);
        messageInput.setOpaque(false);
        messageInput.setFont(messageInput.getFont().deriveFont(Font.PLAIN, 14f));
        messageInput.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        tools.setOpaque(false);

        ttlSelector.setFont(ttlSelector.getFont().deriveFont(Font.PLAIN, 12.5f));
        ttlSelector.setToolTipText("Thời gian tồn tại của tin nhắn");

        sendButton.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        sendButton.setFont(sendButton.getFont().deriveFont(Font.BOLD, 13f));
        sendButton.setBackground(new Color(226, 232, 240));
        sendButton.setForeground(new Color(148, 163, 184));
        sendButton.setFocusPainted(false);
        sendButton.setBorder(BorderFactory.createEmptyBorder(7, 18, 7, 18));
        sendButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendButton.setEnabled(false);

        tools.add(ttlSelector);
        tools.add(sendButton);

        composerBar.add(messageInput, BorderLayout.CENTER);
        composerBar.add(tools, BorderLayout.EAST);

        composerPanel.add(composerBar, BorderLayout.CENTER);
        return composerPanel;
    }

    private void bindActions() {
        friendList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                FriendItem item = friendList.getSelectedValue();
                selectedPeer = item != null ? item.username : null;
                renderSelectedConversation();
            }
        });
        sendButton.addActionListener(event -> submitMessage());
        securityButton.addActionListener(event -> {
            if (selectedPeer != null) {
                actions.onOpenSecurity(selectedPeer);
            }
        });
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
        TtlOption option = (TtlOption) ttlSelector.getSelectedItem();
        int ttlSeconds = option != null ? option.seconds : 0;
        actions.onSendMessage(selectedPeer, content, ttlSeconds);
        messageInput.setText("");
    }

    public void updatePresence(String username, boolean online) {
        requireEdt();
        FriendItem friend = friends.get(username);
        if (friend == null) {
            friend = new FriendItem(username, online);
            friends.put(username, friend);
            friendModel.addElement(friend);
        } else {
            friend.online = online;
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
    }

    public void selectPeer(String username) {
        requireEdt();
        FriendItem item = friends.get(username);
        if (item != null) {
            friendList.setSelectedValue(item, true);
        }
    }

    public void addMessage(String peerUsername, String content,
            long timestamp, boolean sentByMe) {
        addMessage(peerUsername, content, timestamp, sentByMe, 0);
    }

    public void addMessage(String peerUsername, String content,
            long timestamp, boolean sentByMe, int ttlSeconds) {
        requireEdt();
        if (ttlSeconds < 0 || ttlSeconds > 86_400) {
            throw new IllegalArgumentException("TTL khong hop le");
        }
        conversations.computeIfAbsent(peerUsername,
                ignored -> new ArrayList<>())
                .add(new ChatMessage(content, timestamp, sentByMe,
                        ttlSeconds == 0 ? 0L
                                : timestamp + ttlSeconds * 1_000L));
        if (peerUsername.equals(selectedPeer)) {
            renderSelectedConversation();
        }
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

    private void renderSelectedConversation() {
        requireEdt();
        messagesPanel.removeAll();
        if (selectedPeer == null) {
            conversationTitle.setText("Chọn một người bạn");
        } else {
            conversationTitle.setText(selectedPeer);
            for (ChatMessage message : conversations.getOrDefault(
                    selectedPeer, List.of())) {
                messagesPanel.add(new MessageRowPanel(
                        selectedPeer,
                        message.content(),
                        message.timestamp,
                        message.sentByMe,
                        remainingSeconds(message,
                                System.currentTimeMillis())));
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
        boolean allowed = hasPeer
                && sendAllowed.getOrDefault(selectedPeer, false);
        messageInput.setEnabled(allowed);
        sendButton.setEnabled(allowed);
        sendButton.setBackground(allowed ? PRIMARY : new Color(226, 232, 240));
        sendButton.setForeground(allowed ? Color.WHITE : new Color(148, 163, 184));
        securityButton.setEnabled(hasPeer);
        ttlSelector.setEnabled(allowed);
    }

    private void expireMessages(long now) {
        requireEdt();
        boolean selectedConversationChanged = false;
        for (Map.Entry<String, List<ChatMessage>> entry
                : conversations.entrySet()) {
            List<ChatMessage> messages = entry.getValue();
            for (int index = messages.size() - 1; index >= 0; index--) {
                ChatMessage message = messages.get(index);
                if (message.expiresAt > 0 && message.expiresAt <= now) {
                    message.wipe();
                    messages.remove(index);
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
        messagesPanel.setBackground(background);
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

    int getFriendCount() {
        return friendModel.size();
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

        void onOpenSecurity(String username);

        void onClose();
    }

    public static final class FriendItem {
        private final String username;
        private boolean online;

        private FriendItem(String username, boolean online) {
            this.username = username;
            this.online = online;
        }

        public String getUsername() {
            return username;
        }

        public boolean isOnline() {
            return online;
        }

        @Override
        public String toString() {
            return username;
        }
    }

    private static final class ChatMessage {
        private final byte[] contentBytes;
        private final long timestamp;
        private final boolean sentByMe;
        private final long expiresAt;

        private ChatMessage(String content, long timestamp,
                boolean sentByMe, long expiresAt) {
            this.contentBytes = (content == null ? "" : content)
                    .getBytes(StandardCharsets.UTF_8);
            this.timestamp = timestamp;
            this.sentByMe = sentByMe;
            this.expiresAt = expiresAt;
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
        private final String username;
        private final boolean online;
        private final int size;

        private AvatarBadgePanel(String username, boolean online, int size) {
            this.username = username != null ? username : "";
            this.online = online;
            this.size = size;
            setPreferredSize(new Dimension(size + 4, size + 4));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            // Vòng tròn avatar với màu nền theo hash username
            int hash = Math.abs(username.hashCode());
            Color[] colors = {
                new Color(59, 130, 246),
                new Color(16, 185, 129),
                new Color(245, 158, 11),
                new Color(139, 92, 246),
                new Color(236, 72, 153)
            };
            Color bgColor = colors[hash % colors.length];

            g2.setColor(bgColor);
            g2.fillOval(0, 0, size, size);

            // Chữ cái đầu viết hoa
            String initial = username.isEmpty() ? "?"
                    : String.valueOf(Character.toUpperCase(username.charAt(0)));
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Segoe UI", Font.BOLD, (int) (size * 0.45)));
            java.awt.FontMetrics fm = g2.getFontMetrics();
            int tx = (size - fm.stringWidth(initial)) / 2;
            int ty = (size - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(initial, tx, ty);

            // Chấm trạng thái góc dưới phải (Online: Xanh, Offline: Xám)
            int dotSize = (int) Math.max(8, size * 0.28);
            int dx = size - dotSize;
            int dy = size - dotSize;

            g2.setColor(Color.WHITE);
            g2.fillOval(dx - 1, dy - 1, dotSize + 2, dotSize + 2); // Viền trắng
            g2.setColor(online ? ONLINE_COLOR : OFFLINE_COLOR);
            g2.fillOval(dx, dy, dotSize, dotSize);

            g2.dispose();
        }
    }

    /**
     * Renderer hiện đại cho danh sách bạn bè (Item thẻ bo góc, avatar chữ cái, thời gian).
     */
    private static final class ModernFriendCellRenderer
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
                        friend.getUsername(), friend.isOnline(), 38);

                JPanel textPanel = new JPanel();
                textPanel.setOpaque(false);
                textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));

                JLabel nameLabel = new JLabel(friend.getUsername());
                nameLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
                nameLabel.setForeground(selected ? Color.WHITE : TEXT_DARK);

                JLabel statusLabel = new JLabel(
                        friend.isOnline() ? "Online" : "Offline");
                statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                statusLabel.setForeground(selected
                        ? new Color(224, 231, 255)
                        : (friend.isOnline() ? ONLINE_COLOR : TEXT_MUTED));

                textPanel.add(nameLabel);
                textPanel.add(javax.swing.Box.createVerticalStrut(2));
                textPanel.add(statusLabel);

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
            super(new BorderLayout());
            setOpaque(false);

            String timeStr = TIME_FORMAT.format(
                    Instant.ofEpochMilli(timestamp)
                            .atZone(ZoneId.systemDefault()));

            if (sentByMe) {
                // Tin nhắn của tôi: Căn phải, nền xanh, không avatar
                JPanel bubble = new RoundedPanel(18, PRIMARY, PRIMARY);
                bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
                bubble.setBorder(BorderFactory.createEmptyBorder(10, 16, 8, 16));

                JTextArea msgText = createTextArea(content, Color.WHITE);
                msgText.setAlignmentX(Component.LEFT_ALIGNMENT);

                JLabel metaLabel = new JLabel(
                        (remainingSeconds > 0
                                ? "TTL " + remainingSeconds + "s   " : "")
                                + timeStr);
                metaLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                metaLabel.setForeground(new Color(191, 219, 254));
                metaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

                bubble.add(msgText);
                bubble.add(javax.swing.Box.createVerticalStrut(4));
                bubble.add(metaLabel);

                add(bubble, BorderLayout.EAST);
            } else {
                // Tin nhắn đối phương: Căn trái, có avatar nhỏ bên cạnh, nền xám nhạt
                JPanel leftContainer = new JPanel(new BorderLayout(8, 0));
                leftContainer.setOpaque(false);

                AvatarBadgePanel avatar = new AvatarBadgePanel(peerUsername, true, 30);

                JPanel bubble = new RoundedPanel(18, new Color(241, 245, 249), BORDER_COLOR);
                bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
                bubble.setBorder(BorderFactory.createEmptyBorder(10, 16, 8, 16));

                JTextArea msgText = createTextArea(content, TEXT_DARK);
                msgText.setAlignmentX(Component.LEFT_ALIGNMENT);

                JLabel metaLabel = new JLabel(
                        (remainingSeconds > 0
                                ? "Tự hủy: " + remainingSeconds + "s   " : "")
                                + timeStr);
                metaLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                metaLabel.setForeground(TEXT_MUTED);
                metaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

                bubble.add(msgText);
                bubble.add(javax.swing.Box.createVerticalStrut(4));
                bubble.add(metaLabel);

                leftContainer.add(avatar, BorderLayout.WEST);
                leftContainer.add(bubble, BorderLayout.CENTER);

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
        for (List<ChatMessage> messages : conversations.values()) {
            for (ChatMessage message : messages) {
                message.wipe();
            }
        }
        conversations.clear();
        super.dispose();
    }
}
