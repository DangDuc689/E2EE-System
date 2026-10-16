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
import java.security.PublicKey;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private final ConcurrentMap<String, PublicKey> peerPublicKeys =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> presenceByUsername =
            new ConcurrentHashMap<>();

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

    public Map<String, Boolean> getPresenceSnapshot() {
        return Collections.unmodifiableMap(
                new HashMap<>(presenceByUsername));
    }

    public boolean isPeerOnline(String username) {
        return Boolean.TRUE.equals(presenceByUsername.get(username));
    }

    /**
     * Yêu cầu Server trả public key của một người dùng.
     */
    public void lookupPeer(String username) {
        if (!isValidUsername(username)) {
            dispatchError("INVALID_USERNAME", "Username ban chat khong hop le");
            return;
        }

        Identity currentIdentity = identity;
        if (state != State.CONNECTED || currentIdentity == null) {
            dispatchError("NOT_CONNECTED", "Client chua ket noi");
            return;
        }

        Envelope lookup = Envelope.create(
                Opcode.KEY_LOOKUP, currentIdentity.getUsername(), username);
        sendAsync(lookup);
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
            peerPublicKeys.clear();
            presenceByUsername.clear();
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

        switch (envelope.getOp()) {
            case ERROR -> dispatchError(envelope.getString("code"),
                    envelope.getString("msg"));
            case KEY_LOOKUP_RESULT -> handleKeyLookupResult(envelope);
            case PRESENCE -> handlePresence(envelope);
            default -> {
                // Các opcode chat, keep-alive và offline được xử lý ở lát cắt sau.
            }
        }
    }

    private void handleKeyLookupResult(Envelope envelope) {
        String username = envelope.getFrom();
        String publicKeyBase64 = envelope.getString("pubkey");
        if (!isValidUsername(username)
                || publicKeyBase64 == null
                || publicKeyBase64.isBlank()) {
            dispatchError("INVALID_KEY_RESULT",
                    "KEY_LOOKUP_RESULT khong hop le");
            return;
        }

        try {
            boolean trusted = trustStore.checkPeerKey(
                    username, publicKeyBase64);
            if (!trusted) {
                dispatchUi(() -> listener.onPeerKeyMismatch(
                        username, publicKeyBase64));
                return;
            }

            PublicKey publicKey = decodePublicKey(publicKeyBase64);
            peerPublicKeys.put(username, publicKey);
            String fingerprint = trustStore.getFingerprint(username);
            boolean verified = trustStore.isVerified(username);
            dispatchUi(() -> listener.onPeerKeyAvailable(
                    username, fingerprint, verified));
        } catch (IOException | GeneralSecurityException
                | IllegalArgumentException e) {
            dispatchError("INVALID_PUBLIC_KEY", e.getMessage());
        }
    }

    private void handlePresence(Envelope envelope) {
        Object onlineValue = envelope.get("online");
        String username = envelope.getString("user");
        if (!isValidUsername(username)
                || !(onlineValue instanceof Boolean online)) {
            dispatchError("INVALID_PRESENCE", "PRESENCE khong hop le");
            return;
        }

        presenceByUsername.put(username, online);
        dispatchUi(() -> listener.onPresenceChanged(username, online));
    }

    private void sendAsync(Envelope envelope) {
        backgroundExecutor.execute(() -> {
            PacketChannel current = channel;
            if (closed || state != State.CONNECTED
                    || current == null || !current.isOpen()) {
                dispatchError("NOT_CONNECTED", "Client chua ket noi");
                return;
            }
            try {
                current.send(envelope);
            } catch (IOException e) {
                if (current == channel) {
                    connectionLost(e.getMessage());
                }
            }
        });
    }

    private static PublicKey decodePublicKey(String publicKeyBase64)
            throws GeneralSecurityException {
        try {
            return ClientCryptoEngine.decodeRsaPublicKey(
                    Base64.getDecoder().decode(publicKeyBase64));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException(
                    "Public key Base64 khong hop le", e);
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

    private static boolean isValidUsername(String username) {
        return username != null
                && !"server".equals(username)
                && username.matches("^[a-z0-9_]{3,20}$");
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
            peerPublicKeys.clear();
            presenceByUsername.clear();
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

        default void onPeerKeyAvailable(String username,
                String fingerprint, boolean verified) {
        }

        default void onPeerKeyMismatch(String username,
                String newPublicKeyBase64) {
        }

        default void onPresenceChanged(String username, boolean online) {
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
