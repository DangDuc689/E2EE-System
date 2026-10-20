package e2ee.server;

import e2ee.common.codec.EnvelopeMapper;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.net.SocketPacketChannel;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;

/**
 * Định tuyến tin nhắn Chat 1-1 với cơ chế Store-and-Forward (Lưu trữ và chuyển tiếp)
 * kết hợp xử lý gói tin xác nhận ACK (Pure JDK 17) - Issue 12.
 *
 * <p>Nguyên tắc hoạt động:
 * 1. Khi nhận CHAT_1TO1:
 *    - Nếu người nhận trực tuyến (online): chuyển tiếp ngay qua SocketPacketChannel.
 *    - Nếu người nhận ngoại tuyến (offline): lưu nguyên vẹn Envelope bản mã vào OfflineMessageQueue.
 * 2. Khi nhận ACK:
 *    - Bóc tách mã ID tin nhắn (ack_msg_id) từ body gói tin.
 *    - Xóa tin nhắn tương ứng khỏi hàng đợi offline của người dùng.
 * 3. Chuyển tiếp các gói tin khác (KEY_LOOKUP, v.v.) sang router kế tiếp trong pipeline.</p>
 *
 * <p>Bất biến bảo mật: Server là Blind Relay, chỉ đọc siêu dữ liệu Envelope để định tuyến
 * hoặc lưu trữ hàng đợi, tuyệt đối không giải mã và không làm biến dạng body.</p>
 *
 * @author 2380600293_LeQuangDung
 */
public final class OfflineQueueRouter implements MessageRouter {

    private final ConcurrentMap<String, ClientHandler> onlineUsers;
    private final OfflineMessageQueue offlineQueue;
    private final MessageRouter nextRouter;
    private final RoutedEnvelopeSender routedEnvelopeSender;
    private volatile ServerEventListener eventListener;

    public void setEventListener(ServerEventListener listener) {
        this.eventListener = listener;
    }

    private void notifyEvent(String level, String event, String username, String details) {
        ServerEventListener listener = this.eventListener;
        if (listener != null) {
            listener.onEvent(new ServerLogEntry(System.currentTimeMillis(), level, event, username, details));
        }
    }

    public OfflineQueueRouter(
            ConcurrentMap<String, ClientHandler> onlineUsers,
            OfflineMessageQueue offlineQueue,
            MessageRouter nextRouter) {
        this(onlineUsers, offlineQueue, nextRouter, ClientHandler::sendRoutedEnvelopeIfCurrent);
    }

    OfflineQueueRouter(
            ConcurrentMap<String, ClientHandler> onlineUsers,
            OfflineMessageQueue offlineQueue,
            MessageRouter nextRouter,
            RoutedEnvelopeSender routedEnvelopeSender) {
        this.onlineUsers = Objects.requireNonNull(onlineUsers, "onlineUsers cannot be null");
        this.offlineQueue = Objects.requireNonNull(offlineQueue, "offlineQueue cannot be null");
        this.nextRouter = nextRouter;
        this.routedEnvelopeSender = Objects.requireNonNull(routedEnvelopeSender, "routedEnvelopeSender cannot be null");
    }

    @Override
    public void route(Envelope env, ClientHandler sender) throws IOException {
        if (env.getOp() == Opcode.CHAT_1TO1) {
            handleChat1to1(env, sender);
        } else if (env.getOp() == Opcode.ACK) {
            handleAck(env, sender);
        } else if (nextRouter != null) {
            nextRouter.route(env, sender);
        }
    }

    /**
     * Xử lý định tuyến tin nhắn Chat 1-1:
     * - Thử gửi trực tiếp nếu recipient đang online.
     * - Nếu recipient offline hoặc rớt mạng: đưa vào hàng đợi offline.
     */
    private void handleChat1to1(Envelope env, ClientHandler sender) throws IOException {
        String recipientUsername = env.getTo();
        if (recipientUsername == null || recipientUsername.isBlank()) {
            sender.sendRouterError("INVALID_RECIPIENT", "Recipient username cannot be empty");
            return;
        }

        ForwardResult result = sendToCurrentRecipient(recipientUsername, env);
        if (result == ForwardResult.SENT) {
            notifyEvent("INFO", "MSG_RELAY", env.getFrom(),
                    "Chuyển tiếp gói tin mã hóa AES-GCM đến " + recipientUsername);
        } else {
            // Người nhận đang offline: Lưu nguyên vẹn Envelope bản mã vào hàng đợi
            offlineQueue.enqueue(recipientUsername, env);
            int currentSize = offlineQueue.getQueueSize(recipientUsername);
            System.out.println("[E2EE-SERVER] [Issue 12] Nguoi nhan '" + recipientUsername
                    + "' offline. Da dua Envelope id=" + env.getId() + " vao OfflineMessageQueue.");
            notifyEvent("INFO", "MSG_OFFLINE", recipientUsername,
                    "Lưu tạm tin nhắn từ '" + env.getFrom() + "' vào hàng đợi (" + currentSize + "/" + OfflineMessageQueue.MAX_QUEUE_PER_USER + ")");
        }
    }

