package e2ee.client.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * Động cơ mã hóa bảo mật lõi phía Client (Pure JDK 17).
 * Phụ trách:
 * 1. Sinh cặp khóa RSA-2048 cho người dùng mới.
 * 2. Bọc và mở bọc khóa đối xứng (Key Wrap / Unwrap) bằng RSA-OAEP SHA-256 tường minh.
 * 3. Mã hóa và giải mã tin nhắn AES-GCM-256 kết hợp AAD xác thực toàn vẹn.
 * 4. Lưu trữ an toàn Private Key tại máy cục bộ bằng PBKDF2 (>= 200.000 vòng) và AES-GCM.
 */
public final class ClientCryptoEngine {

    // Kích thước khóa và cấu hình chuẩn
    public static final int RSA_KEY_SIZE_BITS = 2048;
    public static final int AES_KEY_SIZE_BITS = 256;
    public static final int GCM_IV_LENGTH_BYTES = 12;
    public static final int GCM_TAG_LENGTH_BITS = 128;
    public static final int PBKDF2_SALT_LENGTH_BYTES = 16;
    public static final int PBKDF2_ITERATIONS = 200_000;

    // Chuẩn thuật toán
    public static final String RSA_ALGORITHM = "RSA";
    public static final String AES_ALGORITHM = "AES";
    public static final String RSA_OAEP_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    public static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    public static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ClientCryptoEngine() {
        // Lớp tiện ích không khởi tạo instance
    }

    // =========================================================================
    // 1. SINH CẶP KHÓA RSA-2048
    // =========================================================================

    /**
     * Sinh cặp khóa bất đối xứng RSA-2048 bits khi tạo tài khoản mới.
     *
     * @return Cặp khóa RSA (PublicKey, PrivateKey)
     * @throws GeneralSecurityException khi môi trường không hỗ trợ thuật toán RSA
     */
    public static KeyPair generateRsaKeyPair() throws GeneralSecurityException {
        // Khởi tạo KeyPairGenerator chuẩn RSA với độ dài 2048-bit
        KeyPairGenerator generator = KeyPairGenerator.getInstance(RSA_ALGORITHM);
        generator.initialize(RSA_KEY_SIZE_BITS, SECURE_RANDOM);
        return generator.generateKeyPair();
    }

    // =========================================================================
    // 2. BỌC VÀ MỞ BỌC KHÓA ĐỐI XỨNG (RSA-OAEP WITH SHA-256)
    // =========================================================================

