package e2ee.server;

import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.json.MiniJson;
import e2ee.common.net.SocketPacketChannel;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

final class ClientHandler implements Runnable {
    private static final int CLIENT_TIMEOUT_MS = 90_000;
    private static final int AUTH_NONCE_BYTES = 32;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9_]{3,20}$");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Socket clientSocket;
    private final Runnable onClose;
    private final ConcurrentMap<String, String> publicKeyRegistry;
    private final ConcurrentMap<String, ClientHandler> onlineUsers;
    private final PresenceDispatcher presenceDispatcher;
    private final MessageRouter messageRouter;
    private final Runnable afterOwnershipCheck;
    private final Runnable beforeRequestDispatch;
    private final Runnable beforePresenceSnapshot;
    private final Runnable beforePresenceWrite;
    private final Runnable beforeKeyChangedBroadcast;
    private final Object dispatchLock = new Object();
    private final Object keyChangedSendLock = new Object();
    private boolean dispatchAllowed = true;
    private int activeDispatches;
    private byte[] challengeNonce;
    private boolean authenticated;
    private String authenticatedUsername;
    private volatile SocketPacketChannel presenceChannel;
    private final long connectedAt = System.currentTimeMillis();
    private volatile ServerEventListener eventListener;

    public long getConnectedAt() {
        return connectedAt;
    }

    public void setEventListener(ServerEventListener listener) {
        this.eventListener = listener;
    }

    private void notifyEvent(String level, String event, String username, String details) {
        ServerEventListener listener = this.eventListener;
        if (listener != null) {
            listener.onEvent(new ServerLogEntry(System.currentTimeMillis(), level, event, username, details));
        }
    }

    ClientHandler(Socket clientSocket) {
        this(clientSocket, () -> {
        }, new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), null, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        });
    }

    ClientHandler(Socket clientSocket, Runnable onClose) {
        this(clientSocket, onClose, new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), null,
                () -> {
                }, () -> {
                }, () -> {
                }, () -> {
                });
    }

    ClientHandler(Socket clientSocket, Runnable onClose,
            ConcurrentMap<String, String> publicKeyRegistry,
            ConcurrentMap<String, ClientHandler> onlineUsers) {
        this(clientSocket, onClose, publicKeyRegistry, onlineUsers,
                new PresenceDispatcher(onlineUsers), () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        });
    }

    ClientHandler(Socket clientSocket, Runnable onClose,
            ConcurrentMap<String, String> publicKeyRegistry,
            ConcurrentMap<String, ClientHandler> onlineUsers,
            PresenceDispatcher presenceDispatcher,
            Runnable afterOwnershipCheck,
            Runnable beforeRequestDispatch,
            Runnable beforePresenceSnapshot,
            Runnable beforePresenceWrite) {
        this(clientSocket, onClose, publicKeyRegistry, onlineUsers,
                presenceDispatcher, afterOwnershipCheck, beforeRequestDispatch,
                beforePresenceSnapshot, beforePresenceWrite, () -> {
                });
    }

    ClientHandler(Socket clientSocket, Runnable onClose,
            ConcurrentMap<String, String> publicKeyRegistry,
            ConcurrentMap<String, ClientHandler> onlineUsers,
            PresenceDispatcher presenceDispatcher,
            Runnable afterOwnershipCheck,
            Runnable beforeRequestDispatch,
            Runnable beforePresenceSnapshot,
            Runnable beforePresenceWrite,
            Runnable beforeKeyChangedBroadcast) {
        this(clientSocket, onClose, publicKeyRegistry, onlineUsers,
                presenceDispatcher, afterOwnershipCheck, beforeRequestDispatch,
                beforePresenceSnapshot, beforePresenceWrite,
                beforeKeyChangedBroadcast,
                new Default1to1Router(onlineUsers, publicKeyRegistry));
    }

    ClientHandler(Socket clientSocket, Runnable onClose,
            ConcurrentMap<String, String> publicKeyRegistry,
            ConcurrentMap<String, ClientHandler> onlineUsers,
            PresenceDispatcher presenceDispatcher,
            MessageRouter messageRouter,
            Runnable afterOwnershipCheck,
            Runnable beforeRequestDispatch,
            Runnable beforePresenceSnapshot,
            Runnable beforePresenceWrite) {
        this(clientSocket, onClose, publicKeyRegistry, onlineUsers,
                presenceDispatcher, afterOwnershipCheck, beforeRequestDispatch,
                beforePresenceSnapshot, beforePresenceWrite, () -> {
                }, messageRouter);
    }

    private ClientHandler(Socket clientSocket, Runnable onClose,
            ConcurrentMap<String, String> publicKeyRegistry,
            ConcurrentMap<String, ClientHandler> onlineUsers,
            PresenceDispatcher presenceDispatcher,
            Runnable afterOwnershipCheck,
            Runnable beforeRequestDispatch,
            Runnable beforePresenceSnapshot,
            Runnable beforePresenceWrite,
            Runnable beforeKeyChangedBroadcast,
            MessageRouter messageRouter) {
        this.clientSocket = clientSocket;
        this.onClose = onClose;
        this.publicKeyRegistry = publicKeyRegistry;
        this.onlineUsers = onlineUsers;
        this.presenceDispatcher = presenceDispatcher == null
                ? new PresenceDispatcher(onlineUsers) : presenceDispatcher;
        this.messageRouter = messageRouter == null
                ? new Default1to1Router(onlineUsers, publicKeyRegistry)
                : messageRouter;
        this.afterOwnershipCheck = afterOwnershipCheck;
        this.beforeRequestDispatch = beforeRequestDispatch;
        this.beforePresenceSnapshot = beforePresenceSnapshot;
        this.beforePresenceWrite = beforePresenceWrite;
        this.beforeKeyChangedBroadcast = beforeKeyChangedBroadcast;
    }

    @Override
    public void run() {
        /*
         * ==============================================
         * CONNECTION LIFECYCLE
         * CLIENT TIMEOUT - 90s
         * ==============================================
         */
        String clientAddress = String.valueOf(clientSocket.getRemoteSocketAddress());

        try (Socket socket = clientSocket;
                SocketPacketChannel channel = new SocketPacketChannel(socket)) {
            presenceChannel = channel;
            sendAuthChallenge(channel);
            socket.setSoTimeout(CLIENT_TIMEOUT_MS);
            System.out.println("[E2EE-SERVER] Client da ket noi: "
                    + clientAddress
                    + " | Thread: " + Thread.currentThread().getName());
            notifyEvent("INFO", "CLIENT_CONNECT", "-",
                    "Chấp nhận kết nối socket TCP mới: " + clientAddress);

            /*
             * ==============================================
             * ENVELOPE RECEIVE LOOP
             * SOCKET PACKET CHANNEL
             * ==============================================
             */
            while (true) {
                Envelope envelope = channel.receive();
                logEnvelopeMetadata(envelope);

                if (authenticated
                        && onlineUsers.get(authenticatedUsername) != this) {
                    break;
                }

                if (!authenticated) {
                    if (envelope.getOp() != Opcode.AUTH) {
                        sendError(channel, envelope.getFrom(),
                                "UNAUTHENTICATED", "Authentication is required");
                        continue;
                    }
                    if (!authenticate(envelope, channel)) {
                        break;
                    }
                    continue;
                }

                afterOwnershipCheck.run();
                if (!beginRequestDispatch()) {
                    break;
                }

                /*
                 * ===================================================================
                 * ____ ___ _ _ ____ __ ____ ___ _ _ ____
                 * | _ \|_ _| \ | |/ ___| / / | _ \ / _ \| \ | |/ ___|
                 * | |_) || || \| | | _ / / | |_) | | | | \| | | _
                 * | __/ | || |\ | |_| | / / | __/| |_| | |\ | |_| |
                 * |_| |___|_| \_|\____| /_/ |_| \___/|_| \_|\____|
                 * ===================================================================
                 */
                try {
                    beforeRequestDispatch.run();
                    if (!isRequestDispatchAllowed()) {
                        break;
                    }
                    if (envelope.getOp() == Opcode.PING) {
                        Envelope pong = Envelope.create(
                                Opcode.PONG, "server", envelope.getFrom());
                        pong.setId(envelope.getId());
                        channel.send(pong);
                    } else if (envelope.getOp() == Opcode.KEY_PUBLISH) {
                        handleKeyPublish(envelope);
                    } else if (envelope.getOp() == Opcode.CHAT_1TO1
                            || envelope.getOp() == Opcode.KEY_LOOKUP
                            || envelope.getOp() == Opcode.GROUP_CREATE
                            || envelope.getOp() == Opcode.CHAT_GROUP
                            || envelope.getOp() == Opcode.ACK) {
                        envelope.setFrom(authenticatedUsername);
                        messageRouter.route(envelope, this);
                    }
                } finally {
                    endRequestDispatch();
                }
            }
        } catch (SocketTimeoutException e) {
            System.out.println("[E2EE-SERVER] Client khong hoat dong trong 90 giay: "
                    + clientAddress);
            notifyEvent("WARN", "CLIENT_TIMEOUT",
                    authenticatedUsername != null ? authenticatedUsername : "-",
                    "Client không hoạt động trong 90 giây: " + clientAddress);
        } catch (EOFException e) {
            System.out.println("[E2EE-SERVER] Client da dong ket noi (EOF): "
                    + clientAddress);
        } catch (IOException e) {
            if (!clientSocket.isClosed()) {
                System.err.println("[E2EE-SERVER] Loi ket noi client: "
                        + clientAddress + " - " + e.getMessage());
                notifyEvent("ERROR", "SOCKET_ERROR",
                        authenticatedUsername != null ? authenticatedUsername : "-",
                        "Lỗi kết nối client: " + e.getMessage());
            }
        } finally {
            destroyChallengeNonce();
            if (authenticatedUsername != null) {
                presenceDispatcher.disconnect(authenticatedUsername, this);
            }
            presenceChannel = null;
            onClose.run();
            System.out.println("[E2EE-SERVER] Da cleanup client: " + clientAddress);
            notifyEvent("INFO", "CLIENT_DISCONNECT",
                    authenticatedUsername != null ? authenticatedUsername : "-",
                    "Client đã ngắt kết nối: " + clientAddress);
        }
    }

    static boolean isValidUsername(String username) {
        return username != null
                && !"server".equals(username)
                && !username.contains("|")
                && USERNAME_PATTERN.matcher(username).matches();
    }

    private void sendAuthChallenge(SocketPacketChannel channel) throws IOException {
        challengeNonce = new byte[AUTH_NONCE_BYTES];
        SECURE_RANDOM.nextBytes(challengeNonce);

        Envelope challenge = Envelope.create(Opcode.AUTH_CHALLENGE, "server", null)
                .put("nonce", Base64.getEncoder().encodeToString(challengeNonce));
        channel.send(challenge);
    }

    private boolean authenticate(Envelope envelope, SocketPacketChannel channel)
            throws IOException {
        String username = envelope.getFrom();
        if (!isValidUsername(username)) {
            destroyChallengeNonce();
            sendError(channel, username, "INVALID_USERNAME", "Invalid username");
            return false;
        }

        Object signatureValue = envelope.get("sig");
        Object publicKeyValue = envelope.get("pubkey");
        if (!(signatureValue instanceof String signatureBase64)
                || !(publicKeyValue instanceof String publicKeyBase64)
                || signatureBase64.isBlank()
                || publicKeyBase64.isBlank()
                || !authenticateWithRegisteredKey(
                        username, signatureBase64, publicKeyBase64)) {
            destroyChallengeNonce();
            sendError(channel, username, "AUTH_FAILED", "Authentication failed");
            notifyEvent("WARN", "AUTH_FAIL", username, "Xác thực chữ ký số RSA thất bại");
            return false;
        }

        authenticatedUsername = username;
        authenticated = true;
        destroyChallengeNonce();
        notifyEvent("INFO", "SESSION_AUTH", username,
                "Xác thực khóa công khai RSA-2048 thành công");

        PresenceDispatcher.Registration registration =
                presenceDispatcher.register(username, this);

        Envelope authOk = Envelope.create(Opcode.AUTH_OK, "server", authenticatedUsername)
                .put("msg", "Authenticated successfully");
        channel.send(authOk);
        beforePresenceSnapshot.run();
        sendPresenceSnapshot(channel, registration.snapshot());
        presenceDispatcher.activate(username, this);
        sendOfflineSyncIfAvailable(channel, username);
        return true;
    }

    private void sendOfflineSyncIfAvailable(SocketPacketChannel channel, String username)
            throws IOException {
        OfflineQueueRouter oqr = OfflineQueueRouter.findIn(messageRouter);
        if (oqr != null) {
            oqr.syncOfflineMessages(username, channel);
        }
    }

    private void sendPresenceSnapshot(SocketPacketChannel channel,
            Iterable<String> snapshot) throws IOException {
        if (!beginRequestDispatch()) {
            return;
        }

        try {
            for (String onlineUsername : snapshot) {
                Envelope presence = Envelope.create(
                        Opcode.PRESENCE, "server", authenticatedUsername)
                        .put("user", onlineUsername)
                        .put("online", true);
                channel.send(presence);
            }
        } finally {
            endRequestDispatch();
        }
    }

    private boolean beginRequestDispatch() {
        synchronized (dispatchLock) {
            if (!dispatchAllowed) {
                return false;
            }
            activeDispatches++;
            return true;
        }
    }

    private void endRequestDispatch() {
        synchronized (dispatchLock) {
            activeDispatches--;
        }
    }

    private boolean isRequestDispatchAllowed() {
        synchronized (dispatchLock) {
            return dispatchAllowed;
        }
    }

    void revokeRequestDispatch() {
        synchronized (dispatchLock) {
            dispatchAllowed = false;
        }
    }

    void closeReplacedSession() {
        try {
            clientSocket.close();
        } catch (IOException e) {
            System.err.println("[E2EE-SERVER] Loi khi dong session bi thay the: "
                    + clientSocket.getRemoteSocketAddress());
        }
    }

    void sendPresence(Envelope presence) throws IOException {
        beforePresenceWrite.run();
        SocketPacketChannel channel = presenceChannel;
        if (channel == null) {
            throw new IOException("Presence channel is closed");
        }
        channel.send(presence);
    }

    void closePresenceSession() {
        closeReplacedSession();
    }

    public String authenticatedUsername() {
        return authenticatedUsername;
    }

    void sendRouterEnvelope(Envelope envelope) throws IOException {
        sendRoutedEnvelopeIfCurrent(authenticatedUsername, envelope);
    }

    void sendRouterError(String code, String message) throws IOException {
        Envelope error = Envelope.create(
                Opcode.ERROR, "server", authenticatedUsername)
                .put("code", code)
                .put("msg", message);
        sendRouterEnvelope(error);
    }

    boolean sendRoutedEnvelopeIfCurrent(String expectedUsername,
            Envelope envelope) throws IOException {
        if (!isCurrentOnlineSession(expectedUsername)) {
            return false;
        }

        SocketPacketChannel channel = presenceChannel;
        if (channel == null) {
            return false;
        }
        channel.send(envelope);
        return true;
    }

    void closeRoutedSession() {
        closeReplacedSession();
    }

    private void handleKeyPublish(Envelope envelope) {
        Object publicKeyValue = envelope.get("pubkey");
        if (!(publicKeyValue instanceof String publicKeyBase64)
                || publicKeyBase64.isBlank()
                || !isValidRsaPublicKey(publicKeyBase64)
                || !replacePublishedKey(publicKeyBase64)) {
            return;
        }

        beforeKeyChangedBroadcast.run();
        broadcastKeyChanged(publicKeyBase64);
    }

    private boolean replacePublishedKey(String publicKeyBase64) {
        synchronized (dispatchLock) {
            if (!dispatchAllowed
                    || onlineUsers.get(authenticatedUsername) != this) {
                return false;
            }
            publicKeyRegistry.put(authenticatedUsername, publicKeyBase64);
            return true;
        }
    }

    private void broadcastKeyChanged(String publicKeyBase64) {
        for (String onlineUsername : onlineUsers.keySet()) {
            ClientHandler recipient = onlineUsers.get(onlineUsername);
            if (recipient == null) {
                continue;
            }

            Envelope keyChanged = Envelope.create(
                    Opcode.KEY_CHANGED, authenticatedUsername, onlineUsername)
                    .put("new_pubkey", publicKeyBase64);
            try {
                recipient.sendKeyChangedIfCurrent(onlineUsername,
                        authenticatedUsername, publicKeyBase64, keyChanged);
            } catch (IOException e) {
                recipient.closePresenceSession();
            }
        }
    }

    private void sendKeyChangedIfCurrent(String expectedUsername,
            String keyOwner, String expectedPublicKey,
            Envelope keyChanged) throws IOException {
        synchronized (keyChangedSendLock) {
            if (!isCurrentOnlineSession(expectedUsername)
                    || !expectedPublicKey.equals(publicKeyRegistry.get(keyOwner))) {
                return;
            }

            SocketPacketChannel channel = presenceChannel;
            if (channel == null) {
                throw new IOException("Session channel is closed");
            }
            channel.send(keyChanged);
        }
    }

    private boolean isCurrentOnlineSession(String expectedUsername) {
        synchronized (dispatchLock) {
            return dispatchAllowed && onlineUsers.get(expectedUsername) == this;
        }
    }

    private static boolean isValidRsaPublicKey(String publicKeyBase64) {
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            return "RSA".equals(publicKey.getAlgorithm());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private boolean authenticateWithRegisteredKey(String username,
            String signatureBase64, String suppliedPublicKeyBase64) {
        String registeredPublicKey = publicKeyRegistry.get(username);
        if (registeredPublicKey != null) {
            return verifyAuthSignature(signatureBase64, registeredPublicKey);
        }

        // TOFU: chỉ ghim khóa sau khi chính khóa đó xác thực được challenge.
        if (!verifyAuthSignature(signatureBase64, suppliedPublicKeyBase64)) {
            return false;
        }

        String concurrentlyRegisteredKey = publicKeyRegistry.putIfAbsent(
                username, suppliedPublicKeyBase64);
        return concurrentlyRegisteredKey == null
                || concurrentlyRegisteredKey.equals(suppliedPublicKeyBase64)
                || verifyAuthSignature(signatureBase64, concurrentlyRegisteredKey);
    }

    private boolean verifyAuthSignature(String signatureBase64, String publicKeyBase64) {
        if (challengeNonce == null) {
            return false;
        }

        try {
            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(publicKeyBytes));

            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(challengeNonce);
            return verifier.verify(signatureBytes);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private void destroyChallengeNonce() {
        if (challengeNonce != null) {
            Arrays.fill(challengeNonce, (byte) 0);
            challengeNonce = null;
        }
    }

    private static void sendError(SocketPacketChannel channel, String to,
            String code, String message) throws IOException {
        Envelope error = Envelope.create(Opcode.ERROR, "server", to)
                .put("code", code)
                .put("msg", message);
        channel.send(error);
    }

    private static void logEnvelopeMetadata(Envelope envelope) {
        int bodyBytes = MiniJson.stringify(envelope.getBody())
                .getBytes(StandardCharsets.UTF_8).length;
        System.out.println("[E2EE-SERVER] Envelope: op=" + envelope.getOp()
                + ", from=" + envelope.getFrom()
                + ", to=" + envelope.getTo()
                + ", id=" + envelope.getId()
                + ", ts=" + envelope.getTs()
                + ", bodyBytes=" + bodyBytes);
    }
}
