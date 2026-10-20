package e2ee.server;

import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;

import java.io.IOException;
import java.util.concurrent.ConcurrentMap;

/**
 * Định tuyến chat 1-1 và tra cứu public key cho các session đã xác thực.
 *
 * <p>CHAT_1TO1 được chuyển tiếp mù: server chỉ đọc metadata định tuyến,
 * không giải mã, thay đổi hoặc lưu body. KEY_LOOKUP chỉ trả public key đang
 * có trong registry. Đây là cài đặt mặc định có thể được thay bằng
 * OfflineQueueRouter về sau mà không thay đổi protocol.</p>
 */
public final class Default1to1Router implements MessageRouter {
    private final ConcurrentMap<String, ClientHandler> onlineUsers;
    private final ConcurrentMap<String, String> publicKeyRegistry;
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

    public Default1to1Router(
            ConcurrentMap<String, ClientHandler> onlineUsers,
            ConcurrentMap<String, String> publicKeyRegistry) {
        this(onlineUsers, publicKeyRegistry,
                ClientHandler::sendRoutedEnvelopeIfCurrent);
    }

    Default1to1Router(
            ConcurrentMap<String, ClientHandler> onlineUsers,
            ConcurrentMap<String, String> publicKeyRegistry,
            RoutedEnvelopeSender routedEnvelopeSender) {
        this.onlineUsers = onlineUsers;
        this.publicKeyRegistry = publicKeyRegistry;
        this.routedEnvelopeSender = routedEnvelopeSender;
    }

    @Override
    public void route(Envelope env, ClientHandler sender) throws IOException {
        if (env.getOp() == Opcode.CHAT_1TO1) {
            routeChat(env, sender);
        } else if (env.getOp() == Opcode.KEY_LOOKUP) {
            routeKeyLookup(env, sender);
        }
    }

    private void routeChat(Envelope env, ClientHandler sender) throws IOException {
        String recipientUsername = env.getTo();
        ForwardResult result = recipientUsername == null
                ? ForwardResult.OFFLINE
                : sendToCurrentRecipient(recipientUsername, env);
        if (result == ForwardResult.SENT) {
            notifyEvent("INFO", "MSG_RELAY", env.getFrom(),
                    "Chuyển tiếp gói tin mã hóa AES-GCM đến " + recipientUsername);
        } else if (result == ForwardResult.OFFLINE) {
            sender.sendRouterError(
                    "RECIPIENT_OFFLINE", "User is offline");
            notifyEvent("WARN", "RECIPIENT_OFFLINE", env.getFrom(),
                    "Người nhận ngoại tuyến: " + recipientUsername);
        }
    }

    private ForwardResult sendToCurrentRecipient(String recipientUsername,
            Envelope env) {
        ClientHandler attempted = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            ClientHandler recipient = onlineUsers.get(recipientUsername);
            if (recipient == null || recipient == attempted) {
                return ForwardResult.OFFLINE;
            }
            attempted = recipient;

            try {
                if (routedEnvelopeSender.send(
                        recipient, recipientUsername, env)) {
                    return ForwardResult.SENT;
                }
            } catch (IOException e) {
                recipient.closeRoutedSession();
                return ForwardResult.IO_UNKNOWN;
            }
        }
        return ForwardResult.OFFLINE;
    }

    private void routeKeyLookup(Envelope env, ClientHandler sender)
            throws IOException {
        String targetUsername = env.getTo();
        String publicKey = targetUsername == null
                ? null : publicKeyRegistry.get(targetUsername);
        if (publicKey == null) {
            sender.sendRouterError("KEY_NOT_FOUND", "User not found");
            return;
        }

        Envelope result = Envelope.create(
                Opcode.KEY_LOOKUP_RESULT,
                targetUsername,
                sender.authenticatedUsername())
                .put("pubkey", publicKey);
        sender.sendRouterEnvelope(result);
    }

    @FunctionalInterface
    interface RoutedEnvelopeSender {
        boolean send(ClientHandler recipient, String expectedUsername,
                Envelope env) throws IOException;
    }

    private enum ForwardResult {
        SENT,
        OFFLINE,
        IO_UNKNOWN
    }
}
