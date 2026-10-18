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

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Kiểm thử keep-alive PING/PONG của ChatClientController.
 */
public final class ChatClientControllerKeepAliveTest {

    private ChatClientControllerKeepAliveTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("CCC-K1 - Gui PING dinh ky sau khi AUTH_OK",
                ChatClientControllerKeepAliveTest::testPeriodicPing);
        TestSupport.summary();
    }

    private static void testPeriodicPing() throws Exception {
        Path directory = Files.createTempDirectory(
                "e2ee-controller-keepalive-test-");
        Path identityDirectory = directory.resolve("identities");
        char[] passphrase = "keepalive-passphrase".toCharArray();
        PacketChannel[] pair = LoopbackChannel.pair();
        ChatClientController controller = null;
        try {
            ClientIdentityStore identityStore =
                    new ClientIdentityStore(identityDirectory);
            identityStore.create("alice", passphrase);
            LocalTrustStore trustStore = new LocalTrustStore(
                    directory.resolve("truststore.json"));
            authenticateInBackground(pair[1]);

            CountDownLatch authenticated = new CountDownLatch(1);
            controller = new ChatClientController(
                    identityStore, trustStore, new ClientCryptoService(),
                    (host, port, timeout) -> pair[0], 1L);
            controller.setListener(new ChatClientController.Listener() {
                @Override
                public void onAuthenticated(String username) {
                    authenticated.countDown();
                }
            });
            controller.login("localhost", 8888, "alice", passphrase);
            TestSupport.assertTrue(authenticated.await(
                    10, TimeUnit.SECONDS), "Client chua AUTH_OK");

            Envelope ping = pair[1].receive();
            TestSupport.assertEquals(Opcode.PING, ping.getOp());
            TestSupport.assertEquals("alice", ping.getFrom());
            TestSupport.assertEquals("server", ping.getTo());

            Envelope pong = Envelope.create(Opcode.PONG, "server", "alice");
            pong.setId(ping.getId());
            pair[1].send(pong);
            TestSupport.assertEquals(ChatClientController.State.CONNECTED,
                    controller.getState());
        } finally {
            if (controller != null) {
                controller.close();
            }
            pair[0].close();
            pair[1].close();
            ClientCryptoEngine.wipeMemory(passphrase);
            Files.deleteIfExists(identityDirectory.resolve("alice.private.key"));
            Files.deleteIfExists(identityDirectory.resolve("alice.public.key"));
            Files.deleteIfExists(identityDirectory);
            Files.deleteIfExists(directory.resolve("truststore.json"));
            Files.deleteIfExists(directory);
        }
    }

    private static void authenticateInBackground(PacketChannel serverChannel)
            throws Exception {
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
                            Opcode.AUTH_OK, "server", "alice"));
                }
            } catch (Exception ignored) {
            } finally {
                ClientCryptoEngine.wipeMemory(nonce);
            }
        }, "keepalive-test-server");
        authThread.setDaemon(true);
        authThread.start();
    }
}
