package com.e2ee.common.contract;

/**
 * Danh sách 15 mã lệnh chuẩn của giao thức E2EE (Protocol Specification v1).
 * Tuân thủ theo phân rã kiến trúc trong MASTER_PLAN_9_WEEKS.md.
 */
public enum Opcode {
    // Nhóm 1: Xác thực & Bắt tay (Issue 4 - Bình An)
    AUTH_CHALLENGE,
    AUTH,
    AUTH_OK,

    // Nhóm 2: Đăng ký & Tra cứu Khóa công khai (Issue 4 - Bình An)
    KEY_PUBLISH,
    KEY_LOOKUP,
    KEY_LOOKUP_RESULT,
    KEY_CHANGED,

    // Nhóm 3: Truyền tin nhắn mã hóa E2EE (Issue 5, 6, 7 - Dũng, Nghĩa)
    CHAT_1TO1,
    CHAT_GROUP,
    GROUP_CREATE,

    // Nhóm 4: Đồng bộ tin offline & Trạng thái nhận (Issue 8 - Nghĩa)
    OFFLINE_SYNC,
    ACK,

    // Nhóm 5: Hệ thống & Kiểm tra kết nối (Issue 2, 3 - Đức, An)
    ERROR,
    PING,
    PONG
}
