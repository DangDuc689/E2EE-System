package e2ee.client;

import e2ee.client.crypto.ClientCryptoEngine;
import e2ee.client.crypto.ClientCryptoService;
import e2ee.client.crypto.GroupCryptoService;
import e2ee.client.crypto.OfflineSyncService;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ điều phối kết nối, xác thực và xử lý gói tin của ứng dụng Swing Client.
 * Mọi I/O, đọc file khóa và crypto đều chạy ngoài Swing EDT.
 */
public final class ChatClientController implements AutoCloseable {
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    public static final long KEEP_ALIVE_INTERVAL_SECONDS = 30L;
    private static final int MAX_PENDING_MESSAGES_PER_PEER = 100;
    private static final long[] DEFAULT_RECONNECT_DELAYS_MS = {
        1_000L, 2_000L, 4_000L, 8_000L, 15_000L
    };

    private final Object lifecycleLock = new Object();
    private final ClientIdentityStore identityStore;
    private final LocalTrustStore trustStore;
    private final ClientCryptoService cryptoService;
    private final OfflineSyncService offlineSyncService;
    private final ChannelConnector channelConnector;
    private final ExecutorService backgroundExecutor;
    private final ScheduledExecutorService scheduler;
    private final long keepAliveIntervalSeconds;
    private final long[] reconnectDelaysMs;
    private final ConcurrentMap<String, PublicKey> peerPublicKeys =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> presenceByUsername =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ArrayList<Envelope>> pendingMessages =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ArrayList<Envelope>> pendingOfflineMessages =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> pendingChangedKeys =
            new ConcurrentHashMap<>();
    private final java.util.Set<String> blockedPeers =
            ConcurrentHashMap.newKeySet();

    private volatile Listener listener = new Listener() {
    };
    private volatile State state = State.DISCONNECTED;
    private volatile PacketChannel channel;
    private volatile PacketChannel connectingChannel;
    private volatile Identity identity;
    private volatile ScheduledFuture<?> keepAliveTask;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile String connectionHost;
    private volatile int connectionPort;
    private volatile int reconnectAttempt;
    private long connectionAttempt;
    private volatile boolean closed;

    public ChatClientController(ClientIdentityStore identityStore,
            LocalTrustStore trustStore) {
        this(identityStore, trustStore,
                new ClientCryptoService(),
                SocketPacketChannel::connect,
                KEEP_ALIVE_INTERVAL_SECONDS,
                DEFAULT_RECONNECT_DELAYS_MS);
    }

    ChatClientController(ClientIdentityStore identityStore,
            LocalTrustStore trustStore,
            ClientCryptoService cryptoService,
            ChannelConnector channelConnector) {
        this(identityStore, trustStore, cryptoService, channelConnector,
                KEEP_ALIVE_INTERVAL_SECONDS, DEFAULT_RECONNECT_DELAYS_MS);
    }

    ChatClientController(ClientIdentityStore identityStore,
            LocalTrustStore trustStore,
            ClientCryptoService cryptoService,
            ChannelConnector channelConnector,
            long keepAliveIntervalSeconds) {
        this(identityStore, trustStore, cryptoService, channelConnector,
                keepAliveIntervalSeconds, DEFAULT_RECONNECT_DELAYS_MS);
    }

