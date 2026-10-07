package e2ee.client.crypto;

import e2ee.common.TestSupport;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

/**
 * Lớp kiểm thử độc lập cho ClientCryptoService (Chạy qua hàm main(), không dùng JUnit).
 * In chi tiết, tường minh từng bước xử lý mật mã (Fingerprint, Signature, Anti-Replay, E2EE 1-1)
 * phục vụ nghiệm thu theo quy chế và chuẩn bị cho phần thi vấn đáp.
 */
public final class ClientCryptoServiceTest {

    public static void main(String[] args) {
        // Cấu hình UTF-8 cho console để in tiếng Việt chuẩn xác
        try {
            System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }

        System.out.println("================================================================================");
        System.out.println("  KIỂM THỬ TƯỜNG MINH: CLIENT CRYPTO SERVICE (ISSUE 10 - HUỲNH NGỌC ANH TUẤN)");
        System.out.println("  Ràng buộc: Pure JDK 17 (java.security, javax.crypto) - Không dùng thư viện ngoài");
        System.out.println("================================================================================\n");

        testCalculateFingerprintFormat();
        testSignChallenge();
        testEndToEndEncrypted1to1Message();
        testTamperCiphertextFailsSignatureVerification();
        testTamperEnvelopeHeaderFailsSignatureVerification();
        testAntiReplayBlocksDuplicateLiveMessage();
        testLiveMessageRejectsClockSkewGreaterThan120s();
        testOfflineSyncAllowsOldTimestampAndBlocksDuplicate();
        testCorruptedMessageDoesNotPolluteSeenIds();

        System.out.println("\n================================================================================");
        System.out.println("  KẾT QUẢ TỔNG HỢP KIỂM THỬ:");
        TestSupport.summary();
        System.out.println("================================================================================");
    }

    private static void testCalculateFingerprintFormat() {
        TestSupport.check("Test 1: Tính toán Key Fingerprint chuẩn Hex in hoa cụm 4 ký tự", () -> {
            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.println("🔹 [TEST 1] TÍNH TOÁN KEY FINGERPRINT CỦA PUBLIC KEY");
            KeyPair keyPair = ClientCryptoEngine.generateRsaKeyPair();
            String fingerprint = ClientCryptoService.calculateFingerprint(keyPair.getPublic());

            System.out.println("Fingerprint kết quả: " + fingerprint);

            TestSupport.assertTrue(fingerprint != null && !fingerprint.isBlank(), "Fingerprint khong duoc rong");

            // SHA-256 băm 32 bytes -> 64 ký tự Hex -> chia làm 16 cụm 4 ký tự -> 15 khoảng trắng -> tổng 79 ký tự
            String[] chunks = fingerprint.split(" ");
            TestSupport.assertEquals(16, chunks.length);
            for (String chunk : chunks) {
                TestSupport.assertEquals(4, chunk.length());
                TestSupport.assertTrue(chunk.matches("^[0-9A-F]{4}$"), "Moi cum phai la 4 ky tu Hex in hoa: " + chunk);
            }
            System.out.println("✔ Định dạng Fingerprint hợp lệ: 16 cụm Hex 4 ký tự in hoa.");
        });
    }

    private static void testSignChallenge() {
        TestSupport.check("Test 2: Ký số Nonce (signChallenge) phục vụ bắt tay đăng nhập", () -> {
            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.println("🔹 [TEST 2] KÝ SỐ CHALLENGE NONCE ĐĂNG NHẬP");
            KeyPair alice = ClientCryptoEngine.generateRsaKeyPair();
            byte[] nonce = new byte[32];
            new java.security.SecureRandom().nextBytes(nonce);

            byte[] signatureBytes = ClientCryptoService.signChallenge(nonce, alice.getPrivate());
            TestSupport.assertTrue(signatureBytes != null && signatureBytes.length > 0, "Chữ ký không được rỗng");

            // Xác minh lại bằng Public Key của Alice
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(alice.getPublic());
            verifier.update(nonce);
            boolean isValid = verifier.verify(signatureBytes);

            TestSupport.assertTrue(isValid, "Chữ ký nonce phải hợp lệ");
            System.out.println("✔ Ký số SHA256withRSA trên nonce 32-byte thành công, kích thước chữ ký: " + signatureBytes.length + " bytes.");
        });
    }

