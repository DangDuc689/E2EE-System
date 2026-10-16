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
 * Kiểm thử luồng cảnh báo MitM và xác minh lại fingerprint của Controller.
 */
public final class ChatClientControllerTrustTest {
    private static final long TIMEOUT_SECONDS = 10;

    private ChatClientControllerTrustTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("CCC-T1 - Chan khoa doi va chi cap nhat sau xac minh",
                ChatClientControllerTrustTest::testChangedKeyConfirmation);
        TestSupport.summary();
    }

    private static void testChangedKeyConfirmation() throws Exception {
        Path directory = Files.createTempDirectory("e2ee-controller-trust-");
        Path identityDirectory = directory.resolve("identities");
        Path trustFile = directory.resolve("truststore.json");
        ClientIdentityStore identityStore = new ClientIdentityStore(
                identityDirectory);
        LocalTrustStore trustStore = new LocalTrustStore(trustFile);
        char[] passphrase = "trust-controller-passphrase".toCharArray();
        PacketChannel[] channels = LoopbackChannel.pair();
        ChatClientController controller = null;
        try {
            identityStore.create("alice", passphrase);
            KeyPair oldKeyPair = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair changedKeyPair = ClientCryptoEngine.generateRsaKeyPair();
            String oldKey = encode(oldKeyPair);
            String changedKey = encode(changedKeyPair);
            trustStore.checkPeerKey("bob", oldKey);

            startAuthenticationServer(channels[1], "alice");
            controller = new ChatClientController(
                    identityStore, trustStore, new ClientCryptoService(),
                    (host, port, timeout) -> channels[0]);
            TrustListener listener = new TrustListener();
            controller.setListener(listener);
            controller.login("localhost", 8888, "alice", passphrase);
            listener.await(listener.authenticated, "authenticated");

            channels[1].send(Envelope.create(
                    Opcode.KEY_CHANGED, "bob", "alice")
                    .put("new_pubkey", changedKey));
            listener.await(listener.mismatch, "key mismatch");

            TestSupport.assertTrue(listener.mismatchOnEdt.get(),
                    "Canh bao doi khoa phai chay tren EDT");
            TestSupport.assertTrue(controller.isPeerBlocked("bob"),
                    "Peer co khoa thay doi phai bi chan");
            TestSupport.assertEquals(oldKey,
                    trustStore.getPublicKeyBase64("bob"));

            controller.requestFingerprintDetails("bob");
            listener.await(listener.fingerprintDetails,
                    "fingerprint details");
            TestSupport.assertTrue(listener.keyChanged.get(),
                    "Hop thoai phai hien thi trang thai khoa thay doi");
            TestSupport.assertEquals(
                    ClientCryptoService.calculateFingerprint(
                            changedKeyPair.getPublic()),
                    listener.peerFingerprint.get());

            controller.acceptPeerKey("bob", "fingerprint-khong-khop");
            listener.await(listener.trustRejected, "trust rejected");
            TestSupport.assertEquals("TRUST_CANDIDATE_CHANGED",
                    listener.errorCode.get());
            TestSupport.assertTrue(controller.isPeerBlocked("bob"),
                    "Xac nhan sai fingerprint khong duoc bo chan");
            TestSupport.assertEquals(oldKey,
                    trustStore.getPublicKeyBase64("bob"));

            controller.acceptPeerKey("bob", listener.peerFingerprint.get());
            listener.await(listener.trustRestored, "trust restored");
            TestSupport.assertTrue(listener.restoredOnEdt.get(),
                    "Callback xac minh lai phai chay tren EDT");
            TestSupport.assertTrue(!controller.isPeerBlocked("bob"),
                    "Peer phai duoc bo chan sau khi xac minh");
            TestSupport.assertEquals(changedKey,
                    trustStore.getPublicKeyBase64("bob"));
            TestSupport.assertTrue(trustStore.isVerified("bob"),
                    "Khoa moi phai duoc danh dau da xac minh");
        } finally {
            if (controller != null) {
                controller.close();
            }
            channels[0].close();
            channels[1].close();
            ClientCryptoEngine.wipeMemory(passphrase);
            Files.deleteIfExists(identityDirectory.resolve(
                    "alice.private.key"));
            Files.deleteIfExists(identityDirectory.resolve(
                    "alice.public.key"));
            Files.deleteIfExists(identityDirectory);
            Files.deleteIfExists(trustFile);
            Files.deleteIfExists(directory);
        }
    }

    private static void startAuthenticationServer(PacketChannel channel,
            String username) throws Exception {
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        channel.send(Envelope.create(
                Opcode.AUTH_CHALLENGE, "server", null)
                .put("nonce", Base64.getEncoder().encodeToString(nonce)));
        Thread thread = new Thread(() -> {
            try {
                if (channel.receive().getOp() == Opcode.AUTH) {
                    channel.send(Envelope.create(
                            Opcode.AUTH_OK, "server", username));
                }
            } catch (Exception ignored) {
            } finally {
                ClientCryptoEngine.wipeMemory(nonce);
            }
        }, "trust-test-server");
        thread.setDaemon(true);
        thread.start();
    }

    private static String encode(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(
                keyPair.getPublic().getEncoded());
    }

    private static final class TrustListener
            implements ChatClientController.Listener {
        private final CountDownLatch authenticated = new CountDownLatch(1);
        private final CountDownLatch mismatch = new CountDownLatch(1);
        private final CountDownLatch fingerprintDetails =
                new CountDownLatch(1);
        private final CountDownLatch trustRestored = new CountDownLatch(1);
        private final CountDownLatch trustRejected = new CountDownLatch(1);
        private final AtomicBoolean mismatchOnEdt = new AtomicBoolean();
        private final AtomicBoolean restoredOnEdt = new AtomicBoolean();
        private final AtomicBoolean keyChanged = new AtomicBoolean();
        private final AtomicReference<String> peerFingerprint =
                new AtomicReference<>();
        private final AtomicReference<String> errorCode =
                new AtomicReference<>();

        @Override
        public void onAuthenticated(String username) {
            authenticated.countDown();
        }

        @Override
        public void onPeerKeyMismatch(String username,
                String newPublicKeyBase64) {
            mismatchOnEdt.set(SwingUtilities.isEventDispatchThread());
            mismatch.countDown();
        }

        @Override
        public void onFingerprintDetails(String username,
                String myFingerprint, String currentPeerFingerprint,
                boolean currentKeyChanged) {
            peerFingerprint.set(currentPeerFingerprint);
            keyChanged.set(currentKeyChanged);
            fingerprintDetails.countDown();
        }

        @Override
        public void onPeerTrustRestored(String username,
                String fingerprint) {
            restoredOnEdt.set(SwingUtilities.isEventDispatchThread());
            trustRestored.countDown();
        }

        @Override
        public void onError(String code, String message) {
            if ("TRUST_CANDIDATE_CHANGED".equals(code)) {
                errorCode.set(code);
                trustRejected.countDown();
            }
        }

        private void await(CountDownLatch latch, String callback)
                throws Exception {
            TestSupport.assertTrue(latch.await(
                    TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Khong nhan duoc callback " + callback);
        }
    }
}
