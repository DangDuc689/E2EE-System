package e2ee.client;

import e2ee.client.crypto.ClientCryptoEngine;
import e2ee.client.crypto.ClientCryptoService;
import e2ee.client.identity.ClientIdentityStore;
import e2ee.client.security.LocalTrustStore;
import e2ee.common.TestSupport;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.contract.PacketChannel;
import e2ee.common.stub.LoopbackChannel;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Kiểm thử KEY_LOOKUP, LocalTrustStore và PRESENCE của Controller.
 */
public final class ChatClientControllerDirectoryTest {
    private static final long TIMEOUT_SECONDS = 10;

    private ChatClientControllerDirectoryTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("CCC-D1 - KEY_LOOKUP luu khoa TOFU",
                ChatClientControllerDirectoryTest::testKeyLookup);
        TestSupport.check("CCC-D2 - Khoa thay doi khong ghi de trust store",
                ChatClientControllerDirectoryTest::testChangedKey);
        TestSupport.check("CCC-D3 - PRESENCE cap nhat tren EDT",
                ChatClientControllerDirectoryTest::testPresence);

        TestSupport.summary();
    }

    private static void testKeyLookup() throws Exception {
        TestContext context = TestContext.create();
        char[] passphrase = "directory-passphrase".toCharArray();
        DirectoryServer server = null;
        ChatClientController controller = null;
        try {
            context.identityStore.create("alice", passphrase);
            String bobPublicKey = encodePublicKey(
                    ClientCryptoEngine.generateRsaKeyPair());
            server = new DirectoryServer("alice");
            controller = context.newController(server.clientChannel);
            DirectoryListener listener = new DirectoryListener();
            controller.setListener(listener);

            controller.login("localhost", 8888, "alice", passphrase);
            listener.awaitAuthenticated();
            controller.lookupPeer("bob");
            Envelope lookup = server.serverChannel.receive();
            TestSupport.assertEquals(Opcode.KEY_LOOKUP, lookup.getOp());
            TestSupport.assertEquals("bob", lookup.getTo());

            server.serverChannel.send(Envelope.create(
                    Opcode.KEY_LOOKUP_RESULT, "bob", "alice")
                    .put("pubkey", bobPublicKey));
            listener.awaitKeyAvailable();

            TestSupport.assertEquals(bobPublicKey,
                    context.trustStore.getPublicKeyBase64("bob"));
            TestSupport.assertTrue(listener.keyCallbackOnEdt.get(),
                    "Callback key phai chay tren EDT");
        } finally {
            if (controller != null) {
                controller.close();
            }
            if (server != null) {
                server.close();
            }
            ClientCryptoEngine.wipeMemory(passphrase);
            context.delete();
        }
    }

    private static void testChangedKey() throws Exception {
        TestContext context = TestContext.create();
        char[] passphrase = "changed-key-passphrase".toCharArray();
        DirectoryServer server = null;
        ChatClientController controller = null;
        try {
            context.identityStore.create("alice", passphrase);
            String trustedKey = encodePublicKey(
                    ClientCryptoEngine.generateRsaKeyPair());
            String changedKey = encodePublicKey(
                    ClientCryptoEngine.generateRsaKeyPair());
            context.trustStore.checkPeerKey("bob", trustedKey);

            server = new DirectoryServer("alice");
            controller = context.newController(server.clientChannel);
            DirectoryListener listener = new DirectoryListener();
            controller.setListener(listener);
            controller.login("localhost", 8888, "alice", passphrase);
            listener.awaitAuthenticated();

            server.serverChannel.send(Envelope.create(
                    Opcode.KEY_LOOKUP_RESULT, "bob", "alice")
                    .put("pubkey", changedKey));
            listener.awaitKeyMismatch();

            TestSupport.assertEquals("bob", listener.mismatchUser.get());
            TestSupport.assertEquals(trustedKey,
                    context.trustStore.getPublicKeyBase64("bob"));
        } finally {
            if (controller != null) {
                controller.close();
            }
            if (server != null) {
                server.close();
            }
            ClientCryptoEngine.wipeMemory(passphrase);
            context.delete();
        }
    }

    private static void testPresence() throws Exception {
        TestContext context = TestContext.create();
        char[] passphrase = "presence-passphrase".toCharArray();
        DirectoryServer server = null;
        ChatClientController controller = null;
        try {
            context.identityStore.create("alice", passphrase);
            server = new DirectoryServer("alice");
            controller = context.newController(server.clientChannel);
            DirectoryListener listener = new DirectoryListener();
            controller.setListener(listener);
            controller.login("localhost", 8888, "alice", passphrase);
            listener.awaitAuthenticated();

            server.serverChannel.send(Envelope.create(
                    Opcode.PRESENCE, "server", "alice")
                    .put("user", "bob")
                    .put("online", true));
            listener.awaitPresence();

            TestSupport.assertTrue(controller.isPeerOnline("bob"),
                    "Bob phai online trong snapshot Controller");
            TestSupport.assertTrue(listener.presenceCallbackOnEdt.get(),
                    "Callback presence phai chay tren EDT");
        } finally {
            if (controller != null) {
                controller.close();
            }
            if (server != null) {
                server.close();
            }
            ClientCryptoEngine.wipeMemory(passphrase);
            context.delete();
        }
    }

    private static String encodePublicKey(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(
                keyPair.getPublic().getEncoded());
    }

    private static final class DirectoryListener
            implements ChatClientController.Listener {
        private final CountDownLatch authenticated = new CountDownLatch(1);
        private final CountDownLatch keyAvailable = new CountDownLatch(1);
        private final CountDownLatch keyMismatch = new CountDownLatch(1);
        private final CountDownLatch presence = new CountDownLatch(1);
        private final AtomicBoolean keyCallbackOnEdt = new AtomicBoolean();
        private final AtomicBoolean presenceCallbackOnEdt = new AtomicBoolean();
        private final AtomicReference<String> mismatchUser = new AtomicReference<>();

        @Override
        public void onAuthenticated(String username) {
            authenticated.countDown();
        }

        @Override
        public void onPeerKeyAvailable(String username,
                String fingerprint, boolean verified) {
            keyCallbackOnEdt.set(SwingUtilities.isEventDispatchThread());
            keyAvailable.countDown();
        }

        @Override
        public void onPeerKeyMismatch(String username,
                String newPublicKeyBase64) {
            mismatchUser.set(username);
            keyMismatch.countDown();
        }

        @Override
        public void onPresenceChanged(String username, boolean online) {
            presenceCallbackOnEdt.set(SwingUtilities.isEventDispatchThread());
            presence.countDown();
        }

        private void awaitAuthenticated() throws Exception {
            await(authenticated, "authenticated");
        }

        private void awaitKeyAvailable() throws Exception {
            await(keyAvailable, "key available");
        }

        private void awaitKeyMismatch() throws Exception {
            await(keyMismatch, "key mismatch");
        }

        private void awaitPresence() throws Exception {
            await(presence, "presence");
        }

        private static void await(CountDownLatch latch, String name)
                throws Exception {
            TestSupport.assertTrue(latch.await(
                    TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Khong nhan duoc callback " + name);
        }
    }

    private static final class DirectoryServer implements AutoCloseable {
        private final PacketChannel clientChannel;
        private final PacketChannel serverChannel;

        private DirectoryServer(String username) throws Exception {
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
                    if (auth.getOp() == Opcode.AUTH) {
                        serverChannel.send(Envelope.create(
                                Opcode.AUTH_OK, "server", username));
                    }
                } catch (Exception ignored) {
                } finally {
                    ClientCryptoEngine.wipeMemory(nonce);
                }
            }, "directory-test-server");
            authThread.setDaemon(true);
            authThread.start();
        }

        @Override
        public void close() throws Exception {
            clientChannel.close();
            serverChannel.close();
        }
    }

    private static final class TestContext {
        private final Path directory;
        private final ClientIdentityStore identityStore;
        private final LocalTrustStore trustStore;

        private TestContext(Path directory) throws Exception {
            this.directory = directory;
            identityStore = new ClientIdentityStore(
                    directory.resolve("identities"));
            trustStore = new LocalTrustStore(
                    directory.resolve("truststore.json"));
        }

        private static TestContext create() throws Exception {
            return new TestContext(Files.createTempDirectory(
                    "e2ee-controller-directory-test-"));
        }

        private ChatClientController newController(PacketChannel channel) {
            return new ChatClientController(identityStore, trustStore,
                    new ClientCryptoService(),
                    (host, port, timeout) -> channel);
        }

        private void delete() throws Exception {
            Path identityDirectory = directory.resolve("identities");
            Files.deleteIfExists(identityDirectory.resolve("alice.private.key"));
            Files.deleteIfExists(identityDirectory.resolve("alice.public.key"));
            Files.deleteIfExists(identityDirectory);
            Files.deleteIfExists(directory.resolve("truststore.json"));
            Files.deleteIfExists(directory);
        }
    }
}
