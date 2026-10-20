package e2ee.client.crypto;

import e2ee.common.codec.EnvelopeMapper;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.MessageLimits;
import e2ee.common.contract.Opcode;
import e2ee.common.json.JsonParseException;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;
import java.util.Objects;

/**
 * Dịch vụ xử lý đồng bộ tin nhắn Offline và xác nhận ACK phía Client (Pure JDK 17) - Issue 12.
 * Phụ trách:
 * 1. Bóc tách và phục hồi Envelope gốc từ gói tin OFFLINE_SYNC thông qua EnvelopeMapper.
 * 2. Tự động khởi tạo gói tin phản hồi ACK mang mã ID gói tin gốc (ack_msg_id) và status DELIVERED.
 * 3. Tự động giải mã tin nhắn E2EE 1-1 (CHAT_1TO1) hoặc tin nhóm (CHAT_GROUP) với cờ isOfflineSync = true.
 * 4. Khử trùng lặp tin nhắn thông qua bộ lọc Anti-Replay seenMessageIds mà không bị lỗi lệch timestamp 120s.
 *
 * @author 2380600293_LeQuangDung
 */
public class OfflineSyncService {

    private final ClientCryptoService clientCryptoService;
    private final GroupCryptoService groupCryptoService;

    private static final OfflineSyncService DEFAULT_INSTANCE = new OfflineSyncService();

    public OfflineSyncService() {
        this(ClientCryptoService.getDefault(), GroupCryptoService.getDefault());
    }

    public OfflineSyncService(ClientCryptoService clientCryptoService, GroupCryptoService groupCryptoService) {
        this.clientCryptoService = Objects.requireNonNull(clientCryptoService, "clientCryptoService cannot be null");
        this.groupCryptoService = Objects.requireNonNull(groupCryptoService, "groupCryptoService cannot be null");
    }

    public static OfflineSyncService getDefault() {
        return DEFAULT_INSTANCE;
    }

