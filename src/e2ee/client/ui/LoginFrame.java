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
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;

/**
 * Màn hình đăng nhập và đăng ký danh tính E2EE Client.
 */
public final class LoginFrame extends JFrame {
    private static final Color PRIMARY = new Color(36, 99, 235);
    private static final Color ERROR = new Color(185, 28, 28);
    private static final Color MUTED = new Color(100, 116, 139);

    private final JTextField hostField = new JTextField("127.0.0.1", 18);
    private final JSpinner portSpinner = new JSpinner(
            new SpinnerNumberModel(8888, 1, 65_535, 1));
    private final JTextField usernameField = new JTextField(18);
    private final JPasswordField passphraseField = new JPasswordField(18);
    private final JButton loginButton = new JButton("Đăng nhập");
    private final JButton registerButton = new JButton("Đăng ký mới");
    private final JLabel statusLabel = new JLabel(" ", SwingConstants.CENTER);

    private final Actions actions;

    public LoginFrame(Actions actions) {
        super("E2EE Chat - Đăng nhập");
        this.actions = Objects.requireNonNull(actions,
                "actions cannot be null");
        initializeUi();
        bindActions();
    }

    private void initializeUi() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(440, 430));
        setSize(480, 470);
        setLocationByPlatform(true);

        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBorder(BorderFactory.createEmptyBorder(28, 36, 28, 36));
        root.add(createHeader(), BorderLayout.NORTH);
        root.add(createForm(), BorderLayout.CENTER);
        root.add(statusLabel, BorderLayout.SOUTH);
        setContentPane(root);

        getRootPane().setDefaultButton(loginButton);
        statusLabel.setForeground(MUTED);
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(0, 8));
        JLabel title = new JLabel("E2EE CHAT", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));
        title.setForeground(PRIMARY);
        JLabel subtitle = new JLabel(
                "Chat 1-1 mã hóa đầu cuối", SwingConstants.CENTER);
        subtitle.setForeground(MUTED);
        header.add(title, BorderLayout.CENTER);
        header.add(subtitle, BorderLayout.SOUTH);
        return header;
    }

    private JPanel createForm() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(7, 5, 7, 5);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1.0;

        addField(form, constraints, 0, "Host / IP", hostField);
        addField(form, constraints, 1, "Port", portSpinner);
        addField(form, constraints, 2, "Username", usernameField);
        addField(form, constraints, 3, "Passphrase", passphraseField);

        JPanel buttons = new JPanel(new GridBagLayout());
        GridBagConstraints buttonConstraints = new GridBagConstraints();
        buttonConstraints.insets = new Insets(0, 5, 0, 5);
        buttonConstraints.fill = GridBagConstraints.HORIZONTAL;
        buttonConstraints.weightx = 1.0;
        loginButton.setBackground(PRIMARY);
        loginButton.setForeground(Color.WHITE);
        buttons.add(loginButton, buttonConstraints);
        buttonConstraints.gridx = 1;
        registerButton.setForeground(PRIMARY);
        buttons.add(registerButton, buttonConstraints);

        constraints.gridy = 4;
        constraints.gridx = 0;
        constraints.gridwidth = 2;
        constraints.insets = new Insets(18, 5, 5, 5);
        form.add(buttons, constraints);
        return form;
    }

    private static void addField(JPanel form,
            GridBagConstraints constraints, int row, String label,
            java.awt.Component input) {
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.gridwidth = 1;
        constraints.weightx = 0.0;
        form.add(new JLabel(label), constraints);

        constraints.gridx = 1;
        constraints.weightx = 1.0;
        form.add(input, constraints);
    }

    private void bindActions() {
        loginButton.addActionListener(event -> submit(false));
        registerButton.addActionListener(event -> submit(true));
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
            if (host.isBlank() || username.isBlank()
                    || passphrase.length == 0) {
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
        loginButton.setEnabled(!busy);
        registerButton.setEnabled(!busy);
    }

    public void setStatus(String message, boolean error) {
        statusLabel.setText(message == null || message.isBlank() ? " " : message);
        statusLabel.setForeground(error ? ERROR : MUTED);
    }

    public interface Actions {
        void onLogin(String host, int port, String username,
                char[] passphrase);

        void onRegister(String host, int port, String username,
                char[] passphrase);

        void onClose();
    }
}
