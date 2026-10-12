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
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Dịch vụ mật mã E2EE cho Chat nhóm (Pure JDK 17) - Issue 11.
 * Phụ trách:
 * 1. Tính toán members_hash chống tấn công thành viên ma (Ghost Member Attack).
 * 2. Chuẩn hóa chuỗi khóa đối xứng đa người nhận (keysCanonical) bằng TreeMap.
 * 3. Tạo chuỗi dữ liệu ký chuẩn (Canonical Form) cho gói tin CHAT_GROUP.
 * 4. Đóng gói mã hóa đa người nhận (Multi-recipient Key Wrap: 1 AES key bọc N lần qua RSA-OAEP).
 * 5. Xác minh chữ ký số TRƯỚC KHI giải mã và khôi phục nội dung tin nhắn nhóm.
 * 6. Bộ lọc Anti-Replay tương thích đồng bộ Offline.
 */
public class GroupCryptoService {

    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    public static final String HASH_ALGORITHM = "SHA-256";
    public static final long MAX_ALLOWED_CLOCK_SKEW_MS = 120_000L; // +-120s cho tin nhắn live
    public static final int MAX_SEEN_CACHE_SIZE = 10_000;

    // Bộ nhớ đệm lưu trữ danh sách ID gói tin nhóm đã nhận (LRU Bounded Set an toàn đa luồng)
    private final Set<String> seenMessageIds;

    // Thực thể mặc định dùng chung
    private static final GroupCryptoService DEFAULT_INSTANCE = new GroupCryptoService();

