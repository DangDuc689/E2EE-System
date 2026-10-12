package e2ee.client.crypto;

import e2ee.common.TestSupport;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.net.SocketPacketChannel;
import e2ee.server.GroupRouter;
import e2ee.server.ServerMain;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bài kiểm thử nghiệm thu toàn diện cho Group Chat E2EE & Server Group Routing (Issue 11 - Lê Quang Dũng).
 * Chạy bằng hàm main() thuần JDK 17, không phụ thuộc JUnit/Mockito.
 */
public final class GroupChatAcceptanceTest {

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
        System.out.println("  KIỂM THỬ NGHIỆM THU: E2EE GROUP CHAT & GHOST MEMBER SECURITY (ISSUE 11)");
        System.out.println("  Thành viên phụ trách: Lê Quang Dũng (MSSV: 2380600293)");
        System.out.println("================================================================================\n");

        // Các bài test Bước 1: Chuẩn hóa & members_hash
        testCleanFingerprint();
        testMembersHashDeterminismAndOrder();
        testGhostMemberDetectionByHashMismatch();
        testKeysCanonicalOrdering();

        // Các bài test Bước 2: Mã hóa & Giải mã đa người nhận E2EE
        testEndToEndGroupEncryptionAndDecryption();
        testOutsiderCannotDecryptGroupMessage();
        testGhostMemberBlockedAtDecryption();
        testTamperCiphertextFailsSignatureVerification();
        testTamperHeaderFailsSignatureVerification();
        testGroupAntiReplay();
        testTamperedGroupMessageDoesNotPolluteSeenIds();

        // Các bài test Bước 3: Server GroupRouter & Quản lý nhóm
        testGroupRouterRegistry();

        // Các bài test Bước 4: Tích hợp mạng Socket thật với ServerMain
        testFullNetworkGroupChatOverSockets();

