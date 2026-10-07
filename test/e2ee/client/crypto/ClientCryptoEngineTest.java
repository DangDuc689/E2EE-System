package e2ee.client.crypto;

import e2ee.common.TestSupport;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;

import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Base64;

/**
 * Lớp kiểm thử độc lập cho ClientCryptoEngine (Chạy qua hàm main(), không dùng JUnit).
 * In chi tiết, tường minh từng bước xử lý mật mã (RSA-OAEP, AES-GCM, PBKDF2) phục vụ
 * nghiệm thu và vấn đáp kỹ thuật.
 */
public final class ClientCryptoEngineTest {

    public static void main(String[] args) {
        // Cấu hình mã hóa UTF-8 cho System.out và System.err để in tiếng Việt chuẩn trên NetBeans Output Window & Terminal
        try {
            System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }

        System.out.println("================================================================================");
        System.out.println("  KIỂM THỬ TƯỜNG MINH: CLIENT CRYPTO ENGINE (ISSUE 5 - HUỲNH NGỌC ANH TUẤN)");
        System.out.println("  Ràng buộc: Pure JDK 17 (java.security, javax.crypto) - Không dùng thư viện ngoài");
        System.out.println("================================================================================\n");

        testGenerateRsaKeyPair();
        testWrapAndUnwrapAesKey();
        testAesGcmEncryptDecryptUtf8();
        testTamperCiphertextThrowsAEADBadTagException();
        testTamperAadThrowsAEADBadTagException();
        testSaveAndLoadPrivateKeyWithPassphrase();
        testLoadPrivateKeyWithWrongPassphraseThrowsException();

        System.out.println("================================================================================");
        System.out.println("  KẾT QUẢ TỔNG HỢP KIỂM THỬ:");
        TestSupport.summary();
        System.out.println("================================================================================");
    }

    private static void testGenerateRsaKeyPair() {
        TestSupport.check("Test 1: Sinh cặp khóa RSA-2048", () -> {
            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.println("🔹 [BƯỚC 1] SINH CẶP KHÓA BẤT ĐỐI XỨNG RSA-2048");
            long start = System.currentTimeMillis();
            KeyPair keyPair = ClientCryptoEngine.generateRsaKeyPair();
            long elapsed = System.currentTimeMillis() - start;

            TestSupport.assertTrue(keyPair != null, "KeyPair không được null");
            TestSupport.assertEquals("RSA", keyPair.getPublic().getAlgorithm());
            TestSupport.assertEquals("RSA", keyPair.getPrivate().getAlgorithm());

            byte[] pubEncoded = keyPair.getPublic().getEncoded();
            byte[] privEncoded = keyPair.getPrivate().getEncoded();

            System.out.println("   • Thuật toán        : " + keyPair.getPublic().getAlgorithm());
            System.out.println("   • Kích thước khóa   : " + ClientCryptoEngine.RSA_KEY_SIZE_BITS + " bits");
            System.out.println("   • Định dạng Public  : " + keyPair.getPublic().getFormat() + " (" + pubEncoded.length + " bytes)");
            System.out.println("   • Base64 Public Key : " + truncate(Base64.getEncoder().encodeToString(pubEncoded), 60));
            System.out.println("   • Định dạng Private : " + keyPair.getPrivate().getFormat() + " (" + privEncoded.length + " bytes)");
            System.out.println("   • Thời gian sinh khóa: " + elapsed + " ms");
            System.out.println("   => Trạng thái: Cặp khóa RSA-2048 được sinh thành công và hợp lệ 100%.");
        });
    }

