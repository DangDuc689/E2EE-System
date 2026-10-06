package e2ee.common.net;

import e2ee.common.TestSupport;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.contract.PacketChannel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class SocketPacketChannelTest {

    public static void main(String[] args) {
        TestSupport.check("S1 - Gui va nhan co ban qua TCP Loopback", SocketPacketChannelTest::testBasicSendReceive);
        TestSupport.check("S2 - Gui 100 goi lien tuc mot mach", SocketPacketChannelTest::testHundredPacketsSequential);
        TestSupport.check("S3 - Da luong dong thoi gui goi tin (4 luong x 25 goi)", SocketPacketChannelTest::testMultiThreadedSend);
        TestSupport.check("S4 - Ben doi tac dong ket noi nem IOException", SocketPacketChannelTest::testPeerClosure);
        TestSupport.check("S5 - Goi close() nhieu lan an toan", SocketPacketChannelTest::testMultipleClose);
        TestSupport.check("S6 - Gui sau khi da dong nem IOException", SocketPacketChannelTest::testSendAfterClose);

        TestSupport.summary();
    }

    private static void testBasicSendReceive() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int port = serverSocket.getLocalPort();

            AtomicReference<Envelope> receivedRef = new AtomicReference<>();
            Thread serverThread = new Thread(() -> {
                try (PacketChannel channel = new SocketPacketChannel(serverSocket.accept())) {
                    receivedRef.set(channel.receive());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            serverThread.start();

            try (PacketChannel clientChannel = SocketPacketChannel.connect("127.0.0.1", port, 3000)) {
                Envelope env = Envelope.create(Opcode.CHAT_1TO1, "alice", "bob");
                env.put("text", "Xin chao qua TCP Socket!");
                clientChannel.send(env);
            }

            serverThread.join(3000);
            Envelope received = receivedRef.get();
            TestSupport.assertTrue(received != null, "Server phai nhan duoc Envelope");
            TestSupport.assertEquals("alice", received.getFrom());
            TestSupport.assertEquals("bob", received.getTo());
            TestSupport.assertEquals("Xin chao qua TCP Socket!", received.get("text"));
        }
    }

    private static void testHundredPacketsSequential() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int port = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try (PacketChannel channel = new SocketPacketChannel(serverSocket.accept())) {
                    for (int i = 0; i < 100; i++) {
                        Envelope env = channel.receive();
                        TestSupport.assertEquals((long) i, env.get("seq"));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            serverThread.start();

            try (PacketChannel clientChannel = SocketPacketChannel.connect("127.0.0.1", port, 3000)) {
                for (int i = 0; i < 100; i++) {
                    Envelope env = Envelope.create(Opcode.PING, "client", null);
                    env.put("seq", (long) i);
                    clientChannel.send(env);
                }
            }

            serverThread.join(5000);
        }
    }

    private static void testMultiThreadedSend() throws Exception {
        int threadCount = 4;
        int packetsPerThread = 25;
        int totalPackets = threadCount * packetsPerThread;

        try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int port = serverSocket.getLocalPort();

            Set<String> receivedIds = new HashSet<>();
            CountDownLatch serverDone = new CountDownLatch(1);

            Thread serverThread = new Thread(() -> {
                try (PacketChannel channel = new SocketPacketChannel(serverSocket.accept())) {
                    for (int i = 0; i < totalPackets; i++) {
                        Envelope env = channel.receive();
                        synchronized (receivedIds) {
                            receivedIds.add(env.getId());
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            try (PacketChannel clientChannel = SocketPacketChannel.connect("127.0.0.1", port, 3000)) {
                ExecutorService executor = Executors.newFixedThreadPool(threadCount);
                CountDownLatch readyLatch = new CountDownLatch(threadCount);
                CountDownLatch startLatch = new CountDownLatch(1);

                for (int t = 0; t < threadCount; t++) {
                    final int threadId = t;
                    executor.submit(() -> {
                        readyLatch.countDown();
                        try {
                            startLatch.await();
                            for (int i = 0; i < packetsPerThread; i++) {
                                Envelope env = Envelope.create(Opcode.CHAT_1TO1, "user" + threadId, "server");
                                env.put("seq", (long) i);
                                clientChannel.send(env);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }

                readyLatch.await(3, TimeUnit.SECONDS);
                startLatch.countDown(); // Bắn đồng thời tất cả các luồng
                executor.shutdown();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }

            serverDone.await(5, TimeUnit.SECONDS);
            serverThread.join(2000);

            TestSupport.assertEquals(totalPackets, receivedIds.size());
        }
    }

    private static void testPeerClosure() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int port = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try {
                    PacketChannel channel = new SocketPacketChannel(serverSocket.accept());
                    channel.close(); // Đóng ngay lập tức
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            serverThread.start();

            try (PacketChannel clientChannel = SocketPacketChannel.connect("127.0.0.1", port, 3000)) {
                serverThread.join(2000);
                TestSupport.assertThrows(IOException.class, clientChannel::receive);
            }
        }
    }

    private static void testMultipleClose() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int port = serverSocket.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try {
                    serverSocket.accept().close();
                } catch (Exception ignored) {
                }
            });
            serverThread.start();

            PacketChannel clientChannel = SocketPacketChannel.connect("127.0.0.1", port, 3000);
            TestSupport.assertTrue(clientChannel.isOpen(), "Kenh phai mo ban dau");
            clientChannel.close();
            TestSupport.assertTrue(!clientChannel.isOpen(), "Kenh phai dong sau close()");
            clientChannel.close(); // Lan 2 khong duoc nem Exception
            TestSupport.assertTrue(!clientChannel.isOpen(), "Kenh van dong sau close() lan 2");
            serverThread.join(2000);
        }
    }

    private static void testSendAfterClose() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int port = serverSocket.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try {
                    serverSocket.accept().close();
                } catch (Exception ignored) {
                }
            });
            serverThread.start();

            PacketChannel clientChannel = SocketPacketChannel.connect("127.0.0.1", port, 3000);
            clientChannel.close();
            Envelope env = Envelope.create(Opcode.PING, "client", null);
            TestSupport.assertThrows(IOException.class, () -> clientChannel.send(env));
            serverThread.join(2000);
        }
    }
}