    /**
     * Tạo thông số tham số tường minh cho RSA-OAEP để tránh xung đột ngầm định MGF1
     * giữa các nền tảng JVM khác nhau:
     * - Message Digest: SHA-256
     * - MGF1 Mask Generation: MGF1ParameterSpec.SHA256
     * - PSource: PSpecified.DEFAULT
     */
    public static OAEPParameterSpec createOaepParameterSpec() {
        return new OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT
        );
    }

    /**
     * Bọc (Wrap) khóa đối xứng AES-256 bằng Public Key của người nhận qua RSA-OAEP SHA-256.
     *
     * @param aesKey Khóa đối xứng AES cần bọc
     * @param recipientPublicKey Public Key của người nhận
     * @return Mảng byte chứa khóa AES đã được bọc an toàn
     * @throws GeneralSecurityException nếu xảy ra lỗi trong quá trình bọc khóa
     */
    public static byte[] wrapAesKey(SecretKey aesKey, PublicKey recipientPublicKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION);
        // Thiết lập WRAP_MODE với tham số OAEP tường minh
        cipher.init(Cipher.WRAP_MODE, recipientPublicKey, createOaepParameterSpec(), SECURE_RANDOM);
        return cipher.wrap(aesKey);
    }

    /**
     * Mở bọc (Unwrap) khóa đối xứng AES-256 bằng Private Key của chính mình qua RSA-OAEP SHA-256.
     *
     * @param wrappedKeyBytes Mảng byte chứa khóa AES đã bọc
     * @param myPrivateKey Private Key của người nhận
     * @return Đối tượng SecretKey khôi phục hoàn chỉnh
     * @throws GeneralSecurityException nếu Private Key không khớp hoặc bản mã khóa bị lỗi
     */
    public static SecretKey unwrapAesKey(byte[] wrappedKeyBytes, PrivateKey myPrivateKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION);
        // Thiết lập UNWRAP_MODE với tham số OAEP tường minh
        cipher.init(Cipher.UNWRAP_MODE, myPrivateKey, createOaepParameterSpec());
        Key key = cipher.unwrap(wrappedKeyBytes, AES_ALGORITHM, Cipher.SECRET_KEY);
        return (SecretKey) key;
    }

    // =========================================================================
    // 3. MÃ HÓA VÀ GIẢI MÃ AES-GCM-256
    // =========================================================================

    /**
     * Sinh khóa bí mật AES-256 ngẫu nhiên độc lập cho từng tin nhắn.
     *
     * @return SecretKey AES-256
     * @throws NoSuchAlgorithmException khi hệ thống không hỗ trợ AES
     */
    public static SecretKey generateAesKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance(AES_ALGORITHM);
        keyGen.init(AES_KEY_SIZE_BITS, SECURE_RANDOM);
        return keyGen.generateKey();
    }

    /**
     * Sinh Initialization Vector (IV) ngẫu nhiên độ dài 12 bytes chuẩn cho chế độ AES-GCM.
     *
     * @return Mảng 12 bytes ngẫu nhiên
     */
    public static byte[] generateRandomIv() {
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        return iv;
    }

    /**
     * Mã hóa dữ liệu bằng AES-GCM-256 kết hợp AAD (Additional Authenticated Data).
     *
     * @param plaintext Dữ liệu bản rõ (UTF-8 bytes)
     * @param aesKey Khóa đối xứng AES-256
     * @param iv Vector khởi tạo 12 bytes
     * @param aad Dữ liệu bổ sung cần bảo vệ toàn vẹn (từ envelope.computeAADBytes()), có thể rỗng
     * @return Ciphertext kèm Authentication Tag 128-bit
     * @throws GeneralSecurityException khi lỗi cấu hình tham số hoặc lỗi mã hóa
     */
    public static byte[] encryptAesGcm(byte[] plaintext, SecretKey aesKey, byte[] iv, byte[] aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
        // Khởi tạo thông số GCM với tag độ dài 128 bits
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, spec, SECURE_RANDOM);

        // Gắn dữ liệu xác thực AAD bảo vệ chống giả mạo Header
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }

        return cipher.doFinal(plaintext);
    }

    /**
     * Giải mã dữ liệu AES-GCM-256 và kiểm tra tính toàn vẹn thông qua Authentication Tag và AAD.
     *
     * @param ciphertextWithTag Bản mã kèm Tag 128-bit
     * @param aesKey Khóa đối xứng AES-256
     * @param iv Vector khởi tạo 12 bytes
     * @param aad Dữ liệu xác thực bổ sung AAD (phải khớp 100% với bên gửi)
     * @return Dữ liệu bản rõ ban đầu
     * @throws javax.crypto.AEADBadTagException nếu ciphertext hoặc AAD bị can thiệp dù chỉ 1 byte
     * @throws GeneralSecurityException khi có lỗi giải mã khác
     */
    public static byte[] decryptAesGcm(byte[] ciphertextWithTag, SecretKey aesKey, byte[] iv, byte[] aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE, aesKey, spec);

        // Gắn AAD để đối soát toàn vẹn
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }

        // Tự động ném AEADBadTagException nếu dữ liệu bị giả mạo / can thiệp
        return cipher.doFinal(ciphertextWithTag);
    }

    // =========================================================================
    // 4. LƯU TRỮ VÀ TẢI PRIVATE KEY AN TOÀN TẠI MÁY CỤC BỘ
    // =========================================================================

    /**
     * Dẫn xuất khóa AES-256 từ passphrase và Salt thông qua thuật toán PBKDF2WithHmacSHA256 (200.000 vòng lặp).
     */
    private static SecretKey deriveKeyFromPassphrase(char[] passphrase, byte[] salt) throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, AES_KEY_SIZE_BITS);
        SecretKey tmp = factory.generateSecret(spec);
        spec.clearPassword();
        return new SecretKeySpec(tmp.getEncoded(), AES_ALGORITHM);
    }

    /**
     * Mã hóa Private Key bằng passphrase của người dùng và lưu xuống file cục bộ.
     * Định dạng file nhị phân: [Salt (16 bytes)] || [IV (12 bytes)] || [EncryptedKey (Ciphertext + Tag 128-bit)]
     *
     * @param targetFile File cần ghi khóa
     * @param privateKey Private Key RSA cần bảo vệ
     * @param passphrase Mật khẩu khẩu lệnh của người dùng
     * @throws IOException khi lỗi đọc/ghi đĩa
     * @throws GeneralSecurityException khi lỗi thuật toán mã hóa
     */
    public static void savePrivateKey(File targetFile, PrivateKey privateKey, char[] passphrase)
            throws IOException, GeneralSecurityException {
        if (privateKey == null) {
            throw new IllegalArgumentException("PrivateKey khong duoc null");
        }
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException("Passphrase khong duoc de trong");
        }

        // 1. Sinh Salt ngẫu nhiên 16 bytes
        byte[] salt = new byte[PBKDF2_SALT_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(salt);

        // 2. Dẫn xuất khóa bảo vệ từ passphrase
        SecretKey derivedKey = deriveKeyFromPassphrase(passphrase, salt);

        // 3. Sinh IV ngẫu nhiên 12 bytes
        byte[] iv = generateRandomIv();

        // 4. Lấy dữ liệu nhị phân PKCS#8 của Private Key và mã hóa AES-GCM
        byte[] keyEncoded = privateKey.getEncoded();
        byte[] encryptedKey;
        try {
            encryptedKey = encryptAesGcm(keyEncoded, derivedKey, iv, null);
        } finally {
            wipeMemory(keyEncoded);
        }

        // 5. Ghi theo cấu trúc: Salt (16B) || IV (12B) || EncryptedKey
        File parentDir = targetFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write(salt);
            fos.write(iv);
            fos.write(encryptedKey);
            fos.flush();
        }
    }

    /**
     * Tải và giải mã Private Key từ file cục bộ bằng passphrase.
     * Báo lỗi rõ ràng nếu user nhập sai passphrase hoặc file bị can thiệp.
     *
     * @param sourceFile File chứa khóa đã mã hóa
     * @param passphrase Mật khẩu khẩu lệnh của người dùng
     * @return Đối tượng PrivateKey khôi phục hoàn chỉnh
     * @throws IOException khi lỗi truy cập file
     * @throws GeneralSecurityException khi sai passphrase hoặc file khóa bị can thiệp
     */
    public static PrivateKey loadPrivateKey(File sourceFile, char[] passphrase)
            throws IOException, GeneralSecurityException {
        if (!sourceFile.exists()) {
            throw new IOException("File luu tru Private Key khong ton tai: " + sourceFile.getAbsolutePath());
        }
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException("Passphrase khong duoc de trong");
        }

        byte[] fileBytes = Files.readAllBytes(sourceFile.toPath());
        int headerSize = PBKDF2_SALT_LENGTH_BYTES + GCM_IV_LENGTH_BYTES;
        int minimumFileSize = headerSize + (GCM_TAG_LENGTH_BITS / 8); // Tối thiểu phải có Salt, IV và Tag

        if (fileBytes.length < minimumFileSize) {
            throw new GeneralSecurityException("File Private Key khong hop le hoac du lieu bi hong!");
        }

        // Bóc tách cấu trúc file nhị phân
        byte[] salt = Arrays.copyOfRange(fileBytes, 0, PBKDF2_SALT_LENGTH_BYTES);
        byte[] iv = Arrays.copyOfRange(fileBytes, PBKDF2_SALT_LENGTH_BYTES, headerSize);
        byte[] encryptedKey = Arrays.copyOfRange(fileBytes, headerSize, fileBytes.length);

        // Dẫn xuất khóa từ passphrase + Salt
        SecretKey derivedKey = deriveKeyFromPassphrase(passphrase, salt);

        // Giải mã Private Key bằng AES-GCM
        byte[] keyEncoded;
        try {
            keyEncoded = decryptAesGcm(encryptedKey, derivedKey, iv, null);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new GeneralSecurityException("Sai passphrase hoac file Private Key da bi can thiep!", e);
        }

        try {
            // Tái tạo lại PrivateKey từ định dạng chuẩn PKCS#8
            KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyEncoded));
        } finally {
            wipeMemory(keyEncoded);
        }
    }

    // =========================================================================
    // 5. CÁC HÀM TIỆN ÍCH HỖ TRỢ XỬ LÝ KHÓA VÀ AN TOÀN BỘ NHỚ
    // =========================================================================

    /**
     * Tái tạo đối tượng PublicKey từ mảng byte mã hóa chuẩn X.509 (SPKI).
     */
    public static PublicKey decodeRsaPublicKey(byte[] x509Bytes) throws GeneralSecurityException {
        KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
        return keyFactory.generatePublic(new X509EncodedKeySpec(x509Bytes));
    }

    /**
     * Tái tạo đối tượng PrivateKey từ mảng byte mã hóa chuẩn PKCS#8.
     */
    public static PrivateKey decodeRsaPrivateKey(byte[] pkcs8Bytes) throws GeneralSecurityException {
        KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
        return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));
    }

    /**
     * Ghi đè số 0 vào vùng nhớ mảng byte chứa thông tin nhạy cảm (Best-effort wiping).
     */
    public static void wipeMemory(byte[] sensitiveData) {
        if (sensitiveData != null) {
            Arrays.fill(sensitiveData, (byte) 0);
        }
    }

    /**
     * Ghi đè ký tự null vào vùng nhớ mảng char chứa mật khẩu (Best-effort wiping).
     */
    public static void wipeMemory(char[] sensitiveChars) {
        if (sensitiveChars != null) {
            Arrays.fill(sensitiveChars, '\0');
        }
    }
}
