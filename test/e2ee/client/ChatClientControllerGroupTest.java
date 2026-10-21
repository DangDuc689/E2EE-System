package e2ee.client;

import e2ee.client.crypto.ClientCryptoEngine;
import e2ee.client.crypto.ClientCryptoService;
import e2ee.client.crypto.GroupCryptoService;
import e2ee.client.identity.ClientIdentityStore;
import e2ee.client.security.ClientGroupStore;
import e2ee.client.security.LocalTrustStore;
import e2ee.common.TestSupport;
import e2ee.common.codec.EnvelopeMapper;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.contract.PacketChannel;
import e2ee.common.stub.LoopbackChannel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Kiểm thử tích hợp Chat Nhóm E2EE qua ChatClientController.
 */
public final class ChatClientControllerGroupTest {
    private static final long TIMEOUT_SECONDS = 5;

    private ChatClientControllerGroupTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("CCCG-1 - Tao nhom thanh cong qua createGroup",
                ChatClientControllerGroupTest::testCreateGroupSuccess);
        TestSupport.check("CCCG-2 - Nhan SNAPSHOT cap nhat danh ba nhom",
                ChatClientControllerGroupTest::testReceiveGroupSnapshot);
        TestSupport.check("CCCG-3 - Ma hoa va gui tin nhan nhom CHAT_GROUP",
                ChatClientControllerGroupTest::testSendGroupMessage);
        TestSupport.check("CCCG-4 - Nhan va giai ma tin nhan nhom CHAT_GROUP",
                ChatClientControllerGroupTest::testReceiveGroupMessage);
        TestSupport.check("CCCG-5 - Phat hien Ghost Member Attack va canh bao",
                ChatClientControllerGroupTest::testGhostMemberDetection);
        TestSupport.check("CCCG-6 - Chay thu offline sync tin nhan nhom va gui ACK",
                ChatClientControllerGroupTest::testOfflineGroupSyncAndAck);

