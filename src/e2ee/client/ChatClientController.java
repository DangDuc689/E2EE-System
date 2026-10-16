package e2ee.client;

import e2ee.client.crypto.ClientCryptoEngine;
import e2ee.client.crypto.ClientCryptoService;
import e2ee.client.identity.ClientIdentityStore;
import e2ee.client.identity.ClientIdentityStore.Identity;
import e2ee.client.security.LocalTrustStore;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.contract.PacketChannel;
import e2ee.common.net.SocketPacketChannel;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ điều phối kết nối, xác thực và xử lý gói tin của ứng dụng Swing Client.
 * Mọi I/O, đọc file khóa và crypto đều chạy ngoài Swing EDT.
 */
public final class ChatClientController implements AutoCloseable {
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

    private final Object lifecycleLock = new Object();
    private final ClientIdentityStore identityStore;
    private final LocalTrustStore trustStore;
    private final ClientCryptoService cryptoService;
    private final ChannelConnector channelConnector;
    private final ExecutorService backgroundExecutor;

    private volatile Listener listener = new Listener() {
    };
    private volatile State state = State.DISCONNECTED;
    private volatile PacketChannel channel;
    private volatile Identity identity;
    private volatile boolean closed;

    public ChatClientController(ClientIdentityStore identityStore,
            LocalTrustStore trustStore) {
        this(identityStore, trustStore,
                new ClientCryptoService(),
                SocketPacketChannel::connect);
    }

    ChatClientController(ClientIdentityStore identityStore,
            LocalTrustStore trustStore,
            ClientCryptoService cryptoService,
            ChannelConnector channelConnector) {
        this.identityStore = Objects.requireNonNull(
                identityStore, "identityStore cannot be null");
        this.trustStore = Objects.requireNonNull(
                trustStore, "trustStore cannot be null");
        this.cryptoService = Objects.requireNonNull(
                cryptoService, "cryptoService cannot be null");
        this.channelConnector = Objects.requireNonNull(
                channelConnector, "channelConnector cannot be null");
        this.backgroundExecutor = Executors.newFixedThreadPool(
                2, new ClientThreadFactory());
    }

    public void setListener(Listener listener) {
        this.listener = listener != null ? listener : new Listener() {
        };
    }

    public State getState() {
        return state;
    }

    public String getAuthenticatedUsername() {
        Identity current = identity;
        return current != null ? current.getUsername() : null;
    }

    public LocalTrustStore getTrustStore() {
        return trustStore;
    }

    /**
     * Tạo cặp khóa mới, lưu cục bộ rồi kết nối và xác thực.
     */
    public void register(String host, int port, String username,
            char[] passphrase) {
        char[] passphraseCopy;
        try {
            validateEndpoint(host, port);
            passphraseCopy = copyPassphrase(passphrase);
        } catch (IllegalArgumentException e) {
            dispatchError("INVALID_INPUT", e.getMessage());
            return;
        }
        if (!beginConnection()) {
            ClientCryptoEngine.wipeMemory(passphraseCopy);
            return;
        }

        backgroundExecutor.execute(() -> {
            try {
                Identity newIdentity = identityStore.create(
                        username, passphraseCopy);
                connectAndAuthenticate(host, port, newIdentity);
            } catch (IOException | GeneralSecurityException
                    | IllegalArgumentException e) {
                authenticationFailed("REGISTER_FAILED", e.getMessage());
            } finally {
                ClientCryptoEngine.wipeMemory(passphraseCopy);
            }
        });
    }

    /**
     * Mở cặp khóa đã lưu rồi kết nối và xác thực.
     */
    public void login(String host, int port, String username,
            char[] passphrase) {
        char[] passphraseCopy;
        try {
            validateEndpoint(host, port);
            passphraseCopy = copyPassphrase(passphrase);
        } catch (IllegalArgumentException e) {
            dispatchError("INVALID_INPUT", e.getMessage());
            return;
        }
        if (!beginConnection()) {
            ClientCryptoEngine.wipeMemory(passphraseCopy);
            return;
        }

        backgroundExecutor.execute(() -> {
            try {
                Identity loadedIdentity = identityStore.load(
                        username, passphraseCopy);
                connectAndAuthenticate(host, port, loadedIdentity);
            } catch (IOException | GeneralSecurityException
                    | IllegalArgumentException e) {
                authenticationFailed("LOGIN_FAILED", e.getMessage());
            } finally {
                ClientCryptoEngine.wipeMemory(passphraseCopy);
            }
        });
    }

    public void disconnect() {
        PacketChannel current;
        synchronized (lifecycleLock) {
            current = channel;
            channel = null;
            identity = null;
        }
        closeQuietly(current);
        if (!closed) {
            setState(State.DISCONNECTED);
        }
    }

    private boolean beginConnection() {
        synchronized (lifecycleLock) {
            if (closed) {
                dispatchError("CLIENT_CLOSED", "Client da dong");
                return false;
            }
            if (state != State.DISCONNECTED) {
                dispatchError("CONNECTION_BUSY",
                        "Client dang ket noi hoac da dang nhap");
                return false;
            }
            setState(State.CONNECTING);
            return true;
        }
    }

