package e2ee.integration;

import e2ee.client.ChatClientController;
import e2ee.client.crypto.ClientCryptoEngine;
import e2ee.client.identity.ClientIdentityStore;
import e2ee.client.security.LocalTrustStore;
import e2ee.common.TestSupport;
import e2ee.common.contract.PacketChannel;
import e2ee.common.net.SocketPacketChannel;
import e2ee.server.ServerMain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Nghiệm thu Issue #13 với hai ChatClientController qua Server TCP thật.
 */
public final class ClientIssue13AcceptanceTest {
    private static final long TIMEOUT_SECONDS = 15;

    private ClientIssue13AcceptanceTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("Issue #13 - Hai Client Presence va chat E2EE 1-1",
                ClientIssue13AcceptanceTest::runAcceptance);
        TestSupport.summary();
    }

    private static void runAcceptance() throws Exception {
        ServerMain server = new ServerMain();
        Thread serverThread = new Thread(server::run,
                "issue-13-acceptance-server");
        ClientFiles aliceFiles = ClientFiles.create("alice");
        ClientFiles bobFiles = ClientFiles.create("bob");
        char[] alicePassphrase = "alice-passphrase".toCharArray();
        char[] bobPassphrase = "bob-passphrase".toCharArray();
        ChatClientController alice = null;
        ChatClientController bob = null;
        try {
            serverThread.start();
            waitForServer();

            alice = aliceFiles.newController();
            bob = bobFiles.newController();
            AcceptanceListener aliceListener = new AcceptanceListener();
            AcceptanceListener bobListener = new AcceptanceListener();
            alice.setListener(aliceListener);
            bob.setListener(bobListener);

            alice.register("127.0.0.1", ServerMain.DEFAULT_PORT,
                    "alice", alicePassphrase);
            aliceListener.awaitAuthenticated();
            bob.register("127.0.0.1", ServerMain.DEFAULT_PORT,
                    "bob", bobPassphrase);
            bobListener.awaitAuthenticated();

            waitUntil(() -> aliceListener.isOnline("bob"),
                    "Alice khong thay Bob online");
            waitUntil(() -> bobListener.isOnline("alice"),
                    "Bob khong thay Alice online");

            alice.lookupPeer("bob");
            bob.lookupPeer("alice");
            aliceListener.awaitPeerKey("bob");
            bobListener.awaitPeerKey("alice");

            alice.sendMessage("bob", "Xin chao qua E2EE Socket that");
            bobListener.awaitMessage();
            TestSupport.assertEquals("alice",
                    bobListener.lastSender);
            TestSupport.assertEquals("Xin chao qua E2EE Socket that",
                    bobListener.lastMessage);

            bob.close();
            bob = null;
            waitUntil(() -> !aliceListener.isOnline("bob"),
                    "Alice khong thay Bob offline");
        } finally {
            if (alice != null) {
                alice.close();
            }
            if (bob != null) {
                bob.close();
            }
            server.shutdown();
            serverThread.join(5_000L);
            ClientCryptoEngine.wipeMemory(alicePassphrase);
            ClientCryptoEngine.wipeMemory(bobPassphrase);
            aliceFiles.delete();
            bobFiles.delete();
        }
    }

    private static void waitForServer() throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try (PacketChannel channel = SocketPacketChannel.connect(
                    "127.0.0.1", ServerMain.DEFAULT_PORT, 200)) {
                return;
            } catch (Exception e) {
                lastFailure = e;
                Thread.sleep(20L);
            }
        }
        throw new AssertionError("Server khong khoi dong", lastFailure);
    }

    private static void waitUntil(Check condition, String message)
            throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.isTrue()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Check {
        boolean isTrue();
    }

    private static final class AcceptanceListener
            implements ChatClientController.Listener {
        private final CountDownLatch authenticated = new CountDownLatch(1);
        private final CountDownLatch messageReceived = new CountDownLatch(1);
        private final Map<String, Boolean> presence = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch> peerKeys =
                new ConcurrentHashMap<>();
        private volatile String lastSender;
        private volatile String lastMessage;

        @Override
        public void onAuthenticated(String username) {
            authenticated.countDown();
        }

        @Override
        public void onPresenceChanged(String username, boolean online) {
            presence.put(username, online);
        }

        @Override
        public void onPeerKeyAvailable(String username,
                String fingerprint, boolean verified) {
            peerKeys.computeIfAbsent(username,
                    ignored -> new CountDownLatch(1)).countDown();
        }

        @Override
        public void onMessageReceived(String senderUsername, String content,
                String messageId, long timestamp) {
            lastSender = senderUsername;
            lastMessage = content;
            messageReceived.countDown();
        }

        private boolean isOnline(String username) {
            return Boolean.TRUE.equals(presence.get(username));
        }

        private void awaitAuthenticated() throws Exception {
            TestSupport.assertTrue(authenticated.await(
                    TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Client khong AUTH_OK");
        }

        private void awaitPeerKey(String username) throws Exception {
            CountDownLatch latch = peerKeys.computeIfAbsent(username,
                    ignored -> new CountDownLatch(1));
            TestSupport.assertTrue(latch.await(
                    TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Khong nhan duoc public key cua " + username);
        }

        private void awaitMessage() throws Exception {
            TestSupport.assertTrue(messageReceived.await(
                    TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Khong nhan duoc tin nhan E2EE");
        }
    }

    private static final class ClientFiles {
        private final Path directory;
        private final String username;

        private ClientFiles(Path directory, String username) {
            this.directory = directory;
            this.username = username;
        }

        private static ClientFiles create(String username) throws Exception {
            return new ClientFiles(Files.createTempDirectory(
                    "e2ee-issue13-" + username + "-"), username);
        }

        private ChatClientController newController() throws Exception {
            return new ChatClientController(
                    new ClientIdentityStore(directory.resolve("identities")),
                    new LocalTrustStore(directory.resolve("truststore.json")));
        }

        private void delete() throws Exception {
            Path identities = directory.resolve("identities");
            Files.deleteIfExists(identities.resolve(
                    username + ".private.key"));
            Files.deleteIfExists(identities.resolve(
                    username + ".public.key"));
            Files.deleteIfExists(identities);
            Files.deleteIfExists(directory.resolve("truststore.json"));
            Files.deleteIfExists(directory);
        }
    }
}