    private static void testWrapAndUnwrapAesKey() {
        TestSupport.check("Test 2: Bọc (Wrap) & Mở bọc (Unwrap) khóa AES bằng RSA-OAEP SHA-256", () -> {
            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.println("🔹 [BƯỚC 2] BỌC VÀ MỞ BỌC KHÓA ĐỐI XỨNG (RSA-OAEP)");
            KeyPair aliceKeys = ClientCryptoEngine.generateRsaKeyPair();
            SecretKey originalAesKey = ClientCryptoEngine.generateAesKey();

            System.out.println("   • Khóa AES-256 gốc    : " + toHex(originalAesKey.getEncoded()) + " (32 bytes)");
            System.out.println("   • Thuật toán bọc      : " + ClientCryptoEngine.RSA_OAEP_TRANSFORMATION);
            System.out.println("   • Tham số OAEP tường minh: SHA-256, MGF1(SHA-256), PSpecified.DEFAULT");

            // 1. Wrap
            long startWrap = System.currentTimeMillis();
            byte[] wrappedKeyBytes = ClientCryptoEngine.wrapAesKey(originalAesKey, aliceKeys.getPublic());
            long wrapElapsed = System.currentTimeMillis() - startWrap;

            System.out.println("   • Độ dài khóa đã bọc  : " + wrappedKeyBytes.length + " bytes (" + (wrappedKeyBytes.length * 8) + " bits)");
            System.out.println("   • Wrapped Key (Base64): " + truncate(Base64.getEncoder().encodeToString(wrappedKeyBytes), 60));
            System.out.println("   • Thời gian wrap      : " + wrapElapsed + " ms");

            // 2. Unwrap
            long startUnwrap = System.currentTimeMillis();
            SecretKey unwrappedAesKey = ClientCryptoEngine.unwrapAesKey(wrappedKeyBytes, aliceKeys.getPrivate());
            long unwrapElapsed = System.currentTimeMillis() - startUnwrap;

            System.out.println("   • Khóa sau unwrap     : " + toHex(unwrappedAesKey.getEncoded()) + " (32 bytes)");
            System.out.println("   • Thời gian unwrap    : " + unwrapElapsed + " ms");

            boolean matches = Arrays.equals(originalAesKey.getEncoded(), unwrappedAesKey.getEncoded());
            TestSupport.assertTrue(matches, "Khóa AES sau khi unwrap phải trùng khớp 100% khóa gốc");
            System.out.println("   => Trạng thái: Khóa giải mã khớp 100% với khóa AES gốc.");
        });
    }

    private static void testAesGcmEncryptDecryptUtf8() {
        TestSupport.check("Test 3: Mã hóa và giải mã AES-GCM-256 với tin nhắn tiếng Việt UTF-8 kèm AAD", () -> {
            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.println("🔹 [BƯỚC 3] MÃ HÓA & GIẢI MÃ AES-GCM-256 (KÈM DỮ LIỆU XÁC THỰC TOÀN VẸN AAD)");
            SecretKey aesKey = ClientCryptoEngine.generateAesKey();
            byte[] iv = ClientCryptoEngine.generateRandomIv();

            // Giả lập Envelope và tính AAD chuẩn
            Envelope env = Envelope.create(Opcode.CHAT_1TO1, "alice", "bob");
            byte[] aad = env.computeAADBytes();
            String aadText = new String(aad, StandardCharsets.UTF_8);

            String originalMessage = "Xin chào Bob! Đây là tin nhắn E2EE tiếng Việt có dấu: Cộng hòa Xã hội Chủ nghĩa Việt Nam 🔐🇻🇳";
            byte[] plaintextBytes = originalMessage.getBytes(StandardCharsets.UTF_8);

            System.out.println("   • Bản rõ gốc (Plaintext): \"" + originalMessage + "\"");
            System.out.println("   • Độ dài bản rõ UTF-8   : " + plaintextBytes.length + " bytes");
            System.out.println("   • Vector khởi tạo (IV)  : " + toHex(iv) + " (12 bytes / 96 bits)");
            System.out.println("   • Chuỗi AAD từ Envelope : \"" + aadText + "\"");
            System.out.println("   • Độ dài AAD bytes      : " + aad.length + " bytes");

            // Mã hóa
            byte[] ciphertextWithTag = ClientCryptoEngine.encryptAesGcm(plaintextBytes, aesKey, iv, aad);
            int tagLen = ClientCryptoEngine.GCM_TAG_LENGTH_BITS / 8; // 16 bytes
            int cipherOnlyLen = ciphertextWithTag.length - tagLen;

            System.out.println("   • Bản mã + Auth Tag     : " + ciphertextWithTag.length + " bytes (Ciphertext: " 
                    + cipherOnlyLen + "B + Tag: " + tagLen + "B)");
            System.out.println("   • Base64 Ciphertext     : " + truncate(Base64.getEncoder().encodeToString(ciphertextWithTag), 60));

            // Giải mã
            byte[] decryptedBytes = ClientCryptoEngine.decryptAesGcm(ciphertextWithTag, aesKey, iv, aad);
            String decryptedMessage = new String(decryptedBytes, StandardCharsets.UTF_8);

            System.out.println("   • Bản rõ giải mã được   : \"" + decryptedMessage + "\"");
            TestSupport.assertEquals(originalMessage, decryptedMessage);
            System.out.println("   => Trạng thái: Giải mã thành công, nội dung khớp 100% với bản rõ ban đầu.");
        });
    }

