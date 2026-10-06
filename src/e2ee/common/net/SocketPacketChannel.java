package e2ee.common.net;

import e2ee.common.codec.EnvelopeMapper;
import e2ee.common.codec.FrameCodec;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.PacketChannel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cài đặt PacketChannel trên kết nối TCP thật với cơ chế 4-byte framing.
 * Đảm bảo an toàn đa luồng (Thread-safe) cho phương thức send() và receive().
 */
public final class SocketPacketChannel implements PacketChannel {
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Object sendLock = new Object();
    private final Object receiveLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SocketPacketChannel(Socket socket) throws IOException {
        if (socket == null) {
            throw new IllegalArgumentException("Socket cannot be null");
        }
        this.socket = socket;
        socket.setTcpNoDelay(true); // chat cần độ trễ thấp, tắt thuật toán Nagle
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        // Buffer để header 4 byte + payload được đẩy đi trong 1 lần flush
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    public static SocketPacketChannel connect(String host, int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return new SocketPacketChannel(s);
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    @Override
    public void send(Envelope envelope) throws IOException {
        if (!isOpen()) {
            throw new IOException("Channel is closed");
        }
        if (envelope == null) {
            throw new IllegalArgumentException("Envelope cannot be null");
        }
        // Serialize NGOÀI lock để các luồng không phải chờ nhau lúc tạo JSON
        byte[] payload = EnvelopeMapper.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        synchronized (sendLock) {
            if (!isOpen()) {
                throw new IOException("Channel is closed");
            }
            FrameCodec.writeFrame(out, payload); // header + payload của 1 gói không bị chen ngang
        }
    }

    @Override
    public Envelope receive() throws IOException {
        byte[] payload;
        synchronized (receiveLock) {
            if (!isOpen()) {
                throw new IOException("Channel is closed");
            }
            payload = FrameCodec.readFrame(in);
        }
        return EnvelopeMapper.fromJson(new String(payload, StandardCharsets.UTF_8));
    }

    @Override
    public boolean isOpen() {
        return !closed.get() && !socket.isClosed();
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            socket.close(); // luồng đang chặn ở receive() sẽ nhận exception và thoát
        }
    }
}
