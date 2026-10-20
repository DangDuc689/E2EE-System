package e2ee.common.contract;

import java.nio.charset.StandardCharsets;

/**
 * Shared size policy for decrypted chat text.
 * The limit is measured in UTF-8 bytes so it is independent of Java's
 * UTF-16 String representation and matches the wire encoding.
 */
public final class MessageLimits {
    public static final int MAX_TEXT_UTF8_BYTES = 64 * 1024;

    private MessageLimits() {
    }

    /**
     * Returns the size of text after UTF-8 encoding.
     */
    public static int utf8ByteLength(String content) {
        if (content == null) {
            return 0;
        }
        return content.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Returns whether text is within the shared chat text limit.
     */
    public static boolean isWithinLimit(String content) {
        return content != null
                && utf8ByteLength(content) <= MAX_TEXT_UTF8_BYTES;
    }

    /**
     * Rejects text that is too large for a chat message.
     */
    public static void requireWithinLimit(String content) {
        if (content == null) {
            throw new IllegalArgumentException("Tin nhan khong duoc null");
        }
        int byteLength = utf8ByteLength(content);
        if (byteLength > MAX_TEXT_UTF8_BYTES) {
            throw new IllegalArgumentException(String.format(
                    "Tin nhan qua dai (%d byte; gioi han %d byte)",
                    byteLength, MAX_TEXT_UTF8_BYTES));
        }
    }
}