    private void connectAndAuthenticate(String host, int port,
            Identity candidateIdentity) {
        PacketChannel candidateChannel = null;
        try {
            validateEndpoint(host, port);
            candidateChannel = channelConnector.connect(
                    host.trim(), port, DEFAULT_CONNECT_TIMEOUT_MS);
            setState(State.AUTHENTICATING);

            Envelope challenge = candidateChannel.receive();
            if (challenge.getOp() != Opcode.AUTH_CHALLENGE) {
                throw new IOException(
                        "Server khong gui AUTH_CHALLENGE dau tien");
            }
            String nonceBase64 = challenge.getString("nonce");
            if (nonceBase64 == null || nonceBase64.isBlank()) {
                throw new IOException("AUTH_CHALLENGE thieu nonce");
            }

            byte[] nonce;
            try {
                nonce = Base64.getDecoder().decode(nonceBase64);
            } catch (IllegalArgumentException e) {
                throw new IOException("Nonce Base64 khong hop le", e);
            }

            byte[] signature = null;
            try {
                signature = ClientCryptoService.signChallenge(
                        nonce, candidateIdentity.getPrivateKey());
                Envelope auth = Envelope.create(
                        Opcode.AUTH, candidateIdentity.getUsername(), "server")
                        .put("sig", Base64.getEncoder()
                                .encodeToString(signature))
                        .put("pubkey",
                                candidateIdentity.getPublicKeyBase64());
                candidateChannel.send(auth);
            } finally {
                ClientCryptoEngine.wipeMemory(nonce);
                ClientCryptoEngine.wipeMemory(signature);
            }

            Envelope response = candidateChannel.receive();
            if (response.getOp() == Opcode.ERROR) {
                throw new AuthenticationException(
                        response.getString("code"),
                        response.getString("msg"));
            }
            if (response.getOp() != Opcode.AUTH_OK) {
                throw new IOException("Server khong gui AUTH_OK");
            }

            synchronized (lifecycleLock) {
                if (closed) {
                    throw new IOException("Client da dong");
                }
                channel = candidateChannel;
                identity = candidateIdentity;
            }
            setState(State.CONNECTED);
            dispatchUi(() -> listener.onAuthenticated(
                    candidateIdentity.getUsername()));
            receiveLoop(candidateChannel);
        } catch (AuthenticationException e) {
            closeQuietly(candidateChannel);
            authenticationFailed(e.code, e.getMessage());
        } catch (IOException | GeneralSecurityException
                | IllegalArgumentException e) {
            closeQuietly(candidateChannel);
            authenticationFailed("CONNECTION_FAILED", e.getMessage());
        }
    }

    private void receiveLoop(PacketChannel activeChannel) {
        try {
            while (!closed && activeChannel == channel
                    && activeChannel.isOpen()) {
                Envelope envelope = activeChannel.receive();
                handleEnvelope(envelope);
            }
        } catch (IOException e) {
            if (!closed && activeChannel == channel) {
                connectionLost(e.getMessage());
            }
        }
    }

    private void handleEnvelope(Envelope envelope) {
        if (envelope == null || envelope.getOp() == null) {
            dispatchError("INVALID_ENVELOPE", "Goi tin khong hop le");
            return;
        }

        if (envelope.getOp() == Opcode.ERROR) {
            dispatchError(envelope.getString("code"),
                    envelope.getString("msg"));
        }
    }

    private void connectionLost(String message) {
        synchronized (lifecycleLock) {
            closeQuietly(channel);
            channel = null;
        }
        setState(State.DISCONNECTED);
        dispatchUi(() -> listener.onConnectionLost(
                safeMessage(message, "Mat ket noi Server")));
    }

    private void authenticationFailed(String code, String message) {
        synchronized (lifecycleLock) {
            channel = null;
            identity = null;
        }
        if (!closed) {
            setState(State.DISCONNECTED);
        }
        dispatchError(code, safeMessage(message, "Xac thuc that bai"));
    }

    private void setState(State newState) {
        state = newState;
        dispatchUi(() -> listener.onStateChanged(newState));
    }

    private void dispatchError(String code, String message) {
        String safeCode = safeMessage(code, "UNKNOWN_ERROR");
        String safeText = safeMessage(message, "Loi khong xac dinh");
        dispatchUi(() -> listener.onError(safeCode, safeText));
    }

    private static void dispatchUi(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private static char[] copyPassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException(
                    "Passphrase khong duoc de trong");
        }
        return passphrase.clone();
    }

    private static void validateEndpoint(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host khong duoc de trong");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Port khong hop le");
        }
    }

    private static String safeMessage(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void closeQuietly(PacketChannel packetChannel) {
        if (packetChannel != null) {
            try {
                packetChannel.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            closeQuietly(channel);
            channel = null;
            identity = null;
        }
        state = State.CLOSED;
        backgroundExecutor.shutdownNow();
        dispatchUi(() -> listener.onStateChanged(State.CLOSED));
    }

    public enum State {
        DISCONNECTED,
        CONNECTING,
        AUTHENTICATING,
        CONNECTED,
        RECONNECTING,
        CLOSED
    }

    public interface Listener {
        default void onStateChanged(State state) {
        }

        default void onAuthenticated(String username) {
        }

        default void onConnectionLost(String message) {
        }

        default void onError(String code, String message) {
        }
    }

    @FunctionalInterface
    interface ChannelConnector {
        PacketChannel connect(String host, int port, int timeoutMs)
                throws IOException;
    }

    private static final class AuthenticationException extends Exception {
        private final String code;

        private AuthenticationException(String code, String message) {
            super(safeMessage(message, "Xac thuc that bai"));
            this.code = safeMessage(code, "AUTH_FAILED");
        }
    }

    private static final class ClientThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task,
                    "e2ee-client-worker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
