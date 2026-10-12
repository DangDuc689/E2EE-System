package e2ee.server;

import e2ee.common.TestSupport;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.net.SocketPacketChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ServerIssue7AcceptanceTest {
    private static final String HOST = "127.0.0.1";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int RESPONSE_TIMEOUT_MS = 30_000;
    private static final int SHUTDOWN_CLOSE_TIMEOUT_MS = 5_000;
    private static final int TIMEOUT_TEST_READ_MS = 100_000;
    private static final long MINIMUM_SERVER_TIMEOUT_MS = 88_000L;
    private static final long MAXIMUM_SERVER_TIMEOUT_MS = 97_000L;

    private ServerIssue7AcceptanceTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("Issue #7 server acceptance", ServerIssue7AcceptanceTest::runAcceptance);
        TestSupport.summary();
    }

    private static void runAcceptance() throws Exception {
        ServerMain server = new ServerMain();
        Thread serverThread = new Thread(server::run, "issue-7-acceptance-server");
        List<AuthenticatedClient> acceptedClients = new ArrayList<>();

        serverThread.start();
        try {
            waitForServerStartup(serverThread);
            TestSupport.assertEquals(8888, ServerMain.DEFAULT_PORT);

            AuthenticatedClient firstClient = connectAuthenticatedClient(
                    RESPONSE_TIMEOUT_MS, "client000");
            acceptedClients.add(firstClient);
            pass("Server listen port 8888");

            assertPong(firstClient, "acceptance-ping");
            pass("PING -> PONG");

            acceptedClients.addAll(connectRemainingClientsConcurrently(
                    ServerMain.MAX_CLIENTS - acceptedClients.size()));
            TestSupport.assertEquals(ServerMain.MAX_CLIENTS, acceptedClients.size());
            pass("Multiple clients connected concurrently");
            pass("50 clients accepted");

            assertClientLimitReached();
            pass("Client 51 rejected: MAX_CLIENTS_REACHED");

            AuthenticatedClient releasedClient = acceptedClients.remove(0);
            releasedClient.close();
            AuthenticatedClient replacement = connectReplacementClient();
            acceptedClients.add(replacement);
            TestSupport.assertEquals(ServerMain.MAX_CLIENTS, acceptedClients.size());
            pass("Slot reused");

            verifyNinetySecondTimeout(replacement);
            acceptedClients.remove(replacement);

            server.shutdown();
            server.shutdown();
            serverThread.join(5_000);
            TestSupport.assertTrue(!serverThread.isAlive(),
                    "Server accept thread must stop after shutdown");
            TestSupport.assertTrue(server.isTerminated(),
                    "Client thread pool must terminate after shutdown");
            assertClientsClosed(acceptedClients);
            pass("Graceful shutdown");

            try (ServerSocket rebound = new ServerSocket(ServerMain.DEFAULT_PORT)) {
                TestSupport.assertEquals(ServerMain.DEFAULT_PORT, rebound.getLocalPort());
            }
            pass("Port 8888 released");
        } finally {
            server.shutdown();
            closeAll(acceptedClients);
            serverThread.join(5_000);
        }

        verifyServerRestart();
    }

    private static List<AuthenticatedClient> connectRemainingClientsConcurrently(int count)
            throws Exception {
        ExecutorService connectors = Executors.newFixedThreadPool(count);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<AuthenticatedClient>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                final int clientNumber = index;
                futures.add(connectors.submit(() -> {
                    startGate.await();
                    String username = String.format("client%03d", clientNumber + 1);
                    AuthenticatedClient client = connectAuthenticatedClient(
                            RESPONSE_TIMEOUT_MS, username);
                    try {
                        assertPong(client, "concurrent-" + clientNumber);
                        return client;
                    } catch (Exception e) {
                        client.close();
                        throw e;
                    }
                }));
            }
            startGate.countDown();

            List<AuthenticatedClient> channels = new ArrayList<>();
            try {
                for (Future<AuthenticatedClient> future : futures) {
                    channels.add(future.get(60, TimeUnit.SECONDS));
                }
                return channels;
            } catch (Exception e) {
                closeAll(channels);
                throw e;
            }
        } finally {
            connectors.shutdownNow();
            connectors.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void assertClientLimitReached() throws Exception {
        try (SocketPacketChannel client51 = connectClient(RESPONSE_TIMEOUT_MS)) {
            Envelope response = client51.receive();
            TestSupport.assertEquals(Opcode.ERROR, response.getOp());
            TestSupport.assertEquals("MAX_CLIENTS_REACHED", response.getString("code"));
        }
    }

    private static AuthenticatedClient connectReplacementClient() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int attempt = 0;
        while (System.nanoTime() < deadline) {
            SocketPacketChannel candidate = connectClient(TIMEOUT_TEST_READ_MS);
            boolean accepted = false;
            try {
                Envelope response = candidate.receive();
                if (response.getOp() == Opcode.AUTH_CHALLENGE) {
                    String username = "reuse" + attempt;
                    authenticate(candidate, username, response);
                    AuthenticatedClient replacement = new AuthenticatedClient(
                            candidate, username);
                    assertPong(replacement, "slot-reuse-" + attempt);
                    accepted = true;
                    return replacement;
                }
                attempt++;
                TestSupport.assertEquals(Opcode.ERROR, response.getOp());
                TestSupport.assertEquals("MAX_CLIENTS_REACHED", response.getString("code"));
            } finally {
                if (!accepted) {
                    candidate.close();
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Released client slot was not reusable within 5 seconds");
    }

    private static void verifyNinetySecondTimeout(AuthenticatedClient idleClient)
            throws Exception {
        long startedAt = System.nanoTime();
        TestSupport.assertThrows(IOException.class, () -> {
            while (true) {
                Envelope pending = idleClient.channel.receive();
                TestSupport.assertEquals(Opcode.PRESENCE, pending.getOp());
            }
        });
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        TestSupport.assertTrue(elapsedMs >= MINIMUM_SERVER_TIMEOUT_MS,
                "Client timed out too early: " + elapsedMs + " ms");
        TestSupport.assertTrue(elapsedMs <= MAXIMUM_SERVER_TIMEOUT_MS,
                "Client was not closed by the 90-second server timeout: " + elapsedMs + " ms");
        pass("Timeout 90s (" + elapsedMs + " ms)");
        idleClient.close();
    }

    private static void assertClientsClosed(List<AuthenticatedClient> clients) throws Exception {
        for (AuthenticatedClient client : clients) {
            long startedAt = System.nanoTime();
            TestSupport.assertThrows(IOException.class, () -> {
                while (true) {
                    client.channel.receive();
                }
            });
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            TestSupport.assertTrue(elapsedMs < SHUTDOWN_CLOSE_TIMEOUT_MS,
                    "Client socket did not close promptly during shutdown");
        }
    }

    private static void verifyServerRestart() throws Exception {
        ServerMain restartedServer = new ServerMain();
        Thread restartedThread = new Thread(restartedServer::run, "issue-7-restarted-server");
        restartedThread.start();
        try {
            waitForServerStartup(restartedThread);
            try (AuthenticatedClient client = connectAuthenticatedClient(
                    RESPONSE_TIMEOUT_MS, "restart_user")) {
                assertPong(client, "ping-after-restart");
            }
            pass("Server restart");
            pass("PING -> PONG after restart");
        } finally {
            restartedServer.shutdown();
            restartedThread.join(5_000);
        }
        TestSupport.assertTrue(!restartedThread.isAlive(),
                "Restarted server accept thread must stop");
        TestSupport.assertTrue(restartedServer.isTerminated(),
                "Restarted server thread pool must terminate");
    }

    private static SocketPacketChannel connectClient(int readTimeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(HOST, ServerMain.DEFAULT_PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(readTimeoutMs);
            return new SocketPacketChannel(socket);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private static AuthenticatedClient connectAuthenticatedClient(
            int readTimeoutMs, String username) throws Exception {
        SocketPacketChannel channel = connectClient(readTimeoutMs);
        try {
            authenticate(channel, username, channel.receive());
            return new AuthenticatedClient(channel, username);
        } catch (Exception e) {
            channel.close();
            throw e;
        }
    }

    private static void authenticate(SocketPacketChannel channel, String username,
            Envelope challenge) throws Exception {
        TestSupport.assertEquals(Opcode.AUTH_CHALLENGE, challenge.getOp());
        String nonceBase64 = challenge.getString("nonce");
        TestSupport.assertTrue(nonceBase64 != null,
                "AUTH_CHALLENGE must contain nonce");
        byte[] nonce = Base64.getDecoder().decode(nonceBase64);
        TestSupport.assertEquals(32, nonce.length);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(nonce);

        Envelope auth = Envelope.create(Opcode.AUTH, username, "server")
                .put("sig", Base64.getEncoder().encodeToString(signer.sign()))
                .put("pubkey", Base64.getEncoder().encodeToString(
                        keyPair.getPublic().getEncoded()));
        channel.send(auth);
        Envelope authOk = channel.receive();
        TestSupport.assertEquals(Opcode.AUTH_OK, authOk.getOp());
    }

    private static void assertPong(AuthenticatedClient client, String id) throws Exception {
        Envelope ping = newPing(id, client.username);
        client.channel.send(ping);
        Envelope pong;
        do {
            pong = client.channel.receive();
        } while (pong.getOp() == Opcode.PRESENCE);
        TestSupport.assertEquals(Opcode.PONG, pong.getOp());
        TestSupport.assertEquals(ping.getId(), pong.getId());
        TestSupport.assertEquals("server", pong.getFrom());
        TestSupport.assertEquals(ping.getFrom(), pong.getTo());
        TestSupport.assertEquals(1, pong.getV());
        TestSupport.assertTrue(pong.getTs() >= ping.getTs(),
                "PONG timestamp must be generated after PING");
        TestSupport.assertTrue(pong.getBody() != null && pong.getBody().isEmpty(),
                "PONG body must be empty");
    }

    private static Envelope newPing(String id, String username) {
        Envelope ping = Envelope.create(Opcode.PING, username, "server");
        ping.setId(id);
        return ping;
    }

    private static void waitForServerStartup(Thread serverThread) throws InterruptedException {
        Thread.sleep(500);
        TestSupport.assertTrue(serverThread.isAlive(), "Server failed to start");
    }

    private static void closeAll(List<AuthenticatedClient> clients) {
        for (AuthenticatedClient client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void pass(String message) {
        System.out.println("[PASS] " + message);
    }

    private static final class AuthenticatedClient implements AutoCloseable {
        private final SocketPacketChannel channel;
        private final String username;

        private AuthenticatedClient(SocketPacketChannel channel, String username) {
            this.channel = channel;
            this.username = username;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
