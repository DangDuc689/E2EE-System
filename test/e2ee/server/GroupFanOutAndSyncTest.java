package e2ee.server;

import e2ee.client.crypto.ClientCryptoEngine;
import e2ee.client.crypto.ClientCryptoService;
import e2ee.common.TestSupport;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.net.SocketPacketChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Kiểm thử nghiệm thu các chức năng bổ sung của Chat Nhóm:
 * 1. Fan-out SNAPSHOT tới thành viên online ngay khi tạo nhóm.
 * 2. Đồng bộ SNAPSHOT khi thành viên offline đăng nhập lại sau.
 * 3. Bảo toàn nhóm qua khởi động lại server.
 * 4. Không gửi snapshot trùng lặp cho người tạo (người tạo chỉ nhận CREATED).
 * 5. Báo lỗi an toàn khi kho lưu trữ thất bại, không xác nhận thành công giả.
 *
 * Chạy trên cổng TCP độc lập tự động cấp phát để không xung đột cổng 8888.
 */
public final class GroupFanOutAndSyncTest {
    private static final String HOST = "127.0.0.1";
    private static final int TIMEOUT_MS = 6_000;

    public static void main(String[] args) {
        TestSupport.check("GFOS-1 - Fan-out SNAPSHOT toi thanh vien online va CREATED toi nguoi tao",
                GroupFanOutAndSyncTest::testOnlineFanOutAndCreatorConfirmation);
        TestSupport.check("GFOS-2 - Dong bo SNAPSHOT khi thanh vien offline dang nhap lai",
                GroupFanOutAndSyncTest::testOfflineMemberSyncOnLogin);
        TestSupport.check("GFOS-3 - Khoi dong lai Server giu nguyen nhom va dong bo lai",
                GroupFanOutAndSyncTest::testServerRestartPreservesGroups);
        TestSupport.check("GFOS-4 - Loi luu tru ServerGroupStore phai bao ERROR va khong gui CREATED",
                GroupFanOutAndSyncTest::testStoreFailureReportsError);

        TestSupport.summary();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void testOnlineFanOutAndCreatorConfirmation() throws Exception {
        int port = findFreePort();
        Path tempDir = Files.createTempDirectory("gfos-server-1-");
        try {
            Path storeFile = tempDir.resolve("server_groups.json");
            ConcurrentMap<String, Set<String>> registry = new ConcurrentHashMap<>();
            ServerGroupStore store = new ServerGroupStore(registry, storeFile);
            ServerMain server = new ServerMain(port, store);

            Thread serverThread = new Thread(server::run, "test-gfos-server-1");
            serverThread.start();
            Thread.sleep(300);

            try {
                KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
                KeyPair kpBob = ClientCryptoEngine.generateRsaKeyPair();

                TestSocketClient alice = connectAndAuth(port, "alice", kpAlice);
                TestSocketClient bob = connectAndAuth(port, "bob", kpBob);

                // Drain initial presence packets
                drainPresence(alice);
                drainPresence(bob);

                // Alice tạo nhóm 'g-team' gồm alice và bob
                Envelope createEnv = Envelope.createGroup(Opcode.GROUP_CREATE, "alice", "g-team");
                createEnv.put("groupId", "g-team");
                createEnv.put("groupName", "Nhom Chien Binh");
                createEnv.put("members", Arrays.asList("alice", "bob"));
                alice.channel.send(createEnv);

                // 1. Alice (người tạo) phải nhận CREATED
                Envelope aliceResp = alice.channel.receive();
                TestSupport.assertEquals(Opcode.GROUP_CREATE, aliceResp.getOp());
                TestSupport.assertEquals("CREATED", aliceResp.getString("status"));
                TestSupport.assertEquals("g-team", aliceResp.getGroupId());
                TestSupport.assertEquals("Nhom Chien Binh", aliceResp.getString("groupName"));
                TestSupport.assertEquals("alice", aliceResp.getTo());

                // Alice KHÔNG được nhận thêm gói SNAPSHOT trùng lặp
                TestSupport.assertTrue(!hasIncomingPacket(alice, 300),
                        "Alice da nhan CREATED thi khong duoc nhan lap SNAPSHOT");

                // 2. Bob (thành viên online) phải nhận ngay SNAPSHOT
                Envelope bobSnapshot = bob.channel.receive();
                TestSupport.assertEquals(Opcode.GROUP_CREATE, bobSnapshot.getOp());
                TestSupport.assertEquals("SNAPSHOT", bobSnapshot.getString("status"));
                TestSupport.assertEquals("g-team", bobSnapshot.getGroupId());
                TestSupport.assertEquals("Nhom Chien Binh", bobSnapshot.getString("groupName"));
                TestSupport.assertEquals("bob", bobSnapshot.getTo());
                Object mems = bobSnapshot.get("members");
                TestSupport.assertTrue(mems instanceof List<?> list && list.contains("alice") && list.contains("bob"),
                        "Bob phai nhan snapshot co day du thanh vien");

                alice.close();
                bob.close();
            } finally {
                server.shutdown();
                serverThread.join(3_000);
            }
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static void testOfflineMemberSyncOnLogin() throws Exception {
        int port = findFreePort();
        Path tempDir = Files.createTempDirectory("gfos-server-2-");
        try {
            Path storeFile = tempDir.resolve("server_groups.json");
            ConcurrentMap<String, Set<String>> registry = new ConcurrentHashMap<>();
            ServerGroupStore store = new ServerGroupStore(registry, storeFile);
            ServerMain server = new ServerMain(port, store);

            Thread serverThread = new Thread(server::run, "test-gfos-server-2");
            serverThread.start();
            Thread.sleep(300);

            try {
                KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
                KeyPair kpCharlie = ClientCryptoEngine.generateRsaKeyPair();

                // Charlie dang offline. Alice online tao nhom chua Charlie.
                TestSocketClient alice = connectAndAuth(port, "alice", kpAlice);
                drainPresence(alice);

                Envelope createEnv = Envelope.createGroup(Opcode.GROUP_CREATE, "alice", "g-off");
                createEnv.put("groupId", "g-off");
                createEnv.put("groupName", "Nhom Offline Test");
                createEnv.put("members", Arrays.asList("alice", "charlie"));
                alice.channel.send(createEnv);

                Envelope aliceResp = alice.channel.receive();
                TestSupport.assertEquals("CREATED", aliceResp.getString("status"));
                alice.close();

                // Charlie gio moi ket noi va AUTH
                TestSocketClient charlie = connectAndAuth(port, "charlie", kpCharlie);

                // Charlie phai nhan duoc SNAPSHOT cua nhom g-off sau khi AUTH thanh cong
                boolean receivedSnapshot = false;
                long deadline = System.currentTimeMillis() + 4_000;
                while (System.currentTimeMillis() < deadline) {
                    Envelope env = charlie.channel.receive();
                    if (env.getOp() == Opcode.GROUP_CREATE && "SNAPSHOT".equals(env.getString("status"))) {
                        TestSupport.assertEquals("g-off", env.getGroupId());
                        TestSupport.assertEquals("Nhom Offline Test", env.getString("groupName"));
                        TestSupport.assertEquals("charlie", env.getTo());
                        receivedSnapshot = true;
                        break;
                    }
                }
                TestSupport.assertTrue(receivedSnapshot, "Charlie offline phai nhan SNAPSHOT khi login lai");

                charlie.close();
            } finally {
                server.shutdown();
                serverThread.join(3_000);
            }
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static void testServerRestartPreservesGroups() throws Exception {
        int port = findFreePort();
        Path tempDir = Files.createTempDirectory("gfos-server-3-");
        try {
            Path storeFile = tempDir.resolve("server_groups.json");
            KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpBob = ClientCryptoEngine.generateRsaKeyPair();

            // 1. Chay Server lan 1
            ConcurrentMap<String, Set<String>> registry1 = new ConcurrentHashMap<>();
            ServerGroupStore store1 = new ServerGroupStore(registry1, storeFile);
            ServerMain server1 = new ServerMain(port, store1);
            Thread thread1 = new Thread(server1::run);
            thread1.start();
            Thread.sleep(300);

            try {
                TestSocketClient alice = connectAndAuth(port, "alice", kpAlice);
                drainPresence(alice);

                Envelope createEnv = Envelope.createGroup(Opcode.GROUP_CREATE, "alice", "g-persist");
                createEnv.put("groupId", "g-persist");
                createEnv.put("groupName", "Nhom Ben Vung");
                createEnv.put("members", Arrays.asList("alice", "bob"));
                alice.channel.send(createEnv);

                Envelope confirm = alice.channel.receive();
                TestSupport.assertEquals("CREATED", confirm.getString("status"));
                alice.close();
            } finally {
                server1.shutdown();
                thread1.join(3_000);
            }

            // 2. Khoi dong lai Server voi instance ServerMain moi dung chung storeFile
            ConcurrentMap<String, Set<String>> registry2 = new ConcurrentHashMap<>();
            ServerGroupStore store2 = new ServerGroupStore(registry2, storeFile);
            ServerMain server2 = new ServerMain(port, store2);
            Thread thread2 = new Thread(server2::run);
            thread2.start();
            Thread.sleep(300);

            try {
                // Bob dang nhap vao server moi va phai nhan duoc SNAPSHOT cua g-persist
                TestSocketClient bob = connectAndAuth(port, "bob", kpBob);

                boolean receivedSnapshot = false;
                long deadline = System.currentTimeMillis() + 4_000;
                while (System.currentTimeMillis() < deadline) {
                    Envelope env = bob.channel.receive();
                    if (env.getOp() == Opcode.GROUP_CREATE && "SNAPSHOT".equals(env.getString("status"))) {
                        TestSupport.assertEquals("g-persist", env.getGroupId());
                        TestSupport.assertEquals("Nhom Ben Vung", env.getString("groupName"));
                        receivedSnapshot = true;
                        break;
                    }
                }
                TestSupport.assertTrue(receivedSnapshot, "Bob phai nhan snapshot nhom ben vung sau khi server restart");
                bob.close();
            } finally {
                server2.shutdown();
                thread2.join(3_000);
            }
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static void testStoreFailureReportsError() throws Exception {
        int port = findFreePort();
        Path tempDir = Files.createTempDirectory("gfos-err-");
        try {
            Path blockingFile = tempDir.resolve("bad_dir");
            Files.writeString(blockingFile, "block");
            Path unwriteablePath = blockingFile.resolve("groups.json");

            ConcurrentMap<String, Set<String>> groupRegistry = new ConcurrentHashMap<>();
            ServerGroupStore badStore = new ServerGroupStore(groupRegistry, unwriteablePath);
            ServerMain server = new ServerMain(port, badStore);

            Thread serverThread = new Thread(server::run, "test-gfos-err");
            serverThread.start();
            Thread.sleep(300);

            try {
                KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
                TestSocketClient alice = connectAndAuth(port, "alice", kpAlice);
                drainPresence(alice);

                Envelope req = Envelope.createGroup(Opcode.GROUP_CREATE, "alice", "g-fail");
                req.put("groupId", "g-fail");
                req.put("groupName", "Nhom Loi Ghi");
                req.put("members", Arrays.asList("alice", "bob"));
                alice.channel.send(req);

                Envelope resp = alice.channel.receive();
                TestSupport.assertEquals(Opcode.ERROR, resp.getOp());
                TestSupport.assertEquals("STORE_ERROR", resp.getString("code"));
                TestSupport.assertTrue(!groupRegistry.containsKey("g-fail"),
                        "Khi ghi store that bai, groupRegistry khong duoc chua nhom");
                alice.close();
            } finally {
                server.shutdown();
                serverThread.join(3_000);
            }
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static TestSocketClient connectAndAuth(int port, String username, KeyPair keyPair) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(HOST, port), TIMEOUT_MS);
        socket.setSoTimeout(TIMEOUT_MS);
        SocketPacketChannel channel = new SocketPacketChannel(socket);

        Envelope challenge = channel.receive();
        TestSupport.assertEquals(Opcode.AUTH_CHALLENGE, challenge.getOp());
        byte[] nonce = Base64.getDecoder().decode(challenge.getString("nonce"));

        byte[] sig = ClientCryptoService.signChallenge(nonce, keyPair.getPrivate());
        String sigBase64 = Base64.getEncoder().encodeToString(sig);
        String pubkeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        Envelope auth = Envelope.create(Opcode.AUTH, username, "server");
        auth.put("sig", sigBase64);
        auth.put("pubkey", pubkeyBase64);
        channel.send(auth);

        Envelope authOk = channel.receive();
        TestSupport.assertEquals(Opcode.AUTH_OK, authOk.getOp());

        return new TestSocketClient(socket, channel, username);
    }

    private static void drainPresence(TestSocketClient client) {
        try {
            while (true) {
                client.socket.setSoTimeout(200);
                Envelope env = client.channel.receive();
                if (env.getOp() != Opcode.PRESENCE) {
                    break;
                }
            }
        } catch (IOException ignored) {
        } finally {
            try {
                client.socket.setSoTimeout(TIMEOUT_MS);
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean hasIncomingPacket(TestSocketClient client, int waitMs) {
        try {
            client.socket.setSoTimeout(waitMs);
            client.channel.receive();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                client.socket.setSoTimeout(TIMEOUT_MS);
            } catch (Exception ignored) {
            }
        }
    }

    private static void deleteDirectory(Path dir) {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        }
    }

    private static class TestSocketClient implements AutoCloseable {
        final Socket socket;
        final SocketPacketChannel channel;
        final String username;

        TestSocketClient(Socket socket, SocketPacketChannel channel, String username) {
            this.socket = socket;
            this.channel = channel;
            this.username = username;
        }

        @Override
        public void close() throws IOException {
            channel.close();
            socket.close();
        }
    }
}
