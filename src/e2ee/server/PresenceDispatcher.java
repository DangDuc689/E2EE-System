package e2ee.server;

import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * Điều phối snapshot và broadcast PRESENCE cho toàn bộ server.
 *
 * <p>Lớp này bổ sung để tránh race trong đó sự kiện offline cũ có thể đến sau
 * online mới khi một username reconnect nhanh. Mỗi session có mailbox FIFO hữu
 * hạn và một daemon writer riêng, nhờ đó client gửi chậm không chặn client khác.
 * Dispatcher chỉ giữ lock khi cập nhật registry/mailbox, không giữ lock trong
 * network I/O.</p>
 *
 * <p>Việc dừng writer đang kẹt trong blocking I/O là best-effort: dispatcher yêu
 * cầu dừng, đóng socket và không chờ vô hạn. Đây không phải Offline Queue; sự
 * kiện của client đã offline không được lưu để phát lại và protocol không đổi.</p>
 */
final class PresenceDispatcher {
    // Lớn hơn MAX_CLIENTS để chứa một đợt thay đổi của toàn bộ server.
    static final int DEFAULT_MAILBOX_CAPACITY = 64;

    private final Object lock = new Object();
    private final ConcurrentMap<String, ClientHandler> onlineUsers;
    private final Map<ClientHandler, SessionState> sessions = new HashMap<>();
    private final int mailboxCapacity;
    private boolean shuttingDown;

    PresenceDispatcher(ConcurrentMap<String, ClientHandler> onlineUsers) {
        this(onlineUsers, DEFAULT_MAILBOX_CAPACITY);
    }

    PresenceDispatcher(ConcurrentMap<String, ClientHandler> onlineUsers,
            int mailboxCapacity) {
        if (mailboxCapacity < 1) {
            throw new IllegalArgumentException("Mailbox capacity must be positive");
        }
        this.onlineUsers = onlineUsers;
        this.mailboxCapacity = mailboxCapacity;
    }

    Registration register(String username, ClientHandler handler) throws IOException {
        SessionState state;
        ClientHandler replacedHandler = null;
        List<String> snapshot;

        synchronized (lock) {
            if (shuttingDown) {
                throw new IOException("Presence dispatcher is shutting down");
            }

            ClientHandler previous = onlineUsers.put(username, handler);
            SessionState previousState = previous == null ? null : sessions.remove(previous);
            boolean previouslyVisible = previousState != null && previousState.visibleOnline;
            if (previous != null && previous != handler) {
                previous.revokeRequestDispatch();
                stopStateLocked(previousState);
                replacedHandler = previous;
            }

            state = new SessionState(username, handler, previouslyVisible);
            sessions.put(handler, state);
            snapshot = snapshotLocked(username);
        }

        state.writer.start();
        if (replacedHandler != null) {
            replacedHandler.closeReplacedSession();
        }
        return new Registration(snapshot);
    }

    boolean activate(String username, ClientHandler handler) {
        List<ClientHandler> overflowed;
        synchronized (lock) {
            SessionState state = sessions.get(handler);
            if (shuttingDown
                    || state == null
                    || state.stopped
                    || onlineUsers.get(username) != handler) {
                return false;
            }

            state.active = true;
            state.visibleOnline = true;
            lock.notifyAll();
            overflowed = publishLocked(username, true, handler);
        }
        closeOverflowed(overflowed);
        return true;
    }

    void disconnect(String username, ClientHandler handler) {
        if (username == null) {
            return;
        }

        List<ClientHandler> overflowed;
        synchronized (lock) {
            SessionState state = sessions.remove(handler);
            stopStateLocked(state);
            boolean removed = onlineUsers.remove(username, handler);
            overflowed = removed && state != null && state.visibleOnline
                    ? publishLocked(username, false, handler)
                    : List.of();
        }
        closeOverflowed(overflowed);
    }

    void shutdown() {
        List<ClientHandler> handlers;
        synchronized (lock) {
            if (shuttingDown) {
                return;
            }
            shuttingDown = true;
            handlers = new ArrayList<>(sessions.keySet());
            for (SessionState state : sessions.values()) {
                stopStateLocked(state);
            }
            sessions.clear();
            onlineUsers.clear();
        }
        handlers.forEach(ClientHandler::closePresenceSession);
    }

