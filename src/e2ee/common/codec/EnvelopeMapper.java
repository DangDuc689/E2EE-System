package e2ee.common.codec;

import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.json.JsonParseException;
import e2ee.common.json.MiniJson;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bộ chuyển đổi hai chiều giữa Envelope và Map/JSON.
 * Chịu trách nhiệm bảo toàn siêu dữ liệu Envelope và dữ liệu bảo mật trong body.
 */
public final class EnvelopeMapper {

    private EnvelopeMapper() {
    }

    /**
     * Chuyển Envelope thành Map thuần túy để chuẩn bị serialize JSON.
     *
     * @param env đối tượng Envelope
     * @return Map chứa các trường dữ liệu
     */
    public static Map<String, Object> toMap(Envelope env) {
        if (env == null) {
            throw new IllegalArgumentException("Envelope cannot be null");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("v", env.getV());
        map.put("op", env.getOp() != null ? env.getOp().name() : null);
        map.put("id", env.getId());
        map.put("ts", env.getTs());
        if (env.getFrom() != null) {
            map.put("from", env.getFrom());
        }
        if (env.getTo() != null) {
            map.put("to", env.getTo());
        }
        if (env.getGroupId() != null) {
            map.put("groupId", env.getGroupId());
        }
        map.put("body", env.getBody() != null ? env.getBody() : new HashMap<>());
        return map;
    }

    /**
     * Chuyển Map dữ liệu thành đối tượng Envelope.
     *
     * @param map Map dữ liệu đầu vào
     * @return đối tượng Envelope hoàn chỉnh
     * @throws JsonParseException nếu thiếu trường bắt buộc hoặc sai kiểu dữ liệu
     */
    public static Envelope fromMap(Map<String, Object> map) throws JsonParseException {
        if (map == null) {
            throw new JsonParseException("Map cannot be null");
        }

        // Bắt buộc: v
        Object vObj = map.get("v");
        if (!(vObj instanceof Number numV)) {
            throw new JsonParseException("Field 'v' must be a number");
        }
        int v = numV.intValue();

        // Bắt buộc: op
        Object opObj = map.get("op");
        if (!(opObj instanceof String opStr)) {
            throw new JsonParseException("Field 'op' must be a String");
        }
        Opcode op;
        try {
            op = Opcode.valueOf(opStr);
        } catch (IllegalArgumentException ex) {
            throw new JsonParseException("Unknown opcode: " + opStr);
        }

        // Bắt buộc: id
        Object idObj = map.get("id");
        if (!(idObj instanceof String idStr)) {
            throw new JsonParseException("Field 'id' must be a String");
        }

        // Bắt buộc: ts
        Object tsObj = map.get("ts");
        if (!(tsObj instanceof Number numTs)) {
            throw new JsonParseException("Field 'ts' must be a number");
        }
        long ts = numTs.longValue();

        // Tùy chọn: from, to, groupId
        Object fromObj = map.get("from");
        if (fromObj != null && !(fromObj instanceof String)) {
            throw new JsonParseException("Field 'from' must be a String");
        }

        Object toObj = map.get("to");
        if (toObj != null && !(toObj instanceof String)) {
            throw new JsonParseException("Field 'to' must be a String");
        }

        Object groupObj = map.get("groupId");
        if (groupObj != null && !(groupObj instanceof String)) {
            throw new JsonParseException("Field 'groupId' must be a String");
        }

        // Tùy chọn: body
        Object bodyObj = map.get("body");
        Map<String, Object> bodyMap;
        if (bodyObj == null) {
            bodyMap = new HashMap<>();
        } else if (bodyObj instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) m;
            bodyMap = new HashMap<>(casted);
        } else {
            throw new JsonParseException("Field 'body' must be a Map/JSON object");
        }

        // Khởi tạo và ghi đè đầy đủ siêu dữ liệu (tránh id/ts bị gán mới)
        Envelope env = new Envelope();
        env.setV(v);
        env.setOp(op);
        env.setId(idStr);
        env.setTs(ts);
        if (fromObj != null) {
            env.setFrom((String) fromObj);
        }
        if (toObj != null) {
            env.setTo((String) toObj);
        }
        if (groupObj != null) {
            env.setGroupId((String) groupObj);
        }
        env.setBody(bodyMap);
        return env;
    }

    /**
     * Serialize Envelope thành chuỗi JSON.
     *
     * @param env đối tượng Envelope
     * @return chuỗi JSON
     */
    public static String toJson(Envelope env) {
        return MiniJson.stringify(toMap(env));
    }

    /**
     * Deserialize chuỗi JSON thành đối tượng Envelope.
     *
     * @param json chuỗi JSON
     * @return đối tượng Envelope
     * @throws JsonParseException nếu cú pháp JSON sai hoặc thiếu trường bắt buộc
     */
    public static Envelope fromJson(String json) throws JsonParseException {
        return fromMap(MiniJson.parseObject(json));
    }
}
