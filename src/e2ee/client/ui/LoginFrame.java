package e2ee.client.ui;

import e2ee.client.crypto.ClientCryptoEngine;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;

/**
 * Màn hình đăng nhập và đăng ký danh tính E2EE Client (Chuẩn Native Desktop).
 */
public final class LoginFrame extends JFrame {
    // Bảng màu Native Desktop chuẩn mực (sạch, đầm, không lòe loẹt)
    private static final Color BG_WINDOW = new Color(255, 255, 255);     // Nền liền khối trắng tinh
    private static final Color PRIMARY = new Color(2, 132, 199);         // Xanh kỹ thuật (#0284C7)
    private static final Color PRIMARY_HOVER = new Color(3, 105, 161);   // Xanh hover (#0369A1)
    private static final Color TEXT_MAIN = new Color(15, 23, 42);        // Chữ chính than đậm (#0F172A)
    private static final Color TEXT_LABEL = new Color(51, 65, 85);       // Nhãn (#334155)
    private static final Color TEXT_MUTED = new Color(100, 116, 139);    // Chữ phụ (#64748B)
    private static final Color BORDER_INPUT = new Color(226, 232, 240);  // Viền ô nhập (#E2E8F0)
    private static final Color DIVIDER = new Color(241, 245, 249);       // Đường kẻ phân tách (#F1F5F9)
    private static final Color ERROR = new Color(220, 38, 38);           // Đỏ báo lỗi (#DC2626)

    private final JTextField hostField = createDesktopTextField("127.0.0.1");
    private final JSpinner portSpinner = new JSpinner(
            new SpinnerNumberModel(8888, 1, 65_535, 1));
    private final JTextField usernameField = createDesktopTextField("");
    private final JPasswordField passphraseField = createDesktopPasswordField();
    private final JButton submitButton = createDesktopButton("Đăng nhập");
    private final JLabel statusLabel = new JLabel(" ", SwingConstants.CENTER);

    // Tab gạch chân phẳng chuẩn Desktop
    private final UnderlineTabButton tabLogin = new UnderlineTabButton("Đăng nhập", true);
    private final UnderlineTabButton tabRegister = new UnderlineTabButton("Đăng ký", false);
    private boolean isRegisterMode = false;

    private final Actions actions;

    public LoginFrame(Actions actions) {
        super("E2EE Chat - Đăng nhập");
        this.actions = Objects.requireNonNull(actions, "actions cannot be null");
        initializeUi();
        bindActions();
    }