    private static void testTamperCiphertextThrowsAEADBadTagException() {
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("🔹 [BƯỚC 4] KIỂM TRA CHỐNG CAN THIỆP DỮ LIỆU (TAMPER ATTACK TRÊN CIPHERTEXT)");
        TestSupport.check("Test 4: Sửa 1 byte trong Ciphertext -> Ném AEADBadTagException", () -> {
            SecretKey aesKey = ClientCryptoEngine.generateAesKey();
            byte[] iv = ClientCryptoEngine.generateRandomIv();
            Envelope env = Envelope.create(Opcode.CHAT_1TO1, "alice", "bob");
            byte[] aad = env.computeAADBytes();

            byte[] plaintext = "Dữ liệu tối mật của phiên giao dịch E2EE".getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = ClientCryptoEngine.encryptAesGcm(plaintext, aesKey, iv, aad);

            // Can thiệp sửa đổi 1 byte
            byte[] tamperedCiphertext = Arrays.copyOf(ciphertext, ciphertext.length);
            byte originalByte = tamperedCiphertext[0];
            tamperedCiphertext[0] ^= 0x5A; // Đảo bit byte đầu tiên

            System.out.println("   • Byte gốc tại vị trí [0]: 0x" + String.format("%02X", originalByte));
            System.out.println("   • Kẻ tấn công đổi thành  : 0x" + String.format("%02X", tamperedCiphertext[0]));
            System.out.println("   • Tiến hành giải mã Ciphertext bị can thiệp...");

            AEADBadTagException ex = TestSupport.assertThrows(AEADBadTagException.class, () -> {
                ClientCryptoEngine.decryptAesGcm(tamperedCiphertext, aesKey, iv, aad);
            });

            System.out.println("   • Ngoại lệ bắt được      : " + ex.getClass().getName() + " -> " + ex.getMessage());
            System.out.println("   => Trạng thái: AES-GCM phát hiện dữ liệu bị sửa đổi và TỪ CHỐI giải mã!");
        });
    }

    private static void testTamperAadThrowsAEADBadTagException() {
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("🔹 [BƯỚC 5] KIỂM TRA CHỐNG GIẢ MẠO HEADER (TAMPER ATTACK TRÊN DỮ LIỆU AAD)");
        TestSupport.check("Test 5: Sửa 1 ký tự trong AAD (giả mạo người nhận) -> Ném AEADBadTagException", () -> {
            SecretKey aesKey = ClientCryptoEngine.generateAesKey();
            byte[] iv = ClientCryptoEngine.generateRandomIv();

            Envelope originalEnv = Envelope.create(Opcode.CHAT_1TO1, "alice", "bob");
            byte[] originalAad = originalEnv.computeAADBytes();
            String originalAadStr = new String(originalAad, StandardCharsets.UTF_8);

            byte[] plaintext = "Chuyển khoản 10.000.000 VNĐ cho Bob".getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = ClientCryptoEngine.encryptAesGcm(plaintext, aesKey, iv, originalAad);

            // Kẻ tấn công trên mạng sửa trường 'to' từ 'bob' thành 'charlie'
            Envelope tamperedEnv = Envelope.create(Opcode.CHAT_1TO1, "alice", "charlie");
            tamperedEnv.setId(originalEnv.getId());
            tamperedEnv.setTs(originalEnv.getTs());
            byte[] tamperedAad = tamperedEnv.computeAADBytes();
            String tamperedAadStr = new String(tamperedAad, StandardCharsets.UTF_8);

            System.out.println("   • AAD chuẩn của gói tin : \"" + originalAadStr + "\"");
            System.out.println("   • AAD bị giả mạo trên mạng: \"" + tamperedAadStr + "\"");
            System.out.println("   • Tiến hành giải mã với Header giả mạo...");

            AEADBadTagException ex = TestSupport.assertThrows(AEADBadTagException.class, () -> {
                ClientCryptoEngine.decryptAesGcm(ciphertext, aesKey, iv, tamperedAad);
            });

            System.out.println("   • Ngoại lệ bắt được      : " + ex.getClass().getName() + " -> " + ex.getMessage());
            System.out.println("   => Trạng thái: Phát hiện Header gói tin bị can thiệp và ném lỗi toàn vẹn!");
        });
    }

