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
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Kiểm thử luồng đăng ký, đăng nhập và AUTH của ChatClientController.
 */
public final class ChatClientControllerAuthTest {
    private static final long TIMEOUT_SECONDS = 10;

    private ChatClientControllerAuthTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("CCC-A1 - Dang ky va AUTH dung payload",
                ChatClientControllerAuthTest::testRegisterAndAuthenticate);
        TestSupport.check("CCC-A2 - Dang nhap bang danh tinh da luu",
                ChatClientControllerAuthTest::testLoginAndAuthenticate);
        TestSupport.check("CCC-A3 - ERROR xac thuc duoc dua len UI",
                ChatClientControllerAuthTest::testAuthenticationError);
        TestSupport.check("CCC-A4 - Network ngoai EDT, callback tren EDT",
                ChatClientControllerAuthTest::testThreadRules);
        TestSupport.check("CCC-A5 - Tu choi nonce khong dung 32 byte",
                ChatClientControllerAuthTest::testInvalidNonceLength);

        TestSupport.summary();
    }

    private static void testRegisterAndAuthenticate() throws Exception {
        TestContext context = TestContext.create();
        char[] passphrase = "register-passphrase".toCharArray();
        try {
            FakeAuthServer server = FakeAuthServer.success("alice");
            ChatClientController controller = context.newController(server);
            RecordingListener listener = new RecordingListener();
            controller.setListener(listener);

            controller.register("127.0.0.1", 8888,
                    "alice", passphrase);
            listener.awaitAuthenticated();
            server.awaitFinished();

            TestSupport.assertEquals(ChatClientController.State.CONNECTED,
                    controller.getState());
            TestSupport.assertEquals("alice",
                    controller.getAuthenticatedUsername());
            TestSupport.assertTrue(context.identityStore.exists("alice"),
                    "Dang ky phai luu danh tinh xuong dia");
            TestSupport.assertTrue(server.authPayloadValid.get(),
                    "AUTH phai co sig va pubkey hop le");
            controller.close();
        } finally {
            ClientCryptoEngine.wipeMemory(passphrase);
            context.delete();
        }
    }

    private static void testLoginAndAuthenticate() throws Exception {
        TestContext context = TestContext.create();
        char[] createPassphrase = "login-passphrase".toCharArray();
        char[] loginPassphrase = "login-passphrase".toCharArray();
        try {
            context.identityStore.create("alice", createPassphrase);
            FakeAuthServer server = FakeAuthServer.success("alice");
            ChatClientController controller = context.newController(server);
            RecordingListener listener = new RecordingListener();
            controller.setListener(listener);

            controller.login("localhost", 8888,
                    "alice", loginPassphrase);
            listener.awaitAuthenticated();
            server.awaitFinished();

            TestSupport.assertTrue(server.authPayloadValid.get(),
                    "Dang nhap phai ky challenge bang khoa da luu");
            controller.close();
        } finally {
            ClientCryptoEngine.wipeMemory(createPassphrase);
            ClientCryptoEngine.wipeMemory(loginPassphrase);
            context.delete();
        }
    }

    private static void testAuthenticationError() throws Exception {
        TestContext context = TestContext.create();
        char[] passphrase = "auth-error-passphrase".toCharArray();
        try {
            context.identityStore.create("alice", passphrase);
            FakeAuthServer server = FakeAuthServer.failure(
                    "alice", "AUTH_FAILED", "Authentication failed");
            ChatClientController controller = context.newController(server);
            RecordingListener listener = new RecordingListener();
            controller.setListener(listener);

            controller.login("localhost", 8888, "alice", passphrase);
            listener.awaitError();
            server.awaitFinished();

            TestSupport.assertEquals("AUTH_FAILED", listener.errorCode.get());
            TestSupport.assertEquals(ChatClientController.State.DISCONNECTED,
                    controller.getState());
            controller.close();
        } finally {
            ClientCryptoEngine.wipeMemory(passphrase);
            context.delete();
        }
    }

    private static void testThreadRules() throws Exception {
        TestContext context = TestContext.create();
        char[] passphrase = "thread-passphrase".toCharArray();
        try {
            FakeAuthServer server = FakeAuthServer.success("alice");
            AtomicBoolean connectorOnEdt = new AtomicBoolean(true);
            ChatClientController controller = context.newController(
                    (host, port, timeout) -> {
                        connectorOnEdt.set(
                                SwingUtilities.isEventDispatchThread());
                        return server.clientChannel;
                    });
            RecordingListener listener = new RecordingListener();
            controller.setListener(listener);

            SwingUtilities.invokeAndWait(() -> controller.register(
                    "localhost", 8888, "alice", passphrase));
            listener.awaitAuthenticated();
            server.awaitFinished();

            TestSupport.assertTrue(!connectorOnEdt.get(),
                    "Ket noi mang khong duoc chay tren EDT");
            TestSupport.assertTrue(listener.authCallbackOnEdt.get(),
                    "Callback UI bat buoc chay tren EDT");
            controller.close();
        } finally {
            ClientCryptoEngine.wipeMemory(passphrase);
            context.delete();
        }
    }

    private static void testInvalidNonceLength() throws Exception {
        TestContext context = TestContext.create();
        char[] passphrase = "invalid-nonce-passphrase".toCharArray();
        ChatClientController controller = null;
        try {
            context.identityStore.create("alice", passphrase);
            FakeAuthServer server = FakeAuthServer.invalidNonce("alice");
            controller = context.newController(server);
            RecordingListener listener = new RecordingListener();
            controller.setListener(listener);

            controller.login("localhost", 8888, "alice", passphrase);
            listener.awaitError();
            server.awaitFinished();

            TestSupport.assertEquals("CONNECTION_FAILED",
                    listener.errorCode.get());
            TestSupport.assertEquals(ChatClientController.State.DISCONNECTED,
                    controller.getState());
        } finally {
            if (controller != null) {
                controller.close();
            }
            ClientCryptoEngine.wipeMemory(passphrase);
            context.delete();
        }
    }

    private static final class RecordingListener
            implements ChatClientController.Listener {
        private final CountDownLatch authenticated = new CountDownLatch(1);
        private final CountDownLatch error = new CountDownLatch(1);
        private final AtomicBoolean authCallbackOnEdt = new AtomicBoolean();
        private final AtomicReference<String> errorCode = new AtomicReference<>();

        @Override
        public void onAuthenticated(String username) {
            authCallbackOnEdt.set(SwingUtilities.isEventDispatchThread());
            authenticated.countDown();
        }

        @Override
        public void onError(String code, String message) {
            errorCode.set(code);
            error.countDown();
        }

        private void awaitAuthenticated() throws Exception {
            TestSupport.assertTrue(authenticated.await(
                    TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Khong nhan duoc callback authenticated");
        }

        private void awaitError() throws Exception {
            TestSupport.assertTrue(error.await(
                    TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Khong nhan duoc callback error");
        }
    }

    private static final class FakeAuthServer {
        private final PacketChannel clientChannel;
        private final PacketChannel serverChannel;
        private final String expectedUsername;
        private final String errorCode;
        private final String errorMessage;
        private final int nonceLength;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicBoolean authPayloadValid = new AtomicBoolean();

        private FakeAuthServer(String expectedUsername,
                String errorCode, String errorMessage, int nonceLength) {
            PacketChannel[] pair = LoopbackChannel.pair();
            this.clientChannel = pair[0];
            this.serverChannel = pair[1];
            this.expectedUsername = expectedUsername;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.nonceLength = nonceLength;
            Thread serverThread = new Thread(this::run, "fake-auth-server");
            serverThread.setDaemon(true);
            serverThread.start();
        }

        private static FakeAuthServer success(String username) {
            return new FakeAuthServer(username, null, null, 32);
        }

        private static FakeAuthServer failure(String username,
                String code, String message) {
            return new FakeAuthServer(username, code, message, 32);
        }

        private static FakeAuthServer invalidNonce(String username) {
            return new FakeAuthServer(username, null, null, 16);
        }

        private void run() {
            byte[] nonce = new byte[nonceLength];
            new SecureRandom().nextBytes(nonce);
            try {
                serverChannel.send(Envelope.create(
                        Opcode.AUTH_CHALLENGE, "server", null)
                        .put("nonce", Base64.getEncoder()
                                .encodeToString(nonce)));
                if (nonceLength != 32) {
                    return;
                }
                Envelope auth = serverChannel.receive();
                authPayloadValid.set(validateAuth(auth, nonce));
                if (errorCode == null) {
                    serverChannel.send(Envelope.create(
                            Opcode.AUTH_OK, "server", expectedUsername)
                            .put("msg", "Authenticated successfully"));
                } else {
                    serverChannel.send(Envelope.create(
                            Opcode.ERROR, "server", expectedUsername)
                            .put("code", errorCode)
                            .put("msg", errorMessage));
                }
            } catch (Exception ignored) {
            } finally {
                ClientCryptoEngine.wipeMemory(nonce);
                finished.countDown();
            }
        }

        private boolean validateAuth(Envelope auth, byte[] nonce)
                throws Exception {
            if (auth.getOp() != Opcode.AUTH
                    || !expectedUsername.equals(auth.getFrom())) {
                return false;
            }
            String signatureBase64 = auth.getString("sig");
            String publicKeyBase64 = auth.getString("pubkey");
            if (signatureBase64 == null || publicKeyBase64 == null) {
                return false;
            }

            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(
                            Base64.getDecoder().decode(publicKeyBase64)));
            Signature verifier = Signature.getInstance(
                    ClientCryptoService.SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(nonce);
            return verifier.verify(
                    Base64.getDecoder().decode(signatureBase64));
        }

        private void awaitFinished() throws Exception {
            TestSupport.assertTrue(finished.await(
                    TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Fake server khong hoan tat");
        }
    }

    private static final class TestContext {
        private final Path directory;
        private final ClientIdentityStore identityStore;
        private final LocalTrustStore trustStore;

        private TestContext(Path directory) throws Exception {
            this.directory = directory;
            this.identityStore = new ClientIdentityStore(
                    directory.resolve("identities"));
            this.trustStore = new LocalTrustStore(
                    directory.resolve("truststore.json"));
        }

        private static TestContext create() throws Exception {
            return new TestContext(Files.createTempDirectory(
                    "e2ee-controller-auth-test-"));
        }

        private ChatClientController newController(FakeAuthServer server) {
            return newController((host, port, timeout) ->
                    server.clientChannel);
        }

        private ChatClientController newController(
                ChatClientController.ChannelConnector connector) {
            return new ChatClientController(identityStore, trustStore,
                    new ClientCryptoService(), connector);
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