    ChatClientController(ClientIdentityStore identityStore,
            LocalTrustStore trustStore,
            ClientCryptoService cryptoService,
            ChannelConnector channelConnector,
            long keepAliveIntervalSeconds,
            long[] reconnectDelaysMs) {
        this.identityStore = Objects.requireNonNull(
                identityStore, "identityStore cannot be null");
        this.trustStore = Objects.requireNonNull(
                trustStore, "trustStore cannot be null");
        this.cryptoService = Objects.requireNonNull(
                cryptoService, "cryptoService cannot be null");
        this.offlineSyncService = new OfflineSyncService(
                this.cryptoService, GroupCryptoService.getDefault());
        this.channelConnector = Objects.requireNonNull(
                channelConnector, "channelConnector cannot be null");
        if (keepAliveIntervalSeconds < 1) {
            throw new IllegalArgumentException(
                    "keepAliveIntervalSeconds phai lon hon 0");
        }
        this.keepAliveIntervalSeconds = keepAliveIntervalSeconds;
        if (reconnectDelaysMs == null || reconnectDelaysMs.length == 0) {
            throw new IllegalArgumentException(
                    "reconnectDelaysMs khong duoc rong");
        }
        this.reconnectDelaysMs = reconnectDelaysMs.clone();
        for (long delay : this.reconnectDelaysMs) {
            if (delay < 1) {
                throw new IllegalArgumentException(
                        "Reconnect delay phai lon hon 0");
            }
        }
        this.backgroundExecutor = Executors.newFixedThreadPool(
                2, new ClientThreadFactory());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                new ClientSchedulerThreadFactory());
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
     * Mã hóa và gửi một tin nhắn 1-1 trên luồng nền.
     */
    public void sendMessage(String recipientUsername, String content) {
        if (!isValidUsername(recipientUsername)) {
            dispatchError("INVALID_RECIPIENT",
                    "Username nguoi nhan khong hop le");
            return;
        }
        if (content == null || content.isBlank()) {
            dispatchError("EMPTY_MESSAGE", "Tin nhan khong duoc de trong");
            return;
        }
        if (blockedPeers.contains(recipientUsername)) {
            dispatchError("PEER_KEY_CHANGED",
                    "Khoa bao mat cua ban chat da thay doi");
            return;
        }

        Identity currentIdentity = identity;
        PacketChannel currentChannel = channel;
        if (state != State.CONNECTED || currentIdentity == null
                || currentChannel == null) {
            dispatchError("NOT_CONNECTED", "Client chua ket noi");
            return;
        }

        PublicKey recipientKey = findPeerKey(recipientUsername);
        if (recipientKey == null) {
            lookupPeer(recipientUsername);
            dispatchError("PEER_KEY_UNAVAILABLE",
                    "Chua co public key cua ban chat");
            return;
        }

        backgroundExecutor.execute(() -> {
            try {
                Envelope encrypted = cryptoService.createEncrypted1to1Message(
                        currentIdentity.getUsername(), recipientUsername,
                        content, recipientKey,
                        currentIdentity.getPrivateKey());
                if (currentChannel != channel || !currentChannel.isOpen()) {
                    dispatchError("NOT_CONNECTED", "Client da mat ket noi");
                    return;
                }
                currentChannel.send(encrypted);
                dispatchUi(() -> listener.onMessageSent(
                        recipientUsername, content, encrypted.getId(),
                        encrypted.getTs()));
            } catch (GeneralSecurityException e) {
                dispatchError("ENCRYPT_FAILED", e.getMessage());
            } catch (IOException e) {
                if (currentChannel == channel) {
                    connectionLost(currentChannel, e.getMessage());
                }
            }
        });
    }

    /**
     * Tính fingerprint trên luồng nền để mở hộp thoại đối soát.
     */
    public void requestFingerprintDetails(String username) {
        if (!isValidUsername(username)) {
            dispatchError("INVALID_USERNAME", "Username ban chat khong hop le");
            return;
        }
        Identity currentIdentity = identity;
        if (currentIdentity == null) {
            dispatchError("NOT_CONNECTED", "Client chua dang nhap");
            return;
        }

        backgroundExecutor.execute(() -> {
            try {
                String peerKeyBase64 = pendingChangedKeys.get(username);
                boolean keyChanged = peerKeyBase64 != null;
                if (peerKeyBase64 == null) {
                    peerKeyBase64 = trustStore.getPublicKeyBase64(username);
                }
                if (peerKeyBase64 == null) {
                    dispatchError("PEER_KEY_UNAVAILABLE",
                            "Chua co public key cua ban chat");
                    return;
                }

                String myFingerprint = ClientCryptoService
                        .calculateFingerprint(currentIdentity.getPublicKey());
                String peerFingerprint = ClientCryptoService
                        .calculateFingerprint(decodePublicKey(peerKeyBase64));
                dispatchUi(() -> listener.onFingerprintDetails(
                        username, myFingerprint, peerFingerprint,
                        keyChanged));
            } catch (GeneralSecurityException e) {
                dispatchError("FINGERPRINT_FAILED", e.getMessage());
            }
        });
    }

