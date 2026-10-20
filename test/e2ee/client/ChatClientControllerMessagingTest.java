package e2ee.client;

import e2ee.client.crypto.ClientCryptoEngine;
import e2ee.client.crypto.ClientCryptoService;
import e2ee.client.identity.ClientIdentityStore;
import e2ee.client.security.LocalTrustStore;
import e2ee.common.TestSupport;
import e2ee.common.codec.EnvelopeMapper;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.contract.PacketChannel;
import e2ee.common.stub.LoopbackChannel;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Kiểm thử gửi, nhận và xác minh tin nhắn E2EE 1-1 qua Controller.
 */
public final class ChatClientControllerMessagingTest {
    private static final long TIMEOUT_SECONDS = 10;

    private ChatClientControllerMessagingTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("CCC-M1 - Ma hoa va gui CHAT_1TO1",
                ChatClientControllerMessagingTest::testSendMessage);
        TestSupport.check("CCC-M2 - Verify va giai ma CHAT_1TO1",
                ChatClientControllerMessagingTest::testReceiveMessage);
        TestSupport.check("CCC-M3 - Tin la cho KEY_LOOKUP roi moi giai ma",
                ChatClientControllerMessagingTest::testPendingKeyLookup);
        TestSupport.check("CCC-M4 - Tin bi sua chi tao canh bao bao mat",
                ChatClientControllerMessagingTest::testTamperedMessage);
        TestSupport.check("CCC-M5 - Tin offline lap lai van gui ACK",
                ChatClientControllerMessagingTest::testOfflineRetryAck);
        TestSupport.check("CCC-M6 - TTL cuc bo khong doi contract mang",
                ChatClientControllerMessagingTest::testMessageTtl);
        TestSupport.check("CCC-M7 - Nguoi gui va nhan dong bo thoi diem het han",
                ChatClientControllerMessagingTest::testSyncedExpiration);
        TestSupport.check("CCC-M8 - Tin truc tiep den sau thoi diem het han khong hien thi",
                ChatClientControllerMessagingTest::testDirectExpiredNotDisplayed);
        TestSupport.check("CCC-M9 - Tin offline con han hien thi kem expiresAt dung",
                ChatClientControllerMessagingTest::testOfflineValidTtl);
        TestSupport.check("CCC-M10 - Tin offline da het han khong hien thi nhung van gui ACK",
                ChatClientControllerMessagingTest::testOfflineExpiredNotDisplayedAndSendsAck);
        TestSupport.check("CCC-M11 - Tin offline bi can thiep khong hien thi va khong gui ACK",
                ChatClientControllerMessagingTest::testOfflineTamperedNotAcked);