    public GroupCryptoService() {
        Map<String, Boolean> lruMap = new LinkedHashMap<String, Boolean>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_SEEN_CACHE_SIZE;
            }
        };
        this.seenMessageIds = Collections.synchronizedSet(Collections.newSetFromMap(lruMap));
    }

    public static GroupCryptoService getDefault() {
        return DEFAULT_INSTANCE;
    }

    // =========================================================================
    // 1. CHUẨN HÓA FINGERPRINT & TÍNH MEMBERS_HASH (CHỐNG GHOST MEMBER)
    // =========================================================================

    /**
     * Chuẩn hóa vân tay khóa công khai: loại bỏ toàn bộ khoảng cách trắng và chuyển sang chữ hoa.
     * Ví dụ: "4A8F C291 9B02 ..." -> "4A8FC2919B02..."
     *
     * @param fingerprint Chuỗi fingerprint gốc (có thể có khoảng cách)
     * @return Chuỗi fingerprint viết liền không khoảng cách
     */
    public static String cleanFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("Fingerprint khong duoc rong hoac null");
        }
        return fingerprint.replace(" ", "").trim().toUpperCase();
    }

    /**
     * Tính toán members_hash từ danh sách thành viên và fingerprint tương ứng:
     * - Sắp xếp theo thứ tự alphabet của username tăng dần.
     * - Mỗi phần tử định dạng: username:fingerprint (fingerprint viết liền).
     * - Nối các phần tử bằng dấu phẩy ','.
     * - Băm SHA-256 và encode Base64.
     *
     * @param memberFingerprints Map username -> fingerprint
     * @return Chuỗi Base64 của băm SHA-256
     */
    public static String computeMembersHashFromFingerprints(Map<String, String> memberFingerprints) {
        if (memberFingerprints == null || memberFingerprints.isEmpty()) {
            throw new IllegalArgumentException("Danh sach memberFingerprints khong duoc rong");
        }

        // Bước 1: Sắp xếp theo alphabet của username bằng TreeMap
        TreeMap<String, String> sorted = new TreeMap<>(memberFingerprints);

        // Bước 2: Nối các chuỗi username:fingerprint (bỏ dấu cách) ngăn cách bởi dấu phẩy
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            String username = entry.getKey();
            String fp = entry.getValue();
            if (username == null || username.isBlank() || fp == null || fp.isBlank()) {
                throw new IllegalArgumentException("Username va Fingerprint cua tung thanh vien khong duoc rong");
            }
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append(username).append(":").append(cleanFingerprint(fp));
        }

        // Bước 3: Tính băm SHA-256 và mã hóa Base64
        try {
            MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Thuat toan SHA-256 khong kha dung", e);
        }
    }

    /**
     * Tiện ích tính members_hash trực tiếp từ Map username -> PublicKey.
     *
     * @param memberKeys Map chứa username và PublicKey của từng thành viên
     * @return Chuỗi Base64 members_hash
     * @throws GeneralSecurityException khi lỗi tính fingerprint
     */
    public static String computeMembersHash(Map<String, PublicKey> memberKeys) throws GeneralSecurityException {
        if (memberKeys == null || memberKeys.isEmpty()) {
            throw new IllegalArgumentException("Danh sach memberKeys khong duoc rong");
        }
        Map<String, String> fingerprints = new HashMap<>();
        for (Map.Entry<String, PublicKey> entry : memberKeys.entrySet()) {
            String fp = ClientCryptoService.calculateFingerprint(entry.getValue());
            fingerprints.put(entry.getKey(), fp);
        }
        return computeMembersHashFromFingerprints(fingerprints);
    }

    // =========================================================================
    // 2. CHUẨN HÓA KEYS CANONICAL & DỮ LIỆU KÝ CANONICAL FORM
    // =========================================================================

    /**
     * Chuẩn hóa danh sách keys (Multi-recipient wrapped keys):
     * - Sắp xếp theo username tăng dần bằng TreeMap.
     * - Nối thành chuỗi: username1=wrapped_key1,username2=wrapped_key2,...
     *
     * @param keys Map username -> Base64 wrapped AES key
     * @return Chuỗi keysCanonical chuẩn hóa
     */
    public static String buildKeysCanonical(Map<?, ?> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("Map keys khong duoc rong");
        }

        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : keys.entrySet()) {
            sorted.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * Xây dựng chuỗi dữ liệu ký chuẩn (Canonical Form) cho gói tin CHAT_GROUP:
     * Công thức: from|groupId|keysCanonical|iv|Base64(aad)|ct|members_hash
     *
     * @param from Tên người gửi
     * @param groupId ID nhóm chat
     * @param keysCanonical Chuỗi keys đã chuẩn hóa
     * @param iv Base64 của IV 12 bytes
     * @param aadBase64 Base64 của mảng byte AAD
     * @param ct Base64 của ciphertext AES-GCM
     * @param membersHash Chuỗi Base64 members_hash
     * @return Chuỗi Canonical Form
     */
    public static String buildCanonicalForm(String from, String groupId, String keysCanonical,
                                            String iv, String aadBase64, String ct, String membersHash) {
        return (from != null ? from : "") + "|"
                + (groupId != null ? groupId : "") + "|"
                + (keysCanonical != null ? keysCanonical : "") + "|"
                + (iv != null ? iv : "") + "|"
                + (aadBase64 != null ? aadBase64 : "") + "|"
                + (ct != null ? ct : "") + "|"
                + (membersHash != null ? membersHash : "");
    }

    // =========================================================================
    // 3. MÃ HÓA ĐA NGƯỜI NHẬN & ĐÓNG GÓI TIN NHẮN NHÓM (CHAT_GROUP)
    // =========================================================================

    /**
     * Đóng gói mã hóa tin nhắn nhóm CHAT_GROUP:
     * - Sinh duy nhất 1 khóa AES-256 và IV 12 bytes cho mỗi tin nhắn.
     * - Mã hóa nội dung bằng AES-GCM với AAD lấy từ Envelope.computeAADBytes() (chứa groupId).
     * - Bọc khóa AES đó bằng Public Key của từng thành viên trong nhóm qua RSA-OAEP.
     * - Sắp xếp keysCanonical bằng TreeMap.
     * - Ký số SHA256withRSA trên Canonical Form: from|groupId|keysCanonical|iv|Base64(aad)|ct|members_hash.
     * - Đóng gói vào Envelope.body: {keys, iv, ct, members_hash, sig}.
     *
     * @param from Tên người gửi
     * @param groupId ID nhóm chat
     * @param content Nội dung tin nhắn bản rõ
     * @param recipientKeys Map username -> PublicKey của các thành viên nhận tin
     * @param myPrivateKey Khóa riêng của người gửi để ký số
     * @param membersHash Chuỗi Base64 hash thành viên nhóm
     * @return Envelope CHAT_GROUP hoàn chỉnh
     * @throws GeneralSecurityException khi lỗi mã hóa hoặc ký số
     */
    public Envelope createEncryptedGroupMessage(String from, String groupId, String content,
                                                Map<String, PublicKey> recipientKeys,
                                                PrivateKey myPrivateKey,
                                                String membersHash)
            throws GeneralSecurityException {
        if (from == null || groupId == null || content == null
                || recipientKeys == null || recipientKeys.isEmpty()
                || myPrivateKey == null || membersHash == null) {
            throw new IllegalArgumentException("Cac tham so khong duoc null hoac rong");
        }

        // 1. Tạo Envelope CHAT_GROUP
        Envelope env = Envelope.createGroup(Opcode.CHAT_GROUP, from, groupId);

        // 2. Chuẩn bị bản rõ JSON: {"content": "..."}
        Map<String, Object> plaintextJsonMap = new LinkedHashMap<>();
        plaintextJsonMap.put("content", content);
        String plaintextJson = MiniJson.stringify(plaintextJsonMap);
        byte[] plaintextBytes = plaintextJson.getBytes(StandardCharsets.UTF_8);

        Map<String, Object> keysBodyMap = new LinkedHashMap<>();
        try {
            // 3. Sinh duy nhất 1 khóa AES-256 và IV 12 bytes
            SecretKey aesKey = ClientCryptoEngine.generateAesKey();
            byte[] ivBytes = ClientCryptoEngine.generateRandomIv();

            // 4. Lấy dữ liệu AAD từ Envelope Header (v|op|id|from|groupId|ts)
            byte[] aadBytes = env.computeAADBytes();

            // 5. Mã hóa AES-GCM với AAD
            byte[] ctBytes = ClientCryptoEngine.encryptAesGcm(plaintextBytes, aesKey, ivBytes, aadBytes);

            // 6. Bọc khóa AES cho từng thành viên nhận tin (Multi-recipient wrapping)
            for (Map.Entry<String, PublicKey> entry : recipientKeys.entrySet()) {
                String memberUsername = entry.getKey();
                PublicKey memberKey = entry.getValue();
                byte[] wrapped = ClientCryptoEngine.wrapAesKey(aesKey, memberKey);
                keysBodyMap.put(memberUsername, Base64.getEncoder().encodeToString(wrapped));
                ClientCryptoEngine.wipeMemory(wrapped);
            }

            // 7. Chuẩn hóa chuỗi Base64
            String ivBase64 = Base64.getEncoder().encodeToString(ivBytes);
            String ctBase64 = Base64.getEncoder().encodeToString(ctBytes);
            String aadBase64 = Base64.getEncoder().encodeToString(aadBytes);
            String keysCanonical = buildKeysCanonical(keysBodyMap);

            // 8. Tạo dữ liệu ký Canonical Form
            String canonical = buildCanonicalForm(from, groupId, keysCanonical, ivBase64, aadBase64, ctBase64, membersHash);
            byte[] canonicalBytes = canonical.getBytes(StandardCharsets.UTF_8);

            // 9. Ký số SHA256withRSA bằng Private Key của người gửi
            Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
            signer.initSign(myPrivateKey);
            signer.update(canonicalBytes);
            byte[] sigBytes = signer.sign();
            String sigBase64 = Base64.getEncoder().encodeToString(sigBytes);

            // 10. Đóng gói Envelope.body
            env.put("keys", keysBodyMap);
            env.put("iv", ivBase64);
            env.put("ct", ctBase64);
            env.put("members_hash", membersHash);
            env.put("sig", sigBase64);

            return env;
        } finally {
            // Dọn dẹp RAM an toàn
            ClientCryptoEngine.wipeMemory(plaintextBytes);
        }
    }

    // =========================================================================
    // 4. XÁC MINH & GIẢI MÃ TIN NHẮN NHÓM (CHAT_GROUP)
    // =========================================================================

    /**
     * Xác minh chữ ký số và giải mã tin nhắn nhóm CHAT_GROUP:
     * - Kiểm tra Anti-Replay sơ bộ: từ chối ID trùng lặp, kiểm tra skew +-120s đối với tin live.
     * - Chống Ghost Member: So khớp members_hash với expectedMembersHash. Nếu lệch -> chặn ngay!
     * - Tái tạo Canonical Form: from|groupId|keysCanonical|iv|Base64(aad)|ct|members_hash.
     * - XÁC MINH CHỮ KÝ SỐ TRƯỚC KHI GIẢI MÃ bằng Public Key của người gửi.
     * - Tìm khóa AES bọc cho myUsername trong keys, mở bọc bằng Private Key của mình.
     * - Giải mã AES-GCM với AAD và trích xuất nội dung tin nhắn.
     * - BẤT BIẾN AN TOÀN: GHI NHẬN ID vào seenMessageIds CHỈ SAU KHI verify và giải mã thành công!
     *
     * @param env Gói tin Envelope CHAT_GROUP
     * @param myUsername Tên người nhận (để tìm khóa bọc của mình)
     * @param senderPublicKey Khóa công khai của người gửi
     * @param myPrivateKey Khóa riêng của người nhận
     * @param expectedMembersHash Chuỗi Base64 members_hash cục bộ đáng tin của nhóm
     * @param isOfflineSync true nếu tin nhắn đến từ hàng đợi đồng bộ offline
     * @return Chuỗi nội dung tin nhắn ban đầu (trường "content")
     * @throws SecurityException khi phát hiện phát lại, chữ ký sai hoặc phát hiện Ghost Member
     * @throws GeneralSecurityException khi lỗi giải mã khóa hoặc AES
     * @throws JsonParseException khi lỗi parse JSON bản rõ
     */
    public String verifyAndDecryptGroupMessage(Envelope env,
                                               String myUsername,
                                               PublicKey senderPublicKey,
                                               PrivateKey myPrivateKey,
                                               String expectedMembersHash,
                                               boolean isOfflineSync)
            throws GeneralSecurityException, JsonParseException {
        if (env == null || myUsername == null || senderPublicKey == null
                || myPrivateKey == null || expectedMembersHash == null) {
            throw new IllegalArgumentException("Tham so khong duoc null");
        }

        if (env.getOp() != Opcode.CHAT_GROUP) {
            throw new IllegalArgumentException("Envelope opcode phai la CHAT_GROUP, nhan duoc: " + env.getOp());
        }

        String msgId = env.getId();
        if (msgId == null || msgId.isBlank()) {
            throw new SecurityException("Goi tin khong co id hop le");
        }

        // 1. Kiểm tra Anti-Replay: Khử trùng lặp ID
        if (seenMessageIds.contains(msgId)) {
            throw new SecurityException("Phat hien tin nhan nhom phat lai (Replay Attack)! Id da ton tai: " + msgId);
        }

        // 2. Kiểm tra cửa sổ thời gian (Timestamp skew) đối với tin nhắn Live
        long now = System.currentTimeMillis();
        long msgTs = env.getTs();
        if (!isOfflineSync) {
            long skew = Math.abs(now - msgTs);
            if (skew > MAX_ALLOWED_CLOCK_SKEW_MS) {
                throw new SecurityException("Goi tin nhom live bi tu choi do lech timestamp qua 120 giay (lech "
                        + (skew / 1000) + "s)");
            }
        }

        // 3. Trích xuất các trường từ Envelope.body
        Object keysObj = env.get("keys");
        if (!(keysObj instanceof Map<?, ?> rawKeysMap)) {
            throw new SecurityException("Envelope.body thieu truong 'keys' hop le");
        }

        String ivBase64 = env.getString("iv");
        String ctBase64 = env.getString("ct");
        String membersHash = env.getString("members_hash");
        String sigBase64 = env.getString("sig");

        if (ivBase64 == null || ctBase64 == null || membersHash == null || sigBase64 == null) {
            throw new SecurityException("Thieu truong bat buoc trong Envelope.body (iv, ct, members_hash, sig)");
        }

        // 4. CHỐNG GHOST MEMBER ATTACK: So khớp members_hash với giá trị tính từ danh bạ nhóm cục bộ
        if (!Objects.equals(expectedMembersHash, membersHash)) {
            throw new SecurityException("CANH BAO AN TOAN: Phat hien tan cong thanh vien ma (Ghost Member Attack)! "
                    + "members_hash khong khop voi danh ba nhom cuc bo.");
        }

        // 5. Kiểm tra người nhận có khóa bọc trong danh sách keys không
        Object myWrappedKeyObj = rawKeysMap.get(myUsername);
        if (myWrappedKeyObj == null) {
            throw new SecurityException("Nguoi nhan '" + myUsername + "' khong co khoa duoc boc trong goi tin nhom");
        }
        String myWrappedKeyBase64 = String.valueOf(myWrappedKeyObj);

        // 6. Tái tạo keysCanonical và Canonical Form
        String keysCanonical = buildKeysCanonical(rawKeysMap);
        byte[] aadBytes = env.computeAADBytes();
        String aadBase64 = Base64.getEncoder().encodeToString(aadBytes);

        String sender = (env.getFrom() != null) ? env.getFrom() : "";
        String groupId = (env.getGroupId() != null) ? env.getGroupId() : "";
        String canonical = buildCanonicalForm(sender, groupId, keysCanonical, ivBase64, aadBase64, ctBase64, membersHash);
        byte[] canonicalBytes = canonical.getBytes(StandardCharsets.UTF_8);

        // 7. BẮT BUỘC: XÁC MINH CHỮ KÝ SỐ TRƯỚC KHI GIẢI MÃ
        byte[] sigBytes;
        try {
            sigBytes = Base64.getDecoder().decode(sigBase64);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Chu ky Base64 bi loi dinh dang", e);
        }

        boolean isValid;
        try {
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(senderPublicKey);
            verifier.update(canonicalBytes);
            isValid = verifier.verify(sigBytes);
        } catch (java.security.SignatureException e) {
            throw new SecurityException("Chu ky so SHA256withRSA tin nhan nhom khong hop le hoac du lieu bi can thiep!", e);
        }

        if (!isValid) {
            throw new SecurityException("Chu ky so SHA256withRSA tin nhan nhom khong hop le hoac du lieu bi can thiep!");
        }

        // 8. Giải mã dữ liệu sau khi chữ ký đã được kiểm tra an toàn
        byte[] wrappedKeyBytes = Base64.getDecoder().decode(myWrappedKeyBase64);
        byte[] ivBytes = Base64.getDecoder().decode(ivBase64);
        byte[] ctBytes = Base64.getDecoder().decode(ctBase64);

        byte[] plaintextBytes = null;
        try {
            // Mở bọc khóa AES qua RSA-OAEP SHA-256 bằng Private Key của mình
            SecretKey aesKey = ClientCryptoEngine.unwrapAesKey(wrappedKeyBytes, myPrivateKey);

            // Giải mã AES-GCM với AAD
            plaintextBytes = ClientCryptoEngine.decryptAesGcm(ctBytes, aesKey, ivBytes, aadBytes);

            // Trích xuất nội dung JSON
            String jsonPlaintext = new String(plaintextBytes, StandardCharsets.UTF_8);
            Map<String, Object> map = MiniJson.parseObject(jsonPlaintext);
            Object contentObj = map.get("content");
            if (contentObj == null) {
                throw new SecurityException("Ban ro JSON thieu truong 'content': " + jsonPlaintext);
            }

            // 9. BẤT BIẾN AN TOÀN: CHỈ ghi nhận ID vào seenMessageIds SAU KHI verify & giải mã thành công!
            seenMessageIds.add(msgId);

            return String.valueOf(contentObj);
        } finally {
            ClientCryptoEngine.wipeMemory(wrappedKeyBytes);
            ClientCryptoEngine.wipeMemory(plaintextBytes);
        }
    }

    // =========================================================================
    // 5. TIỆN ÍCH QUẢN LÝ ANTI-REPLAY CACHE
    // =========================================================================

    public boolean isMessageSeen(String messageId) {
        return messageId != null && seenMessageIds.contains(messageId);
    }

    public void clearSeenMessages() {
        seenMessageIds.clear();
    }
}
