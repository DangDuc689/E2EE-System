package e2ee.client.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JList;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;

/**
 * Renderer danh sách bạn bè với đèn xanh online và đèn xám offline.
 */
public final class FriendListCellRenderer extends DefaultListCellRenderer {
    private static final Color ONLINE = new Color(34, 197, 94);
    private static final Color OFFLINE = new Color(148, 163, 184);

    @Override
    public Component getListCellRendererComponent(JList<?> list,
            Object value, int index, boolean selected,
            boolean cellHasFocus) {
        super.getListCellRendererComponent(
                list, value, index, selected, cellHasFocus);
        if (value instanceof ChatFrame.FriendItem friend) {
            setText(friend.getUsername());
            setIcon(new StatusIcon(friend.isOnline()
                    ? ONLINE : OFFLINE));
            setToolTipText(friend.isOnline() ? "Online" : "Offline");
        }
        setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));
        return this;
    }

    private static final class StatusIcon implements Icon {
        private final Color color;

        private StatusIcon(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics,
                int x, int y) {
            graphics.setColor(color);
            graphics.fillOval(x + 2, y + 2, 10, 10);
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
}
