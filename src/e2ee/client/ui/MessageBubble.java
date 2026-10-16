package e2ee.client.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Một bong bóng tin nhắn co giãn, căn phải cho tin gửi và căn trái cho tin nhận.
 */
public final class MessageBubble extends JPanel {
    private static final Color SENT_BACKGROUND = new Color(37, 99, 235);
    private static final Color RECEIVED_BACKGROUND = new Color(226, 232, 240);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");

    public MessageBubble(String content, long timestamp, boolean sentByMe) {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));

        JPanel bubble = new JPanel();
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBackground(sentByMe
                ? SENT_BACKGROUND : RECEIVED_BACKGROUND);
        bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                        sentByMe ? SENT_BACKGROUND : new Color(203, 213, 225)),
                BorderFactory.createEmptyBorder(8, 12, 6, 12)));
        bubble.setMaximumSize(new Dimension(540, Integer.MAX_VALUE));

        JTextArea message = new JTextArea(content == null ? "" : content);
        message.setEditable(false);
        message.setFocusable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setOpaque(false);
        message.setColumns(28);
        message.setFont(message.getFont().deriveFont(Font.PLAIN, 14f));
        message.setForeground(sentByMe ? Color.WHITE : new Color(15, 23, 42));

        JLabel time = new JLabel(TIME_FORMAT.format(
                Instant.ofEpochMilli(timestamp)
                        .atZone(ZoneId.systemDefault())));
        time.setFont(time.getFont().deriveFont(Font.PLAIN, 10f));
        time.setForeground(sentByMe
                ? new Color(219, 234, 254) : new Color(100, 116, 139));
        time.setAlignmentX(sentByMe ? RIGHT_ALIGNMENT : LEFT_ALIGNMENT);

        bubble.add(message);
        bubble.add(time);
        add(bubble, sentByMe ? BorderLayout.EAST : BorderLayout.WEST);
    }
}
