package e2ee.client.crypto;

import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.json.JsonParseException;
import e2ee.common.json.MiniJson;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Lớp Facade dịch vụ mật mã cấp cao phía Client (Pure JDK 17).
 * Phụ trách:
 * 1. Ký số Challenge-Response khi đăng nhập (SHA256withRSA).
 * 2. Tính vân tay khóa công khai (Key Fingerprint) chuẩn Hex in hoa.
 * 3. Chống tấn công phát lại (Anti-Replay) tương thích Offline Queue với cửa sổ thời gian 120s.
 * 4. Đóng gói mã hóa tin nhắn E2EE 1-1 (Envelope CHAT_1TO1).
 * 5. Xác minh chữ ký số TRƯỚC KHI giải mã và khôi phục nội dung tin nhắn.
 * 6. Quản lý dọn dẹp bộ nhớ nhạy cảm an toàn.
 */
public class ClientCryptoService {

    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    public static final String FINGERPRINT_HASH_ALGORITHM = "SHA-256";
    public static final long MAX_ALLOWED_CLOCK_SKEW_MS = 120_000L; // +-120 giây cho tin nhắn live
    public static final int MAX_SEEN_CACHE_SIZE = 10_000;

    // Bộ nhớ đệm lưu trữ danh sách id gói tin đã nhận (LRU Bounded Set, an toàn đa luồng)
    private final Set<String> seenMessageIds;

    // Singleton mặc định dùng cho các hàm tiện ích static
    private static final ClientCryptoService DEFAULT_INSTANCE = new ClientCryptoService();