    private void initializeUi() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(380, 500));
        setSize(410, 530);
        setLocationRelativeTo(null);

        // Bề mặt liền khối (Solid window layout), không dùng card lơ lửng
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(BG_WINDOW);
        root.setBorder(BorderFactory.createEmptyBorder(28, 36, 24, 36));

        root.add(createHeader(), BorderLayout.NORTH);
        root.add(createForm(), BorderLayout.CENTER);
        root.add(statusLabel, BorderLayout.SOUTH);

        setContentPane(root);
        getRootPane().setDefaultButton(submitButton);

        statusLabel.setForeground(TEXT_MUTED);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 12f));

        styleSpinner(portSpinner);
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.setBackground(BG_WINDOW);

        JLabel title = new JLabel("E2EE CHAT", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        title.setForeground(TEXT_MAIN);

        JLabel subtitle = new JLabel("Chat mã hóa đầu cuối", SwingConstants.CENTER);
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 13f));
        subtitle.setForeground(TEXT_MUTED);

        header.add(title, BorderLayout.CENTER);
        header.add(subtitle, BorderLayout.SOUTH);
        return header;
    }

    private JPanel createForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(BG_WINDOW);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;

        // 1. Tab Bar gạch chân (Desktop Underline Tabs)
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.insets = new Insets(0, 0, 18, 0);
        form.add(createTabBar(), c);

        // 2. Tên đăng nhập
        c.gridy = 1;
        c.insets = new Insets(0, 0, 4, 0);
        form.add(createFieldLabel("Tên đăng nhập"), c);

        c.gridy = 2;
        c.insets = new Insets(0, 0, 14, 0);
        form.add(usernameField, c);

        // 3. Mật khẩu
        c.gridy = 3;
        c.insets = new Insets(0, 0, 4, 0);
        form.add(createFieldLabel("Mật khẩu"), c);

        c.gridy = 4;
        c.insets = new Insets(0, 0, 14, 0);
        form.add(passphraseField, c);

        // 4. Máy chủ (IP) & Cổng (Port)
        JPanel connectionPanel = new JPanel(new GridBagLayout());
        connectionPanel.setBackground(BG_WINDOW);
        GridBagConstraints connC = new GridBagConstraints();
        connC.fill = GridBagConstraints.HORIZONTAL;

        connC.gridx = 0;
        connC.gridy = 0;
        connC.weightx = 0.65;
        connC.insets = new Insets(0, 0, 4, 8);
        connectionPanel.add(createFieldLabel("Máy chủ (IP)"), connC);

        connC.gridy = 1;
        connC.insets = new Insets(0, 0, 0, 8);
        connectionPanel.add(hostField, connC);

        connC.gridx = 1;
        connC.gridy = 0;
        connC.weightx = 0.35;
        connC.insets = new Insets(0, 0, 4, 0);
        connectionPanel.add(createFieldLabel("Cổng (Port)"), connC);

        connC.gridy = 1;
        connC.insets = new Insets(0, 0, 0, 0);
        connectionPanel.add(portSpinner, connC);

        c.gridy = 5;
        c.insets = new Insets(0, 0, 22, 0);
        form.add(connectionPanel, c);

        // 5. Nút submit chính
        c.gridy = 6;
        c.insets = new Insets(0, 0, 4, 0);
        form.add(submitButton, c);

        return form;
    }

    private JPanel createTabBar() {
        JPanel tabBar = new JPanel(new BorderLayout());
        tabBar.setBackground(BG_WINDOW);

        JPanel buttonsPanel = new JPanel(new GridBagLayout());
        buttonsPanel.setBackground(BG_WINDOW);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 0.5;
        c.weighty = 1.0;

        c.gridx = 0;
        buttonsPanel.add(tabLogin, c);
        c.gridx = 1;
        buttonsPanel.add(tabRegister, c);

        tabLogin.addActionListener(e -> setMode(false));
        tabRegister.addActionListener(e -> setMode(true));

        tabBar.add(buttonsPanel, BorderLayout.CENTER);
        return tabBar;
    }

    private void setMode(boolean register) {
        this.isRegisterMode = register;
        tabLogin.setActive(!register);
        tabRegister.setActive(register);
        submitButton.setText(register ? "Đăng ký tài khoản" : "Đăng nhập");
        setTitle(register ? "E2EE Chat - Đăng ký" : "E2EE Chat - Đăng nhập");
        setStatus(" ", false);
    }

    private JLabel createFieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setForeground(TEXT_LABEL);
        return label;
    }

    private static JTextField createDesktopTextField(String text) {
        JTextField tf = new JTextField(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 6, 6);
                super.paintComponent(g);
                g2.dispose();
            }

            @Override
            protected void paintBorder(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hasFocus() ? PRIMARY : BORDER_INPUT);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                g2.dispose();
            }
        };
        tf.setOpaque(false);
        tf.setBackground(Color.WHITE);
        tf.setPreferredSize(new Dimension(tf.getPreferredSize().width, 36));
        tf.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        return tf;
    }

    private static JPasswordField createDesktopPasswordField() {
        JPasswordField pf = new JPasswordField() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 6, 6);
                super.paintComponent(g);
                g2.dispose();
            }

            @Override
            protected void paintBorder(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hasFocus() ? PRIMARY : BORDER_INPUT);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                g2.dispose();
            }
        };
        pf.setOpaque(false);
        pf.setBackground(Color.WHITE);
        pf.setPreferredSize(new Dimension(pf.getPreferredSize().width, 36));
        pf.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        return pf;
    }

    private static JButton createDesktopButton(String text) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (getModel().isPressed()) {
                    g2.setColor(PRIMARY_HOVER.darker());
                } else if (getModel().isRollover()) {
                    g2.setColor(PRIMARY_HOVER);
                } else {
                    g2.setColor(PRIMARY);
                }
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setForeground(Color.WHITE);
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, 13f));
        btn.setPreferredSize(new Dimension(btn.getPreferredSize().width, 38));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private static void styleSpinner(JSpinner spinner) {
        spinner.setPreferredSize(new Dimension(spinner.getPreferredSize().width, 36));
        Component editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor defaultEditor) {
            JTextField tf = defaultEditor.getTextField();
            tf.setBackground(Color.WHITE);
            tf.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        }
        spinner.setBorder(BorderFactory.createLineBorder(BORDER_INPUT, 1, true));
    }

    private void bindActions() {
        submitButton.addActionListener(event -> submit(isRegisterMode));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                actions.onClose();
            }
        });
    }

    private void submit(boolean register) {
        String host = hostField.getText().trim();
        int port = ((Number) portSpinner.getValue()).intValue();
        String username = usernameField.getText().trim();
        char[] passphrase = passphraseField.getPassword();
        try {
            if (host.isBlank() || username.isBlank() || passphrase.length == 0) {
                setStatus("Vui lòng nhập đầy đủ thông tin.", true);
                return;
            }
            setBusy(true);
            setStatus(register
                    ? "Đang tạo danh tính và đăng ký..."
                    : "Đang mở khóa và đăng nhập...", false);
            if (register) {
                actions.onRegister(host, port, username, passphrase);
            } else {
                actions.onLogin(host, port, username, passphrase);
            }
        } finally {
            ClientCryptoEngine.wipeMemory(passphrase);
            passphraseField.setText("");
        }
    }

    public void setBusy(boolean busy) {
        hostField.setEnabled(!busy);
        portSpinner.setEnabled(!busy);
        usernameField.setEnabled(!busy);
        passphraseField.setEnabled(!busy);
        submitButton.setEnabled(!busy);
        tabLogin.setEnabled(!busy);
        tabRegister.setEnabled(!busy);
    }

    public void setStatus(String message, boolean error) {
        statusLabel.setText(message == null || message.isBlank() ? " " : message);
        statusLabel.setForeground(error ? ERROR : TEXT_MUTED);
    }

    public interface Actions {
        void onLogin(String host, int port, String username, char[] passphrase);
        void onRegister(String host, int port, String username, char[] passphrase);
        void onClose();
    }

    /**
     * Nút tab gạch chân chuẩn desktop (Underline Tab).
     */
    private static class UnderlineTabButton extends JButton {
        private boolean active;

        public UnderlineTabButton(String text, boolean active) {
            super(text);
            this.active = active;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setPreferredSize(new Dimension(140, 36));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            updateStyle();
        }

        public void setActive(boolean active) {
            this.active = active;
            updateStyle();
            repaint();
        }

        private void updateStyle() {
            setForeground(active ? PRIMARY : TEXT_MUTED);
            setFont(getFont().deriveFont(active ? Font.BOLD : Font.PLAIN, 13f));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Đường kẻ đáy toàn tab bar
            g2.setColor(DIVIDER);
            g2.fillRect(0, getHeight() - 2, getWidth(), 2);

            // Đường gạch chân active (indicator line 2px)
            if (active) {
                g2.setColor(PRIMARY);
                g2.fillRect(8, getHeight() - 2, getWidth() - 16, 2);
            }
            g2.dispose();
        }
    }
}