        TestSupport.summary();
    }

    private static void testSendMessage() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        context.trustStore.checkPeerKey("bob", encodePublicKey(bob));
        Session session = context.openSession();
        try {
            session.controller.sendMessage("bob", "Xin chao Bob");
            Envelope encrypted = session.server.serverChannel.receive();
            session.listener.awaitSent();

            TestSupport.assertEquals(Opcode.CHAT_1TO1, encrypted.getOp());
            TestSupport.assertEquals("alice", encrypted.getFrom());
            TestSupport.assertEquals("bob", encrypted.getTo());
            String plaintext = new ClientCryptoService()
                    .verifyAndDecrypt1to1Message(
                            encrypted, session.server.alicePublicKey.get(),
                            bob.getPrivate(), false);
            TestSupport.assertEquals("Xin chao Bob", plaintext);
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testReceiveMessage() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        context.trustStore.checkPeerKey("bob", encodePublicKey(bob));
        Session session = context.openSession();
        try {
            Envelope encrypted = new ClientCryptoService()
                    .createEncrypted1to1Message(
                            "bob", "alice", "Chao Alice",
                            session.server.alicePublicKey.get(),
                            bob.getPrivate());
            session.server.serverChannel.send(encrypted);
            session.listener.awaitReceived();

            TestSupport.assertEquals("bob",
                    session.listener.receivedFrom.get());
            TestSupport.assertEquals("Chao Alice",
                    session.listener.receivedContent.get());
            TestSupport.assertTrue(session.listener.receivedOnEdt.get(),
                    "Callback tin nhan phai chay tren EDT");
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testPendingKeyLookup() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        Session session = context.openSession();
        try {
            Envelope encrypted = new ClientCryptoService()
                    .createEncrypted1to1Message(
                            "bob", "alice", "Tin den truoc khoa",
                            session.server.alicePublicKey.get(),
                            bob.getPrivate());
            session.server.serverChannel.send(encrypted);

            Envelope lookup = session.server.serverChannel.receive();
            TestSupport.assertEquals(Opcode.KEY_LOOKUP, lookup.getOp());
            TestSupport.assertEquals("bob", lookup.getTo());
            session.server.serverChannel.send(Envelope.create(
                    Opcode.KEY_LOOKUP_RESULT, "bob", "alice")
                    .put("pubkey", encodePublicKey(bob)));
            session.listener.awaitReceived();

            TestSupport.assertEquals("Tin den truoc khoa",
                    session.listener.receivedContent.get());
            TestSupport.assertEquals(encodePublicKey(bob),
                    context.trustStore.getPublicKeyBase64("bob"));
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testTamperedMessage() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        context.trustStore.checkPeerKey("bob", encodePublicKey(bob));
        Session session = context.openSession();
        try {
            Envelope encrypted = new ClientCryptoService()
                    .createEncrypted1to1Message(
                            "bob", "alice", "Khong duoc hien thi",
                            session.server.alicePublicKey.get(),
                            bob.getPrivate());
            String ciphertext = encrypted.getString("ct");
            char replacement = ciphertext.charAt(0) == 'A' ? 'B' : 'A';
            encrypted.put("ct", replacement + ciphertext.substring(1));
            session.server.serverChannel.send(encrypted);

            session.listener.awaitSecurityWarning();
            TestSupport.assertTrue(!session.listener.received.await(
                    300, TimeUnit.MILLISECONDS),
                    "Tin bi sua khong duoc dua ban ro len UI");
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testOfflineRetryAck() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        context.trustStore.checkPeerKey("bob", encodePublicKey(bob));
        Session session = context.openSession();
        try {
            Envelope original = new ClientCryptoService()
                    .createEncrypted1to1Message(
                            "bob", "alice", "Tin offline chi hien thi mot lan",
                            session.server.alicePublicKey.get(),
                            bob.getPrivate());
            Envelope sync = Envelope.create(
                    Opcode.OFFLINE_SYNC, "server", "alice")
                    .put("original_envelope",
                            EnvelopeMapper.toMap(original));

            session.server.serverChannel.send(sync);
            session.listener.awaitReceived();
            Envelope firstAck = session.server.serverChannel.receive();
            assertDeliveredAck(firstAck, original.getId());

            session.server.serverChannel.send(sync);
            Envelope retryAck = session.server.serverChannel.receive();
            assertDeliveredAck(retryAck, original.getId());
            Thread.sleep(300L);
            TestSupport.assertEquals(1,
                    session.listener.receivedCount.get());
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testMessageTtl() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        context.trustStore.checkPeerKey("bob", encodePublicKey(bob));
        Session session = context.openSession();
        try {
            session.controller.sendMessage("bob", "Tu huy 10 giay", 10);
            Envelope outgoing = session.server.serverChannel.receive();
            session.listener.awaitSent();
            String sentMessage = new ClientCryptoService()
                    .verifyAndDecrypt1to1Message(
                            outgoing,
                            session.server.alicePublicKey.get(),
                            bob.getPrivate(), false);
            TestSupport.assertEquals("Tu huy 10 giay", sentMessage);
            TestSupport.assertEquals(10, session.listener.sentTtl.get());
            TestSupport.assertEquals(Set.of("ek", "iv", "ct", "sig"),
                    outgoing.getBody().keySet());
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testSyncedExpiration() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        context.trustStore.checkPeerKey("bob", encodePublicKey(bob));
        Session session = context.openSession();
        try {
            session.controller.sendMessage("bob", "Tin nhan TTL 10 giay", 10);
            Envelope outgoing = session.server.serverChannel.receive();
            session.listener.awaitSent();

            long expectedExpiresAt = outgoing.getTs() + 10_000L;
            ClientCryptoService.Decrypted1to1Message decrypted = new ClientCryptoService()
                    .verifyAndDecrypt1to1(
                            outgoing,
                            session.server.alicePublicKey.get(),
                            bob.getPrivate(), false);

            TestSupport.assertEquals("Tin nhan TTL 10 giay", decrypted.getContent());
            TestSupport.assertEquals(10, decrypted.getTtlSeconds());
            TestSupport.assertEquals(expectedExpiresAt, decrypted.getExpiresAt());
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testDirectExpiredNotDisplayed() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        context.trustStore.checkPeerKey("bob", encodePublicKey(bob));
        Session session = context.openSession();
        try {
            Envelope env = Envelope.create(Opcode.CHAT_1TO1, "bob", "alice");
            env.setTs(System.currentTimeMillis() - 5_000L);
            long expiredTime = env.getTs() + 2_000L;

            Map<String, Object> plainMap = new java.util.LinkedHashMap<>();
            plainMap.put("content", "Tin da het han");
            plainMap.put("ttl", 2L);
            plainMap.put("expires_at", expiredTime);
            String plainJson = e2ee.common.json.MiniJson.stringify(plainMap);
            byte[] plainBytes = plainJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            javax.crypto.SecretKey aesKey = ClientCryptoEngine.generateAesKey();
            byte[] iv = ClientCryptoEngine.generateRandomIv();
            byte[] aad = env.computeAADBytes();
            byte[] ct = ClientCryptoEngine.encryptAesGcm(plainBytes, aesKey, iv, aad);
            byte[] ek = ClientCryptoEngine.wrapAesKey(aesKey, session.server.alicePublicKey.get());
            String ekB64 = Base64.getEncoder().encodeToString(ek);
            String ivB64 = Base64.getEncoder().encodeToString(iv);
            String ctB64 = Base64.getEncoder().encodeToString(ct);
            String aadB64 = Base64.getEncoder().encodeToString(aad);
            String canon = "bob|alice|" + ekB64 + "|" + ivB64 + "|" + aadB64 + "|" + ctB64;
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
            signer.initSign(bob.getPrivate());
            signer.update(canon.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String sigB64 = Base64.getEncoder().encodeToString(signer.sign());

            env.put("ek", ekB64).put("iv", ivB64).put("ct", ctB64).put("sig", sigB64);

            session.server.serverChannel.send(env);
            boolean received = session.listener.received.await(300, TimeUnit.MILLISECONDS);
            TestSupport.assertTrue(!received, "Tin den sau thoi diem het han khong duoc hien thi");
            TestSupport.assertEquals(0, session.listener.receivedCount.get());
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testOfflineValidTtl() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        context.trustStore.checkPeerKey("bob", encodePublicKey(bob));
        Session session = context.openSession();
        try {
            Envelope env = Envelope.create(Opcode.CHAT_1TO1, "bob", "alice");
            long msgTs = System.currentTimeMillis() - 10_000L;
            env.setTs(msgTs);
            long expectedExpiresAt = msgTs + 60_000L;

            Map<String, Object> plainMap = new java.util.LinkedHashMap<>();
            plainMap.put("content", "Tin offline con han");
            plainMap.put("ttl", 60L);
            plainMap.put("expires_at", expectedExpiresAt);
            String plainJson = e2ee.common.json.MiniJson.stringify(plainMap);
            byte[] plainBytes = plainJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            javax.crypto.SecretKey aesKey = ClientCryptoEngine.generateAesKey();
            byte[] iv = ClientCryptoEngine.generateRandomIv();
            byte[] aad = env.computeAADBytes();
            byte[] ct = ClientCryptoEngine.encryptAesGcm(plainBytes, aesKey, iv, aad);
            byte[] ek = ClientCryptoEngine.wrapAesKey(aesKey, session.server.alicePublicKey.get());
            String ekB64 = Base64.getEncoder().encodeToString(ek);
            String ivB64 = Base64.getEncoder().encodeToString(iv);
            String ctB64 = Base64.getEncoder().encodeToString(ct);
            String aadB64 = Base64.getEncoder().encodeToString(aad);
            String canon = "bob|alice|" + ekB64 + "|" + ivB64 + "|" + aadB64 + "|" + ctB64;
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
            signer.initSign(bob.getPrivate());
            signer.update(canon.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String sigB64 = Base64.getEncoder().encodeToString(signer.sign());

            env.put("ek", ekB64).put("iv", ivB64).put("ct", ctB64).put("sig", sigB64);

            Envelope sync = Envelope.create(Opcode.OFFLINE_SYNC, "server", "alice")
                    .put("original_envelope", EnvelopeMapper.toMap(env));

            session.server.serverChannel.send(sync);
            session.listener.awaitReceived();

            TestSupport.assertEquals("Tin offline con han", session.listener.receivedContent.get());
            TestSupport.assertEquals(expectedExpiresAt, session.listener.receivedExpiresAt.get());

            Envelope ack = session.server.serverChannel.receive();
            assertDeliveredAck(ack, env.getId());
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testOfflineExpiredNotDisplayedAndSendsAck() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        context.trustStore.checkPeerKey("bob", encodePublicKey(bob));
        Session session = context.openSession();
        try {
            Envelope env = Envelope.create(Opcode.CHAT_1TO1, "bob", "alice");
            long msgTs = System.currentTimeMillis() - 60_000L;
            env.setTs(msgTs);
            long expiredTime = msgTs + 10_000L;

            Map<String, Object> plainMap = new java.util.LinkedHashMap<>();
            plainMap.put("content", "Tin offline da het han");
            plainMap.put("ttl", 10L);
            plainMap.put("expires_at", expiredTime);
            String plainJson = e2ee.common.json.MiniJson.stringify(plainMap);
            byte[] plainBytes = plainJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            javax.crypto.SecretKey aesKey = ClientCryptoEngine.generateAesKey();
            byte[] iv = ClientCryptoEngine.generateRandomIv();
            byte[] aad = env.computeAADBytes();
            byte[] ct = ClientCryptoEngine.encryptAesGcm(plainBytes, aesKey, iv, aad);
            byte[] ek = ClientCryptoEngine.wrapAesKey(aesKey, session.server.alicePublicKey.get());
            String ekB64 = Base64.getEncoder().encodeToString(ek);
            String ivB64 = Base64.getEncoder().encodeToString(iv);
            String ctB64 = Base64.getEncoder().encodeToString(ct);
            String aadB64 = Base64.getEncoder().encodeToString(aad);
            String canon = "bob|alice|" + ekB64 + "|" + ivB64 + "|" + aadB64 + "|" + ctB64;
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
            signer.initSign(bob.getPrivate());
            signer.update(canon.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String sigB64 = Base64.getEncoder().encodeToString(signer.sign());

            env.put("ek", ekB64).put("iv", ivB64).put("ct", ctB64).put("sig", sigB64);

            Envelope sync = Envelope.create(Opcode.OFFLINE_SYNC, "server", "alice")
                    .put("original_envelope", EnvelopeMapper.toMap(env));

            session.server.serverChannel.send(sync);

            Envelope ack = session.server.serverChannel.receive();
            assertDeliveredAck(ack, env.getId());

            boolean received = session.listener.received.await(300, TimeUnit.MILLISECONDS);
            TestSupport.assertTrue(!received, "Tin offline het han khong duoc hien thi");
            TestSupport.assertEquals(0, session.listener.receivedCount.get());
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testOfflineTamperedNotAcked() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        context.trustStore.checkPeerKey("bob", encodePublicKey(bob));
        Session session = context.openSession();
        try {
            Envelope env = new ClientCryptoService().createEncrypted1to1Message(
                    "bob", "alice", "Tin offline can thiep",
                    session.server.alicePublicKey.get(), bob.getPrivate(), 10);
            String ct = env.getString("ct");
            char rep = ct.charAt(0) == 'A' ? 'B' : 'A';
            env.put("ct", rep + ct.substring(1));

            Envelope sync = Envelope.create(Opcode.OFFLINE_SYNC, "server", "alice")
                    .put("original_envelope", EnvelopeMapper.toMap(env));

            session.server.serverChannel.send(sync);

            session.listener.awaitSecurityWarning();
            TestSupport.assertTrue(!session.listener.received.await(300, TimeUnit.MILLISECONDS),
                    "Tin offline bi sua khong duoc dua len UI");

            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<Envelope> future = executor.submit(() -> session.server.serverChannel.receive());
                try {
                    future.get(300, TimeUnit.MILLISECONDS);
                    TestSupport.assertTrue(false, "Khong duoc gui ACK cho tin offline bi can thiep");
                } catch (java.util.concurrent.TimeoutException expected) {
                    future.cancel(true);
                }
            } finally {
                executor.shutdownNow();
            }
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void assertDeliveredAck(Envelope ack,
            String expectedMessageId) {
        TestSupport.assertEquals(Opcode.ACK, ack.getOp());
        TestSupport.assertEquals("alice", ack.getFrom());
        TestSupport.assertEquals("server", ack.getTo());
        TestSupport.assertEquals(expectedMessageId,
                ack.getString("ack_msg_id"));
        TestSupport.assertEquals("DELIVERED", ack.getString("status"));
    }

    private static String encodePublicKey(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(
                keyPair.getPublic().getEncoded());
    }

    private static final class MessageListener
            implements ChatClientController.Listener {
        private final CountDownLatch authenticated = new CountDownLatch(1);
        private final CountDownLatch sent = new CountDownLatch(1);
        private final CountDownLatch received = new CountDownLatch(1);
        private final CountDownLatch warning = new CountDownLatch(1);
        private final AtomicReference<String> receivedFrom = new AtomicReference<>();
        private final AtomicReference<String> receivedContent = new AtomicReference<>();
        private final AtomicBoolean receivedOnEdt = new AtomicBoolean();
        private final AtomicInteger receivedCount = new AtomicInteger();
        private final AtomicInteger sentTtl = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicLong receivedExpiresAt = new java.util.concurrent.atomic.AtomicLong();

        @Override
        public void onAuthenticated(String username) {
            authenticated.countDown();
        }

        @Override
        public void onMessageSentWithTtl(String recipientUsername,
                String content, String messageId, long timestamp,
                int ttlSeconds) {
            sentTtl.set(ttlSeconds);
            sent.countDown();
        }

        @Override
        public void onMessageReceived(String senderUsername, String content,
                String messageId, long timestamp) {
            onMessageReceived(senderUsername, content, messageId, timestamp, 0L);
        }

        @Override
        public void onMessageReceived(String senderUsername, String content,
                String messageId, long timestamp, long expiresAt) {
            receivedFrom.set(senderUsername);
            receivedContent.set(content);
            receivedExpiresAt.set(expiresAt);
            receivedOnEdt.set(SwingUtilities.isEventDispatchThread());
            receivedCount.incrementAndGet();
            received.countDown();
        }

        @Override
        public void onSecurityWarning(String username, String message) {
            warning.countDown();
        }

        private void awaitAuthenticated() throws Exception {
            await(authenticated, "authenticated");
        }

        private void awaitSent() throws Exception {
            await(sent, "message sent");
        }

        private void awaitReceived() throws Exception {
            await(received, "message received");
        }

        private void awaitSecurityWarning() throws Exception {
            await(warning, "security warning");
        }

        private static void await(CountDownLatch latch, String name)
                throws Exception {
            TestSupport.assertTrue(latch.await(
                    TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Khong nhan duoc callback " + name);
        }
    }

    private static final class MessagingServer implements AutoCloseable {
        private final PacketChannel clientChannel;
        private final PacketChannel serverChannel;
        private final AtomicReference<PublicKey> alicePublicKey =
                new AtomicReference<>();
        private final CountDownLatch authenticated = new CountDownLatch(1);

        private MessagingServer() throws Exception {
            PacketChannel[] pair = LoopbackChannel.pair();
            clientChannel = pair[0];
            serverChannel = pair[1];
            byte[] nonce = new byte[32];
            new SecureRandom().nextBytes(nonce);
            serverChannel.send(Envelope.create(
                    Opcode.AUTH_CHALLENGE, "server", null)
                    .put("nonce", Base64.getEncoder().encodeToString(nonce)));

            Thread authThread = new Thread(() -> {
                try {
                    Envelope auth = serverChannel.receive();
                    alicePublicKey.set(ClientCryptoEngine.decodeRsaPublicKey(
                            Base64.getDecoder().decode(
                                    auth.getString("pubkey"))));
                    serverChannel.send(Envelope.create(
                            Opcode.AUTH_OK, "server", "alice"));
                } catch (Exception ignored) {
                } finally {
                    ClientCryptoEngine.wipeMemory(nonce);
                    authenticated.countDown();
                }
            }, "messaging-test-server");
            authThread.setDaemon(true);
            authThread.start();
        }

        private void awaitAuthenticated() throws Exception {
            TestSupport.assertTrue(authenticated.await(
                    TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Fake server chua AUTH xong");
            TestSupport.assertTrue(alicePublicKey.get() != null,
                    "Fake server phai doc duoc public key Alice");
        }

        @Override
        public void close() throws Exception {
            clientChannel.close();
            serverChannel.close();
        }
    }

    private static final class Session implements AutoCloseable {
        private final ChatClientController controller;
        private final MessagingServer server;
        private final MessageListener listener;

        private Session(ChatClientController controller,
                MessagingServer server, MessageListener listener) {
            this.controller = controller;
            this.server = server;
            this.listener = listener;
        }

        @Override
        public void close() throws Exception {
            controller.close();
            server.close();
        }
    }

    private static final class TestContext {
        private final Path directory;
        private final ClientIdentityStore identityStore;
        private final LocalTrustStore trustStore;
        private final char[] passphrase = "messaging-passphrase".toCharArray();

        private TestContext(Path directory) throws Exception {
            this.directory = directory;
            identityStore = new ClientIdentityStore(
                    directory.resolve("identities"));
            trustStore = new LocalTrustStore(
                    directory.resolve("truststore.json"));
            identityStore.create("alice", passphrase);
        }

        private static TestContext create() throws Exception {
            return new TestContext(Files.createTempDirectory(
                    "e2ee-controller-message-test-"));
        }

        private Session openSession() throws Exception {
            MessagingServer server = new MessagingServer();
            ChatClientController controller = new ChatClientController(
                    identityStore, trustStore, new ClientCryptoService(),
                    (host, port, timeout) -> server.clientChannel);
            MessageListener listener = new MessageListener();
            controller.setListener(listener);
            controller.login("localhost", 8888, "alice", passphrase);
            listener.awaitAuthenticated();
            server.awaitAuthenticated();
            return new Session(controller, server, listener);
        }

        private void delete() throws Exception {
            ClientCryptoEngine.wipeMemory(passphrase);
            Path identityDirectory = directory.resolve("identities");
            Files.deleteIfExists(identityDirectory.resolve("alice.private.key"));
            Files.deleteIfExists(identityDirectory.resolve("alice.public.key"));
            Files.deleteIfExists(identityDirectory);
            Files.deleteIfExists(directory.resolve("truststore.json"));
            Files.deleteIfExists(directory);
        }
    }
}
