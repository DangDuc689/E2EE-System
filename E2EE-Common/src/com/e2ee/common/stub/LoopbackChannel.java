package com.e2ee.common.stub;

import com.e2ee.common.contract.Envelope;
import com.e2ee.common.contract.PacketChannel;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stub kênh truyền in-memory sử dụng LinkedBlockingQueue kết nối chéo 2 đầu.
 * Phục vụ chiến lược "Contract Day" (Ngày 1-2):
 * Cho phép các thành viên (Bình An, Dũng, Nghĩa, Tuấn) phát triển và chạy test độc lập
 * các module Server / Client / Crypto / Group / UI mà chưa cần đến Socket TCP thật.
 */
public final class LoopbackChannel implements PacketChannel {
    private final BlockingQueue<Envelope> inboundQueue;
    private final BlockingQueue<Envelope> outboundQueue;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final String channelName;

    private LoopbackChannel(String channelName, BlockingQueue<Envelope> inboundQueue, BlockingQueue<Envelope> outboundQueue) {
        this.channelName = channelName;
        this.inboundQueue = inboundQueue;
        this.outboundQueue = outboundQueue;
    }

    /**
     * Tạo một cặp PacketChannel kết nối chéo nhau [clientEnd, serverEnd].
     * Dữ liệu gửi từ clientEnd sẽ được serverEnd nhận và ngược lại.
     *
     * @return Mảng 2 phần tử [clientChannel, serverChannel]
     */
    public static PacketChannel[] pair() {
        BlockingQueue<Envelope> clientToServer = new LinkedBlockingQueue<>();
        BlockingQueue<Envelope> serverToClient = new LinkedBlockingQueue<>();

        LoopbackChannel clientEnd = new LoopbackChannel("Client-End", serverToClient, clientToServer);
        LoopbackChannel serverEnd = new LoopbackChannel("Server-End", clientToServer, serverToClient);

        return new PacketChannel[]{clientEnd, serverEnd};
    }

    @Override
    public void send(Envelope envelope) throws IOException {
        if (closed.get()) {
            throw new IOException("Kênh " + channelName + " đã bị đóng.");
        }
        try {
            outboundQueue.put(envelope);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Gửi thông điệp bị gián đoạn", e);
        }
    }

    @Override
    public Envelope receive() throws IOException {
        if (closed.get() && inboundQueue.isEmpty()) {
            throw new IOException("Kênh " + channelName + " đã bị đóng.");
        }
        try {
            return inboundQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Nhận thông điệp bị gián đoạn", e);
        }
    }

    @Override
    public boolean isOpen() {
        return !closed.get();
    }

    @Override
    public void close() throws IOException {
        closed.set(true);
    }

    @Override
    public String toString() {
        return "LoopbackChannel{" + channelName + ", open=" + isOpen() + "}";
    }
}
