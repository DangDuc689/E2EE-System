package com.e2ee.common.contract;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Plaintext Envelope bọc ngoài tất cả các gói tin trao đổi trong hệ thống.
 * 
 * Siêu dữ liệu mở (Server Blind Relay được phép đọc để định tuyến):
 * - v: Phiên bản giao thức (mặc định = 1)
 * - op: Mã lệnh Opcode
 * - id: Mã định danh duy nhất của gói tin (UUID)
 * - ts: Timestamp gửi gói tin (milliseconds)
 * - from: Username người gửi
 * - to: Username người nhận (áp dụng cho chat 1-1 hoặc lệnh cá nhân)
 * - groupId: Mã nhóm (áp dụng cho chat nhóm)
 * 
 * Dữ liệu bảo mật (Server hoàn toàn không thể đọc, mã hóa trong body):
 * - ct: Ciphertext
 * - iv: Initialization Vector
 * - ek: Encrypted Key (Wrap RSA)
 * - sig: Chữ ký số người gửi (SHA256withRSA)
 */
public final class Envelope {
    private int v = 1;
    private Opcode op;
    private String id;
    private long ts;
    private String from;
    private String to;
    private String groupId;
    private Map<String, Object> body;

    public Envelope() {
        this.id = UUID.randomUUID().toString();
        this.ts = System.currentTimeMillis();
        this.body = new HashMap<>();
    }

    public Envelope(Opcode op, String from, String to) {
        this();
        this.op = op;
        this.from = from;
        this.to = to;
    }

    public static Envelope create(Opcode op, String from, String to) {
        return new Envelope(op, from, to);
    }

    public static Envelope createGroup(Opcode op, String from, String groupId) {
        Envelope env = new Envelope();
        env.setOp(op);
        env.setFrom(from);
        env.setGroupId(groupId);
        return env;
    }

    public int getV() {
        return v;
    }

    public void setV(int v) {
        this.v = v;
    }

    public Opcode getOp() {
        return op;
    }

    public void setOp(Opcode op) {
        this.op = op;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getTs() {
        return ts;
    }

    public void setTs(long ts) {
        this.ts = ts;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Map<String, Object> getBody() {
        return body;
    }

    public void setBody(Map<String, Object> body) {
        this.body = (body != null) ? body : new HashMap<>();
    }

    public Envelope put(String key, Object value) {
        if (this.body == null) {
            this.body = new HashMap<>();
        }
        this.body.put(key, value);
        return this;
    }

    public Object get(String key) {
        return (this.body != null) ? this.body.get(key) : null;
    }

    public String getString(String key) {
        Object val = get(key);
        return (val != null) ? String.valueOf(val) : null;
    }

    /**
     * Tính AAD (Additional Authenticated Data) chuẩn phục vụ xác thực toàn vẹn & chống giả mạo:
     * Công thức: v|op|id|from|to_or_group_id|ts (UTF-8 bytes)
     */
    public byte[] computeAADBytes() {
        String target = (groupId != null && !groupId.isBlank()) ? groupId : (to != null ? to : "");
        String sender = (from != null) ? from : "";
        String opName = (op != null) ? op.name() : "";
        String aadStr = v + "|" + opName + "|" + id + "|" + sender + "|" + target + "|" + ts;
        return aadStr.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "Envelope{" +
                "v=" + v +
                ", op=" + op +
                ", id='" + id + '\'' +
                ", ts=" + ts +
                ", from='" + from + '\'' +
                ", to='" + to + '\'' +
                ", groupId='" + groupId + '\'' +
                ", bodyKeys=" + (body != null ? body.keySet() : "null") +
                '}';
    }
}