    private List<String> snapshotLocked(String selfUsername) {
        List<String> snapshot = new ArrayList<>();
        for (Map.Entry<String, ClientHandler> entry : onlineUsers.entrySet()) {
            String username = entry.getKey();
            ClientHandler handler = entry.getValue();
            SessionState state = sessions.get(handler);
            if (!username.equals(selfUsername)
                    && state != null
                    && !state.stopped
                    && state.visibleOnline
                    && onlineUsers.get(username) == handler) {
                snapshot.add(username);
            }
        }
        return snapshot;
    }

    private List<ClientHandler> publishLocked(String username, boolean online,
            ClientHandler self) {
        Envelope event = Envelope.create(Opcode.PRESENCE, "server", null)
                .put("user", username)
                .put("online", online);
        ArrayDeque<SessionState> overflowQueue = new ArrayDeque<>();
        List<ClientHandler> closeAfterUnlock = new ArrayList<>();
        enqueueForRecipientsLocked(event, self, overflowQueue);

        while (!overflowQueue.isEmpty()) {
            SessionState overflowed = overflowQueue.removeFirst();
            if (overflowed.stopped) {
                continue;
            }
            sessions.remove(overflowed.handler, overflowed);
            stopStateLocked(overflowed);
            boolean removed = onlineUsers.remove(overflowed.username, overflowed.handler);
            closeAfterUnlock.add(overflowed.handler);
            if (removed && overflowed.visibleOnline) {
                Envelope offline = Envelope.create(Opcode.PRESENCE, "server", null)
                        .put("user", overflowed.username)
                        .put("online", false);
                enqueueForRecipientsLocked(offline, overflowed.handler, overflowQueue);
            }
        }
        lock.notifyAll();
        return closeAfterUnlock;
    }

    private void enqueueForRecipientsLocked(Envelope event, ClientHandler self,
            ArrayDeque<SessionState> overflowQueue) {
        for (SessionState recipient : new ArrayList<>(sessions.values())) {
            if (recipient.handler == self || recipient.stopped) {
                continue;
            }
            if (recipient.mailbox.size() >= mailboxCapacity) {
                overflowQueue.addLast(recipient);
            } else {
                recipient.mailbox.addLast(copyForRecipient(event, recipient.username));
            }
        }
    }

    private static Envelope copyForRecipient(Envelope event, String recipient) {
        return Envelope.create(Opcode.PRESENCE, "server", recipient)
                .put("user", event.getString("user"))
                .put("online", event.get("online"));
    }

    private void writerLoop(SessionState state) {
        while (true) {
            Envelope event;
            synchronized (lock) {
                while (!state.stopped && (!state.active || state.mailbox.isEmpty())) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        if (state.stopped || shuttingDown) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                if (state.stopped) {
                    return;
                }
                event = state.mailbox.removeFirst();
            }

            try {
                state.handler.sendPresence(event);
            } catch (IOException e) {
                writerFailed(state);
                return;
            }
        }
    }

    private void writerFailed(SessionState failed) {
        List<ClientHandler> overflowed;
        synchronized (lock) {
            if (failed.stopped) {
                return;
            }
            sessions.remove(failed.handler, failed);
            stopStateLocked(failed);
            boolean removed = onlineUsers.remove(failed.username, failed.handler);
            overflowed = removed && failed.visibleOnline
                    ? publishLocked(failed.username, false, failed.handler)
                    : List.of();
        }
        failed.handler.closePresenceSession();
        closeOverflowed(overflowed);
    }

    private void stopStateLocked(SessionState state) {
        if (state == null || state.stopped) {
            return;
        }
        state.stopped = true;
        state.mailbox.clear();
        state.writer.interrupt();
        lock.notifyAll();
    }

    private static void closeOverflowed(List<ClientHandler> handlers) {
        handlers.forEach(ClientHandler::closePresenceSession);
    }

    static final class Registration {
        private final List<String> snapshot;

        private Registration(List<String> snapshot) {
            this.snapshot = List.copyOf(snapshot);
        }

        List<String> snapshot() {
            return snapshot;
        }
    }

    private final class SessionState {
        private final String username;
        private final ClientHandler handler;
        private final ArrayDeque<Envelope> mailbox = new ArrayDeque<>();
        private final Thread writer;
        private boolean active;
        private boolean visibleOnline;
        private boolean stopped;

        private SessionState(String username, ClientHandler handler,
                boolean visibleOnline) {
            this.username = username;
            this.handler = handler;
            this.visibleOnline = visibleOnline;
            writer = new Thread(() -> writerLoop(this),
                    "e2ee-presence-" + username);
            writer.setDaemon(true);
        }
    }
}
