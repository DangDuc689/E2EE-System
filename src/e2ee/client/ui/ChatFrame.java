package e2ee.client.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
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
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cửa sổ chat 1-1 chính của E2EE Client.
 */
public final class ChatFrame extends JFrame {
    private static final Color PRIMARY = new Color(37, 99, 235);
    private static final Color BACKGROUND = new Color(248, 250, 252);
    private static final Color SECURITY_WARNING_BACKGROUND =
            new Color(254, 226, 226);
    private static final Color SECURITY_WARNING_FOREGROUND =
            new Color(153, 27, 27);

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
    private final JButton securityButton = new JButton("🔒 Đối soát");
    private final JLabel conversationTitle = new JLabel(
            "Chọn một người bạn", SwingConstants.CENTER);
    private final JLabel connectionBanner = new JLabel(
            "Đã kết nối an toàn", SwingConstants.CENTER);

    private final Map<String, FriendItem> friends = new HashMap<>();
    private final Map<String, List<ChatMessage>> conversations =
            new HashMap<>();
    private final Map<String, Boolean> sendAllowed = new HashMap<>();
    private final Map<String, Boolean> securityWarnings = new HashMap<>();

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
    }

    private void initializeUi() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(820, 560));
        setSize(1040, 680);
        setLocationByPlatform(true);

        connectionBanner.setOpaque(true);
        connectionBanner.setBackground(new Color(220, 252, 231));
        connectionBanner.setForeground(new Color(22, 101, 52));
        connectionBanner.setBorder(
                BorderFactory.createEmptyBorder(7, 10, 7, 10));

        JSplitPane splitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                createSidebar(), createConversationPanel());
        splitPane.setDividerLocation(250);
        splitPane.setResizeWeight(0.24);
        splitPane.setContinuousLayout(true);

        JPanel root = new JPanel(new BorderLayout());
        root.add(connectionBanner, BorderLayout.NORTH);
        root.add(splitPane, BorderLayout.CENTER);
        setContentPane(root);
    }

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 10));
        sidebar.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        sidebar.setPreferredSize(new Dimension(250, 0));

        JLabel account = new JLabel("Bạn: " + myUsername);
        account.setFont(account.getFont().deriveFont(Font.BOLD, 16f));

        JPanel addPanel = new JPanel(new BorderLayout(6, 0));
        addFriendField.setToolTipText("Nhập username cần tra cứu");
        JButton addButton = new JButton("Thêm");
        addButton.addActionListener(event -> submitFriendLookup());
        addFriendField.addActionListener(event -> submitFriendLookup());
        addPanel.add(addFriendField, BorderLayout.CENTER);
        addPanel.add(addButton, BorderLayout.EAST);

        JPanel sidebarHeader = new JPanel(new BorderLayout(0, 10));
        sidebarHeader.add(account, BorderLayout.NORTH);
        sidebarHeader.add(addPanel, BorderLayout.SOUTH);

        friendList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        friendList.setCellRenderer(new FriendListCellRenderer());
        sidebar.add(sidebarHeader, BorderLayout.NORTH);
        sidebar.add(new JScrollPane(friendList), BorderLayout.CENTER);
        return sidebar;
    }

    private JPanel createConversationPanel() {
        conversationTitle.setFont(
                conversationTitle.getFont().deriveFont(Font.BOLD, 17f));
        conversationTitle.setBorder(
                BorderFactory.createEmptyBorder(12, 12, 12, 12));

        messagesPanel.setLayout(
                new javax.swing.BoxLayout(messagesPanel,
                        javax.swing.BoxLayout.Y_AXIS));
        messagesPanel.setBackground(BACKGROUND);
        messagesScroll.setBorder(BorderFactory.createEmptyBorder());
        messagesScroll.getVerticalScrollBar().setUnitIncrement(16);

        conversationPanel.add(conversationTitle, BorderLayout.NORTH);
        conversationPanel.add(messagesScroll, BorderLayout.CENTER);
        conversationPanel.add(createComposer(), BorderLayout.SOUTH);
        return conversationPanel;
    }

    private JPanel createComposer() {
        composerPanel.setBorder(
                BorderFactory.createEmptyBorder(10, 12, 12, 12));
        messageInput.setLineWrap(true);
        messageInput.setWrapStyleWord(true);
        messageInput.setEnabled(false);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        securityButton.setEnabled(false);
        sendButton.setEnabled(false);
        sendButton.setBackground(PRIMARY);
        sendButton.setForeground(Color.WHITE);
        buttons.add(securityButton);
        buttons.add(sendButton);

        composerPanel.add(new JScrollPane(messageInput), BorderLayout.CENTER);
        composerPanel.add(buttons, BorderLayout.EAST);
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
        actions.onSendMessage(selectedPeer, content);
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
        requireEdt();
        conversations.computeIfAbsent(peerUsername,
                ignored -> new ArrayList<>())
                .add(new ChatMessage(content, timestamp, sentByMe));
        if (peerUsername.equals(selectedPeer)) {
            renderSelectedConversation();
        }
    }

    public void setPeerSendAllowed(String username, boolean allowed) {
        requireEdt();
        sendAllowed.put(username, allowed);
        updateComposerState();
    }

    /**
     * Hiển thị trạng thái nguy hiểm cho đúng cuộc trò chuyện có khóa thay đổi.
     */
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
            conversationTitle.setText("Trò chuyện với " + selectedPeer);
            for (ChatMessage message : conversations.getOrDefault(
                    selectedPeer, List.of())) {
                messagesPanel.add(new MessageBubble(
                        message.content, message.timestamp,
                        message.sentByMe));
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
        securityButton.setEnabled(hasPeer);
    }

    private void updateSecurityAppearance() {
        boolean warning = selectedPeer != null
                && securityWarnings.getOrDefault(selectedPeer, false);
        Color background = warning
                ? SECURITY_WARNING_BACKGROUND : BACKGROUND;
        messagesPanel.setBackground(background);
        composerPanel.setBackground(background);
        conversationTitle.setOpaque(warning);
        conversationTitle.setBackground(background);
        conversationTitle.setForeground(warning
                ? SECURITY_WARNING_FOREGROUND : Color.BLACK);
        conversationPanel.setBorder(warning
                ? BorderFactory.createLineBorder(
                        SECURITY_WARNING_FOREGROUND, 3)
                : BorderFactory.createEmptyBorder());
    }

    int getFriendCount() {
        return friendModel.size();
    }

    int getDisplayedMessageCount() {
        return messagesPanel.getComponentCount();
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
        private final String content;
        private final long timestamp;
        private final boolean sentByMe;

        private ChatMessage(String content, long timestamp,
                boolean sentByMe) {
            this.content = content;
            this.timestamp = timestamp;
            this.sentByMe = sentByMe;
        }
    }
}
