package e2ee.server;

import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.net.SocketPacketChannel;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Điểm khởi chạy của máy chủ E2EE Server (Blind Relay & Key Registry).
 * 
 * RÀNG BUỘC KIẾN TRÚC SỐNG CÒN (Issue #7 - Bình An phụ trách):
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
    private final ThreadPoolExecutor clientExecutor;

    private volatile ServerSocket serverSocket;

    ServerMain() {
        /*
         * ==============================================
         * BOUNDED THREAD POOL
         * MAX CLIENTS - 50
         * ==============================================
         */
        clientExecutor = new ThreadPoolExecutor(
                MAX_CLIENTS,
                MAX_CLIENTS,
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public static void main(String[] args) {
        ServerMain server = new ServerMain();
        Runtime.getRuntime().addShutdownHook(
                new Thread(server::shutdown, "e2ee-server-shutdown"));
        server.run();
    }

    void run() {
        /*
         * ==============================================
         * SERVER SOCKET - ISSUE #7
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
            }

            System.out.println("[E2EE-SERVER] Server da khoi dong.");
            System.out.println("[E2EE-SERVER] Dang lang nghe tai port " + DEFAULT_PORT + ".");

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
                    clientExecutor.submit(new ClientHandler(
                            clientSocket,
                            () -> activeClients.remove(clientSocket)));
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
            }
        } finally {
            shutdown();
        }
    }

    void shutdown() {
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
        }
        activeClients.forEach(ServerMain::closeSocket);
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
    }

    boolean isTerminated() {
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

    private static void rejectClient(Socket clientSocket) {
        try (Socket socket = clientSocket;
                SocketPacketChannel channel = new SocketPacketChannel(socket)) {
            Envelope error = Envelope.create(Opcode.ERROR, "server", "client")
                    .put("code", "MAX_CLIENTS_REACHED")
                    .put("msg", "Server is full, please retry later");
            channel.send(error);
            System.out.println("[E2EE-SERVER] Tu choi ket noi vi da du "
                    + MAX_CLIENTS + " client: " + socket.getRemoteSocketAddress());
        } catch (IOException e) {
            System.err.println("[E2EE-SERVER] Loi khi tu choi client: "
                    + e.getMessage());
        }
    }
}
