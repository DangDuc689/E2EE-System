package e2ee.server;

import e2ee.common.contract.Envelope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * Hàng đợi tin nhắn Offline tạm thời trong RAM của Server (Pure JDK 17) - Issue 12.
 * Phụ trách:
 * 1. Lưu trữ tạm thời các gói tin Envelope mã hóa cho người dùng đang ngoại tuyến.
 * 2. Cơ chế FIFO Bounded Queue: Giới hạn tối đa 100 tin nhắn/người dùng.
 * 3. Chống tràn RAM Server: Tự động loại bỏ tin cũ nhất khi hàng đợi vượt ngưỡng 100.
 * 4. Cơ chế At-least-once: Chỉ xóa tin nhắn khỏi hàng đợi khi nhận được gói ACK hợp lệ.
 *
 * <p>Ràng buộc an toàn: Server đóng vai trò Blind Relay, chỉ lưu trữ Envelope bản mã
 * nguyên vẹn, tuyệt đối không giải mã và không can thiệp vào trường body.</p>
 *
 * @author 2380600293_LeQuangDung
 */
public final class OfflineMessageQueue {

    public static final int MAX_QUEUE_PER_USER = 100;

    // Lưu trữ hàng đợi tin nhắn theo từng người dùng (username -> Queue<Envelope>)
    private final ConcurrentMap<String, Queue<Envelope>> userQueues;

    public OfflineMessageQueue() {
        this.userQueues = new ConcurrentHashMap<>();
    }

    /**
     * Đưa một gói tin Envelope vào hàng đợi offline của người nhận.
     * Áp dụng quy tắc FIFO: Nếu hàng đợi đã đạt ngưỡng 100 tin nhắn,
     * tự động loại bỏ tin cũ nhất trước khi thêm tin mới.
     *
     * @param recipientUsername Tên người nhận tin nhắn ngoại tuyến
     * @param envelope Gói tin Envelope bản mã cần lưu trữ
     */
    public void enqueue(String recipientUsername, Envelope envelope) {
        if (recipientUsername == null || recipientUsername.isBlank() || envelope == null) {
            return;
        }

        Queue<Envelope> queue = userQueues.computeIfAbsent(recipientUsername, k -> new ConcurrentLinkedQueue<>());
        synchronized (queue) {
            while (queue.size() >= MAX_QUEUE_PER_USER) {
                // Loại bỏ tin nhắn cũ nhất ở đầu hàng đợi để chống tràn RAM
                queue.poll();
            }
            queue.offer(envelope);
        }
    }

    /**
     * Xác nhận đã nhận tin nhắn và xóa tin khỏi hàng đợi (khi nhận gói ACK từ Client).
     *
     * @param recipientUsername Tên người dùng đã gửi ACK xác nhận
     * @param messageId ID (UUID) của gói tin Envelope cần xóa
     * @return true nếu tìm thấy và xóa thành công tin nhắn, ngược lại false
     */
    public boolean acknowledge(String recipientUsername, String messageId) {
        if (recipientUsername == null || messageId == null || messageId.isBlank()) {
            return false;
        }

        Queue<Envelope> queue = userQueues.get(recipientUsername);
        if (queue == null) {
            return false;
        }

        synchronized (queue) {
            return queue.removeIf(env -> messageId.equals(env.getId()));
        }
    }

    /**
     * Lấy danh sách bản sao các tin nhắn đang chờ trong hàng đợi của một người dùng.
     *
     * @param recipientUsername Tên người dùng cần kiểm tra
     * @return Danh sách các gói tin Envelope theo thứ tự FIFO (không làm thay đổi hàng đợi)
     */
    public List<Envelope> getPendingMessages(String recipientUsername) {
        if (recipientUsername == null || recipientUsername.isBlank()) {
            return Collections.emptyList();
        }

        Queue<Envelope> queue = userQueues.get(recipientUsername);
        if (queue == null) {
            return Collections.emptyList();
        }

        synchronized (queue) {
            return new ArrayList<>(queue);
        }
    }

    /**
     * Lấy số lượng tin nhắn hiện có trong hàng đợi của một người dùng.
     *
     * @param recipientUsername Tên người dùng cần kiểm tra
     * @return Số lượng tin nhắn đang chờ
     */
    public int getQueueSize(String recipientUsername) {
        if (recipientUsername == null) {
            return 0;
        }
        Queue<Envelope> queue = userQueues.get(recipientUsername);
        if (queue == null) {
            return 0;
        }
        synchronized (queue) {
            return queue.size();
        }
    }

    /**
     * Kiểm tra xem hàng đợi của người dùng có đang trống hay không.
     *
     * @param recipientUsername Tên người dùng cần kiểm tra
     * @return true nếu hàng đợi trống hoặc người dùng chưa có tin nhắn
     */
    public boolean isEmpty(String recipientUsername) {
        return getQueueSize(recipientUsername) == 0;
    }

    /**
     * Xóa toàn bộ hàng đợi của một người dùng.
     *
     * @param recipientUsername Tên người dùng cần xóa hàng đợi
     */
    public void clear(String recipientUsername) {
        if (recipientUsername == null) {
            return;
        }
        Queue<Envelope> queue = userQueues.remove(recipientUsername);
        if (queue != null) {
            synchronized (queue) {
                queue.clear();
            }
        }
    }

    /**
     * Xóa toàn bộ dữ liệu trong tất cả các hàng đợi (phục vụ reset kiểm thử).
     */
    public void clearAll() {
        userQueues.clear();
    }

    /**
     * Lấy tóm tắt số lượng tin nhắn đang chờ theo từng người dùng (chỉ đọc).
     *
     * @return Map chứa username và số lượng tin nhắn tương ứng
     */
    public Map<String, Integer> getQueueSummary() {
        Map<String, Integer> summary = new LinkedHashMap<>();
        for (Map.Entry<String, Queue<Envelope>> entry : userQueues.entrySet()) {
            int size = entry.getValue().size();
            if (size > 0) {
                summary.put(entry.getKey(), size);
            }
        }
        return Collections.unmodifiableMap(summary);
    }

    /**
     * Lấy tổng số lượng tin nhắn đang chờ trong toàn bộ các hàng đợi.
     *
     * @return Tổng số tin nhắn
     */
    public int getTotalQueueSize() {
        int total = 0;
        for (Queue<Envelope> queue : userQueues.values()) {
            total += queue.size();
        }
        return total;
    }
}