    /**
     * Bóc tách và phục hồi đối tượng Envelope gốc từ gói tin bọc OFFLINE_SYNC.
     *
     * @param syncEnvelope Gói tin OFFLINE_SYNC nhận được từ Server
     * @return Đối tượng Envelope gốc nguyên vẹn siêu dữ liệu và body bản mã
     * @throws JsonParseException khi cấu trúc gói tin không hợp lệ
     */
    public static Envelope extractOriginalEnvelope(Envelope syncEnvelope) throws JsonParseException {
        if (syncEnvelope == null) {
            throw new IllegalArgumentException("Envelope khong duoc null");
        }
        if (syncEnvelope.getOp() != Opcode.OFFLINE_SYNC) {
            throw new IllegalArgumentException("Opcode phai la OFFLINE_SYNC, nhan duoc: " + syncEnvelope.getOp());
        }

        Object origObj = syncEnvelope.get("original_envelope");
        if (!(origObj instanceof Map<?, ?> rawMap)) {
            throw new JsonParseException("Goi tin OFFLINE_SYNC thieu truong 'original_envelope' hop le");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> casted = (Map<String, Object>) rawMap;
        return EnvelopeMapper.fromMap(casted);
    }

    /**
     * Tạo gói tin ACK chuẩn hóa phản hồi về Server để xác nhận đã nhận tin thành công.
     *
     * @param myUsername Tên người nhận tin (người gửi gói ACK)
     * @param originalMessageId UUID của gói tin gốc đã nhận
     * @return Envelope ACK hoàn chỉnh
     */
    public static Envelope createAckEnvelope(String myUsername, String originalMessageId) {
        if (myUsername == null || originalMessageId == null) {
            throw new IllegalArgumentException("myUsername va originalMessageId khong duoc null");
        }
        return Envelope.create(Opcode.ACK, myUsername, "server")
                .put("ack_msg_id", originalMessageId)
                .put("status", "DELIVERED");
    }

    /**
     * Xử lý gói tin OFFLINE_SYNC chứa tin nhắn E2EE 1-1 (CHAT_1TO1):
     * 1. Bóc tách Envelope CHAT_1TO1 gốc.
     * 2. Tạo Envelope ACK tương ứng.
     * 3. Xác minh chữ ký số và giải mã với cờ isOfflineSync = true.
     *
     * @param syncEnvelope Gói tin OFFLINE_SYNC
     * @param myUsername Tên người nhận
     * @param senderPublicKey Khóa công khai của người gửi
     * @param myPrivateKey Khóa riêng của người nhận
     * @return Đối tượng kết quả SyncResult chứa nội dung bản rõ, gói tin gốc và gói tin ACK
     */
    public SyncResult process1to1Message(Envelope syncEnvelope,
                                         String myUsername,
                                         PublicKey senderPublicKey,
                                         PrivateKey myPrivateKey)
            throws GeneralSecurityException, JsonParseException {
        Envelope original = extractOriginalEnvelope(syncEnvelope);
        if (original.getOp() != Opcode.CHAT_1TO1) {
            throw new IllegalArgumentException("Goi tin goc phai co opcode la CHAT_1TO1, nhan duoc: " + original.getOp());
        }

        ClientCryptoService.Decrypted1to1Message decrypted =
                clientCryptoService.verifyAndDecrypt1to1(
                        original, senderPublicKey, myPrivateKey, true);
        String content = decrypted.getContent();
        MessageLimits.requireWithinLimit(content);
        Envelope ack = createAckEnvelope(myUsername, original.getId());

        return new SyncResult(original, content, ack, decrypted.getExpiresAt());
    }

    /**
     * Xử lý gói tin OFFLINE_SYNC chứa tin nhắn nhóm E2EE (CHAT_GROUP):
     * 1. Bóc tách Envelope CHAT_GROUP gốc.
     * 2. Tạo Envelope ACK tương ứng.
     * 3. Xác minh chữ ký số, kiểm tra Ghost Member và giải mã với cờ isOfflineSync = true.
     *
     * @param syncEnvelope Gói tin OFFLINE_SYNC
     * @param myUsername Tên người nhận trong nhóm
     * @param senderPublicKey Khóa công khai của người gửi
     * @param myPrivateKey Khóa riêng của người nhận
     * @param expectedMembersHash Hash danh sách thành viên nhóm cục bộ
     * @return Đối tượng kết quả SyncResult chứa nội dung bản rõ, gói tin gốc và gói tin ACK
     */
    public SyncResult processGroupMessage(Envelope syncEnvelope,
                                          String myUsername,
                                          PublicKey senderPublicKey,
                                          PrivateKey myPrivateKey,
                                          String expectedMembersHash)
            throws GeneralSecurityException, JsonParseException {
        Envelope original = extractOriginalEnvelope(syncEnvelope);
        if (original.getOp() != Opcode.CHAT_GROUP) {
            throw new IllegalArgumentException("Goi tin goc phai co opcode la CHAT_GROUP, nhan duoc: " + original.getOp());
        }

        String content = groupCryptoService.verifyAndDecryptGroupMessage(
                original, myUsername, senderPublicKey, myPrivateKey, expectedMembersHash, true);
        Envelope ack = createAckEnvelope(myUsername, original.getId());

        return new SyncResult(original, content, ack, 0L);
    }

    /**
     * Lớp DTO chứa kết quả xử lý gói tin đồng bộ offline.
     */
    public static final class SyncResult {
        private final Envelope originalEnvelope;
        private final String decryptedContent;
        private final Envelope ackEnvelope;
        private final long expiresAt;

        public SyncResult(Envelope originalEnvelope, String decryptedContent, Envelope ackEnvelope) {
            this(originalEnvelope, decryptedContent, ackEnvelope, 0L);
        }

        public SyncResult(Envelope originalEnvelope, String decryptedContent, Envelope ackEnvelope, long expiresAt) {
            this.originalEnvelope = originalEnvelope;
            this.decryptedContent = decryptedContent;
            this.ackEnvelope = ackEnvelope;
            this.expiresAt = Math.max(0L, expiresAt);
        }

        public Envelope getOriginalEnvelope() {
            return originalEnvelope;
        }

        public String getDecryptedContent() {
            return decryptedContent;
        }

        public Envelope getAckEnvelope() {
            return ackEnvelope;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }

        public boolean isExpired(long now) {
            return expiresAt > 0 && expiresAt <= now;
        }
    }
}