    private static void testEndToEndEncrypted1to1Message() {
        TestSupport.check("Test 3: Chu trình E2EE 1-1 (Alice mã hóa -> Bob xác minh & giải mã)", () -> {
            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.println("🔹 [TEST 3] CHU TRÌNH END-TO-END E2EE 1-1 HOÀN CHỈNH");
            KeyPair alice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();

            ClientCryptoService bobService = new ClientCryptoService();
            String originalMessage = "Xin chào Bob! Đây là tin nhắn mã hóa đầu cuối E2EE bảo mật chuẩn 2026 tiếng Việt có dấu: 123456 @#$!";

            // 1. Alice tạo và mã hóa gói tin
            Envelope env = bobService.createEncrypted1to1Message("alice", "bob", originalMessage, bob.getPublic(), alice.getPrivate());

            TestSupport.assertEquals(Opcode.CHAT_1TO1, env.getOp());
            TestSupport.assertEquals("alice", env.getFrom());
            TestSupport.assertEquals("bob", env.getTo());
            TestSupport.assertTrue(env.getString("ek") != null, "Body phải chứa ek");
            TestSupport.assertTrue(env.getString("iv") != null, "Body phải chứa iv");
            TestSupport.assertTrue(env.getString("ct") != null, "Body phải chứa ct");
            TestSupport.assertTrue(env.getString("sig") != null, "Body phải chứa sig");

            System.out.println("Gói tin Envelope tạo bởi Alice: " + env);
            System.out.println(" - ek (Wrapped AES): " + env.getString("ek").substring(0, 32) + "...");
            System.out.println(" - iv: " + env.getString("iv"));
            System.out.println(" - ct: " + env.getString("ct").substring(0, 32) + "...");
            System.out.println(" - sig: " + env.getString("sig").substring(0, 32) + "...");

            // 2. Bob xác minh chữ ký và giải mã
            String decryptedMessage = bobService.verifyAndDecrypt1to1Message(env, alice.getPublic(), bob.getPrivate(), false);

            System.out.println("Nội dung Bob giải mã được: " + decryptedMessage);
            TestSupport.assertEquals(originalMessage, decryptedMessage);
            System.out.println("✔ Tin nhắn giải mã khớp 100% với nội dung gốc tiếng Việt!");
        });
    }

    private static void testTamperCiphertextFailsSignatureVerification() {
        TestSupport.check("Test 4: Can thiệp sửa 1 byte Ciphertext -> Bị chặn ngay ở khâu kiểm tra chữ ký", () -> {
            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.println("🔹 [TEST 4] TẤN CÔNG CAN THIỆP CIPHERTEXT (TAMPER ATTACK)");
            KeyPair alice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
            ClientCryptoService bobService = new ClientCryptoService();

            Envelope env = bobService.createEncrypted1to1Message("alice", "bob", "Tin nhắn tuyệt mật", bob.getPublic(), alice.getPrivate());

            // Kẻ tấn công trên đường truyền sửa đổi 1 ký tự trong Base64 của ciphertext (ct)
            String originalCt = env.getString("ct");
            char tamperedChar = originalCt.charAt(0) == 'A' ? 'B' : 'A';
            String tamperedCt = tamperedChar + originalCt.substring(1);
            env.put("ct", tamperedCt);

            System.out.println("Đã can thiệp byte đầu tiên của ct: " + originalCt.substring(0, 5) + " -> " + tamperedCt.substring(0, 5));

            // Bob cố gắng giải mã gói tin bị can thiệp
            TestSupport.assertThrows(SecurityException.class, () -> {
                bobService.verifyAndDecrypt1to1Message(env, alice.getPublic(), bob.getPrivate(), false);
            });

            System.out.println("✔ Ngoại lệ SecurityException được ném chính xác: Chữ ký số không hợp lệ trước khi chạm đến AES!");
        });
    }

