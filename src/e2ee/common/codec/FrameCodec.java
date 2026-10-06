package e2ee.common.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Đóng và mở frame giao thức theo cơ chế 4-byte length prefix (Big-Endian).
 * Định dạng: [4 byte độ dài Big-Endian][payload N byte].
 * Giới hạn payload tối đa là 1 MiB để ngăn chặn tấn công DoS / tràn bộ nhớ.
 */
public final class FrameCodec {
    public static final int MAX_FRAME_SIZE = 1024 * 1024; // 1 MiB

    private FrameCodec() {
    }

    /**
     * Ghi một frame ra luồng output.
     *
     * @param out luồng dữ liệu đích
     * @param payload mảng byte dữ liệu cần gửi
     * @throws IOException nếu payload vượt quá MAX_FRAME_SIZE hoặc lỗi I/O
     */
    public static void writeFrame(DataOutputStream out, byte[] payload) throws IOException {
        if (payload == null) {
            throw new IOException("Payload cannot be null");
        }
        if (payload.length > MAX_FRAME_SIZE) {
            throw new IOException("Frame too large: " + payload.length + " bytes");
        }
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    /**
     * Đọc một frame từ luồng input.
     *
     * @param in luồng dữ liệu nguồn
     * @return mảng byte payload nhận được
     * @throws IOException nếu độ dài frame không hợp lệ (< 0 hoặc > MAX_FRAME_SIZE) hoặc lỗi I/O
     */
    public static byte[] readFrame(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_FRAME_SIZE) {
            throw new IOException("Invalid frame length: " + length);
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        return payload;
    }
}
