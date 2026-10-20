package e2ee.server.ui;

import e2ee.server.OfflineMessageQueue;
import e2ee.server.ServerLogEntry;
import e2ee.server.ServerMain;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Giao diện Quản trị E2EE Server (Single JFrame thuần JDK 17).
 * Tuân thủ chuẩn Native Desktop: #FFFFFF, #F6F8FA, #D0D7DE, #1F2328.
 */
public class ServerDashboardFrame extends JFrame {

    // Bảng màu Native Desktop chuẩn GitHub/Modern Desktop
    private static final Color BG_WINDOW = new Color(0xF6, 0xF8, 0xFA);
    private static final Color BG_CARD = Color.WHITE;
    private static final Color BORDER_COLOR = new Color(0xD0, 0xD7, 0xDE);
    private static final Color TEXT_MAIN = new Color(0x1F, 0x23, 0x28);
    private static final Color TEXT_MUTED = new Color(0x65, 0x6D, 0x76);
    private static final Color COLOR_GREEN = new Color(0x1A, 0x7F, 0x37);
    private static final Color COLOR_RED = new Color(0xCF, 0x22, 0x2E);
    private static final Color COLOR_BLUE = new Color(0x09, 0x69, 0xDA);
    private static final Color COLOR_AMBER = new Color(0x9A, 0x67, 0x00);
    private static final Color GRID_COLOR = new Color(0xEB, 0xF0, 0xF4);

