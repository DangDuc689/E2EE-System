package e2ee.server;

/**
 * Interface lắng nghe sự kiện có cấu trúc từ E2EE Server.
 */
@FunctionalInterface
public interface ServerEventListener {
    void onEvent(ServerLogEntry entry);
}