    /**
     * Khởi tạo một phiên ClientCryptoService độc lập với bộ nhớ đệm Anti-Replay riêng biệt.
     */
    public ClientCryptoService() {
        Map<String, Boolean> lruMap = new LinkedHashMap<String, Boolean>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_SEEN_CACHE_SIZE;
            }
        };
        this.seenMessageIds = Collections.synchronizedSet(Collections.newSetFromMap(lruMap));
    }

    /**
     * Lấy thực thể dịch vụ mặc định dùng chung.
     */
    public static ClientCryptoService getDefault() {
        return DEFAULT_INSTANCE;
    }

    // =========================================================================
    // 1. TÍNH KEY FINGERPRINT (VÂN TAY KHÓA CÔNG KHAI)
    // =========================================================================

    /**
     * Tính toán vân tay của Public Key theo chuẩn:
     * - Lấy byte DER SPKI: publicKey.getEncoded()
     * - Băm SHA-256
     * - Chuyển sang chuỗi Hex in hoa, chia thành từng nhóm 4 ký tự cách nhau dấu cách (ví dụ: 4A8F C291 9B02 ...).
     *
     * @param key Public Key cần tính vân tay
     * @return Chuỗi Hex vân tay khóa công khai
     * @throws GeneralSecurityException khi thuật toán băm SHA-256 không khả dụng
     */
    public static String calculateFingerprint(PublicKey key) throws GeneralSecurityException {
        if (key == null) {
            throw new IllegalArgumentException("PublicKey khong duoc null");
        }
        byte[] spkiBytes = key.getEncoded();
        if (spkiBytes == null || spkiBytes.length == 0) {
            throw new IllegalArgumentException("PublicKey encoded bytes rong hoac khong hop le");
        }

        MessageDigest md = MessageDigest.getInstance(FINGERPRINT_HASH_ALGORITHM);
        byte[] hash = md.digest(spkiBytes);

        // Chuyển mảng byte sang chuỗi Hex in hoa
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            hexString.append(String.format("%02X", b));
        }

        // Định dạng chia thành các nhóm 4 ký tự cách nhau dấu cách
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < hexString.length(); i += 4) {
            if (i > 0) {
                formatted.append(" ");
            }
            formatted.append(hexString.substring(i, Math.min(i + 4, hexString.length())));
        }

        return formatted.toString();
    }

    // =========================================================================
    // 2. KÝ SỐ CHALLENGE ĐĂNG NHẬP (CHALLENGE-RESPONSE)
    // =========================================================================

    /**
     * Ký số mảng byte nonce bằng Private Key người dùng bằng thuật toán SHA256withRSA.
     *
     * @param nonce Mảng byte ngẫu nhiên nhận từ server (AUTH_CHALLENGE)
     * @param myPrivKey Khóa riêng RSA của client
     * @return Mảng byte chữ ký số
     * @throws GeneralSecurityException khi xảy ra lỗi ký số
     */
    public static byte[] signChallenge(byte[] nonce, PrivateKey myPrivKey) throws GeneralSecurityException {
        if (nonce == null || myPrivKey == null) {
            throw new IllegalArgumentException("Nonce va PrivateKey khong duoc null");
        }
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(myPrivKey);
        signature.update(nonce);
        return signature.sign();
    }

    // =========================================================================
    // 3. MÃ HÓA VÀ ĐÓNG GÓI TIN NHẮN E2EE 1-1 (CHAT_1TO1)
    // =========================================================================

    /**
     * Tạo tin nhắn CHAT_1TO1 mã hóa đầu cuối hoàn chỉnh:
     * - Chuẩn bị JSON bản rõ: {"content": "<content>"}
     * - Sinh khóa AES-256 ngẫu nhiên và IV 12 bytes ngẫu nhiên
     * - Lấy AAD từ Envelope.computeAADBytes()
     * - Mã hóa AES-GCM với AAD
     * - Bọc khóa AES bằng RSA-OAEP SHA-256 bằng Public Key của người nhận
     * - Sinh Canonical String: from|to|ek|iv|Base64(aad)|ct và ký số SHA256withRSA
     * - Đóng gói vào Envelope.body: {ek, iv, ct, sig}
     *
     * @param from Tên người gửi
     * @param to Tên người nhận
     * @param content Nội dung tin nhắn bản rõ
     * @param peerKey Khóa công khai của người nhận
     * @param myKey Khóa riêng của người gửi
     * @return Gói tin Envelope CHAT_1TO1 sẵn sàng gửi qua mạng
     * @throws GeneralSecurityException khi xảy ra lỗi mã hóa hoặc ký số
     */
    public Envelope createEncrypted1to1Message(String from, String to, String content,
                                               PublicKey peerKey, PrivateKey myKey)
            throws GeneralSecurityException {
        if (from == null || to == null || content == null || peerKey == null || myKey == null) {
            throw new IllegalArgumentException("Cac tham so khong duoc null");
        }

        // 1. Tạo Envelope với Opcode CHAT_1TO1
        Envelope env = Envelope.create(Opcode.CHAT_1TO1, from, to);

        // 2. Định dạng bản rõ trước khi mã hóa: JSON string {"content": "..."}
        Map<String, Object> plaintextJsonMap = new LinkedHashMap<>();
        plaintextJsonMap.put("content", content);
        String plaintextJson = MiniJson.stringify(plaintextJsonMap);
        byte[] plaintextBytes = plaintextJson.getBytes(StandardCharsets.UTF_8);

        byte[] wrappedKeyBytes = null;
        try {
            // 3. Sinh khóa AES-256 ngẫu nhiên và IV 12 bytes
            SecretKey aesKey = ClientCryptoEngine.generateAesKey();
            byte[] ivBytes = ClientCryptoEngine.generateRandomIv();

            // 4. Lấy dữ liệu xác thực toàn vẹn AAD từ Header Envelope
            byte[] aadBytes = env.computeAADBytes();

            // 5. Mã hóa bản rõ qua AES-GCM-256 kết hợp AAD
            byte[] ciphertextWithTag = ClientCryptoEngine.encryptAesGcm(plaintextBytes, aesKey, ivBytes, aadBytes);

            // 6. Bọc khóa AES-256 bằng RSA-OAEP SHA-256 bằng Public Key người nhận
            wrappedKeyBytes = ClientCryptoEngine.wrapAesKey(aesKey, peerKey);

            // 7. Chuyển đổi các thành phần sang Base64
            String ekBase64 = Base64.getEncoder().encodeToString(wrappedKeyBytes);
            String ivBase64 = Base64.getEncoder().encodeToString(ivBytes);
            String ctBase64 = Base64.getEncoder().encodeToString(ciphertextWithTag);
            String aadBase64 = Base64.getEncoder().encodeToString(aadBytes);

            // 8. Tạo dữ liệu ký chuẩn (Canonical Form): from|to|ek|iv|Base64(aad)|ct
            String canonicalForm = from + "|" + to + "|" + ekBase64 + "|" + ivBase64 + "|" + aadBase64 + "|" + ctBase64;
            byte[] canonicalBytes = canonicalForm.getBytes(StandardCharsets.UTF_8);

            // 9. Ký số SHA256withRSA trên Canonical Form bằng Private Key của người gửi
            Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
            signer.initSign(myKey);
            signer.update(canonicalBytes);
            byte[] signatureBytes = signer.sign();
            String sigBase64 = Base64.getEncoder().encodeToString(signatureBytes);

            // 10. Đóng gói đầy đủ các trường vào Envelope.body
            env.put("ek", ekBase64);
            env.put("iv", ivBase64);
            env.put("ct", ctBase64);
            env.put("sig", sigBase64);

            return env;
        } finally {
            // Dọn dẹp an toàn các mảng byte nhạy cảm trên bộ nhớ RAM (best-effort)
            wipeMemory(plaintextBytes);
            wipeMemory(wrappedKeyBytes);
        }
    }

    /**
     * Phương thức tiện ích static tạo tin nhắn 1-1 qua thực thể mặc định.
     */
    public static Envelope createEncrypted1to1MessageStatic(String from, String to, String content,
                                                            PublicKey peerKey, PrivateKey myKey)
            throws GeneralSecurityException {
        return DEFAULT_INSTANCE.createEncrypted1to1Message(from, to, content, peerKey, myKey);
    }

    // =========================================================================
    // 4. XÁC MINH VÀ GIẢI MÃ TIN NHẮN E2EE 1-1
    // =========================================================================

    /**
     * Xác minh chữ ký số và giải mã tin nhắn E2EE 1-1:
     * - Kiểm tra Anti-Replay sơ bộ:
     *   + Nếu là tin live: từ chối nếu timestamp lệch quá +-120 giây hoặc id đã có trong cache.
     *   + Nếu là tin offline sync: bỏ qua kiểm tra timestamp, chỉ từ chối nếu id đã có trong cache.
     * - Bóc tách body: ek, iv, ct, sig.
     * - Tái tạo Canonical Form: from|to|ek|iv|Base64(aad)|ct.
     * - XÁC MINH CHỮ KÝ TRƯỚC KHI GIẢI MÃ bằng Public Key của người gửi.
     * - Mở bọc khóa AES qua RSA-OAEP SHA-256 bằng Private Key của mình.
     * - Giải mã AES-GCM với AAD và khôi phục nội dung JSON.
     * - GHI NHẬN ID vào seenMessageIds CHỈ SAU KHI verify và giải mã thành công.
     * - Dọn dẹp RAM mảng byte giải mã nhạy cảm.
     *
     * @param env Gói tin Envelope nhận được
     * @param peerKey Public Key của người gửi (dùng để verify chữ ký)
     * @param myKey Private Key của mình (dùng để unwrap khóa AES)
     * @param isOfflineSync true nếu gói tin đến từ hàng đợi đồng bộ offline, false nếu tin nhắn live
     * @return Chuỗi nội dung tin nhắn ban đầu (trường "content")
     * @throws SecurityException khi phát hiện phát lại, timestamp sai lệch hoặc chữ ký số không hợp lệ
     * @throws GeneralSecurityException khi lỗi thuật toán mã hóa hoặc khóa không đúng
     * @throws JsonParseException khi bản rõ giải mã ra không phải JSON hợp lệ
     */
    public String verifyAndDecrypt1to1Message(Envelope env, PublicKey peerKey, PrivateKey myKey,
                                              boolean isOfflineSync)
            throws GeneralSecurityException, JsonParseException {
        if (env == null || peerKey == null || myKey == null) {
            throw new IllegalArgumentException("Tham so khong duoc null");
        }

        String msgId = env.getId();
        if (msgId == null || msgId.isBlank()) {
            throw new SecurityException("Goi tin khong co id hop le");
        }

        // 1. Kiểm tra Anti-Replay: Khử trùng lặp ID
        if (seenMessageIds.contains(msgId)) {
            throw new SecurityException("Phat hien tin nhan phat lai (Replay Attack)! Id da ton tai: " + msgId);
        }

        // 2. Kiểm tra cửa sổ thời gian (Timestamp skew) đối với tin nhắn Live
        long now = System.currentTimeMillis();
        long msgTs = env.getTs();
        if (!isOfflineSync) {
            long skew = Math.abs(now - msgTs);
            if (skew > MAX_ALLOWED_CLOCK_SKEW_MS) {
                throw new SecurityException("Goi tin live bi tu choi do lech timestamp qua 120 giay (lech "
                        + (skew / 1000) + "s, ts=" + msgTs + ", now=" + now + ")");
            }
        }

        // 3. Trích xuất các trường mã hóa từ Envelope.body
        String ekBase64 = env.getString("ek");
        String ivBase64 = env.getString("iv");
        String ctBase64 = env.getString("ct");
        String sigBase64 = env.getString("sig");

        if (ekBase64 == null || ivBase64 == null || ctBase64 == null || sigBase64 == null) {
            throw new SecurityException("Thieu truong ma hoa bat buoc trong Envelope.body (ek, iv, ct, sig)");
        }

        // 4. Tái tạo dữ liệu xác thực AAD từ Header Envelope
        byte[] aadBytes = env.computeAADBytes();
        String aadBase64 = Base64.getEncoder().encodeToString(aadBytes);

        // 5. Tái tạo chuỗi dữ liệu ký chuẩn (Canonical Form): from|to|ek|iv|Base64(aad)|ct
        String sender = (env.getFrom() != null) ? env.getFrom() : "";
        String recipient = (env.getTo() != null) ? env.getTo() : "";
        String canonicalForm = sender + "|" + recipient + "|" + ekBase64 + "|" + ivBase64 + "|" + aadBase64 + "|" + ctBase64;
        byte[] canonicalBytes = canonicalForm.getBytes(StandardCharsets.UTF_8);

        // 6. BẮT BUỘC: XÁC MINH CHỮ KÝ SỐ TRƯỚC KHI GIẢI MÃ
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(sigBase64);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Chu ky Base64 bi loi dinh dang", e);
        }

        Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
        verifier.initVerify(peerKey);
        verifier.update(canonicalBytes);
        boolean isSignatureValid = verifier.verify(signatureBytes);

        if (!isSignatureValid) {
            throw new SecurityException("Chu ky so SHA256withRSA khong hop le hoac du lieu bi can thiep!");
        }

        // 7. Giải mã dữ liệu sau khi chữ ký đã được xác thực an toàn
        byte[] wrappedKeyBytes = Base64.getDecoder().decode(ekBase64);
        byte[] ivBytes = Base64.getDecoder().decode(ivBase64);
        byte[] ciphertextWithTag = Base64.getDecoder().decode(ctBase64);

        byte[] plaintextBytes = null;
        try {
            // Mở bọc khóa AES bằng Private Key của người nhận
            SecretKey aesKey = ClientCryptoEngine.unwrapAesKey(wrappedKeyBytes, myKey);

            // Giải mã AES-GCM với AAD
            plaintextBytes = ClientCryptoEngine.decryptAesGcm(ciphertextWithTag, aesKey, ivBytes, aadBytes);

            // 8. Chuyển đổi bản rõ và trích xuất trường "content" từ JSON
            String jsonPlaintext = new String(plaintextBytes, StandardCharsets.UTF_8);
            Map<String, Object> map = MiniJson.parseObject(jsonPlaintext);
            Object contentObj = map.get("content");
            if (contentObj == null) {
                throw new SecurityException("Ban ro JSON thieu truong 'content': " + jsonPlaintext);
            }

            // 9. BẤT BIẾN AN TOÀN: CHỈ ghi nhận ID vào seenMessageIds SAU KHI chữ ký & giải mã thành công!
            seenMessageIds.add(msgId);

            return String.valueOf(contentObj);
        } finally {
            // Dọn dẹp RAM an toàn
            wipeMemory(wrappedKeyBytes);
            wipeMemory(plaintextBytes);
        }
    }

    /**
     * Phương thức tiện ích static xác minh và giải mã qua thực thể mặc định.
     */
    public static String verifyAndDecrypt1to1MessageStatic(Envelope env, PublicKey peerKey,
                                                           PrivateKey myKey, boolean isOfflineSync)
            throws GeneralSecurityException, JsonParseException {
        return DEFAULT_INSTANCE.verifyAndDecrypt1to1Message(env, peerKey, myKey, isOfflineSync);
    }

    // =========================================================================
    // 5. QUẢN LÝ CACHE ANTI-REPLAY & TIỆN ÍCH DỌN DẸP BỘ NHỚ
    // =========================================================================

    /**
     * Kiểm tra xem một ID tin nhắn đã được ghi nhận trong cache chống phát lại chưa.
     */
    public boolean isMessageSeen(String messageId) {
        return messageId != null && seenMessageIds.contains(messageId);
    }

    /**
     * Xóa toàn bộ ID trong bộ nhớ đệm chống phát lại (phục vụ reset hoặc kiểm thử).
     */
    public void clearSeenMessages() {
        seenMessageIds.clear();
    }

    /**
     * Ghi đè số 0 vào vùng nhớ mảng byte chứa thông tin nhạy cảm (Best-effort wiping).
     */
    public static void wipeMemory(byte[] sensitiveData) {
        ClientCryptoEngine.wipeMemory(sensitiveData);
    }

    /**
     * Ghi đè ký tự null vào vùng nhớ mảng char chứa mật khẩu (Best-effort wiping).
     */
    public static void wipeMemory(char[] sensitiveChars) {
        ClientCryptoEngine.wipeMemory(sensitiveChars);
    }
}