    private static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 18);
    private static final Font FONT_HEADER = new Font("Segoe UI", Font.BOLD, 13);
    private static final Font FONT_LABEL = new Font("Segoe UI", Font.BOLD, 11);
    private static final Font FONT_BIG_NUMBER = new Font("Segoe UI", Font.BOLD, 26);
    private static final Font FONT_REGULAR = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font FONT_BOLD = new Font("Segoe UI", Font.BOLD, 12);
    private static final Font FONT_MONO = new Font("Consolas", Font.PLAIN, 12);

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    // Các thành phần trạng thái Top Bar
    private StatusBadge statusBadge;
    private PillLabel portBadge;
    private PillLabel uptimeBadge;
    private JButton btnStart;
    private JButton btnStop;

    // 5 Thẻ thống kê
    private JLabel lblSessionCount;
    private JLabel lblSocketCount;
    private JLabel lblClientLimit;
    private JLabel lblQueueCount;
    private JLabel lblErrorCount;
    private final AtomicInteger errorAndWarnCount = new AtomicInteger(0);

    // 2 Bảng trung tâm
    private DefaultTableModel activeSessionsModel;
    private JTable activeSessionsTable;
    private PillLabel activeCountBadge;

    private DefaultTableModel offlineQueueModel;
    private JTable offlineQueueTable;
    private PillLabel queueCountBadge;

    // Nhật ký sự kiện (Console Table)
    private DefaultTableModel logTableModel;
    private JTable logTable;
    private TableRowSorter<DefaultTableModel> logSorter;
    private JComboBox<String> cmbLogLevel;
    private JTextField txtSearchLog;
    private JCheckBox chkAutoScroll;
    private JButton btnClearLog;
    private final List<ServerLogEntry> logEntries = new ArrayList<>();

    // Máy chủ & Điều khiển luồng
    private ServerMain server;
    private Thread serverWorkerThread;
    private Timer refreshTimer;

    public ServerDashboardFrame() {
        setTitle("Bảng Điều Khiển Máy Chủ E2EE (Blind Relay Daemon)");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1060, 720);
        setMinimumSize(new Dimension(960, 640));
        setLocationRelativeTo(null);

        initComponents();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleWindowClose();
            }
        });

        initRefreshTimer();
    }

    private void initComponents() {
        JPanel rootPanel = new JPanel();
        rootPanel.setLayout(new BoxLayout(rootPanel, BoxLayout.Y_AXIS));
        rootPanel.setBackground(BG_WINDOW);
        rootPanel.setBorder(new EmptyBorder(14, 18, 16, 18));

        // 1. Thanh trạng thái trên cùng
        rootPanel.add(buildTopBar());
        rootPanel.add(Box.createVerticalStrut(14));

        // 2. Dãy 5 thẻ số liệu
        rootPanel.add(buildMetricsRow());
        rootPanel.add(Box.createVerticalStrut(14));

        // 3. Khu vực giữa (2 bảng song song)
        rootPanel.add(buildMiddleSection());
        rootPanel.add(Box.createVerticalStrut(14));

        // 4. Khu vực dưới (Nhật ký console)
        rootPanel.add(buildLogSection());

        setContentPane(rootPanel);
    }

    /*
     * ===================================================================
     * 1. THANH TRÊN (TOP BAR)
     * ===================================================================
     */
    private JPanel buildTopBar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_WINDOW);

        // Bên trái: Tiêu đề + Các nhãn trạng thái
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftPanel.setBackground(BG_WINDOW);

        JLabel lblTitle = new JLabel("Trạng Thái Máy Chủ");
        lblTitle.setFont(FONT_TITLE);
        lblTitle.setForeground(TEXT_MAIN);
        leftPanel.add(lblTitle);

        statusBadge = new StatusBadge("Đã dừng", false);
        leftPanel.add(statusBadge);

        portBadge = new PillLabel("Port: " + ServerMain.DEFAULT_PORT);
        leftPanel.add(portBadge);

        uptimeBadge = new PillLabel("Uptime: 00:00:00");
        leftPanel.add(uptimeBadge);

        panel.add(leftPanel, BorderLayout.WEST);

        // Bên phải: Nút Khởi động & Dừng Server
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightPanel.setBackground(BG_WINDOW);

        btnStart = createStyledButton("Khởi động", false);
        btnStart.addActionListener(e -> startServer());

        btnStop = createStyledButton("Dừng Server", true);
        btnStop.addActionListener(e -> stopServer());

        rightPanel.add(btnStart);
        rightPanel.add(btnStop);

        panel.add(rightPanel, BorderLayout.EAST);
        return panel;
    }

    /*
     * ===================================================================
     * 2. DÃY 5 THẺ SỐ LIỆU (METRIC CARDS)
     * ===================================================================
     */
    private JPanel buildMetricsRow() {
        JPanel panel = new JPanel(new GridLayout(1, 5, 12, 0));
        panel.setBackground(BG_WINDOW);

        lblSessionCount = new JLabel("0");
        lblSocketCount = new JLabel("0");
        lblClientLimit = new JLabel(String.valueOf(ServerMain.MAX_CLIENTS));
        lblQueueCount = new JLabel("0");
        lblErrorCount = new JLabel("0");

        panel.add(createMetricCard("SESSION ONLINE", lblSessionCount, TEXT_MAIN));
        panel.add(createMetricCard("SOCKET ĐANG MỞ", lblSocketCount, TEXT_MAIN));
        panel.add(createMetricCard("GIỚI HẠN CLIENT", lblClientLimit, TEXT_MAIN));
        panel.add(createMetricCard("TIN HÀNG ĐỢI", lblQueueCount, COLOR_AMBER));
        panel.add(createMetricCard("LỖI / CẢNH BÁO", lblErrorCount, COLOR_GREEN));

        return panel;
    }

    private JPanel createMetricCard(String title, JLabel valueLabel, Color valueColor) {
        CardPanel card = new CardPanel();
        card.setLayout(new BorderLayout());
        card.setBackground(BG_CARD);
        card.setBorder(new EmptyBorder(12, 14, 12, 14));

        JLabel lblTitle = new JLabel(title);
        lblTitle.setFont(FONT_LABEL);
        lblTitle.setForeground(TEXT_MUTED);

        valueLabel.setFont(FONT_BIG_NUMBER);
        valueLabel.setForeground(valueColor);

        card.add(lblTitle, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);

        return card;
    }

    /*
     * ===================================================================
     * 3. KHU VỰC GIỮA (2 BẢNG SONG SONG)
     * ===================================================================
     */
    private JPanel buildMiddleSection() {
        JPanel panel = new JPanel(new GridLayout(1, 2, 12, 0));
        panel.setBackground(BG_WINDOW);
        panel.setPreferredSize(new Dimension(1000, 200));

        // Bảng trái: Kết Nối Hiện Tại
        CardPanel leftCard = new CardPanel();
        leftCard.setLayout(new BorderLayout());
        leftCard.setBackground(BG_CARD);

        JPanel leftHeader = new JPanel(new BorderLayout());
        leftHeader.setBackground(BG_CARD);
        leftHeader.setBorder(new EmptyBorder(10, 14, 10, 14));
        JLabel lblLeftTitle = new JLabel("Kết Nối Hiện Tại");
        lblLeftTitle.setFont(FONT_HEADER);
        lblLeftTitle.setForeground(TEXT_MAIN);
        leftHeader.add(lblLeftTitle, BorderLayout.WEST);

        activeCountBadge = new PillLabel("0 user");
        leftHeader.add(activeCountBadge, BorderLayout.EAST);
        leftCard.add(leftHeader, BorderLayout.NORTH);

        activeSessionsModel = new DefaultTableModel(
                new Object[]{"TÀI KHOẢN", "THỜI ĐIỂM KẾT NỐI", "TRẠNG THÁI"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        activeSessionsTable = createCustomTable(activeSessionsModel);
        activeSessionsTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        activeSessionsTable.getColumnModel().getColumn(1).setPreferredWidth(180);
        activeSessionsTable.getColumnModel().getColumn(2).setPreferredWidth(110);

        // Renderer cho cột trạng thái (chữ xanh lá "Đã xác thực" thuần, không kèm dấu chấm/icon)
        activeSessionsTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);
                lbl.setFont(FONT_BOLD);
                lbl.setForeground(COLOR_GREEN);
                return lbl;
            }
        });

        JScrollPane scrollLeft = new JScrollPane(activeSessionsTable);
        scrollLeft.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR));
        scrollLeft.getViewport().setBackground(BG_CARD);
        leftCard.add(scrollLeft, BorderLayout.CENTER);
        panel.add(leftCard);

        // Bảng phải: Hàng Đợi Ngoại Tuyến
        CardPanel rightCard = new CardPanel();
        rightCard.setLayout(new BorderLayout());
        rightCard.setBackground(BG_CARD);

        JPanel rightHeader = new JPanel(new BorderLayout());
        rightHeader.setBackground(BG_CARD);
        rightHeader.setBorder(new EmptyBorder(10, 14, 10, 14));
        JLabel lblRightTitle = new JLabel("Hàng Đợi Ngoại Tuyến");
        lblRightTitle.setFont(FONT_HEADER);
        lblRightTitle.setForeground(TEXT_MAIN);
        rightHeader.add(lblRightTitle, BorderLayout.WEST);

        queueCountBadge = new PillLabel("0 user");
        rightHeader.add(queueCountBadge, BorderLayout.EAST);
        rightCard.add(rightHeader, BorderLayout.NORTH);

        offlineQueueModel = new DefaultTableModel(
                new Object[]{"USERNAME NGƯỜI NHẬN", "SỐ TIN ĐANG CHỜ"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        offlineQueueTable = createCustomTable(offlineQueueModel);
        offlineQueueTable.getColumnModel().getColumn(0).setPreferredWidth(220);
        offlineQueueTable.getColumnModel().getColumn(1).setPreferredWidth(140);

        // Renderer dạng badge cho số tin chờ
        offlineQueueTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);
                lbl.setHorizontalAlignment(SwingConstants.CENTER);
                lbl.setFont(FONT_REGULAR);
                lbl.setForeground(TEXT_MAIN);
                return lbl;
            }
        });

        JScrollPane scrollRight = new JScrollPane(offlineQueueTable);
        scrollRight.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR));
        scrollRight.getViewport().setBackground(BG_CARD);
        rightCard.add(scrollRight, BorderLayout.CENTER);
        panel.add(rightCard);

        return panel;
    }

    /*
     * ===================================================================
     * 4. KHU VỰC DƯỚI (NHẬT KÝ SỰ KIỆN CONSOLE)
     * ===================================================================
     */
    private JPanel buildLogSection() {
        CardPanel card = new CardPanel();
        card.setLayout(new BorderLayout());
        card.setBackground(BG_CARD);
        card.setPreferredSize(new Dimension(1000, 240));

        // Header điều khiển lọc
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(BG_CARD);
        headerPanel.setBorder(new EmptyBorder(10, 14, 10, 14));

        JLabel lblTitle = new JLabel("Nhật Ký Sự Kiện Hệ Thống");
        lblTitle.setFont(FONT_HEADER);
        lblTitle.setForeground(TEXT_MAIN);
        headerPanel.add(lblTitle, BorderLayout.WEST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        controls.setBackground(BG_CARD);

        JLabel lblLevel = new JLabel("Mức độ:");
        lblLevel.setFont(FONT_REGULAR);
        lblLevel.setForeground(TEXT_MUTED);
        controls.add(lblLevel);

        cmbLogLevel = new JComboBox<>(new String[]{"Tất cả", "INFO", "WARN", "ERROR"});
        cmbLogLevel.setFont(FONT_REGULAR);
        cmbLogLevel.setBackground(BG_CARD);
        cmbLogLevel.setPreferredSize(new Dimension(85, 26));
        cmbLogLevel.addActionListener(e -> applyLogFilter());
        controls.add(cmbLogLevel);

        txtSearchLog = new JTextField();
        txtSearchLog.setFont(FONT_REGULAR);
        txtSearchLog.setPreferredSize(new Dimension(170, 26));
        txtSearchLog.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(2, 6, 2, 6)));
        txtSearchLog.setToolTipText("Tìm kiếm log...");
        txtSearchLog.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyLogFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyLogFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyLogFilter();
            }
        });
        controls.add(txtSearchLog);

        chkAutoScroll = new JCheckBox("Tự cuộn", true);
        chkAutoScroll.setFont(FONT_REGULAR);
        chkAutoScroll.setForeground(TEXT_MAIN);
        chkAutoScroll.setBackground(BG_CARD);
        controls.add(chkAutoScroll);

        btnClearLog = createStyledButton("Xóa log", false);
        btnClearLog.setPreferredSize(new Dimension(75, 26));
        btnClearLog.addActionListener(e -> clearLogs());
        controls.add(btnClearLog);

        headerPanel.add(controls, BorderLayout.EAST);
        card.add(headerPanel, BorderLayout.NORTH);

        // Bảng Console Log
        logTableModel = new DefaultTableModel(
                new Object[]{"THỜI GIAN", "MỨC ĐỘ", "SỰ KIỆN", "USERNAME", "CHI TIẾT"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        logTable = createCustomTable(logTableModel);
        logSorter = new TableRowSorter<>(logTableModel);
        logTable.setRowSorter(logSorter);

        logTable.getColumnModel().getColumn(0).setPreferredWidth(85);
        logTable.getColumnModel().getColumn(1).setPreferredWidth(75);
        logTable.getColumnModel().getColumn(2).setPreferredWidth(130);
        logTable.getColumnModel().getColumn(3).setPreferredWidth(100);
        logTable.getColumnModel().getColumn(4).setPreferredWidth(550);

        // Renderer cho mức độ log (INFO xanh, WARN cam, ERROR đỏ)
        logTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);
                lbl.setFont(FONT_BOLD);
                String level = String.valueOf(value);
                if ("INFO".equalsIgnoreCase(level)) {
                    lbl.setForeground(COLOR_BLUE);
                } else if ("WARN".equalsIgnoreCase(level)) {
                    lbl.setForeground(COLOR_AMBER);
                } else if ("ERROR".equalsIgnoreCase(level)) {
                    lbl.setForeground(COLOR_RED);
                } else {
                    lbl.setForeground(TEXT_MUTED);
                }
                return lbl;
            }
        });

        // Renderer cho mã sự kiện dạng Code Monospace
        logTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);
                lbl.setFont(FONT_MONO);
                lbl.setForeground(TEXT_MAIN);
                return lbl;
            }
        });

        JScrollPane scrollLog = new JScrollPane(logTable);
        scrollLog.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR));
        scrollLog.getViewport().setBackground(BG_CARD);
        card.add(scrollLog, BorderLayout.CENTER);

        return card;
    }

    /*
     * ===================================================================
     * QUẢN LÝ VÒNG ĐỜI SERVER (THREAD & LIFECYCLE)
     * ===================================================================
     */
    public synchronized void startServer() {
        if (server != null && server.isRunning()) {
            return;
        }

        server = new ServerMain();
        server.addEventListener(this::handleServerEvent);

        serverWorkerThread = new Thread(() -> {
            try {
                server.run();
            } finally {
                SwingUtilities.invokeLater(this::onServerStopped);
            }
        }, "e2ee-server-worker");
        serverWorkerThread.start();

        onServerStarted();
    }

    public synchronized void stopServer() {
        if (server == null || !server.isRunning()) {
            return;
        }

        btnStop.setEnabled(false);
        new Thread(() -> {
            try {
                server.shutdown();
            } finally {
                SwingUtilities.invokeLater(this::onServerStopped);
            }
        }, "e2ee-server-stop").start();
    }

    private void onServerStarted() {
        statusBadge.setStatus("Đang chạy", true);
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
    }

    private void onServerStopped() {
        statusBadge.setStatus("Đã dừng", false);
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        uptimeBadge.setText("Uptime: 00:00:00");
    }

    private void handleWindowClose() {
        if (server != null && server.isRunning()) {
            stopServer();
        }
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
        dispose();
        System.exit(0);
    }

    /*
     * ===================================================================
     * SỰ KIỆN & CẬP NHẬT GIAO DIỆN
     * ===================================================================
     */
    private void handleServerEvent(ServerLogEntry entry) {
        SwingUtilities.invokeLater(() -> {
            logEntries.add(entry);

            if ("WARN".equalsIgnoreCase(entry.level()) || "ERROR".equalsIgnoreCase(entry.level())) {
                int count = errorAndWarnCount.incrementAndGet();
                lblErrorCount.setText(String.valueOf(count));
                lblErrorCount.setForeground(COLOR_RED);
            }

            logTableModel.addRow(new Object[]{
                    entry.formattedTime(),
                    entry.level(),
                    entry.event(),
                    entry.username(),
                    entry.details()
            });

            if (chkAutoScroll.isSelected() && logTable.getRowCount() > 0) {
                int lastRow = logTable.getRowCount() - 1;
                logTable.scrollRectToVisible(logTable.getCellRect(lastRow, 0, true));
            }
        });
    }

    private void initRefreshTimer() {
        refreshTimer = new Timer(1000, e -> refreshSnapshot());
        refreshTimer.start();
    }

    private void refreshSnapshot() {
        if (server == null) {
            return;
        }

        boolean running = server.isRunning();
        if (!running) {
            lblSessionCount.setText("0");
            lblSocketCount.setText("0");
            return;
        }

        // Cập nhật Uptime
        long startedAt = server.getStartedAt();
        if (startedAt > 0) {
            long uptimeSeconds = (System.currentTimeMillis() - startedAt) / 1000;
            long hours = uptimeSeconds / 3600;
            long minutes = (uptimeSeconds % 3600) / 60;
            long seconds = uptimeSeconds % 60;
            uptimeBadge.setText(String.format("Uptime: %02d:%02d:%02d", hours, minutes, seconds));
        }

        // Cập nhật 5 thẻ số liệu
        int onlineCount = server.getOnlineSessionCount();
        int socketCount = server.getActiveClientCount();
        OfflineMessageQueue queue = server.getOfflineQueue();
        int queueTotal = queue != null ? queue.getTotalQueueSize() : 0;

        lblSessionCount.setText(String.valueOf(onlineCount));
        lblSocketCount.setText(String.valueOf(socketCount));
        lblQueueCount.setText(String.valueOf(queueTotal));

        // Cập nhật Bảng 1: Kết nối hiện tại
        List<ServerMain.SessionSnapshot> sessions = server.getSessionSnapshots();
        activeCountBadge.setText(sessions.size() + " user");
        activeSessionsModel.setRowCount(0);
        long now = System.currentTimeMillis();
        for (ServerMain.SessionSnapshot s : sessions) {
            String timeText = formatRelativeTime(s.connectedAt(), now);
            activeSessionsModel.addRow(new Object[]{s.username(), timeText, s.status()});
        }

        // Cập nhật Bảng 2: Hàng đợi ngoại tuyến
        Map<String, Integer> queueSummary = queue != null ? queue.getQueueSummary() : Map.of();
        queueCountBadge.setText(queueSummary.size() + " user");
        offlineQueueModel.setRowCount(0);
        for (Map.Entry<String, Integer> entry : queueSummary.entrySet()) {
            offlineQueueModel.addRow(new Object[]{entry.getKey(), entry.getValue() + " tin chờ"});
        }
    }

    private String formatRelativeTime(long timestamp, long now) {
        String exactTime = TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp));
        long diffSec = (now - timestamp) / 1000;
        if (diffSec < 60) {
            return exactTime + " (vừa xong)";
        }
        long minutes = diffSec / 60;
        if (minutes < 60) {
            return exactTime + " (" + minutes + "m trước)";
        }
        long hours = minutes / 60;
        return exactTime + " (" + hours + "h " + (minutes % 60) + "m trước)";
    }

    private void applyLogFilter() {
        String selectedLevel = (String) cmbLogLevel.getSelectedItem();
        String searchText = txtSearchLog.getText().trim().toLowerCase();

        logSorter.setRowFilter(new javax.swing.RowFilter<DefaultTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                if (!"Tất cả".equals(selectedLevel)) {
                    String rowLevel = entry.getStringValue(1);
                    if (!selectedLevel.equalsIgnoreCase(rowLevel)) {
                        return false;
                    }
                }
                if (!searchText.isEmpty()) {
                    boolean matches = false;
                    for (int i = 0; i < entry.getValueCount(); i++) {
                        if (entry.getStringValue(i).toLowerCase().contains(searchText)) {
                            matches = true;
                            break;
                        }
                    }
                    return matches;
                }
                return true;
            }
        });
    }

    private void clearLogs() {
        logTableModel.setRowCount(0);
        logEntries.clear();
        errorAndWarnCount.set(0);
        lblErrorCount.setText("0");
        lblErrorCount.setForeground(COLOR_GREEN);
    }

    /*
     * ===================================================================
     * HELPER COMPONENTS & STYLING
     * ===================================================================
     */
    private JTable createCustomTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setFont(FONT_REGULAR);
        table.setRowHeight(32);
        table.setShowGrid(false);
        table.setShowHorizontalLines(true);
        table.setGridColor(GRID_COLOR);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(new Color(0xEB, 0xF2, 0xFA));
        table.setSelectionForeground(TEXT_MAIN);

        JTableHeader header = table.getTableHeader();
        header.setFont(FONT_LABEL);
        header.setForeground(TEXT_MUTED);
        header.setBackground(BG_CARD);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR));
        header.setReorderingAllowed(false);

        return table;
    }

    private JButton createStyledButton(String text, boolean isDanger) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_BOLD);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(100, 30));

        if (isDanger) {
            btn.setForeground(COLOR_RED);
            btn.setBackground(BG_CARD);
            btn.setBorder(new LineBorder(COLOR_RED, 1, true));
        } else {
            btn.setForeground(TEXT_MAIN);
            btn.setBackground(BG_CARD);
            btn.setBorder(new LineBorder(BORDER_COLOR, 1, true));
        }
        return btn;
    }

    /**
     * Panel bo góc nhẹ với viền xám mỏng chuẩn card giao diện hiện đại.
     */
    private static class CardPanel extends JPanel {
        CardPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(BG_CARD);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.setColor(BORDER_COLOR);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * Nhãn Pill Badge bo tròn bo góc (cho Port, Uptime, User count).
     */
    private static class PillLabel extends JLabel {
        PillLabel(String text) {
            super(text);
            setFont(FONT_REGULAR);
            setForeground(TEXT_MUTED);
            setHorizontalAlignment(SwingConstants.CENTER);
            setBorder(new EmptyBorder(3, 10, 3, 10));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0xF6, 0xF8, 0xFA));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g2.setColor(BORDER_COLOR);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * Badge hiển thị trạng thái máy chủ (● Đang chạy / ● Đã dừng).
     */
    private static class StatusBadge extends JPanel {
        private final JLabel lblText = new JLabel();
        private boolean isRunning = false;

        StatusBadge(String text, boolean running) {
            setLayout(new FlowLayout(FlowLayout.CENTER, 6, 2));
            setOpaque(false);
            setBorder(new EmptyBorder(2, 8, 2, 8));
            lblText.setFont(FONT_BOLD);
            lblText.setForeground(TEXT_MAIN);
            add(lblText);
            setStatus(text, running);
        }

        void setStatus(String text, boolean running) {
            this.isRunning = running;
            lblText.setText((running ? "● " : "● ") + text);
            lblText.setForeground(running ? COLOR_GREEN : COLOR_RED);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(BG_CARD);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g2.setColor(BORDER_COLOR);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