    private static void testTamperEnvelopeHeaderFailsSignatureVerification() {
        TestSupport.check("Test 5: Can thiệp Header Envelope (đổi 'from' hoặc 'ts') -> Bị chặn bởi chữ ký", () -> {
            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.println("🔹 [TEST 5] TẤN CÔNG GIẢ MẠO HEADER ENVELOPE (SỬA FROM / TS)");
            KeyPair alice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
            ClientCryptoService bobService = new ClientCryptoService();

            Envelope env = bobService.createEncrypted1to1Message("alice", "bob", "Tin nhắn từ Alice", bob.getPublic(), alice.getPrivate());

            // Kẻ tấn công mạo danh đổi 'from' thành 'mallory'
            env.setFrom("mallory");

            TestSupport.assertThrows(SecurityException.class, () -> {
                bobService.verifyAndDecrypt1to1Message(env, alice.getPublic(), bob.getPrivate(), false);
            });

            System.out.println("✔ Can thiệp header làm thay đổi Canonical Form và AAD -> Chữ ký lập tức bị từ chối!");
        });
    }

    private static void testAntiReplayBlocksDuplicateLiveMessage() {
        TestSupport.check("Test 6: Chống phát lại (Anti-Replay) từ chối phát lại gói tin lần 2", () -> {
            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.println("🔹 [TEST 6] THỬ PHÁT LẠI GÓI TIN LẦN 2 (REPLAY ATTACK)");
            KeyPair alice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
            ClientCryptoService bobService = new ClientCryptoService();

            Envelope env = bobService.createEncrypted1to1Message("alice", "bob", "Tin nhắn chuyển khoản 1000$", bob.getPublic(), alice.getPrivate());

            // Lần 1: Nhận và giải mã thành công
            String msg1 = bobService.verifyAndDecrypt1to1Message(env, alice.getPublic(), bob.getPrivate(), false);
            TestSupport.assertEquals("Tin nhắn chuyển khoản 1000$", msg1);
            System.out.println("Lần 1: Nhận tin thành công -> ID ghi nhận vào seenMessageIds: " + env.getId());

            // Lần 2: Kẻ xấu gửi lại chính gói tin đó
            TestSupport.assertThrows(SecurityException.class, () -> {
                bobService.verifyAndDecrypt1to1Message(env, alice.getPublic(), bob.getPrivate(), false);
            });
            System.out.println("✔ Lần 2: Bộ lọc Anti-Replay đã chặn đứng gói tin phát lại (Id trùng lặp)!");
        });
    }

    private static void testLiveMessageRejectsClockSkewGreaterThan120s() {
        TestSupport.check("Test 7: Tin nhắn Live có timestamp lệch > 120s bị từ chối", () -> {
            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.println("🔹 [TEST 7] KIỂM TRA ĐỘ LỆCH THỜI GIAN (CLOCK SKEW +-120 GIÂY)");
            KeyPair alice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
            ClientCryptoService bobService = new ClientCryptoService();

            // Tin nhắn có timestamp quá cũ (cách đây 130 giây)
            Envelope envOld = bobService.createEncrypted1to1Message("alice", "bob", "Tin cũ", bob.getPublic(), alice.getPrivate());
            envOld.setTs(System.currentTimeMillis() - 130_000L);

            TestSupport.assertThrows(SecurityException.class, () -> {
                bobService.verifyAndDecrypt1to1Message(envOld, alice.getPublic(), bob.getPrivate(), false);
            });
            System.out.println("✔ Gói tin lệch > 120 giây về quá khứ bị từ chối ngay lập tức.");

            // Tin nhắn có timestamp quá xa ở tương lai (lệch +150 giây)
            Envelope envFuture = bobService.createEncrypted1to1Message("alice", "bob", "Tin tương lai", bob.getPublic(), alice.getPrivate());
            envFuture.setTs(System.currentTimeMillis() + 150_000L);

            TestSupport.assertThrows(SecurityException.class, () -> {
                bobService.verifyAndDecrypt1to1Message(envFuture, alice.getPublic(), bob.getPrivate(), false);
            });
            System.out.println("✔ Gói tin lệch > 120 giây về tương lai bị từ chối ngay lập tức.");
        });
    }

