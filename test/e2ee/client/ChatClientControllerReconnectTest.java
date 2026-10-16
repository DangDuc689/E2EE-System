package e2ee.client;

import e2ee.client.crypto.ClientCryptoEngine;
import e2ee.client.crypto.ClientCryptoService;
import e2ee.client.identity.ClientIdentityStore;
import e2ee.client.security.LocalTrustStore;
import e2ee.common.TestSupport;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.net.SocketPacketChannel;

import javax.swing.SwingUtilities;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kiểm thử reconnect bằng hai kết nối TCP thật liên tiếp.
 */
public final class ChatClientControllerReconnectTest {
    private static final long TIMEOUT_SECONDS = 10;

    private ChatClientControllerReconnectTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("CCC-R1 - Mat mang tu dong reconnect va AUTH lai",
                ChatClientControllerReconnectTest::testReconnect);
        TestSupport.summary();
    }

    private static void testReconnect() throws Exception {
        Path directory = Files.createTempDirectory(
                "e2ee-controller-reconnect-test-");
        Path identityDirectory = directory.resolve("identities");
        char[] passphrase = "reconnect-passphrase".toCharArray();
        ReconnectServer server = new ReconnectServer();
        ChatClientController controller = null;
        try {
            ClientIdentityStore identityStore =
                    new ClientIdentityStore(identityDirectory);
            identityStore.create("alice", passphrase);
            LocalTrustStore trustStore = new LocalTrustStore(
                    directory.resolve("truststore.json"));
            ReconnectListener listener = new ReconnectListener();

            controller = new ChatClientController(
                    identityStore, trustStore, new ClientCryptoService(),
                    SocketPacketChannel::connect,
                    ChatClientController.KEEP_ALIVE_INTERVAL_SECONDS,
                    new long[]{20L, 40L, 80L});
            controller.setListener(listener);
            controller.login("127.0.0.1", server.getPort(),
                    "alice", passphrase);

            listener.awaitAuthenticated();
            listener.awaitConnectionLost();
            listener.awaitReconnectScheduled();
            listener.awaitReconnected();
            server.awaitSecondAuthentication();

            TestSupport.assertEquals(ChatClientController.State.CONNECTED,
                    controller.getState());
            TestSupport.assertEquals(2, server.validAuthCount.get());
            TestSupport.assertEquals(1, listener.reconnectAttempt.get());
            TestSupport.assertEquals(20L, listener.reconnectDelayMs.get());
            TestSupport.assertTrue(listener.reconnectedOnEdt.get(),
                    "Callback reconnect phai chay tren EDT");
        } finally {
            if (controller != null) {
                controller.close();
            }
            server.close();
            ClientCryptoEngine.wipeMemory(passphrase);
            Files.deleteIfExists(identityDirectory.resolve("alice.private.key"));
            Files.deleteIfExists(identityDirectory.resolve("alice.public.key"));
            Files.deleteIfExists(identityDirectory);
            Files.deleteIfExists(directory.resolve("truststore.json"));
            Files.deleteIfExists(directory);
        }
    }

    private static final class ReconnectListener
            implements ChatClientController.Listener {
        private final CountDownLatch authenticated = new CountDownLatch(1);
        private final CountDownLatch connectionLost = new CountDownLatch(1);
        private final CountDownLatch reconnectScheduled = new CountDownLatch(1);
        private final CountDownLatch reconnected = new CountDownLatch(1);
        private final AtomicInteger reconnectAttempt = new AtomicInteger();
        private final AtomicLong reconnectDelayMs = new AtomicLong();
        private final AtomicBoolean reconnectedOnEdt = new AtomicBoolean();

        @Override
        public void onAuthenticated(String username) {
            authenticated.countDown();
        }

        @Override
        public void onConnectionLost(String message) {
            connectionLost.countDown();
        }

        @Override
        public void onReconnectScheduled(int attempt, long delayMs,
                String message) {
            reconnectAttempt.set(attempt);
            reconnectDelayMs.set(delayMs);
            reconnectScheduled.countDown();
        }

        @Override
        public void onReconnected(String username) {
            reconnectedOnEdt.set(SwingUtilities.isEventDispatchThread());
            reconnected.countDown();
        }

        private void awaitAuthenticated() throws Exception {
            await(authenticated, "authenticated");
        }

        private void awaitConnectionLost() throws Exception {
            await(connectionLost, "connection lost");
        }

        private void awaitReconnectScheduled() throws Exception {
            await(reconnectScheduled, "reconnect scheduled");
        }

        private void awaitReconnected() throws Exception {
            await(reconnected, "reconnected");
        }

        private static void await(CountDownLatch latch, String name)
                throws Exception {
            TestSupport.assertTrue(latch.await(
                    TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Khong nhan duoc callback " + name);
        }
    }

    private static final class ReconnectServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread serverThread;
        private final CountDownLatch secondAuthentication =
                new CountDownLatch(1);
        private final CountDownLatch releaseSecondConnection =
                new CountDownLatch(1);
        private final AtomicInteger validAuthCount = new AtomicInteger();

        private ReconnectServer() throws Exception {
            serverSocket = new ServerSocket(
                    0, 10, InetAddress.getLoopbackAddress());
            serverThread = new Thread(this::run, "reconnect-test-server");
            serverThread.setDaemon(true);
            serverThread.start();
        }

        private int getPort() {
            return serverSocket.getLocalPort();
        }

        private void run() {
            for (int connectionNumber = 1;
                    connectionNumber <= 2; connectionNumber++) {
                try (Socket socket = serverSocket.accept();
                        SocketPacketChannel channel =
                                new SocketPacketChannel(socket)) {
                    byte[] nonce = new byte[32];
                    new SecureRandom().nextBytes(nonce);
                    try {
                        channel.send(Envelope.create(
                                Opcode.AUTH_CHALLENGE, "server", null)
                                .put("nonce", Base64.getEncoder()
                                        .encodeToString(nonce)));
                        Envelope auth = channel.receive();
                        if (isValidAuth(auth, nonce)) {
                            validAuthCount.incrementAndGet();
                        }
                        channel.send(Envelope.create(
                                Opcode.AUTH_OK, "server", "alice"));
                        if (connectionNumber == 2) {
                            secondAuthentication.countDown();
                            releaseSecondConnection.await(
                                    TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        }
                    } finally {
                        ClientCryptoEngine.wipeMemory(nonce);
                    }
                } catch (Exception e) {
                    if (!serverSocket.isClosed()) {
                        e.printStackTrace();
                    }
                    return;
                }
            }
        }

        private static boolean isValidAuth(Envelope auth, byte[] nonce)
                throws Exception {
            if (auth.getOp() != Opcode.AUTH
                    || !"alice".equals(auth.getFrom())) {
                return false;
            }
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(
                            Base64.getDecoder().decode(
                                    auth.getString("pubkey"))));
            Signature verifier = Signature.getInstance(
                    ClientCryptoService.SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(nonce);
            return verifier.verify(Base64.getDecoder().decode(
                    auth.getString("sig")));
        }

        private void awaitSecondAuthentication() throws Exception {
            TestSupport.assertTrue(secondAuthentication.await(
                    TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Server khong nhan duoc AUTH lan hai");
        }

        @Override
        public void close() throws Exception {
            releaseSecondConnection.countDown();
            serverSocket.close();
            serverThread.join(2_000L);
        }
    }
}