    /**
     * Chấp nhận khóa đang cảnh báo sau khi người dùng đối soát qua kênh ngoài.
     */
    public void acceptPeerKey(String username, String expectedFingerprint) {
        if (!isValidUsername(username)) {
            dispatchError("INVALID_USERNAME", "Username ban chat khong hop le");
            return;
        }
        if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
            dispatchError("INVALID_FINGERPRINT",
                    "Fingerprint doi soat khong hop le");
            return;
        }
        backgroundExecutor.execute(() -> {
            try {
                String changedKey = pendingChangedKeys.get(username);
                String candidateKey = changedKey != null
                        ? changedKey
                        : trustStore.getPublicKeyBase64(username);
                if (candidateKey == null) {
                    dispatchError("PEER_KEY_UNAVAILABLE",
                            "Chua co public key cua ban chat");
                    return;
                }
                String currentFingerprint = ClientCryptoService
                        .calculateFingerprint(decodePublicKey(candidateKey));
                if (!currentFingerprint.equals(expectedFingerprint)) {
                    dispatchError("TRUST_CANDIDATE_CHANGED",
                            "Khoa da thay doi trong luc doi soat; hay kiem tra lai");
                    return;
                }
                if (changedKey != null) {
                    trustStore.acceptNewKey(username, changedKey);
                }
                trustStore.markVerified(username);
                String trustedKey = trustStore.getPublicKeyBase64(username);
                peerPublicKeys.put(username, decodePublicKey(trustedKey));
                pendingChangedKeys.remove(username);
                blockedPeers.remove(username);
                String fingerprint = trustStore.getFingerprint(username);
                dispatchUi(() -> listener.onPeerTrustRestored(
                        username, fingerprint));
            } catch (IOException | GeneralSecurityException
                    | IllegalArgumentException e) {
                dispatchError("TRUST_UPDATE_FAILED", e.getMessage());
            }
        });
    }

    public boolean isPeerBlocked(String username) {
        return blockedPeers.contains(username);
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
        long attempt = beginConnection();
        if (attempt < 0) {
            ClientCryptoEngine.wipeMemory(passphraseCopy);
            return;
        }

        backgroundExecutor.execute(() -> {
            try {
                Identity newIdentity = identityStore.create(
                        username, passphraseCopy);
                connectAndAuthenticate(host, port, newIdentity, false,
                        attempt);
            } catch (IOException | GeneralSecurityException
                    | IllegalArgumentException e) {
                authenticationFailed("REGISTER_FAILED", e.getMessage(),
                        attempt);
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
        long attempt = beginConnection();
        if (attempt < 0) {
            ClientCryptoEngine.wipeMemory(passphraseCopy);
            return;
        }

        backgroundExecutor.execute(() -> {
            try {
                Identity loadedIdentity = identityStore.load(
                        username, passphraseCopy);
                connectAndAuthenticate(host, port, loadedIdentity, false,
                        attempt);
            } catch (IOException | GeneralSecurityException
                    | IllegalArgumentException e) {
                authenticationFailed("LOGIN_FAILED", e.getMessage(),
                        attempt);
            } finally {
                ClientCryptoEngine.wipeMemory(passphraseCopy);
            }
        });
    }

    public void disconnect() {
        PacketChannel current;
        PacketChannel connecting;
        synchronized (lifecycleLock) {
            connectionAttempt++;
            current = channel;
            connecting = connectingChannel;
            channel = null;
            connectingChannel = null;
            identity = null;
            connectionHost = null;
            connectionPort = 0;
            reconnectAttempt = 0;
            peerPublicKeys.clear();
            presenceByUsername.clear();
            pendingMessages.clear();
            pendingOfflineMessages.clear();
            pendingChangedKeys.clear();
            blockedPeers.clear();
        }
        stopKeepAlive();
        stopReconnect();
        closeQuietly(current);
        if (connecting != current) {
            closeQuietly(connecting);
        }
        if (!closed) {
            setState(State.DISCONNECTED);
        }
    }

    private long beginConnection() {
        synchronized (lifecycleLock) {
            if (closed) {
                dispatchError("CLIENT_CLOSED", "Client da dong");
                return -1L;
            }
            if (state != State.DISCONNECTED) {
                dispatchError("CONNECTION_BUSY",
                        "Client dang ket noi hoac da dang nhap");
                return -1L;
            }
            connectionAttempt++;
            setState(State.CONNECTING);
            return connectionAttempt;
        }
    }

    private void connectAndAuthenticate(String host, int port,
            Identity candidateIdentity, boolean reconnecting, long attempt) {
        PacketChannel candidateChannel = null;
        try {
            validateEndpoint(host, port);
            candidateChannel = channelConnector.connect(
                    host.trim(), port, DEFAULT_CONNECT_TIMEOUT_MS);
            synchronized (lifecycleLock) {
                if (closed || attempt != connectionAttempt) {
                    throw new ConnectionCancelledException();
                }
                connectingChannel = candidateChannel;
                if (!reconnecting) {
                    setState(State.AUTHENTICATING);
                }
            }

            Envelope challenge = candidateChannel.receive();
            if (challenge.getOp() != Opcode.AUTH_CHALLENGE
                    || !"server".equals(challenge.getFrom())
                    || challenge.getTo() != null) {
                throw new IOException(
                        "AUTH_CHALLENGE co header khong hop le");
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

            if (nonce.length != 32) {
                ClientCryptoEngine.wipeMemory(nonce);
                throw new IOException("AUTH_CHALLENGE nonce phai dung 32 byte");
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
            if (!"server".equals(response.getFrom())
                    || !candidateIdentity.getUsername().equals(
                            response.getTo())) {
                throw new IOException("Phan hoi AUTH co header khong hop le");
            }
            if (response.getOp() == Opcode.ERROR) {
                if (response.getString("code") == null
                        || response.getString("code").isBlank()
                        || response.getString("msg") == null
                        || response.getString("msg").isBlank()) {
                    throw new IOException("ERROR xac thuc khong hop le");
                }
                throw new AuthenticationException(
                        response.getString("code"),
                        response.getString("msg"));
            }
            if (response.getOp() != Opcode.AUTH_OK) {
                throw new IOException("Server khong gui AUTH_OK");
            }

            synchronized (lifecycleLock) {
                if (closed || attempt != connectionAttempt
                        || connectingChannel != candidateChannel) {
                    throw new ConnectionCancelledException();
                }
                connectingChannel = null;
                channel = candidateChannel;
                identity = candidateIdentity;
                connectionHost = host.trim();
                connectionPort = port;
                reconnectAttempt = 0;
            }
            stopReconnect();
            setState(State.CONNECTED);
            startKeepAlive(candidateChannel, candidateIdentity);
            if (reconnecting) {
                dispatchUi(() -> listener.onReconnected(
                        candidateIdentity.getUsername()));
            } else {
                dispatchUi(() -> listener.onAuthenticated(
                        candidateIdentity.getUsername()));
            }
            receiveLoop(candidateChannel);
        } catch (ConnectionCancelledException e) {
            closeQuietly(candidateChannel);
        } catch (AuthenticationException e) {
            clearConnectingChannel(candidateChannel);
            closeQuietly(candidateChannel);
            authenticationFailed(e.code, e.getMessage(), attempt);
        } catch (IOException | GeneralSecurityException
                | IllegalArgumentException e) {
            clearConnectingChannel(candidateChannel);
            closeQuietly(candidateChannel);
            if (!isCurrentAttempt(attempt)) {
                return;
            }
            if (reconnecting) {
                scheduleReconnect(e.getMessage());
            } else {
                authenticationFailed("CONNECTION_FAILED", e.getMessage(),
                        attempt);
            }
        }
    }

    private void clearConnectingChannel(PacketChannel candidateChannel) {
        synchronized (lifecycleLock) {
            if (connectingChannel == candidateChannel) {
                connectingChannel = null;
            }
        }
    }

    private boolean isCurrentAttempt(long attempt) {
        synchronized (lifecycleLock) {
            return !closed && attempt == connectionAttempt;
        }
    }

    private void receiveLoop(PacketChannel activeChannel) {
        try {
            while (!closed && activeChannel == channel
                    && activeChannel.isOpen()) {
                Envelope envelope = activeChannel.receive();
                if (closed || activeChannel != channel) {
                    break;
                }
                handleEnvelope(envelope);
            }
        } catch (IOException e) {
            if (!closed && activeChannel == channel) {
                connectionLost(activeChannel, e.getMessage());
            }
        }
    }

    private void handleEnvelope(Envelope envelope) {
        if (envelope == null || envelope.getOp() == null) {
            dispatchError("INVALID_ENVELOPE", "Goi tin khong hop le");
            return;
        }

        Identity currentIdentity = identity;
        if (currentIdentity == null
                || !currentIdentity.getUsername().equals(envelope.getTo())) {
            dispatchError("INVALID_ENVELOPE",
                    "Goi tin khong gui dung nguoi nhan");
            return;
        }

        switch (envelope.getOp()) {
            case ERROR -> handleError(envelope);
            case KEY_LOOKUP_RESULT -> handleKeyLookupResult(envelope);
            case PRESENCE -> handlePresence(envelope);
            case CHAT_1TO1 -> handleIncomingChat(envelope);
            case OFFLINE_SYNC -> handleOfflineSync(envelope);
            case KEY_CHANGED -> handleKeyChanged(envelope);
            case PONG -> {
                if (!"server".equals(envelope.getFrom())) {
                    dispatchError("INVALID_PONG", "PONG khong hop le");
                }
            }
            default -> {
                // Các opcode chat, keep-alive và offline được xử lý ở lát cắt sau.
            }
        }
    }

    private void handleError(Envelope envelope) {
        String code = envelope.getString("code");
        String message = envelope.getString("msg");
        if (!"server".equals(envelope.getFrom())
                || code == null || code.isBlank()
                || message == null || message.isBlank()) {
            dispatchError("INVALID_ERROR", "ERROR khong hop le");
            return;
        }
        dispatchError(code, message);
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
                blockChangedPeerKey(username, publicKeyBase64);
                return;
            }

            PublicKey publicKey = decodePublicKey(publicKeyBase64);
            peerPublicKeys.put(username, publicKey);
            String fingerprint = trustStore.getFingerprint(username);
            boolean verified = trustStore.isVerified(username);
            dispatchUi(() -> listener.onPeerKeyAvailable(
                    username, fingerprint, verified));
            processPendingMessages(username, publicKey);
            processPendingOfflineMessages(username, publicKey);
        } catch (IOException | GeneralSecurityException
                | IllegalArgumentException e) {
            dispatchError("INVALID_PUBLIC_KEY", e.getMessage());
        }
    }

    private void handleKeyChanged(Envelope envelope) {
        String username = envelope.getFrom();
        String newPublicKey = envelope.getString("new_pubkey");
        if (!isValidUsername(username)
                || newPublicKey == null || newPublicKey.isBlank()) {
            dispatchError("INVALID_KEY_CHANGED", "KEY_CHANGED khong hop le");
            return;
        }

        try {
            boolean trusted = trustStore.checkPeerKey(username, newPublicKey);
            if (!trusted) {
                blockChangedPeerKey(username, newPublicKey);
                return;
            }
            PublicKey publicKey = decodePublicKey(newPublicKey);
            peerPublicKeys.put(username, publicKey);
            String fingerprint = trustStore.getFingerprint(username);
            dispatchUi(() -> listener.onPeerKeyAvailable(
                    username, fingerprint,
                    trustStore.isVerified(username)));
        } catch (IOException | GeneralSecurityException
                | IllegalArgumentException e) {
            dispatchError("INVALID_PUBLIC_KEY", e.getMessage());
        }
    }

    private void blockChangedPeerKey(String username,
            String newPublicKeyBase64) {
        try {
            // Giải mã trước để không lưu ứng viên Base64/RSA giả mạo.
            decodePublicKey(newPublicKeyBase64);
            pendingChangedKeys.put(username, newPublicKeyBase64);
            blockedPeers.add(username);
            peerPublicKeys.remove(username);
            pendingMessages.remove(username);
            pendingOfflineMessages.remove(username);
            dispatchUi(() -> listener.onPeerKeyMismatch(
                    username, newPublicKeyBase64));
        } catch (GeneralSecurityException e) {
            dispatchError("INVALID_PUBLIC_KEY", e.getMessage());
        }
    }

    private void handleIncomingChat(Envelope envelope) {
        Identity currentIdentity = identity;
        String senderUsername = envelope.getFrom();
        if (currentIdentity == null
                || !isValidUsername(senderUsername)
                || !currentIdentity.getUsername().equals(envelope.getTo())) {
            dispatchError("INVALID_CHAT", "CHAT_1TO1 khong hop le");
            return;
        }
        if (blockedPeers.contains(senderUsername)) {
            dispatchUi(() -> listener.onSecurityWarning(senderUsername,
                    "Da chan tin nhan vi public key dang bi thay doi"));
            return;
        }

        PublicKey senderKey = findPeerKey(senderUsername);
        if (senderKey == null) {
            ArrayList<Envelope> queue = pendingMessages.computeIfAbsent(
                    senderUsername, ignored -> new ArrayList<>());
            synchronized (queue) {
                if (queue.size() >= MAX_PENDING_MESSAGES_PER_PEER) {
                    dispatchError("PENDING_MESSAGE_LIMIT",
                            "Qua nhieu tin dang cho public key");
                    return;
                }
                queue.add(envelope);
            }
            lookupPeer(senderUsername);
            return;
        }

        decryptIncomingMessage(envelope, senderKey, currentIdentity);
    }

    private void processPendingMessages(String username, PublicKey publicKey) {
        ArrayList<Envelope> queue = pendingMessages.remove(username);
        Identity currentIdentity = identity;
        if (queue == null || currentIdentity == null) {
            return;
        }

        ArrayList<Envelope> messages;
        synchronized (queue) {
            messages = new ArrayList<>(queue);
            queue.clear();
        }
        for (Envelope pending : messages) {
            decryptIncomingMessage(pending, publicKey, currentIdentity);
        }
    }

    private void decryptIncomingMessage(Envelope envelope,
            PublicKey senderKey, Identity currentIdentity) {
        backgroundExecutor.execute(() -> {
            try {
                String content = cryptoService.verifyAndDecrypt1to1Message(
                        envelope, senderKey,
                        currentIdentity.getPrivateKey(), false);
                dispatchUi(() -> listener.onMessageReceived(
                        envelope.getFrom(), content,
                        envelope.getId(), envelope.getTs()));
            } catch (GeneralSecurityException
                    | e2ee.common.json.JsonParseException
                    | SecurityException e) {
                dispatchUi(() -> listener.onSecurityWarning(
                        envelope.getFrom(), safeMessage(e.getMessage(),
                                "Tin nhan khong an toan")));
            }
        });
    }

    private void handleOfflineSync(Envelope syncEnvelope) {
        Identity currentIdentity = identity;
        if (currentIdentity == null
                || !"server".equals(syncEnvelope.getFrom())
                || !currentIdentity.getUsername().equals(syncEnvelope.getTo())) {
            dispatchError("INVALID_OFFLINE_SYNC",
                    "OFFLINE_SYNC khong hop le");
            return;
        }

        Envelope original;
        try {
            original = OfflineSyncService.extractOriginalEnvelope(syncEnvelope);
        } catch (e2ee.common.json.JsonParseException
                | IllegalArgumentException e) {
            dispatchError("INVALID_OFFLINE_SYNC", e.getMessage());
            return;
        }

        String senderUsername = original.getFrom();
        if (original.getOp() != Opcode.CHAT_1TO1
                || !isValidUsername(senderUsername)
                || !currentIdentity.getUsername().equals(original.getTo())) {
            dispatchError("INVALID_OFFLINE_SYNC",
                    "Tin nhan goc trong OFFLINE_SYNC khong hop le");
            return;
        }
        if (cryptoService.isMessageSeen(original.getId())) {
            sendAsync(OfflineSyncService.createAckEnvelope(
                    currentIdentity.getUsername(), original.getId()));
            return;
        }
        if (blockedPeers.contains(senderUsername)) {
            dispatchUi(() -> listener.onSecurityWarning(senderUsername,
                    "Da chan tin offline vi public key dang bi thay doi"));
            return;
        }

        PublicKey senderKey = findPeerKey(senderUsername);
        if (senderKey == null) {
            ArrayList<Envelope> queue = pendingOfflineMessages.computeIfAbsent(
                    senderUsername, ignored -> new ArrayList<>());
            synchronized (queue) {
                if (queue.size() >= MAX_PENDING_MESSAGES_PER_PEER) {
                    dispatchError("PENDING_MESSAGE_LIMIT",
                            "Qua nhieu tin offline dang cho public key");
                    return;
                }
                queue.add(syncEnvelope);
            }
            lookupPeer(senderUsername);
            return;
        }

        decryptOfflineMessage(syncEnvelope, senderKey, currentIdentity,
                senderUsername);
    }

    private void processPendingOfflineMessages(String username,
            PublicKey publicKey) {
        ArrayList<Envelope> queue = pendingOfflineMessages.remove(username);
        Identity currentIdentity = identity;
        if (queue == null || currentIdentity == null) {
            return;
        }

        ArrayList<Envelope> messages;
        synchronized (queue) {
            messages = new ArrayList<>(queue);
            queue.clear();
        }
        for (Envelope pending : messages) {
            decryptOfflineMessage(pending, publicKey, currentIdentity,
                    username);
        }
    }

    private void decryptOfflineMessage(Envelope syncEnvelope,
            PublicKey senderKey, Identity currentIdentity,
            String senderUsername) {
        backgroundExecutor.execute(() -> {
            try {
                OfflineSyncService.SyncResult result =
                        offlineSyncService.process1to1Message(
                                syncEnvelope,
                                currentIdentity.getUsername(), senderKey,
                                currentIdentity.getPrivateKey());
                Envelope original = result.getOriginalEnvelope();
                dispatchUi(() -> {
                    if (closed) {
                        return;
                    }
                    listener.onMessageReceived(
                            original.getFrom(), result.getDecryptedContent(),
                            original.getId(), original.getTs());
                    if (currentIdentity == identity
                            && state == State.CONNECTED) {
                        sendAsync(result.getAckEnvelope());
                    }
                });
            } catch (GeneralSecurityException
                    | e2ee.common.json.JsonParseException
                    | SecurityException | IllegalArgumentException e) {
                dispatchUi(() -> listener.onSecurityWarning(
                        senderUsername, safeMessage(e.getMessage(),
                                "Tin nhan offline khong an toan")));
            }
        });
    }

    private PublicKey findPeerKey(String username) {
        PublicKey cached = peerPublicKeys.get(username);
        if (cached != null) {
            return cached;
        }

        String storedKey = trustStore.getPublicKeyBase64(username);
        if (storedKey == null) {
            return null;
        }
        try {
            PublicKey decoded = decodePublicKey(storedKey);
            PublicKey existing = peerPublicKeys.putIfAbsent(
                    username, decoded);
            return existing != null ? existing : decoded;
        } catch (GeneralSecurityException e) {
            dispatchError("INVALID_PUBLIC_KEY", e.getMessage());
            return null;
        }
    }

    private void handlePresence(Envelope envelope) {
        Object onlineValue = envelope.get("online");
        String username = envelope.getString("user");
        if (!"server".equals(envelope.getFrom())
                || !isValidUsername(username)
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
                    connectionLost(current, e.getMessage());
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

    private void startKeepAlive(PacketChannel activeChannel,
            Identity activeIdentity) {
        stopKeepAlive();
        keepAliveTask = scheduler.scheduleAtFixedRate(() -> {
            if (closed || state != State.CONNECTED
                    || channel != activeChannel
                    || !activeChannel.isOpen()) {
                return;
            }
            Envelope ping = Envelope.create(
                    Opcode.PING, activeIdentity.getUsername(), "server");
            try {
                activeChannel.send(ping);
            } catch (IOException e) {
                if (channel == activeChannel && !closed) {
                    connectionLost(activeChannel, e.getMessage());
                }
            }
        }, keepAliveIntervalSeconds, keepAliveIntervalSeconds,
                TimeUnit.SECONDS);
    }

    private void stopKeepAlive() {
        ScheduledFuture<?> currentTask = keepAliveTask;
        keepAliveTask = null;
        if (currentTask != null) {
            currentTask.cancel(false);
        }
    }

    private void connectionLost(PacketChannel failedChannel, String message) {
        stopKeepAlive();
        synchronized (lifecycleLock) {
            if (closed || channel != failedChannel) {
                return;
            }
            closeQuietly(failedChannel);
            channel = null;
        }
        markAllPeersOffline();
        setState(State.RECONNECTING);
        dispatchUi(() -> listener.onConnectionLost(
                safeMessage(message, "Mat ket noi Server")));
        scheduleReconnect(message);
    }

    private void scheduleReconnect(String failureMessage) {
        Identity reconnectIdentity;
        String host;
        int port;
        int attempt;
        long delayMs;
        synchronized (lifecycleLock) {
            if (closed || identity == null || connectionHost == null) {
                return;
            }
            reconnectIdentity = identity;
            host = connectionHost;
            port = connectionPort;
            attempt = reconnectAttempt + 1;
            int delayIndex = Math.min(reconnectAttempt,
                    reconnectDelaysMs.length - 1);
            delayMs = reconnectDelaysMs[delayIndex];
            reconnectAttempt++;
        }

        stopReconnect();
        setState(State.RECONNECTING);
        dispatchUi(() -> listener.onReconnectScheduled(
                attempt, delayMs,
                safeMessage(failureMessage, "Mat ket noi Server")));
        reconnectTask = scheduler.schedule(() -> {
            long connectionToken;
            synchronized (lifecycleLock) {
                if (closed || state != State.RECONNECTING) {
                    return;
                }
                connectionAttempt++;
                connectionToken = connectionAttempt;
            }
            backgroundExecutor.execute(() -> connectAndAuthenticate(
                    host, port, reconnectIdentity, true, connectionToken));
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void stopReconnect() {
        ScheduledFuture<?> currentTask = reconnectTask;
        reconnectTask = null;
        if (currentTask != null) {
            currentTask.cancel(false);
        }
    }

    private void markAllPeersOffline() {
        for (String username : presenceByUsername.keySet()) {
            presenceByUsername.put(username, false);
            dispatchUi(() -> listener.onPresenceChanged(username, false));
        }
    }

    private void authenticationFailed(String code, String message,
            long attempt) {
        stopKeepAlive();
        synchronized (lifecycleLock) {
            if (closed || attempt != connectionAttempt) {
                return;
            }
            connectingChannel = null;
            channel = null;
            identity = null;
            connectionHost = null;
            connectionPort = 0;
            reconnectAttempt = 0;
        }
        stopReconnect();
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
            connectionAttempt++;
            closeQuietly(connectingChannel);
            connectingChannel = null;
            closeQuietly(channel);
            channel = null;
            identity = null;
            peerPublicKeys.clear();
            presenceByUsername.clear();
            pendingMessages.clear();
            pendingOfflineMessages.clear();
            pendingChangedKeys.clear();
            blockedPeers.clear();
        }
        stopKeepAlive();
        stopReconnect();
        state = State.CLOSED;
        backgroundExecutor.shutdownNow();
        scheduler.shutdownNow();
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

        default void onReconnectScheduled(int attempt, long delayMs,
                String message) {
        }

        default void onReconnected(String username) {
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

        default void onMessageSent(String recipientUsername, String content,
                String messageId, long timestamp) {
        }

        default void onMessageReceived(String senderUsername, String content,
                String messageId, long timestamp) {
        }

        default void onSecurityWarning(String username, String message) {
        }

        default void onFingerprintDetails(String username,
                String myFingerprint, String peerFingerprint,
                boolean keyChanged) {
        }

        default void onPeerTrustRestored(String username,
                String fingerprint) {
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

    private static final class ConnectionCancelledException
            extends IOException {
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

    private static final class ClientSchedulerThreadFactory
            implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "e2ee-client-scheduler");
            thread.setDaemon(true);
            return thread;
        }
    }
}