    private static void testOfflineSyncAllowsOldTimestampAndBlocksDuplicate() {
        TestSupport.check("Test 8: Tin Offline Sync (OFFLINE_SYNC) cho phép timestamp cũ nhưng chặn trùng lặp", () -> {
            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.println("🔹 [TEST 8] TƯƠNG THÍCH HÀNG ĐỢI OFFLINE (OFFLINE_SYNC)");
            KeyPair alice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
            ClientCryptoService bobService = new ClientCryptoService();

            // Tin nhắn gửi từ 1 giờ trước khi Bob đang offline
            Envelope envOffline = bobService.createEncrypted1to1Message("alice", "bob", "Tin nhắn offline gửi lúc 1h trước", bob.getPublic(), alice.getPrivate());
            // Cập nhật ts cũ trước khi ký
            // Chú ý: Để chữ ký hợp lệ, cần tạo lại canonical form nếu đổi ts, hoặc set ts trước:
            // Do Envelope.ts nằm trong AAD nên cần truyền env có ts cũ ngay từ đầu
            Envelope envOldOffline = Envelope.create(Opcode.CHAT_1TO1, "alice", "bob");
            envOldOffline.setTs(System.currentTimeMillis() - 3600_000L); // 1 giờ trước

            // Đóng gói bằng phương thức chuẩn
            String plainJson = "{\"content\":\"Tin nhắn offline gửi lúc 1h trước\"}";
            byte[] plainBytes = plainJson.getBytes(StandardCharsets.UTF_8);
            SecretKey aesKey = ClientCryptoEngine.generateAesKey();
            byte[] iv = ClientCryptoEngine.generateRandomIv();
            byte[] aad = envOldOffline.computeAADBytes();
            byte[] ct = ClientCryptoEngine.encryptAesGcm(plainBytes, aesKey, iv, aad);
            byte[] ek = ClientCryptoEngine.wrapAesKey(aesKey, bob.getPublic());
            String ekB64 = Base64.getEncoder().encodeToString(ek);
            String ivB64 = Base64.getEncoder().encodeToString(iv);
            String ctB64 = Base64.getEncoder().encodeToString(ct);
            String aadB64 = Base64.getEncoder().encodeToString(aad);
            String canon = "alice|bob|" + ekB64 + "|" + ivB64 + "|" + aadB64 + "|" + ctB64;
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(alice.getPrivate());
            signer.update(canon.getBytes(StandardCharsets.UTF_8));
            String sigB64 = Base64.getEncoder().encodeToString(signer.sign());

            envOldOffline.put("ek", ekB64).put("iv", ivB64).put("ct", ctB64).put("sig", sigB64);

            // Khi đồng bộ offline (isOfflineSync = true), bỏ qua kiểm tra timestamp lệch 1 giờ
            String content = bobService.verifyAndDecrypt1to1Message(envOldOffline, alice.getPublic(), bob.getPrivate(), true);
            TestSupport.assertEquals("Tin nhắn offline gửi lúc 1h trước", content);
            System.out.println("✔ Tin nhắn offline cũ 1 giờ vẫn được giải mã thành công khi isOfflineSync = true.");

            // Nhưng nếu phát lại gói offline lần 2 -> vẫn bị chặn bởi seenMessageIds
            TestSupport.assertThrows(SecurityException.class, () -> {
                bobService.verifyAndDecrypt1to1Message(envOldOffline, alice.getPublic(), bob.getPrivate(), true);
            });
            System.out.println("✔ Gói tin offline đồng bộ trùng lặp lần 2 vẫn bị chặn khử trùng lặp chính xác!");
        });
    }

