package e2ee.server;

import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.net.SocketPacketChannel;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Điểm khởi chạy của máy chủ E2EE Server (Blind Relay & Key Registry).
 * 
 * RÀNG BUỘC KIẾN TRÚC SỐNG CÒN (Issue 12 - Store-and-Forward & Offline Queue):
 * - Server đóng vai trò Blind Relay (Chuyển tiếp mù).
 * - Server chỉ đọc Metadata của Envelope để chuyển gói (`to`, `groupId`).
 * - TUYỆT ĐỐI KHÔNG import hoặc sử dụng `javax.crypto.Cipher` trong toàn bộ
 * source Server.
 */
public class ServerMain {
    public static final int DEFAULT_PORT = 8888;
    public static final int MAX_CLIENTS = 50;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final Object lifecycleLock = new Object();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final Set<Socket> activeClients = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, String> publicKeyRegistry = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
    private final PresenceDispatcher presenceDispatcher = new PresenceDispatcher(onlineUsers);
    private final OfflineMessageQueue offlineQueue = new OfflineMessageQueue();
    private final Default1to1Router default1to1Router;
    private final OfflineQueueRouter offlineQueueRouter;
    private final MessageRouter messageRouter;
    private final ThreadPoolExecutor clientExecutor;
    private final List<ServerEventListener> listeners = new CopyOnWriteArrayList<>();

    private volatile ServerSocket serverSocket;
    private volatile long startedAt = 0L;

    public ServerMain() {
        /*
         * ==============================================
         * BOUNDED THREAD POOL
         * MAX CLIENTS - 50
         * ==============================================
         */
        default1to1Router = new Default1to1Router(onlineUsers, publicKeyRegistry);
        default1to1Router.setEventListener(this::notifyEvent);

        offlineQueueRouter = new OfflineQueueRouter(onlineUsers, offlineQueue, default1to1Router);
        offlineQueueRouter.setEventListener(this::notifyEvent);

        messageRouter = new GroupRouter(
                onlineUsers,
                new ConcurrentHashMap<>(),
                offlineQueue,
                offlineQueueRouter);

        clientExecutor = new ThreadPoolExecutor(
                MAX_CLIENTS,
                MAX_CLIENTS,
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public static void main(String[] args) {
        boolean headless = java.awt.GraphicsEnvironment.isHeadless();
        boolean forceCli = args != null && Arrays.asList(args).contains("--no-gui");

        if (!headless && !forceCli) {
            javax.swing.SwingUtilities.invokeLater(() -> {
                e2ee.server.ui.ServerDashboardFrame dashboard = new e2ee.server.ui.ServerDashboardFrame();
                dashboard.setVisible(true);
                dashboard.startServer();
            });
        } else {
            ServerMain server = new ServerMain();
            Runtime.getRuntime().addShutdownHook(
                    new Thread(server::shutdown, "e2ee-server-shutdown"));
            server.run();
        }
    }

    public void run() {
        /*
         * ==============================================
         * SERVER SOCKET - ISSUE #12
         * ==============================================
         */
        ServerSocket listeningSocket;
        try {
            synchronized (lifecycleLock) {
                if (shuttingDown.get()) {
                    return;
                }
                listeningSocket = new ServerSocket(DEFAULT_PORT);
                serverSocket = listeningSocket;
                startedAt = System.currentTimeMillis();
            }

            System.out.println("[E2EE-SERVER] Server da khoi dong.");
            System.out.println("[E2EE-SERVER] Dang lang nghe tai port " + DEFAULT_PORT + ".");
            notifyEvent("INFO", "SERVER_START", "server",
                    "Server đã khởi động, đang lắng nghe tại port " + DEFAULT_PORT);

            while (!shuttingDown.get()) {
                Socket clientSocket = listeningSocket.accept();
                synchronized (lifecycleLock) {
                    if (shuttingDown.get()) {
                        closeSocket(clientSocket);
                        break;
                    }
                    activeClients.add(clientSocket);
                }
                try {
                    ClientHandler handler = new ClientHandler(
                            clientSocket,
                            () -> activeClients.remove(clientSocket),
                            publicKeyRegistry,
                            onlineUsers,
                            presenceDispatcher,
                            messageRouter,
                            () -> {
                            },
                            () -> {
                            },
                            () -> {
                            },
                            () -> {
                            });
                    handler.setEventListener(this::notifyEvent);
                    clientExecutor.submit(handler);
                } catch (RejectedExecutionException e) {
                    try {
                        rejectClient(clientSocket);
                    } finally {
                        activeClients.remove(clientSocket);
                    }
                }
            }
        } catch (IOException e) {
            if (!shuttingDown.get()) {
                System.err.println("[E2EE-SERVER] Khong the lang nghe tai port "
                        + DEFAULT_PORT + ": " + e.getMessage());
                notifyEvent("ERROR", "SERVER_ERROR", "server",
                        "Không thể lắng nghe tại port " + DEFAULT_PORT + ": " + e.getMessage());
            }
        } finally {
            shutdown();
        }
    }

    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        /*
         * ================================================================
         * ____ ____ _ ____ _____ _____ _ _ _
         * / ___| _ \ / \ / ___| ____| ___| | | | |
         * | | _| |_) | / _ \| | | _| | |_ | | | | |
         * | |_| | _ < / ___ \ |___| |___| _| | |_| | |___
         * \____|_| \_\/_/ \_\____|_____|_| \___/|_____|
         * GRACEFUL SHUTDOWN
         * ================================================================
         */
        synchronized (lifecycleLock) {
            closeServerSocket();
            startedAt = 0L;
        }
        activeClients.forEach(ServerMain::closeSocket);
        presenceDispatcher.shutdown();
        clientExecutor.shutdown();

        try {
            if (!clientExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                clientExecutor.shutdownNow();
                clientExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            clientExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("[E2EE-SERVER] Server da dung an toan.");
        notifyEvent("INFO", "SERVER_STOP", "server", "Server đã dừng an toàn.");
    }

    public boolean isTerminated() {
        return clientExecutor.isTerminated();
    }

    private void closeServerSocket() {
        ServerSocket socket = serverSocket;
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("[E2EE-SERVER] Loi khi dong ServerSocket: "
                        + e.getMessage());
            }
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("[E2EE-SERVER] Loi khi dong client socket: "
                        + e.getMessage());
            }
        }
    }

