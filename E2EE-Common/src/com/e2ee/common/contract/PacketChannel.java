package com.e2ee.common.contract;

import java.io.Closeable;
import java.io.IOException;

/**
 * Interface trừu tượng hóa kênh truyền thông điệp (TCP Framing Socket hoặc Loopback in-memory).
 * Bắt buộc phương thức send() phải đảm bảo an toàn đa luồng (Thread-safe)
 * để tránh Race Condition làm vỡ framing.
 */
public interface PacketChannel extends Closeable {
    /**
     * Gửi một Envelope qua kênh truyền.
     * Cài đặt phải đảm bảo an toàn đa luồng (Thread-safe).
     *
     * @param envelope Gói tin Envelope cần truyền đi
     * @throws IOException Khi có lỗi I/O hoặc kênh truyền bị ngắt
     */
    void send(Envelope envelope) throws IOException;

    /**
     * Nhận một Envelope từ kênh truyền (Chặn cho đến khi có gói tin hoặc kênh đóng).
     *
     * @return Envelope nhận được
     * @throws IOException Khi có lỗi đọc dữ liệu hoặc kênh truyền bị đóng
     */
    Envelope receive() throws IOException;

    /**
     * Kiểm tra kênh truyền có đang mở hay không.
     *
     * @return true nếu kênh còn mở và sẵn sàng hoạt động
     */
    boolean isOpen();
}