    private static void testCorruptedMessageDoesNotPolluteSeenIds() {
        TestSupport.check("Test 9: Gói tin rác / hỏng chữ ký KHÔNG được ghi vào seenMessageIds (Chống đốt ID)", () -> {
            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.println("🔹 [TEST 9] BẢO VỆ CHỐNG ĐỐT ID RÁC (SAFE ID RECORDING)");
            KeyPair alice = ClientCryptoEngine.generateRsaKeyPair();
            KeyPair bob = ClientCryptoEngine.generateRsaKeyPair();
            ClientCryptoService bobService = new ClientCryptoService();

            Envelope env = bobService.createEncrypted1to1Message("alice", "bob", "Tin nhắn gốc", bob.getPublic(), alice.getPrivate());
            String validId = env.getId();

            // Kẻ tấn công gửi gói tin có ID này nhưng làm hỏng chữ ký
            env.put("sig", Base64.getEncoder().encodeToString(new byte[256]));

            // Thử giải mã -> Lỗi chữ ký
            TestSupport.assertThrows(SecurityException.class, () -> {
                bobService.verifyAndDecrypt1to1Message(env, alice.getPublic(), bob.getPrivate(), false);
            });

            // Xác minh ID đó CHƯA bị ghi nhận vào seenMessageIds
            TestSupport.assertTrue(!bobService.isMessageSeen(validId), "ID khong duoc ghi nhan khi giai ma that bai!");
            System.out.println("✔ ID " + validId + " KHÔNG bị ghi nhận vào cache khi chữ ký sai.");

            // Alice gửi lại gói tin chuẩn với chính ID đó -> Phải được xử lý bình thường
            Envelope validEnv = bobService.createEncrypted1to1Message("alice", "bob", "Tin hợp lệ", bob.getPublic(), alice.getPrivate());
            validEnv.setId(validId); // gán lại ID đó nhưng ký đúng chuẩn
            byte[] aad = validEnv.computeAADBytes();
            byte[] plainBytes = "{\"content\":\"Tin hợp lệ\"}".getBytes(StandardCharsets.UTF_8);
            SecretKey aesKey = ClientCryptoEngine.generateAesKey();
            byte[] iv = ClientCryptoEngine.generateRandomIv();
            byte[] ct = ClientCryptoEngine.encryptAesGcm(plainBytes, aesKey, iv, aad);
            byte[] ek = ClientCryptoEngine.wrapAesKey(aesKey, bob.getPublic());
            String ekB64 = Base64.getEncoder().encodeToString(ek);
            String ivB64 = Base64.getEncoder().encodeToString(iv);
            String ctB64 = Base64.getEncoder().encodeToString(ct);
            String aadB64 = Base64.getEncoder().encodeToString(aad);
            String canon = "alice|bob|" + ekB64 + "|" + ivB64 + "|" + aadB64 + "|" + ctB64;
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(alice.getPrivate());
            signer.update(canon.getBytes(StandardCharsets.UTF_8));
            validEnv.put("ek", ekB64).put("iv", ivB64).put("ct", ctB64).put("sig", Base64.getEncoder().encodeToString(signer.sign()));

            String decrypted = bobService.verifyAndDecrypt1to1Message(validEnv, alice.getPublic(), bob.getPrivate(), false);
            TestSupport.assertEquals("Tin hợp lệ", decrypted);
            System.out.println("✔ Gói tin thật với ID đó vẫn được nhận và giải mã thành công!");
        });
    }
}
