package e2ee.server;

import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.net.SocketPacketChannel;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

    public static void main(String[] args) {
        /*
         * ==============================================
         * BOUNDED THREAD POOL
         * MAX CLIENTS - 50
         * ==============================================
         */
        ExecutorService clientExecutor = new ThreadPoolExecutor(
                MAX_CLIENTS,
                MAX_CLIENTS,
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy());

        /*
         * ==============================================
         * SERVER SOCKET - ISSUE #7
         * ==============================================
         */
        try (ServerSocket serverSocket = new ServerSocket(DEFAULT_PORT)) {
            System.out.println("[E2EE-SERVER] Server da khoi dong.");
            System.out.println("[E2EE-SERVER] Dang lang nghe tai port " + DEFAULT_PORT + ".");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                try {
                    clientExecutor.submit(new ClientHandler(clientSocket));
                } catch (RejectedExecutionException e) {
                    rejectClient(clientSocket);
                }
            }
        } catch (IOException e) {
            System.err.println("[E2EE-SERVER] Khong the lang nghe tai port "
                    + DEFAULT_PORT + ": " + e.getMessage());
        } finally {
            clientExecutor.shutdown();
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