    private static void testSaveAndLoadPrivateKeyWithPassphrase() {
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("🔹 [BƯỚC 6] LƯU TRỮ VÀ TẢI PRIVATE KEY AN TOÀN BẰNG PASSPHRASE");
        TestSupport.check("Test 6: Lưu & Đọc Private Key với đúng passphrase thành công 100%", () -> {
            KeyPair keyPair = ClientCryptoEngine.generateRsaKeyPair();
            char[] passphrase = "mat_khau_e2ee_cuc_ky_an_toan_#2026".toCharArray();

            File tempFile = File.createTempFile("e2ee_privkey_", ".enc");
            tempFile.deleteOnExit();

            try {
                System.out.println("   • Passphrase người dùng : " + new String(passphrase));
                System.out.println("   • Thuật toán dẫn xuất   : " + ClientCryptoEngine.PBKDF2_ALGORITHM);
                System.out.println("   • Số vòng lặp KDF       : " + ClientCryptoEngine.PBKDF2_ITERATIONS + " vòng");
                System.out.println("   • Cấu trúc file nhị phân: Salt (16B) || IV (12B) || EncryptedKey (AES-GCM)");

                long startSave = System.currentTimeMillis();
                ClientCryptoEngine.savePrivateKey(tempFile, keyPair.getPrivate(), passphrase);
                long saveElapsed = System.currentTimeMillis() - startSave;

                System.out.println("   • File lưu trữ tạm      : " + tempFile.getName());
                System.out.println("   • Dung lượng file mã hóa: " + tempFile.length() + " bytes");
                System.out.println("   • Thời gian mã hóa/ghi  : " + saveElapsed + " ms");

                // Đọc lại với passphrase đúng
                long startLoad = System.currentTimeMillis();
                PrivateKey loadedPrivateKey = ClientCryptoEngine.loadPrivateKey(tempFile, passphrase);
                long loadElapsed = System.currentTimeMillis() - startLoad;

                System.out.println("   • Thời gian đọc/giải mã : " + loadElapsed + " ms");
                TestSupport.assertTrue(loadedPrivateKey != null, "Private Key sau khi load không được null");

                // Kiểm tra giải mã thực tế bằng Private Key vừa khôi phục
                SecretKey testAes = ClientCryptoEngine.generateAesKey();
                byte[] wrapped = ClientCryptoEngine.wrapAesKey(testAes, keyPair.getPublic());
                SecretKey restoredAes = ClientCryptoEngine.unwrapAesKey(wrapped, loadedPrivateKey);

                TestSupport.assertTrue(Arrays.equals(testAes.getEncoded(), restoredAes.getEncoded()),
                        "Private Key khôi phục phải hoạt động chính xác tương đương khóa ban đầu");
                System.out.println("   => Trạng thái: Private Key khôi phục thành công, unwrap khóa AES khớp 100%.");
            } finally {
                tempFile.delete();
            }
        });
    }

    private static void testLoadPrivateKeyWithWrongPassphraseThrowsException() {
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("🔹 [BƯỚC 7] KIỂM TRA BẢO VỆ KHI NHẬP SAI PASSPHRASE");
        TestSupport.check("Test 7: Nhập sai passphrase -> Từ chối giải mã và báo lỗi rõ ràng", () -> {
            KeyPair keyPair = ClientCryptoEngine.generateRsaKeyPair();
            char[] correctPassphrase = "passphrase_dung_123".toCharArray();
            char[] wrongPassphrase = "passphrase_sai_456".toCharArray();

            File tempFile = File.createTempFile("e2ee_privkey_wrong_", ".enc");
            tempFile.deleteOnExit();

            try {
                ClientCryptoEngine.savePrivateKey(tempFile, keyPair.getPrivate(), correctPassphrase);
                System.out.println("   • Passphrase đúng       : " + new String(correctPassphrase));
                System.out.println("   • Thử mở khóa bằng mật khẩu sai: \"" + new String(wrongPassphrase) + "\"");

                GeneralSecurityException ex = TestSupport.assertThrows(GeneralSecurityException.class, () -> {
                    ClientCryptoEngine.loadPrivateKey(tempFile, wrongPassphrase);
                });

                System.out.println("   • Ngoại lệ bảo vệ bắt được: " + ex.getClass().getSimpleName() + " -> " + ex.getMessage());
                TestSupport.assertTrue(ex.getMessage().contains("Sai passphrase"),
                        "Thông báo ngoại lệ phải chứa thông tin lỗi rõ ràng");
                System.out.println("   => Trạng thái: Hệ thống từ chối mở khóa khi sai mật khẩu!");
            } finally {
                tempFile.delete();
            }
        });
    }

    // =========================================================================
    // HÀM TIỆN ÍCH ĐỊNH DẠNG HỖ TRỢ IN TƯỜNG MINH
    // =========================================================================

    private static String toHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            sb.append(String.format("%02X", bytes[i]));
            if (i < bytes.length - 1 && (i + 1) % 4 == 0) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "... (còn tiếp)";
    }
}
