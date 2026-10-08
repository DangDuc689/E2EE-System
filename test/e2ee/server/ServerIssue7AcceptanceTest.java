package e2ee.server;

import e2ee.common.TestSupport;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.net.SocketPacketChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ServerIssue7AcceptanceTest {
    private static final String HOST = "127.0.0.1";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int RESPONSE_TIMEOUT_MS = 5_000;
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
        List<SocketPacketChannel> acceptedClients = new ArrayList<>();

        serverThread.start();
        try {
            waitForServerStartup(serverThread);
            TestSupport.assertEquals(8888, ServerMain.DEFAULT_PORT);

            SocketPacketChannel firstClient = connectClient(RESPONSE_TIMEOUT_MS);
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

            SocketPacketChannel releasedClient = acceptedClients.remove(0);
            releasedClient.close();
            SocketPacketChannel replacement = connectReplacementClient();
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

    private static List<SocketPacketChannel> connectRemainingClientsConcurrently(int count)
            throws Exception {
        ExecutorService connectors = Executors.newFixedThreadPool(count);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<SocketPacketChannel>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                final int clientNumber = index;
                futures.add(connectors.submit(() -> {
                    startGate.await();
                    SocketPacketChannel channel = connectClient(RESPONSE_TIMEOUT_MS);
                    try {
                        assertPong(channel, "concurrent-" + clientNumber);
                        return channel;
                    } catch (Exception e) {
                        channel.close();
                        throw e;
                    }
                }));
            }
            startGate.countDown();

            List<SocketPacketChannel> channels = new ArrayList<>();
            try {
                for (Future<SocketPacketChannel> future : futures) {
                    channels.add(future.get(15, TimeUnit.SECONDS));
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

    private static SocketPacketChannel connectReplacementClient() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int attempt = 0;
        while (System.nanoTime() < deadline) {
            SocketPacketChannel candidate = connectClient(TIMEOUT_TEST_READ_MS);
            boolean accepted = false;
            try {
                Envelope ping = newPing("slot-reuse-" + attempt++);
                candidate.send(ping);
                Envelope response = candidate.receive();
                if (response.getOp() == Opcode.PONG
                        && ping.getId().equals(response.getId())) {
                    accepted = true;
                    return candidate;
                }
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

    private static void verifyNinetySecondTimeout(SocketPacketChannel idleClient)
            throws Exception {
        long startedAt = System.nanoTime();
        TestSupport.assertThrows(IOException.class, idleClient::receive);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        TestSupport.assertTrue(elapsedMs >= MINIMUM_SERVER_TIMEOUT_MS,
                "Client timed out too early: " + elapsedMs + " ms");
        TestSupport.assertTrue(elapsedMs <= MAXIMUM_SERVER_TIMEOUT_MS,
                "Client was not closed by the 90-second server timeout: " + elapsedMs + " ms");
        pass("Timeout 90s (" + elapsedMs + " ms)");
        idleClient.close();
    }

    private static void assertClientsClosed(List<SocketPacketChannel> clients) throws Exception {
        for (SocketPacketChannel client : clients) {
            long startedAt = System.nanoTime();
            TestSupport.assertThrows(IOException.class, client::receive);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            TestSupport.assertTrue(elapsedMs < RESPONSE_TIMEOUT_MS,
                    "Client socket did not close promptly during shutdown");
        }
    }

    private static void verifyServerRestart() throws Exception {
        ServerMain restartedServer = new ServerMain();
        Thread restartedThread = new Thread(restartedServer::run, "issue-7-restarted-server");
        restartedThread.start();
        try {
            waitForServerStartup(restartedThread);
            try (SocketPacketChannel client = connectClient(RESPONSE_TIMEOUT_MS)) {
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

    private static void assertPong(SocketPacketChannel channel, String id) throws Exception {
        Envelope ping = newPing(id);
        channel.send(ping);
        Envelope pong = channel.receive();
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

    private static Envelope newPing(String id) {
        Envelope ping = Envelope.create(Opcode.PING, "issue-7-test", "server");
        ping.setId(id);
        return ping;
    }

    private static void waitForServerStartup(Thread serverThread) throws InterruptedException {
        Thread.sleep(500);
        TestSupport.assertTrue(serverThread.isAlive(), "Server failed to start");
    }

    private static void closeAll(List<SocketPacketChannel> channels) {
        for (SocketPacketChannel channel : channels) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void pass(String message) {
        System.out.println("[PASS] " + message);
    }
}