    /**
     * Xử lý gói tin ACK xác nhận đã nhận tin từ Client:
     * - Xóa tin nhắn có ack_msg_id tương ứng khỏi hàng đợi của người gửi ACK.
     */
    private void handleAck(Envelope env, ClientHandler sender) {
        String senderUsername = sender.authenticatedUsername();
        String ackMsgId = env.getString("ack_msg_id");

        if (senderUsername != null && ackMsgId != null && !ackMsgId.isBlank()) {
            boolean removed = offlineQueue.acknowledge(senderUsername, ackMsgId);
            if (removed) {
                System.out.println("[E2EE-SERVER] [Issue 12] Da nhan ACK tu '" + senderUsername
                        + "' cho message id=" + ackMsgId + " -> Da giai phong khoi queue.");
                notifyEvent("INFO", "MSG_ACK", senderUsername,
                        "Xác nhận ACK tin nhắn " + ackMsgId + ", giải phóng khỏi hàng đợi");
            }
        }
    }

    private ForwardResult sendToCurrentRecipient(String recipientUsername, Envelope env) {
        ClientHandler attempted = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            ClientHandler recipient = onlineUsers.get(recipientUsername);
            if (recipient == null || recipient == attempted) {
                return ForwardResult.OFFLINE;
            }
            attempted = recipient;

            try {
                if (routedEnvelopeSender.send(recipient, recipientUsername, env)) {
                    return ForwardResult.SENT;
                }
            } catch (IOException e) {
                recipient.closeRoutedSession();
                return ForwardResult.IO_UNKNOWN;
            }
        }
        return ForwardResult.OFFLINE;
    }

    public OfflineMessageQueue getOfflineQueue() {
        return offlineQueue;
    }

    public MessageRouter getNextRouter() {
        return nextRouter;
    }

    /**
     * Đồng bộ toàn bộ tin nhắn offline đang chờ cho người dùng vừa mới kết nối và hoàn tất AUTH_OK.
     * Từng gói tin gốc được giữ nguyên vẹn và bọc trong trường 'original_envelope' của gói OFFLINE_SYNC.
     *
     * @param username Tên người dùng nhận tin
     * @param channel Kênh kết nối SocketPacketChannel của phiên hiện tại
     * @throws IOException khi lỗi gửi mạng
     */
    public void syncOfflineMessages(String username, SocketPacketChannel channel) throws IOException {
        if (username == null || channel == null) {
            return;
        }

        List<Envelope> pending = offlineQueue.getPendingMessages(username);
        if (pending.isEmpty()) {
            return;
        }

        System.out.println("[E2EE-SERVER] [Issue 12] Dong bo " + pending.size()
                + " tin nhan offline cho user '" + username + "' qua OFFLINE_SYNC...");

        for (Envelope original : pending) {
            Envelope syncEnv = Envelope.create(Opcode.OFFLINE_SYNC, "server", username);
            syncEnv.put("original_envelope", EnvelopeMapper.toMap(original));
            channel.send(syncEnv);
        }
    }

    /**
     * Tìm kiếm thực thể OfflineQueueRouter trong chuỗi pipeline của MessageRouter.
     *
     * @param router Root router của hệ thống
     * @return OfflineQueueRouter nếu tìm thấy, ngược lại null
     */
    public static OfflineQueueRouter findIn(MessageRouter router) {
        MessageRouter current = router;
        while (current != null) {
            if (current instanceof OfflineQueueRouter oqr) {
                return oqr;
            }
            if (current instanceof GroupRouter gr) {
                current = gr.getNextRouter();
            } else {
                break;
            }
        }
        return null;
    }

    @FunctionalInterface
    interface RoutedEnvelopeSender {
        boolean send(ClientHandler recipient, String recipientUsername, Envelope envelope)
                throws IOException;
    }

    private enum ForwardResult {
        SENT,
        OFFLINE,
        IO_UNKNOWN
    }
}