        TestSupport.summary();
    }

    private static void testCreateGroupSuccess() throws Exception {
        TestContext context = TestContext.create();
        Session session = context.openSession();
        try {
            List<String> members = Arrays.asList("alice", "bob", "charlie");
            session.controller.createGroup("g-001", "Nhom 1", members);

            // Server nhan Envelope GROUP_CREATE
            Envelope req = session.server.serverChannel.receive();
            TestSupport.assertEquals(Opcode.GROUP_CREATE, req.getOp());
            TestSupport.assertEquals("g-001", req.getGroupId());
            TestSupport.assertEquals("Nhom 1", req.getString("groupName"));

            // Server gui lai confirm CREATED
            Envelope confirm = Envelope.createGroup(Opcode.GROUP_CREATE, "server", "g-001")
                    .put("status", "CREATED")
                    .put("groupId", "g-001")
                    .put("groupName", "Nhom 1")
                    .put("members", members);
            session.server.serverChannel.send(confirm);

            session.listener.awaitGroupCreated();
            TestSupport.assertEquals("g-001", session.listener.createdGroupId.get());
            TestSupport.assertEquals("Nhom 1", session.listener.createdGroupName.get());
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testReceiveGroupSnapshot() throws Exception {
        TestContext context = TestContext.create();
        Session session = context.openSession();
        try {
            List<String> members = Arrays.asList("alice", "bob");
            Envelope snapshot = Envelope.createGroup(Opcode.GROUP_CREATE, "server", "g-snap")
                    .put("status", "SNAPSHOT")
                    .put("groupId", "g-snap")
                    .put("groupName", "Nhom Snapshot")
                    .put("members", members);
            session.server.serverChannel.send(snapshot);

            session.listener.awaitGroupSnapshot();
            TestSupport.assertTrue(session.controller.getGroupStore().hasGroup("g-snap"),
                    "GroupStore phai chua nhom snapshot");
            ClientGroupStore.GroupRecord rec = session.controller.getGroupStore().getGroup("g-snap");
            TestSupport.assertEquals("Nhom Snapshot", rec.groupName());
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testSendGroupMessage() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        KeyPair charlie = ClientCryptoEngine.generateRsaKeyPair();
        context.trustStore.checkPeerKey("bob", encodePublicKey(bob));
        context.trustStore.checkPeerKey("charlie", encodePublicKey(charlie));

        Session session = context.openSession();
        try {
            // Setup local group
            List<String> members = Arrays.asList("alice", "bob", "charlie");
            session.controller.getGroupStore().putGroup("g-002", "Nhom Chat", members);

            session.controller.sendGroupMessage("g-002", "Xin chao ca nhom!");

            Envelope sent = session.server.serverChannel.receive();
            session.listener.awaitGroupSent();

            TestSupport.assertEquals(Opcode.CHAT_GROUP, sent.getOp());
            TestSupport.assertEquals("g-002", sent.getGroupId());
            TestSupport.assertEquals("alice", sent.getFrom());

            // Bob va Charlie deu giai ma duoc
            Map<String, PublicKey> roster = new HashMap<>();
            roster.put("alice", session.server.alicePublicKey.get());
            roster.put("bob", bob.getPublic());
            roster.put("charlie", charlie.getPublic());
            String membersHash = GroupCryptoService.computeMembersHash(roster);

            GroupCryptoService bobCrypto = new GroupCryptoService();
            String bobPlaintext = bobCrypto.verifyAndDecryptGroupMessage(
                    sent, "bob", session.server.alicePublicKey.get(), bob.getPrivate(), membersHash, false);
            TestSupport.assertEquals("Xin chao ca nhom!", bobPlaintext);

            GroupCryptoService charlieCrypto = new GroupCryptoService();
            String charliePlaintext = charlieCrypto.verifyAndDecryptGroupMessage(
                    sent, "charlie", session.server.alicePublicKey.get(), charlie.getPrivate(), membersHash, false);
            TestSupport.assertEquals("Xin chao ca nhom!", charliePlaintext);
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testReceiveGroupMessage() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        context.trustStore.checkPeerKey("bob", encodePublicKey(bob));

        Session session = context.openSession();
        try {
            List<String> members = Arrays.asList("alice", "bob");
            session.controller.getGroupStore().putGroup("g-003", "Nhom 2 Nguoi", members);

            Map<String, PublicKey> roster = new HashMap<>();
            roster.put("alice", session.server.alicePublicKey.get());
            roster.put("bob", bob.getPublic());
            String membersHash = GroupCryptoService.computeMembersHash(roster);

            Map<String, PublicKey> recipients = new HashMap<>();
            recipients.put("alice", session.server.alicePublicKey.get());

            GroupCryptoService bobCrypto = new GroupCryptoService();
            Envelope msg = bobCrypto.createEncryptedGroupMessage(
                    "bob", "g-003", "Chao Alice tu Bob", recipients, bob.getPrivate(), membersHash);

            session.server.serverChannel.send(msg);

            session.listener.awaitGroupReceived();
            TestSupport.assertEquals("g-003", session.listener.receivedGroupId.get());
            TestSupport.assertEquals("bob", session.listener.receivedGroupSender.get());
            TestSupport.assertEquals("Chao Alice tu Bob", session.listener.receivedGroupContent.get());
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testGhostMemberDetection() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        KeyPair eve = ClientCryptoEngine.generateRsaKeyPair();
        context.trustStore.checkPeerKey("bob", encodePublicKey(bob));
        context.trustStore.checkPeerKey("eve", encodePublicKey(eve));

        Session session = context.openSession();
        try {
            // Alice nghi nhom chi co [alice, bob]
            List<String> members = Arrays.asList("alice", "bob");
            session.controller.getGroupStore().putGroup("g-004", "Nhom Bi Tan Cong", members);

            // Nhung Bob (hoac ke xau) tao goi tin tinh tren roster co ca Eve!
            Map<String, PublicKey> fakeRoster = new HashMap<>();
            fakeRoster.put("alice", session.server.alicePublicKey.get());
            fakeRoster.put("bob", bob.getPublic());
            fakeRoster.put("eve", eve.getPublic());
            String fakeHash = GroupCryptoService.computeMembersHash(fakeRoster);

            Map<String, PublicKey> recipients = new HashMap<>();
            recipients.put("alice", session.server.alicePublicKey.get());

            GroupCryptoService bobCrypto = new GroupCryptoService();
            Envelope msg = bobCrypto.createEncryptedGroupMessage(
                    "bob", "g-004", "Tin nhan co ma", recipients, bob.getPrivate(), fakeHash);

            session.server.serverChannel.send(msg);

            session.listener.awaitSecurityWarning();
            TestSupport.assertTrue(!session.listener.groupReceived.await(300, TimeUnit.MILLISECONDS),
                    "Tin nhan Ghost Member Attack khong duoc phep hien thi");
        } finally {
            session.close();
            context.delete();
        }
    }

    private static void testOfflineGroupSyncAndAck() throws Exception {
        TestContext context = TestContext.create();
        KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
        context.trustStore.checkPeerKey("bob", encodePublicKey(bob));

        Session session = context.openSession();
        try {
            List<String> members = Arrays.asList("alice", "bob");
            session.controller.getGroupStore().putGroup("g-005", "Nhom Offline", members);

            Map<String, PublicKey> roster = new HashMap<>();
            roster.put("alice", session.server.alicePublicKey.get());
            roster.put("bob", bob.getPublic());
            String membersHash = GroupCryptoService.computeMembersHash(roster);

            Map<String, PublicKey> recipients = new HashMap<>();
            recipients.put("alice", session.server.alicePublicKey.get());

            GroupCryptoService bobCrypto = new GroupCryptoService();
            Envelope originalMsg = bobCrypto.createEncryptedGroupMessage(
                    "bob", "g-005", "Tin nhan nhom offline", recipients, bob.getPrivate(), membersHash);

            Envelope sync = Envelope.create(Opcode.OFFLINE_SYNC, "server", "alice")
                    .put("original_envelope", EnvelopeMapper.toMap(originalMsg));

            session.server.serverChannel.send(sync);

            // Server phai nhan duoc ACK tu Alice
            Envelope ack = session.server.serverChannel.receive();
            TestSupport.assertEquals(Opcode.ACK, ack.getOp());
            TestSupport.assertEquals("alice", ack.getFrom());
            TestSupport.assertEquals("server", ack.getTo());
            TestSupport.assertEquals(originalMsg.getId(), ack.getString("ack_msg_id"));
            TestSupport.assertEquals("DELIVERED", ack.getString("status"));

            // Va Alice phai hien thi tin nhan nhom
            session.listener.awaitGroupReceived();
            TestSupport.assertEquals("g-005", session.listener.receivedGroupId.get());
            TestSupport.assertEquals("bob", session.listener.receivedGroupSender.get());
            TestSupport.assertEquals("Tin nhan nhom offline", session.listener.receivedGroupContent.get());
        } finally {
            session.close();
            context.delete();
        }
    }

    private static String encodePublicKey(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private static PublicKey decodePublicKey(String base64) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
    }

    private static final class GroupTestListener implements ChatClientController.Listener {
        private final CountDownLatch authenticated = new CountDownLatch(1);
        private final CountDownLatch groupCreated = new CountDownLatch(1);
        private final CountDownLatch groupSnapshot = new CountDownLatch(1);
        private final CountDownLatch groupSent = new CountDownLatch(1);
        private final CountDownLatch groupReceived = new CountDownLatch(1);
        private final CountDownLatch warning = new CountDownLatch(1);

        private final AtomicReference<String> createdGroupId = new AtomicReference<>();
        private final AtomicReference<String> createdGroupName = new AtomicReference<>();
        private final AtomicReference<String> receivedGroupId = new AtomicReference<>();
        private final AtomicReference<String> receivedGroupSender = new AtomicReference<>();
        private final AtomicReference<String> receivedGroupContent = new AtomicReference<>();

        @Override
        public void onAuthenticated(String username) {
            authenticated.countDown();
        }

        @Override
        public void onGroupCreated(String groupId, String groupName, List<String> members) {
            createdGroupId.set(groupId);
            createdGroupName.set(groupName);
            groupCreated.countDown();
        }

        @Override
        public void onGroupSnapshot(String groupId, String groupName, List<String> members) {
            groupSnapshot.countDown();
        }

        @Override
        public void onGroupMessageSent(String groupId, String content, String messageId, long timestamp) {
            groupSent.countDown();
        }

        @Override
        public void onGroupMessageReceived(String groupId, String senderUsername, String content, String messageId, long timestamp) {
            receivedGroupId.set(groupId);
            receivedGroupSender.set(senderUsername);
            receivedGroupContent.set(content);
            groupReceived.countDown();
        }

        @Override
        public void onSecurityWarning(String username, String message) {
            warning.countDown();
        }

        private void awaitAuthenticated() throws Exception {
            await(authenticated, "authenticated");
        }

        private void awaitGroupCreated() throws Exception {
            await(groupCreated, "group created");
        }

        private void awaitGroupSnapshot() throws Exception {
            await(groupSnapshot, "group snapshot");
        }

        private void awaitGroupSent() throws Exception {
            await(groupSent, "group sent");
        }

        private void awaitGroupReceived() throws Exception {
            await(groupReceived, "group received");
        }

        private void awaitSecurityWarning() throws Exception {
            await(warning, "security warning");
        }

        private static void await(CountDownLatch latch, String name) throws Exception {
            TestSupport.assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Khong nhan duoc callback " + name);
        }
    }

    private static final class TestServer implements AutoCloseable {
        private final PacketChannel clientChannel;
        private final PacketChannel serverChannel;
        private final AtomicReference<PublicKey> alicePublicKey = new AtomicReference<>();
        private final CountDownLatch authenticated = new CountDownLatch(1);

        private TestServer() throws Exception {
            PacketChannel[] pair = LoopbackChannel.pair();
            clientChannel = pair[0];
            serverChannel = pair[1];

            byte[] nonce = new byte[32];
            new java.security.SecureRandom().nextBytes(nonce);
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
            }, "group-test-server");
            authThread.setDaemon(true);
            authThread.start();
        }

        private void awaitAuthenticated() throws Exception {
            TestSupport.assertTrue(authenticated.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
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
        private final TestServer server;
        private final GroupTestListener listener;

        private Session(ChatClientController controller, TestServer server, GroupTestListener listener) {
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
        private final Path tempDir;
        private final ClientIdentityStore identityStore;
        private final LocalTrustStore trustStore;
        private final ClientGroupStore groupStore;
        private final char[] passphrase = "group-test-passphrase".toCharArray();

        private TestContext(Path tempDir, ClientIdentityStore identityStore, LocalTrustStore trustStore, ClientGroupStore groupStore) {
            this.tempDir = tempDir;
            this.identityStore = identityStore;
            this.trustStore = trustStore;
            this.groupStore = groupStore;
        }

        private static TestContext create() throws Exception {
            Path tempDir = Files.createTempDirectory("e2ee-group-ctrl-test-");
            ClientIdentityStore identityStore = new ClientIdentityStore(tempDir.resolve("identities"));
            LocalTrustStore trustStore = new LocalTrustStore(tempDir.resolve("truststore.json"));
            ClientGroupStore groupStore = new ClientGroupStore(tempDir.resolve("groups.json"));
            identityStore.create("alice", "group-test-passphrase".toCharArray());
            return new TestContext(tempDir, identityStore, trustStore, groupStore);
        }

        private Session openSession() throws Exception {
            TestServer server = new TestServer();
            GroupTestListener listener = new GroupTestListener();
            ChatClientController controller = new ChatClientController(
                    identityStore,
                    trustStore,
                    groupStore,
                    new ClientCryptoService(),
                    (host, port, timeout) -> server.clientChannel,
                    ChatClientController.KEEP_ALIVE_INTERVAL_SECONDS,
                    new long[]{1000L});
            controller.setListener(listener);
            controller.login("127.0.0.1", 8888, "alice", passphrase);
            listener.awaitAuthenticated();
            server.awaitAuthenticated();
            return new Session(controller, server, listener);
        }

        private void delete() throws Exception {
            ClientCryptoEngine.wipeMemory(passphrase);
            if (Files.exists(tempDir)) {
                try (var stream = Files.walk(tempDir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (Exception ignored) {
                                }
                            });
                }
            }
        }
    }
}
