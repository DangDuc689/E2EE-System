package e2ee.common.json;

import java.io.IOException;

/**
 * Lỗi cú pháp JSON khi parse dữ liệu.
 * Kế thừa {@link IOException} để lan tự nhiên qua phương thức receive() của PacketChannel.
 */
public class JsonParseException extends IOException {
    private final int position;

    public JsonParseException(String message, int position) {
        super(message + " at position " + position);
        this.position = position;
    }

    public JsonParseException(String message) {
        super(message);
        this.position = -1;
    }

    public int getPosition() {
        return position;
    }
}
