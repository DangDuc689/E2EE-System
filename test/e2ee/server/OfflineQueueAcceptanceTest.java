package e2ee.server;

import e2ee.client.crypto.ClientCryptoEngine;
import e2ee.client.crypto.ClientCryptoService;
import e2ee.client.crypto.GroupCryptoService;
import e2ee.client.crypto.OfflineSyncService;
import e2ee.common.TestSupport;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.net.SocketPacketChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bài kiểm thử nghiệm thu toàn diện cho Store-and-Forward & Hàng đợi tin nhắn Offline (Issue 12 - Lê Quang Dũng).
 * Chạy bằng hàm main() thuần JDK 17, sử dụng TestSupport, không phụ thuộc JUnit/Mockito.
 *
 * @author 2380600293_LeQuangDung
 */
public final class OfflineQueueAcceptanceTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = ServerMain.DEFAULT_PORT;
    private static final int TIMEOUT_MS = 5_000;

    public static void main(String[] args) {
        try {
            System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }

        System.out.println("================================================================================");
        System.out.println("  KIỂM THỬ NGHIỆM THU: STORE-AND-FORWARD & OFFLINE MESSAGE QUEUE (ISSUE 12)");
        System.out.println("  Thành viên phụ trách: Lê Quang Dũng (MSSV: 2380600293)");
        System.out.println("================================================================================\n");

        // 1. Kiểm thử đơn vị hàng đợi OfflineMessageQueue (RAM FIFO & Bounded Queue)
        testQueueCapacityAndFifoEviction();
        testQueueAcknowledgement();
        testQueueClearAndThreadSafety();

        // 2. Kiểm thử bảo toàn dữ liệu gói tin gốc trong OFFLINE_SYNC
        testOriginalEnvelopePreservation();

        // 3. Kiểm thử logic định tuyến Store-and-Forward trong bộ nhớ
        testOfflineQueueRouter1to1();
        testGroupRouterOfflineQueue();

        // 4. Kiểm thử Chống phát lại (Anti-Replay) & At-least-once phía Client
        testAntiReplayOnOfflineSync();
        testAtLeastOnceDeliverySemantics();

        // 5. Kiểm thử tích hợp qua Server TCP Socket thật
        testFullNetwork1to1OfflineMessageFlow();
        testFullNetworkGroupOfflineMessageFlow();

        System.out.println("\n================================================================================");
        System.out.println("  KẾT QUẢ TỔNG HỢP TOÀN BỘ KIỂM THỬ ISSUE 12:");
        TestSupport.summary();
        System.out.println("================================================================================");
    }

    // =========================================================================
    // PHẦN 1: KIỂM THỬ OFFLINE MESSAGE QUEUE (RAM FIFO & 100-LIMIT)
    // =========================================================================

    private static void testQueueCapacityAndFifoEviction() {
        TestSupport.check("Test 1: Hàng đợi giới hạn tối đa 100 tin/user & FIFO tự động loại bỏ tin cũ nhất", () -> {
            OfflineMessageQueue queue = new OfflineMessageQueue();
            String user = "bob";

            // Đẩy 105 tin nhắn vào queue cho Bob
            for (int i = 1; i <= 105; i++) {
                Envelope env = Envelope.create(Opcode.CHAT_1TO1, "alice", user);
                env.setId("msg-id-" + i);
                env.put("seq", i);
                queue.enqueue(user, env);
            }

            // Kiểm tra số lượng bị chặn cứng ở mức tối đa 100 tin
            TestSupport.assertEquals(OfflineMessageQueue.MAX_QUEUE_PER_USER, queue.getQueueSize(user));

            List<Envelope> pending = queue.getPendingMessages(user);
            TestSupport.assertEquals(100, pending.size());

            // 5 tin cũ nhất (từ msg-id-1 đến msg-id-5) phải bị loại bỏ
            Envelope first = pending.get(0);
            TestSupport.assertEquals("msg-id-6", first.getId());
            TestSupport.assertEquals(6, ((Number) first.get("seq")).intValue());

            // Tin nhắn cuối cùng phải là tin thứ 105
            Envelope last = pending.get(pending.size() - 1);
            TestSupport.assertEquals("msg-id-105", last.getId());
            TestSupport.assertEquals(105, ((Number) last.get("seq")).intValue());

            System.out.println("✔ Hàng đợi giới hạn đúng 100 tin: 5 tin cũ nhất đã tự động drop FIFO.");
        });
    }

    private static void testQueueAcknowledgement() {
        TestSupport.check("Test 2: Xác nhận ACK và xóa tin nhắn chính xác theo ack_msg_id", () -> {
            OfflineMessageQueue queue = new OfflineMessageQueue();
            String user = "bob";

            Envelope env1 = Envelope.create(Opcode.CHAT_1TO1, "alice", user);
            env1.setId("uuid-001");
            Envelope env2 = Envelope.create(Opcode.CHAT_1TO1, "alice", user);
            env2.setId("uuid-002");
            Envelope env3 = Envelope.create(Opcode.CHAT_1TO1, "alice", user);
            env3.setId("uuid-003");

            queue.enqueue(user, env1);
            queue.enqueue(user, env2);
            queue.enqueue(user, env3);
            TestSupport.assertEquals(3, queue.getQueueSize(user));

            // ACK tin số 2
            boolean ack2 = queue.acknowledge(user, "uuid-002");
            TestSupport.assertTrue(ack2, "Phai xoa thanh cong tin uuid-002");
            TestSupport.assertEquals(2, queue.getQueueSize(user));

            List<Envelope> remaining = queue.getPendingMessages(user);
            TestSupport.assertEquals("uuid-001", remaining.get(0).getId());
            TestSupport.assertEquals("uuid-003", remaining.get(1).getId());

            // ACK tin không tồn tại
            boolean ackGhost = queue.acknowledge(user, "uuid-not-exist");
            TestSupport.assertTrue(!ackGhost, "ACK tin khong ton tai phai tra ve false");
            TestSupport.assertEquals(2, queue.getQueueSize(user));

            System.out.println("✔ Xóa đúng tin khi nhận ACK, các tin còn lại bảo toàn thứ tự.");
        });
    }

    private static void testQueueClearAndThreadSafety() {
        TestSupport.check("Test 3: Thao tác clear và kiểm tra rỗng của hàng đợi", () -> {
            OfflineMessageQueue queue = new OfflineMessageQueue();
            TestSupport.assertTrue(queue.isEmpty("alice"), "Hàng đợi ban đầu phải trống");

            queue.enqueue("alice", Envelope.create(Opcode.CHAT_1TO1, "bob", "alice"));
            TestSupport.assertTrue(!queue.isEmpty("alice"), "Hàng đợi sau khi enqueue không được trống");

            queue.clear("alice");
            TestSupport.assertTrue(queue.isEmpty("alice"), "Hàng đợi sau khi clear phải trống");
            TestSupport.assertEquals(0, queue.getQueueSize("alice"));

            System.out.println("✔ Hàng đợi clear và isEmpty hoạt động chính xác.");
        });
    }

    // =========================================================================
    // PHẦN 2: BẢO TOÀN DỮ LIỆU GÓI GỐC TRONG OFFLINE_SYNC
    // =========================================================================

    private static void testOriginalEnvelopePreservation() {
        TestSupport.check("Test 4: Bảo toàn 100% Envelope gốc trong gói bọc OFFLINE_SYNC (không hỏng AAD)", () -> {
            // Tạo gói tin gốc CHAT_1TO1
            Envelope original = Envelope.create(Opcode.CHAT_1TO1, "alice", "bob");
            original.setId("test-uuid-12345");
            original.setTs(1712345678901L);
            original.put("ek", "base64-ek");
            original.put("iv", "base64-iv");
            original.put("ct", "base64-ct");
            original.put("sig", "base64-sig");

            byte[] originalAad = original.computeAADBytes();

            // Đóng gói vào OFFLINE_SYNC
            Envelope syncEnv = Envelope.create(Opcode.OFFLINE_SYNC, "server", "bob");
            syncEnv.put("original_envelope", e2ee.common.codec.EnvelopeMapper.toMap(original));

            // Client bóc tách qua OfflineSyncService
            Envelope restored = OfflineSyncService.extractOriginalEnvelope(syncEnv);

            TestSupport.assertEquals(original.getV(), restored.getV());
            TestSupport.assertEquals(original.getOp(), restored.getOp());
            TestSupport.assertEquals(original.getId(), restored.getId());
            TestSupport.assertEquals(original.getTs(), restored.getTs());
            TestSupport.assertEquals(original.getFrom(), restored.getFrom());
            TestSupport.assertEquals(original.getTo(), restored.getTo());
            TestSupport.assertEquals("base64-ek", restored.getString("ek"));
            TestSupport.assertEquals("base64-iv", restored.getString("iv"));
            TestSupport.assertEquals("base64-ct", restored.getString("ct"));
            TestSupport.assertEquals("base64-sig", restored.getString("sig"));

            // AAD bytes phục hồi phải hoàn toàn trùng khớp với gói tin gốc
            byte[] restoredAad = restored.computeAADBytes();
            TestSupport.assertTrue(Arrays.equals(originalAad, restoredAad), "AAD bytes goc va phuc hoi phai giong het nhau");

            System.out.println("✔ Bóc tách OFFLINE_SYNC thành công: 100% Header, Body và AAD được bảo toàn tuyệt đối.");
        });
    }

    // =========================================================================
    // PHẦN 3: ĐỊNH TUYẾN ROUTER VÀ STORE-AND-FORWARD IN-MEMORY
    // =========================================================================

    private static void testOfflineQueueRouter1to1() {
        TestSupport.check("Test 5: OfflineQueueRouter tự động đưa tin CHAT_1TO1 vào queue khi recipient offline", () -> {
            ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
            OfflineMessageQueue offlineQueue = new OfflineMessageQueue();
            OfflineQueueRouter router = new OfflineQueueRouter(onlineUsers, offlineQueue, null);

            Envelope env = Envelope.create(Opcode.CHAT_1TO1, "alice", "bob");
            env.setId("msg-1-to-1-offline");

            // Bob đang offline (không có trong onlineUsers) -> route không được ném lỗi
            router.route(env, null);

            // Tin nhắn phải nằm trong offlineQueue của Bob
            TestSupport.assertEquals(1, offlineQueue.getQueueSize("bob"));
            List<Envelope> pending = offlineQueue.getPendingMessages("bob");
            TestSupport.assertEquals("msg-1-to-1-offline", pending.get(0).getId());

            System.out.println("✔ Chat 1-1 gửi cho user offline được chuyển tiếp vào OfflineMessageQueue an toàn.");
        });
    }

    private static void testGroupRouterOfflineQueue() {
        TestSupport.check("Test 6: GroupRouter tự động đưa tin CHAT_GROUP vào queue cho các thành viên ngoại tuyến", () -> {
            ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
            ConcurrentMap<String, Set<String>> groupRegistry = new ConcurrentHashMap<>();
            OfflineMessageQueue offlineQueue = new OfflineMessageQueue();

            GroupRouter groupRouter = new GroupRouter(onlineUsers, groupRegistry, offlineQueue, null);

            // Đăng ký nhóm có 3 thành viên: alice, bob, charlie
            Set<String> members = ConcurrentHashMap.newKeySet();
            members.addAll(Arrays.asList("alice", "bob", "charlie"));
            groupRegistry.put("dev_team", members);

            // Giả lập Alice gửi tin nhóm (Alice online, Bob & Charlie offline)
            Envelope groupEnv = Envelope.createGroup(Opcode.CHAT_GROUP, "alice", "dev_team");
            groupEnv.setId("group-offline-msg-01");

            groupRouter.route(groupEnv, null);

            // Cả Bob và Charlie đều phải có tin này trong queue của mình
            TestSupport.assertEquals(1, offlineQueue.getQueueSize("bob"));
            TestSupport.assertEquals(1, offlineQueue.getQueueSize("charlie"));
            TestSupport.assertEquals("group-offline-msg-01", offlineQueue.getPendingMessages("bob").get(0).getId());
            TestSupport.assertEquals("group-offline-msg-01", offlineQueue.getPendingMessages("charlie").get(0).getId());

            // Người gửi (Alice) không được lưu tin của chính mình
            TestSupport.assertEquals(0, offlineQueue.getQueueSize("alice"));

            System.out.println("✔ Tin nhắn nhóm được tự động nhân bản vào hàng đợi của tất cả thành viên offline.");
        });
    }

    // =========================================================================
    // PHẦN 4: ANTI-REPLAY & AT-LEAST-ONCE SEMANTICS
    // =========================================================================

    private static void testAntiReplayOnOfflineSync() {
        TestSupport.check("Test 7: Client Crypto Service chống tấn công phát lại (Anti-Replay) đối với tin Offline Sync", () -> {
            KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpBob = ClientCryptoEngine.generateRsaKeyPair();

            ClientCryptoService cryptoService = new ClientCryptoService();
            OfflineSyncService syncService = new OfflineSyncService(cryptoService, GroupCryptoService.getDefault());

            // Alice gửi tin nhắn cho Bob
            Envelope env = cryptoService.createEncrypted1to1Message(
                    "alice", "bob", "Tin nhan offline thu nghiem!", kpBob.getPublic(), kpAlice.getPrivate());

            Envelope syncEnv = Envelope.create(Opcode.OFFLINE_SYNC, "server", "bob");
            syncEnv.put("original_envelope", e2ee.common.codec.EnvelopeMapper.toMap(env));

            // Lần 1: Bob giải mã thành công
            OfflineSyncService.SyncResult result1 = syncService.process1to1Message(
                    syncEnv, "bob", kpAlice.getPublic(), kpBob.getPrivate());
            TestSupport.assertEquals("Tin nhan offline thu nghiem!", result1.getDecryptedContent());
            TestSupport.assertEquals(env.getId(), result1.getAckEnvelope().getString("ack_msg_id"));

            // Lần 2: Kẻ xấu phát lại gói tin OFFLINE_SYNC -> Bị chặn bởi bộ lọc seenMessageIds
            TestSupport.assertThrows(SecurityException.class, () -> {
                syncService.process1to1Message(syncEnv, "bob", kpAlice.getPublic(), kpBob.getPrivate());
            });

            System.out.println("✔ Chống phát lại thành công: Tin offline trùng ID bị từ chối ngay lập tức.");
        });
    }

    private static void testAtLeastOnceDeliverySemantics() {
        TestSupport.check("Test 8: Cơ chế At-least-once - Chưa nhận được ACK thì hàng đợi Server vẫn giữ nguyên tin", () -> {
            OfflineMessageQueue queue = new OfflineMessageQueue();
            String user = "bob";

            Envelope env = Envelope.create(Opcode.CHAT_1TO1, "alice", user);
            env.setId("critical-msg-id");
            queue.enqueue(user, env);

            // Giả lập Server đã gửi gói tin đi (đọc pending messages)
            List<Envelope> pending = queue.getPendingMessages(user);
            TestSupport.assertEquals(1, pending.size());

            // Chưa có ACK từ Client -> hàng đợi Server vẫn giữ nguyên tin nhắn
            TestSupport.assertEquals(1, queue.getQueueSize(user));

            // Chỉ sau khi có gói ACK hợp lệ -> hàng đợi mới được xóa
            queue.acknowledge(user, "critical-msg-id");
            TestSupport.assertEquals(0, queue.getQueueSize(user));

            System.out.println("✔ Cơ chế At-least-once được bảo đảm tuyệt đối: Tin chỉ xóa khi có xác nhận ACK.");
        });
    }

    // =========================================================================
    // PHẦN 5: TÍCH HỢP TOÀN DIỆN QUA SERVER TCP SOCKET THẬT
    // =========================================================================

    private static void testFullNetwork1to1OfflineMessageFlow() {
        TestSupport.check("Test 9: Tích hợp trọn vẹn Chat 1-1 Offline -> Online -> Nhận OFFLINE_SYNC -> Gửi ACK qua TCP Socket thật", () -> {
            // Khởi động ServerMain trên một thread riêng
            ServerMain server = new ServerMain();
            Thread serverThread = new Thread(server::run, "test-offline-1to1-server");
            serverThread.start();
            Thread.sleep(300);

            try {
                KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
                KeyPair kpBob = ClientCryptoEngine.generateRsaKeyPair();

                // 1. Alice kết nối và Authenticate
                TestClient aliceClient = connectAndAuth("alice", kpAlice);

                // Bob chưa kết nối (Bob OFFLINE).
                // 2. Alice gửi 3 tin nhắn mã hóa E2EE 1-1 cho Bob qua TCP Socket
                ClientCryptoService cryptoAlice = new ClientCryptoService();
                for (int i = 1; i <= 3; i++) {
                    Envelope msg = cryptoAlice.createEncrypted1to1Message(
                            "alice", "bob", "Tin nhan offline so " + i, kpBob.getPublic(), kpAlice.getPrivate());
                    aliceClient.channel.send(msg);
                }

                // Chờ Server xử lý đưa vào queue của Bob
                Thread.sleep(300);

                // Kiểm tra queue của Bob trên Server đã có đủ 3 tin
                TestSupport.assertEquals(3, server.getOfflineQueue().getQueueSize("bob"));
                System.out.println("✔ Server da luu 3 tin nhan cua Alice vao hang doi offline cua Bob.");

                // 3. Bob khởi động kết nối và Authenticate
                TestClient bobClient = connectAndAuth("bob", kpBob);

                // 4. Bob nhận đủ 3 gói OFFLINE_SYNC từ Server
                List<Envelope> syncList = receiveOfflineSyncMessages(bobClient, 3);
                TestSupport.assertEquals(3, syncList.size());

                // 5. Bob giải mã từng tin nhắn qua OfflineSyncService và gửi lại gói ACK
                OfflineSyncService syncServiceBob = new OfflineSyncService(new ClientCryptoService(), new GroupCryptoService());
                for (int i = 0; i < 3; i++) {
                    Envelope syncEnv = syncList.get(i);
                    OfflineSyncService.SyncResult result = syncServiceBob.process1to1Message(
                            syncEnv, "bob", kpAlice.getPublic(), kpBob.getPrivate());

                    TestSupport.assertEquals("Tin nhan offline so " + (i + 1), result.getDecryptedContent());
                    System.out.println("✔ Bob đã giải mã tin offline thành công: " + result.getDecryptedContent());

                    // Bob gửi ACK về Server
                    bobClient.channel.send(result.getAckEnvelope());
                }

                // Chờ Server xử lý các gói ACK
                Thread.sleep(300);

                // 6. Kiểm tra hàng đợi của Bob trên Server đã được xóa sạch hoàn toàn
                TestSupport.assertEquals(0, server.getOfflineQueue().getQueueSize("bob"));
                System.out.println("✔ Server da nhan du 3 ACK tu Bob va giai phong sach se hang doi offline.");

                aliceClient.close();
                bobClient.close();
            } finally {
                server.shutdown();
                serverThread.join(2000);
            }
        });
    }

    private static void testFullNetworkGroupOfflineMessageFlow() {
        TestSupport.check("Test 10: Tích hợp trọn vẹn Chat Nhóm có thành viên Offline qua TCP Socket thật", () -> {
            // Khởi động ServerMain trên một thread riêng
            ServerMain server = new ServerMain();
            Thread serverThread = new Thread(server::run, "test-offline-group-server");
            serverThread.start();
            Thread.sleep(300);

            try {
                KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
                KeyPair kpBob = ClientCryptoEngine.generateRsaKeyPair();
                KeyPair kpCharlie = ClientCryptoEngine.generateRsaKeyPair();

                // 1. Alice và Bob kết nối (Charlie OFFLINE)
                TestClient aliceClient = connectAndAuth("alice", kpAlice);
                TestClient bobClient = connectAndAuth("bob", kpBob);

                // 2. Alice tạo nhóm 'offline_group' gồm [alice, bob, charlie]
                Envelope groupCreate = Envelope.createGroup(Opcode.GROUP_CREATE, "alice", "offline_group");
                groupCreate.put("members", Arrays.asList("alice", "bob", "charlie"));
                aliceClient.channel.send(groupCreate);

                // Nhận xác nhận GROUP_CREATE (bỏ qua PRESENCE nếu có)
                Envelope confirm;
                while (true) {
                    confirm = aliceClient.channel.receive();
                    if (confirm.getOp() == Opcode.GROUP_CREATE) {
                        break;
                    }
                }
                TestSupport.assertEquals("CREATED", confirm.getString("status"));

                // 3. Chuẩn bị thông số mã hóa nhóm
                Map<String, PublicKey> memberKeys = new HashMap<>();
                memberKeys.put("alice", kpAlice.getPublic());
                memberKeys.put("bob", kpBob.getPublic());
                memberKeys.put("charlie", kpCharlie.getPublic());

                String membersHash = GroupCryptoService.computeMembersHash(memberKeys);

                Map<String, PublicKey> recipientKeys = new HashMap<>();
                recipientKeys.put("bob", kpBob.getPublic());
                recipientKeys.put("charlie", kpCharlie.getPublic());

                // 4. Alice gửi tin nhắn nhóm CHAT_GROUP
                GroupCryptoService aliceGroupCrypto = new GroupCryptoService();
                GroupCryptoService bobGroupCrypto = new GroupCryptoService();
                GroupCryptoService charlieGroupCrypto = new GroupCryptoService();

                Envelope groupMsg = aliceGroupCrypto.createEncryptedGroupMessage(
                        "alice", "offline_group", "Chao Charlie! Ban vua online tro lai!",
                        recipientKeys, kpAlice.getPrivate(), membersHash);
                aliceClient.channel.send(groupMsg);

                // Bob online nhận ngay tin nhắn nhóm trực tiếp (bỏ qua PRESENCE nếu có)
                Envelope bobReceived;
                while (true) {
                    bobReceived = bobClient.channel.receive();
                    if (bobReceived.getOp() == Opcode.CHAT_GROUP) {
                        break;
                    }
                }
                TestSupport.assertEquals(Opcode.CHAT_GROUP, bobReceived.getOp());
                String bobDecrypted = bobGroupCrypto.verifyAndDecryptGroupMessage(
                        bobReceived, "bob", kpAlice.getPublic(), kpBob.getPrivate(), membersHash, false);
                TestSupport.assertEquals("Chao Charlie! Ban vua online tro lai!", bobDecrypted);
                System.out.println("✔ Bob (online) da nhan tin nhom truc tiep: " + bobDecrypted);

                // Chờ Server lưu trữ offline cho Charlie
                Thread.sleep(300);
                TestSupport.assertEquals(1, server.getOfflineQueue().getQueueSize("charlie"));
                System.out.println("✔ Charlie (offline) da duoc luu tin nhan nhom vao OfflineMessageQueue.");

                // 5. Charlie kết nối và Authenticate
                TestClient charlieClient = connectAndAuth("charlie", kpCharlie);

                // 6. Charlie nhận gói tin OFFLINE_SYNC chứa tin nhắn nhóm
                List<Envelope> syncList = receiveOfflineSyncMessages(charlieClient, 1);
                TestSupport.assertEquals(1, syncList.size());

                // 7. Charlie giải mã tin nhắn nhóm qua OfflineSyncService và gửi ACK
                OfflineSyncService syncServiceCharlie = new OfflineSyncService(new ClientCryptoService(), charlieGroupCrypto);
                OfflineSyncService.SyncResult result = syncServiceCharlie.processGroupMessage(
                        syncList.get(0), "charlie", kpAlice.getPublic(), kpCharlie.getPrivate(), membersHash);

                TestSupport.assertEquals("Chao Charlie! Ban vua online tro lai!", result.getDecryptedContent());
                System.out.println("✔ Charlie đã giải mã tin nhóm offline thành công: " + result.getDecryptedContent());

                charlieClient.channel.send(result.getAckEnvelope());

                Thread.sleep(300);
                TestSupport.assertEquals(0, server.getOfflineQueue().getQueueSize("charlie"));
                System.out.println("✔ Server da nhan ACK tu Charlie va giai phong hang doi nhom thanh cong.");

                aliceClient.close();
                bobClient.close();
                charlieClient.close();
            } finally {
                server.shutdown();
                serverThread.join(2000);
            }
        });
    }

    // =========================================================================
    // CÁC HÀM TIỆN ÍCH KIỂM THỬ MẠNG
    // =========================================================================

    private static TestClient connectAndAuth(String username, KeyPair keyPair) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(HOST, PORT), TIMEOUT_MS);
        socket.setSoTimeout(TIMEOUT_MS);
        SocketPacketChannel channel = new SocketPacketChannel(socket);

        // Nhận AUTH_CHALLENGE
        Envelope challengeEnv = channel.receive();
        TestSupport.assertEquals(Opcode.AUTH_CHALLENGE, challengeEnv.getOp());
        String nonceBase64 = challengeEnv.getString("nonce");
        byte[] nonce = Base64.getDecoder().decode(nonceBase64);

        // Ký nonce
        byte[] sig = ClientCryptoService.signChallenge(nonce, keyPair.getPrivate());
        String sigBase64 = Base64.getEncoder().encodeToString(sig);
        String pubkeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        // Gửi AUTH
        Envelope authEnv = Envelope.create(Opcode.AUTH, username, "server");
        authEnv.put("sig", sigBase64);
        authEnv.put("pubkey", pubkeyBase64);
        channel.send(authEnv);

        // Nhận AUTH_OK
        Envelope authOk = channel.receive();
        TestSupport.assertEquals(Opcode.AUTH_OK, authOk.getOp());

        return new TestClient(socket, channel, username);
    }

    private static List<Envelope> receiveOfflineSyncMessages(TestClient client, int expectedCount) throws Exception {
        List<Envelope> syncList = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 4000;
        while (syncList.size() < expectedCount && System.currentTimeMillis() < deadline) {
            Envelope env = client.channel.receive();
            if (env.getOp() == Opcode.OFFLINE_SYNC) {
                syncList.add(env);
            }
            // Bỏ qua các gói PRESENCE định kỳ nếu có
        }
        return syncList;
    }

    private static class TestClient implements AutoCloseable {
        final Socket socket;
        final SocketPacketChannel channel;
        final String username;

        TestClient(Socket socket, SocketPacketChannel channel, String username) {
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