    private void rejectClient(Socket clientSocket) {
        try (Socket socket = clientSocket;
                SocketPacketChannel channel = new SocketPacketChannel(socket)) {
            Envelope error = Envelope.create(Opcode.ERROR, "server", "client")
                    .put("code", "MAX_CLIENTS_REACHED")
                    .put("msg", "Server is full, please retry later");
            channel.send(error);
            System.out.println("[E2EE-SERVER] Tu choi ket noi vi da du "
                    + MAX_CLIENTS + " client: " + socket.getRemoteSocketAddress());
            notifyEvent("WARN", "MAX_CLIENTS_REACHED", "-",
                    "Từ chối kết nối do đã đủ " + MAX_CLIENTS + " client");
        } catch (IOException e) {
            System.err.println("[E2EE-SERVER] Loi khi tu choi client: "
                    + e.getMessage());
        }
    }

    public OfflineMessageQueue getOfflineQueue() {
        return offlineQueue;
    }

    public MessageRouter getMessageRouter() {
        return messageRouter;
    }

    public record SessionSnapshot(String username, long connectedAt, String status) {}

    public List<SessionSnapshot> getSessionSnapshots() {
        List<SessionSnapshot> list = new ArrayList<>();
        for (Map.Entry<String, ClientHandler> entry : onlineUsers.entrySet()) {
            list.add(new SessionSnapshot(
                    entry.getKey(),
                    entry.getValue().getConnectedAt(),
                    "Đã xác thực"));
        }
        return list;
    }

    public int getActiveClientCount() {
        return activeClients.size();
    }

    public int getOnlineSessionCount() {
        return onlineUsers.size();
    }

    public long getStartedAt() {
        return startedAt;
    }

    public boolean isRunning() {
        return !shuttingDown.get() && serverSocket != null && !serverSocket.isClosed();
    }

    public void addEventListener(ServerEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeEventListener(ServerEventListener listener) {
        listeners.remove(listener);
    }

    public void notifyEvent(ServerLogEntry entry) {
        if (entry == null) {
            return;
        }
        for (ServerEventListener listener : listeners) {
            try {
                listener.onEvent(entry);
            } catch (Throwable ignored) {
            }
        }
    }

    public void notifyEvent(String level, String event, String username, String details) {
        notifyEvent(new ServerLogEntry(System.currentTimeMillis(), level, event, username, details));
    }
}
