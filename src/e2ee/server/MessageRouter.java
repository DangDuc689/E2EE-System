package e2ee.server;

import e2ee.common.contract.Envelope;

import java.io.IOException;

/**
 * Định tuyến các Envelope đã qua xác thực ở phía server.
 *
 * <p>Interface này là điểm mở rộng để một router khác, chẳng hạn
 * OfflineQueueRouter, có thể thay thế cách xử lý mặc định mà không đổi
 * protocol hoặc vòng đời kết nối.</p>
 */
public interface MessageRouter {
    void route(Envelope env, ClientHandler sender) throws IOException;
}