        System.out.println("\n================================================================================");
        System.out.println("  KẾT QUẢ TỔNG HỢP TOÀN BỘ KIỂM THỬ ISSUE 11:");
        TestSupport.summary();
        System.out.println("================================================================================");
    }

    // =========================================================================
    // BƯỚC 1: CANONICAL KEYS & MEMBERS_HASH CHỐNG GHOST MEMBER
    // =========================================================================

    private static void testCleanFingerprint() {
        TestSupport.check("Test 1: Chuẩn hóa fingerprint (loại bỏ dấu cách, in hoa)", () -> {
            String rawFp = "4A8F C291 9B02 1234 ";
            String cleaned = GroupCryptoService.cleanFingerprint(rawFp);
            TestSupport.assertEquals("4A8FC2919B021234", cleaned);
            System.out.println("✔ Fingerprint sau chuẩn hóa: " + cleaned);
        });
    }

    private static void testMembersHashDeterminismAndOrder() {
        TestSupport.check("Test 2: Tính members_hash bảo toàn thứ tự alphabet của username", () -> {
            Map<String, String> fpsUnordered = new LinkedHashMap<>();
            fpsUnordered.put("charlie", "3333 3333");
            fpsUnordered.put("alice", "1111 1111");
            fpsUnordered.put("bob", "2222 2222");

            String hash1 = GroupCryptoService.computeMembersHashFromFingerprints(fpsUnordered);

            Map<String, String> fpsOrdered = new LinkedHashMap<>();
            fpsOrdered.put("alice", "1111 1111");
            fpsOrdered.put("bob", "2222 2222");
            fpsOrdered.put("charlie", "3333 3333");

            String hash2 = GroupCryptoService.computeMembersHashFromFingerprints(fpsOrdered);

            TestSupport.assertEquals(hash1, hash2);
            TestSupport.assertTrue(hash1 != null && !hash1.isBlank(), "members_hash khong duoc rong");
            System.out.println("✔ members_hash của nhóm (alice, bob, charlie): " + hash1);
        });
    }

    private static void testGhostMemberDetectionByHashMismatch() {
        TestSupport.check("Test 3: Phát hiện kẻ lạ hoặc thay đổi thành viên (Ghost Member Attack)", () -> {
            KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpBob = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpCharlie = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpEve = ClientCryptoEngine.generateRsaKeyPair();

            Map<String, PublicKey> legitimateGroup = new HashMap<>();
            legitimateGroup.put("alice", kpAlice.getPublic());
            legitimateGroup.put("bob", kpBob.getPublic());
            legitimateGroup.put("charlie", kpCharlie.getPublic());

            String legitimateHash = GroupCryptoService.computeMembersHash(legitimateGroup);

            // Trường hợp 1: Thêm thành viên lạ Eve
            Map<String, PublicKey> tamperedGroupWithEve = new HashMap<>(legitimateGroup);
            tamperedGroupWithEve.put("eve", kpEve.getPublic());
            String tamperedHashWithEve = GroupCryptoService.computeMembersHash(tamperedGroupWithEve);

            TestSupport.assertTrue(!legitimateHash.equals(tamperedHashWithEve),
                    "members_hash phai khac khi co them thanh vien la!");

            // Trường hợp 2: Tráo khóa của Bob
            Map<String, PublicKey> tamperedGroupBobKey = new HashMap<>(legitimateGroup);
            tamperedGroupBobKey.put("bob", kpEve.getPublic());
            String tamperedHashBobKey = GroupCryptoService.computeMembersHash(tamperedGroupBobKey);

            TestSupport.assertTrue(!legitimateHash.equals(tamperedHashBobKey),
                    "members_hash phai khac khi khoa cua thanh vien bi thay doi!");

            System.out.println("✔ members_hash hợp lệ   : " + legitimateHash);
            System.out.println("✔ members_hash có Eve   : " + tamperedHashWithEve);
            System.out.println("✔ members_hash tráo Bob : " + tamperedHashBobKey);
        });
    }

    private static void testKeysCanonicalOrdering() {
        TestSupport.check("Test 4: Chuẩn hóa keysCanonical sắp xếp theo alphabet và định dạng key=val", () -> {
            Map<String, String> wrappedKeys = new LinkedHashMap<>();
            wrappedKeys.put("charlie", "key_charlie_base64");
            wrappedKeys.put("alice", "key_alice_base64");
            wrappedKeys.put("bob", "key_bob_base64");

            String canonical = GroupCryptoService.buildKeysCanonical(wrappedKeys);
            String expected = "alice=key_alice_base64,bob=key_bob_base64,charlie=key_charlie_base64";

            TestSupport.assertEquals(expected, canonical);
            System.out.println("✔ keysCanonical: " + canonical);
        });
    }

    // =========================================================================
    // BƯỚC 2: MÃ HÓA & GIẢI MÃ ĐA NGƯỜI NHẬN E2EE
    // =========================================================================

    private static void testEndToEndGroupEncryptionAndDecryption() {
        TestSupport.check("Test 5: Chu trình E2EE Nhóm hoàn chỉnh (Alice gửi, Bob và Charlie cùng giải mã)", () -> {
            KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpBob = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpCharlie = ClientCryptoEngine.generateRsaKeyPair();

            // Danh sách thành viên nhóm
            Map<String, PublicKey> groupRoster = new HashMap<>();
            groupRoster.put("alice", kpAlice.getPublic());
            groupRoster.put("bob", kpBob.getPublic());
            groupRoster.put("charlie", kpCharlie.getPublic());

            String membersHash = GroupCryptoService.computeMembersHash(groupRoster);

            // Alice gửi tin cho Bob và Charlie
            Map<String, PublicKey> recipients = new HashMap<>();
            recipients.put("bob", kpBob.getPublic());
            recipients.put("charlie", kpCharlie.getPublic());

            String originalText = "Xin chào các bạn trong nhóm Mạng máy tính! Tin nhắn E2EE nhóm chuẩn 2026: 123456 @#$!";
            GroupCryptoService aliceService = new GroupCryptoService();
            Envelope groupEnv = aliceService.createEncryptedGroupMessage(
                    "alice", "group_ktpm", originalText, recipients, kpAlice.getPrivate(), membersHash);

            TestSupport.assertEquals("alice", groupEnv.getFrom());
            TestSupport.assertEquals("group_ktpm", groupEnv.getGroupId());

            // Bob giải mã
            GroupCryptoService bobService = new GroupCryptoService();
            String decryptedBob = bobService.verifyAndDecryptGroupMessage(
                    groupEnv, "bob", kpAlice.getPublic(), kpBob.getPrivate(), membersHash, false);
            TestSupport.assertEquals(originalText, decryptedBob);

            // Charlie giải mã
            GroupCryptoService charlieService = new GroupCryptoService();
            String decryptedCharlie = charlieService.verifyAndDecryptGroupMessage(
                    groupEnv, "charlie", kpAlice.getPublic(), kpCharlie.getPrivate(), membersHash, false);
            TestSupport.assertEquals(originalText, decryptedCharlie);

            System.out.println("✔ Bob giải mã thành công: " + decryptedBob);
            System.out.println("✔ Charlie giải mã thành công: " + decryptedCharlie);
        });
    }

    private static void testOutsiderCannotDecryptGroupMessage() {
        TestSupport.check("Test 6: Kẻ lạ ngoài nhóm (David) không có khóa bọc -> Bị từ chối giải mã", () -> {
            KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpBob = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpDavid = ClientCryptoEngine.generateRsaKeyPair();

            Map<String, PublicKey> groupRoster = new HashMap<>();
            groupRoster.put("alice", kpAlice.getPublic());
            groupRoster.put("bob", kpBob.getPublic());
            String membersHash = GroupCryptoService.computeMembersHash(groupRoster);

            Map<String, PublicKey> recipients = new HashMap<>();
            recipients.put("bob", kpBob.getPublic());

            GroupCryptoService service = new GroupCryptoService();
            Envelope groupEnv = service.createEncryptedGroupMessage(
                    "alice", "group_ktpm", "Bi mat nhom", recipients, kpAlice.getPrivate(), membersHash);

            // David cố tình giải mã gói tin
            TestSupport.assertThrows(SecurityException.class, () -> {
                service.verifyAndDecryptGroupMessage(
                        groupEnv, "david", kpAlice.getPublic(), kpDavid.getPrivate(), membersHash, false);
            });
            System.out.println("✔ David bị từ chối giải mã do không có khóa bọc");
        });
    }

    private static void testGhostMemberBlockedAtDecryption() {
        TestSupport.check("Test 7: Nhận gói tin có members_hash sai lệch -> Chặn ngay lập tức", () -> {
            KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpBob = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpEve = ClientCryptoEngine.generateRsaKeyPair();

            Map<String, PublicKey> legitimateRoster = new HashMap<>();
            legitimateRoster.put("alice", kpAlice.getPublic());
            legitimateRoster.put("bob", kpBob.getPublic());
            String legitimateHash = GroupCryptoService.computeMembersHash(legitimateRoster);

            // Giả lập kẻ xấu can thiệp gửi kèm members_hash của nhóm có Eve
            Map<String, PublicKey> rogueRoster = new HashMap<>(legitimateRoster);
            rogueRoster.put("eve", kpEve.getPublic());
            String rogueHash = GroupCryptoService.computeMembersHash(rogueRoster);

            Map<String, PublicKey> recipients = new HashMap<>();
            recipients.put("bob", kpBob.getPublic());

            GroupCryptoService service = new GroupCryptoService();
            Envelope groupEnv = service.createEncryptedGroupMessage(
                    "alice", "group_ktpm", "Tin nhan co ma", recipients, kpAlice.getPrivate(), rogueHash);

            // Bob đối chiếu với legitimateHash của mình -> Phải ném ngoại lệ bảo mật
            SecurityException ex = TestSupport.assertThrows(SecurityException.class, () -> {
                service.verifyAndDecryptGroupMessage(
                        groupEnv, "bob", kpAlice.getPublic(), kpBob.getPrivate(), legitimateHash, false);
            });
            TestSupport.assertTrue(ex.getMessage().contains("Ghost Member"), "Thong bao phai chua Ghost Member");
            System.out.println("✔ Bắt lỗi thành công: " + ex.getMessage());
        });
    }

    private static void testTamperCiphertextFailsSignatureVerification() {
        TestSupport.check("Test 8: Can thiệp sửa 1 byte Ciphertext -> Chữ ký bị từ chối TRƯỚC KHI chạm AES", () -> {
            KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpBob = ClientCryptoEngine.generateRsaKeyPair();

            Map<String, PublicKey> groupRoster = new HashMap<>();
            groupRoster.put("alice", kpAlice.getPublic());
            groupRoster.put("bob", kpBob.getPublic());
            String membersHash = GroupCryptoService.computeMembersHash(groupRoster);

            Map<String, PublicKey> recipients = new HashMap<>();
            recipients.put("bob", kpBob.getPublic());

            GroupCryptoService service = new GroupCryptoService();
            Envelope groupEnv = service.createEncryptedGroupMessage(
                    "alice", "group_ktpm", "Noi dung can bao ve", recipients, kpAlice.getPrivate(), membersHash);

            // Can thiệp sửa ký tự đầu của ct
            String originalCt = groupEnv.getString("ct");
            char tamperedChar = originalCt.charAt(0) == 'A' ? 'B' : 'A';
            groupEnv.put("ct", tamperedChar + originalCt.substring(1));

            SecurityException ex = TestSupport.assertThrows(SecurityException.class, () -> {
                service.verifyAndDecryptGroupMessage(
                        groupEnv, "bob", kpAlice.getPublic(), kpBob.getPrivate(), membersHash, false);
            });
            TestSupport.assertTrue(ex.getMessage().contains("Chu ky so"), "Loi phai bao ve chu ky so");
            System.out.println("✔ Chữ ký nhóm từ chối ciphertext bị can thiệp");
        });
    }

    private static void testTamperHeaderFailsSignatureVerification() {
        TestSupport.check("Test 9: Can thiệp Header (thay đổi groupId hoặc from) -> Chữ ký từ chối ngay", () -> {
            KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpBob = ClientCryptoEngine.generateRsaKeyPair();

            Map<String, PublicKey> groupRoster = new HashMap<>();
            groupRoster.put("alice", kpAlice.getPublic());
            groupRoster.put("bob", kpBob.getPublic());
            String membersHash = GroupCryptoService.computeMembersHash(groupRoster);

            Map<String, PublicKey> recipients = new HashMap<>();
            recipients.put("bob", kpBob.getPublic());

            GroupCryptoService service = new GroupCryptoService();
            Envelope groupEnv = service.createEncryptedGroupMessage(
                    "alice", "group_ktpm", "Noi dung", recipients, kpAlice.getPrivate(), membersHash);

            // Đổi groupId sang group_hack
            groupEnv.setGroupId("group_hack");

            TestSupport.assertThrows(SecurityException.class, () -> {
                service.verifyAndDecryptGroupMessage(
                        groupEnv, "bob", kpAlice.getPublic(), kpBob.getPrivate(), membersHash, false);
            });
            System.out.println("✔ Chữ ký nhóm từ chối khi groupId bị tráo đổi");
        });
    }

    private static void testGroupAntiReplay() {
        TestSupport.check("Test 10: Chống phát lại (Anti-Replay) từ chối tin nhắn nhóm phát lại lần 2", () -> {
            KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpBob = ClientCryptoEngine.generateRsaKeyPair();

            Map<String, PublicKey> groupRoster = new HashMap<>();
            groupRoster.put("alice", kpAlice.getPublic());
            groupRoster.put("bob", kpBob.getPublic());
            String membersHash = GroupCryptoService.computeMembersHash(groupRoster);

            Map<String, PublicKey> recipients = new HashMap<>();
            recipients.put("bob", kpBob.getPublic());

            GroupCryptoService service = new GroupCryptoService();
            Envelope groupEnv = service.createEncryptedGroupMessage(
                    "alice", "group_ktpm", "Tin duy nhat", recipients, kpAlice.getPrivate(), membersHash);

            // Lần 1: Thành công
            String dec = service.verifyAndDecryptGroupMessage(
                    groupEnv, "bob", kpAlice.getPublic(), kpBob.getPrivate(), membersHash, false);
            TestSupport.assertEquals("Tin duy nhat", dec);

            // Lần 2: Bị chặn do trùng lặp ID
            SecurityException ex = TestSupport.assertThrows(SecurityException.class, () -> {
                service.verifyAndDecryptGroupMessage(
                        groupEnv, "bob", kpAlice.getPublic(), kpBob.getPrivate(), membersHash, false);
            });
            TestSupport.assertTrue(ex.getMessage().contains("Replay Attack"), "Phai bao replay attack");
            System.out.println("✔ Chống phát lại thành công: " + ex.getMessage());
        });
    }

    private static void testTamperedGroupMessageDoesNotPolluteSeenIds() {
        TestSupport.check("Test 11: Gói tin nhóm hỏng chữ ký KHÔNG được ghi vào seenMessageIds (Chống đốt ID)", () -> {
            KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair kpBob = ClientCryptoEngine.generateRsaKeyPair();

            Map<String, PublicKey> groupRoster = new HashMap<>();
            groupRoster.put("alice", kpAlice.getPublic());
            groupRoster.put("bob", kpBob.getPublic());
            String membersHash = GroupCryptoService.computeMembersHash(groupRoster);

            Map<String, PublicKey> recipients = new HashMap<>();
            recipients.put("bob", kpBob.getPublic());

            GroupCryptoService service = new GroupCryptoService();
            Envelope groupEnv = service.createEncryptedGroupMessage(
                    "alice", "group_ktpm", "Tin chan that", recipients, kpAlice.getPrivate(), membersHash);

            String msgId = groupEnv.getId();

            // Phá chữ ký
            groupEnv.put("sig", "AA==");

            TestSupport.assertThrows(SecurityException.class, () -> {
                service.verifyAndDecryptGroupMessage(
                        groupEnv, "bob", kpAlice.getPublic(), kpBob.getPrivate(), membersHash, false);
            });

            // Kiểm tra ID không được lưu
            TestSupport.assertTrue(!service.isMessageSeen(msgId), "ID khong duoc luu neu chu ky sai");
            System.out.println("✔ Bất biến an toàn: ID gói tin hỏng chữ ký không bị đốt");
        });
    }

    // =========================================================================
    // BƯỚC 3: SERVER GROUP ROUTER & QUẢN LÝ NHÓM
    // =========================================================================

    private static void testGroupRouterRegistry() {
        TestSupport.check("Test 12: Quản lý danh bạ nhóm trong GroupRouter (groupRegistry)", () -> {
            ConcurrentMap<String, Set<String>> registry = new ConcurrentHashMap<>();
            GroupRouter router = new GroupRouter(new ConcurrentHashMap<>(), registry, null);

            // Đăng ký nhóm group_dev với alice, bob, charlie
            Set<String> members = ConcurrentHashMap.newKeySet();
            members.addAll(Arrays.asList("alice", "bob", "charlie"));
            registry.put("group_dev", members);

            Set<String> fetched = router.getGroupMembers("group_dev");
            TestSupport.assertTrue(fetched.contains("alice"), "alice phai co trong nhom");
            TestSupport.assertTrue(fetched.contains("bob"), "bob phai co trong nhom");
            TestSupport.assertTrue(fetched.contains("charlie"), "charlie phai co trong nhom");
            TestSupport.assertTrue(!fetched.contains("eve"), "eve khong co trong nhom");

            System.out.println("✔ GroupRegistry luu tru va truy van thanh vien nhom chinh xac: " + fetched);
        });
    }

    // =========================================================================
    // BƯỚC 4: TÍCH HỢP MẠNG THẬT SERVERMAIN QUA SOCKET TCP
    // =========================================================================

    private static void testFullNetworkGroupChatOverSockets() {
        TestSupport.check("Test 13: Tích hợp trọn vẹn E2EE Group Chat qua Server Socket thật (GROUP_CREATE & CHAT_GROUP)", () -> {
            // Khởi động ServerMain trên một luồng riêng
            ServerMain server = new ServerMain();
            Thread serverThread = new Thread(server::run, "test-group-server-thread");
            serverThread.start();

            // Chờ server lắng nghe trên port 8888
            Thread.sleep(300);

            try {
                // Tạo cặp khóa cho Alice, Bob, Charlie
                KeyPair kpAlice = ClientCryptoEngine.generateRsaKeyPair();
                KeyPair kpBob = ClientCryptoEngine.generateRsaKeyPair();
                KeyPair kpCharlie = ClientCryptoEngine.generateRsaKeyPair();

                // 1. Kết nối và Authenticate Alice
                TestClient clientAlice = connectAndAuth("alice", kpAlice);

                // 2. Kết nối và Authenticate Bob
                TestClient clientBob = connectAndAuth("bob", kpBob);

                // 3. Kết nối và Authenticate Charlie
                TestClient clientCharlie = connectAndAuth("charlie", kpCharlie);

                // Đọc bỏ các gói PRESENCE ban đầu giữa các bên
                drainPresence(clientAlice);
                drainPresence(clientBob);
                drainPresence(clientCharlie);

                // 4. Alice tạo nhóm "nhom_04" với các thành viên alice, bob, charlie
                Envelope createGroupEnv = Envelope.createGroup(Opcode.GROUP_CREATE, "alice", "nhom_04");
                createGroupEnv.put("groupId", "nhom_04");
                createGroupEnv.put("members", Arrays.asList("alice", "bob", "charlie"));
                clientAlice.channel.send(createGroupEnv);

                // Alice nhận phản hồi xác nhận từ Server
                Envelope createConfirm = clientAlice.channel.receive();
                TestSupport.assertEquals(Opcode.GROUP_CREATE, createConfirm.getOp());
                TestSupport.assertEquals("CREATED", createConfirm.getString("status"));
                System.out.println("✔ Alice đã tạo nhóm 'nhom_04' thành công trên Server");

                // 5. Chuẩn bị mã hóa E2EE Group Chat
                Map<String, PublicKey> groupRoster = new HashMap<>();
                groupRoster.put("alice", kpAlice.getPublic());
                groupRoster.put("bob", kpBob.getPublic());
                groupRoster.put("charlie", kpCharlie.getPublic());

                String membersHash = GroupCryptoService.computeMembersHash(groupRoster);

                Map<String, PublicKey> recipients = new HashMap<>();
                recipients.put("bob", kpBob.getPublic());
                recipients.put("charlie", kpCharlie.getPublic());

                String messageText = "Xin chào Bob và Charlie! Đây là tin nhắn nhóm E2EE qua Socket thật: 987654321!";

                GroupCryptoService aliceCrypto = new GroupCryptoService();
                Envelope groupMsgEnv = aliceCrypto.createEncryptedGroupMessage(
                        "alice", "nhom_04", messageText, recipients, kpAlice.getPrivate(), membersHash);

                // 6. Alice gửi tin nhắn nhóm lên Server
                clientAlice.channel.send(groupMsgEnv);

                // 7. Bob nhận được gói tin CHAT_GROUP từ Server chuyển tiếp
                Envelope envReceivedByBob = clientBob.channel.receive();
                TestSupport.assertEquals(Opcode.CHAT_GROUP, envReceivedByBob.getOp());
                TestSupport.assertEquals("alice", envReceivedByBob.getFrom());
                TestSupport.assertEquals("nhom_04", envReceivedByBob.getGroupId());

                // Bob xác minh chữ ký của Alice và giải mã
                GroupCryptoService bobCrypto = new GroupCryptoService();
                String decryptedBob = bobCrypto.verifyAndDecryptGroupMessage(
                        envReceivedByBob, "bob", kpAlice.getPublic(), kpBob.getPrivate(), membersHash, false);
                TestSupport.assertEquals(messageText, decryptedBob);
                System.out.println("✔ Bob đã nhận và giải mã tin nhắn nhóm qua Socket: " + decryptedBob);

                // 8. Charlie nhận được gói tin CHAT_GROUP từ Server chuyển tiếp
                Envelope envReceivedByCharlie = clientCharlie.channel.receive();
                TestSupport.assertEquals(Opcode.CHAT_GROUP, envReceivedByCharlie.getOp());
                TestSupport.assertEquals("alice", envReceivedByCharlie.getFrom());
                TestSupport.assertEquals("nhom_04", envReceivedByCharlie.getGroupId());

                // Charlie xác minh chữ ký của Alice và giải mã
                GroupCryptoService charlieCrypto = new GroupCryptoService();
                String decryptedCharlie = charlieCrypto.verifyAndDecryptGroupMessage(
                        envReceivedByCharlie, "charlie", kpAlice.getPublic(), kpCharlie.getPrivate(), membersHash, false);
                TestSupport.assertEquals(messageText, decryptedCharlie);
                System.out.println("✔ Charlie đã nhận và giải mã tin nhắn nhóm qua Socket: " + decryptedCharlie);

                // Dọn dẹp clients
                clientAlice.close();
                clientBob.close();
                clientCharlie.close();
            } finally {
                server.shutdown();
                serverThread.join(3_000);
            }
        });
    }

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

    private static void drainPresence(TestClient client) {
        // Đọc hết các gói tin PRESENCE snapshot ban đầu mà không làm kẹt luồng
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
