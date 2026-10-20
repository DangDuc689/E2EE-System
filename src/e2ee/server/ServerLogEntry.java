package e2ee.server;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Bản ghi sự kiện nhật ký máy chủ (Pure JDK 17 Record).
 * Tuyệt đối không chứa payload giải mã (Blind Relay).
 */
public record ServerLogEntry(
        long timestamp,
        String level,
        String event,
        String username,
        String details) {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public String formattedTime() {
        return TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }
}
