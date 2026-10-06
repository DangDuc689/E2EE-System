package e2ee.common.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bộ Parser và Serializer JSON tối giản, thuần JDK 17 (Recursive Descent).
 * Tuân thủ quy định:
 * - Số nguyên luôn parse ra Long.
 * - Số thập phân hoặc số mũ (scientific notation) bị từ chối bằng JsonParseException.
 * - Giới hạn độ sâu lồng nhau tối đa 64 để chống tràn Call Stack (StackOverflowError).
 * - Không bổ sung khoảng trắng thừa khi stringify để tối ưu kích thước frame mạng.
 */
public final class MiniJson {
    private static final int MAX_DEPTH = 64;

    private final String src;
    private int pos;
    private int depth;

    private MiniJson(String src) {
        this.src = src;
    }

    // ======================= API PUBLIC =======================

    /**
     * Parse một chuỗi JSON thành cấu trúc đối tượng Java tương ứng.
     *
     * @param json chuỗi JSON đầu vào
     * @return đối tượng Java (Map, List, String, Long, Boolean, null)
     * @throws JsonParseException nếu cú pháp JSON không hợp lệ
     */
    public static Object parse(String json) throws JsonParseException {
        if (json == null) {
            throw new JsonParseException("Input is null", 0);
        }
        MiniJson parser = new MiniJson(json);
        parser.skipWhitespace();
        if (parser.pos >= json.length()) {
            throw parser.error("Empty input");
        }
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos != json.length()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    /**
     * Parse một chuỗi JSON với gốc bắt buộc là một JSON Object ({}).
     *
     * @param json chuỗi JSON đầu vào
     * @return Map biểu diễn JSON Object
     * @throws JsonParseException nếu cú pháp không hợp lệ hoặc phần tử gốc không phải Object
     */
    public static Map<String, Object> parseObject(String json) throws JsonParseException {
        Object res = parse(json);
        if (res instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typedMap = (Map<String, Object>) map;
            return typedMap;
        }
        throw new JsonParseException("Root must be a JSON object");
    }

    /**
     * Chuyển đổi đối tượng Java thành chuỗi JSON nhỏ gọn (không khoảng trắng thừa).
     *
     * @param value đối tượng Java cần chuyển đổi
     * @return chuỗi JSON tương ứng
     * @throws IllegalArgumentException nếu gặp kiểu dữ liệu không hỗ trợ (Float, Double, Enum...)
     */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    // ======================= PARSER ===========================

    private Object parseValue() throws JsonParseException {
        if (pos >= src.length()) {
            throw error("Unexpected end of input");
        }
        char c = peek();
        switch (c) {
            case '{':
                return parseObjectInternal();
            case '[':
                return parseArrayInternal();
            case '"':
                return parseString();
            case 't':
                return parseLiteral("true", Boolean.TRUE);
            case 'f':
                return parseLiteral("false", Boolean.FALSE);
            case 'n':
                return parseLiteral("null", null);
            default:
                if (c == '-' || isDigit(c)) {
                    return parseNumber();
                }
                throw error(c == '\0' ? "Unexpected end of input" : "Unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> parseObjectInternal() throws JsonParseException {
        enterNested();
        pos++; // bỏ qua '{'
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            depth--;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("Expected string key");
            }
            String key = parseString();
            if (map.containsKey(key)) {
                throw error("Duplicate key '" + key + "'");
            }
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, parseValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                break;
            }
            if (c != ',') {
                throw error("Expected ',' or '}'");
            }
            skipWhitespace();
            if (peek() == '}') {
                throw error("Trailing comma in object");
            }
        }
        depth--;
        return map;
    }

    private List<Object> parseArrayInternal() throws JsonParseException {
        enterNested();
        pos++; // bỏ qua '['
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            depth--;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(parseValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                break;
            }
            if (c != ',') {
                throw error("Expected ',' or ']'");
            }
            skipWhitespace();
            if (peek() == ']') {
                throw error("Trailing comma in array");
            }
        }
        depth--;
        return list;
    }

    private String parseString() throws JsonParseException {
        pos++; // bỏ qua '"' mở đầu
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("Unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw error("Unescaped control character in string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw error("Unterminated escape sequence");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    sb.append(parseHex4());
                    break;
                default:
                    throw error("Invalid escape '\\" + e + "'");
            }
        }
    }

    private char parseHex4() throws JsonParseException {
        if (pos + 4 > src.length()) {
            throw error("Incomplete \\u escape");
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            char h = src.charAt(pos++);
            int d;
            if (h >= '0' && h <= '9') {
                d = h - '0';
            } else if (h >= 'a' && h <= 'f') {
                d = h - 'a' + 10;
            } else if (h >= 'A' && h <= 'F') {
                d = h - 'A' + 10;
            } else {
                throw error("Invalid hex digit in \\u escape");
            }
            code = (code << 4) | d;
        }
        return (char) code;
    }

    private Long parseNumber() throws JsonParseException {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
            if (isDigit(peek())) {
                throw error("Leading zeros are not allowed");
            }
        } else if (isDigit(peek())) {
            while (isDigit(peek())) {
                pos++;
            }
        } else {
            throw error("Invalid number");
        }
        char c = peek();
        if (c == '.' || c == 'e' || c == 'E') {
            throw error("Decimal/exponent numbers are not supported");
        }
        try {
            return Long.parseLong(src.substring(start, pos));
        } catch (NumberFormatException ex) {
            throw error("Number out of long range");
        }
    }

    private Object parseLiteral(String word, Object value) throws JsonParseException {
        if (!src.startsWith(word, pos)) {
            throw error("Invalid literal, expected '" + word + "'");
        }
        pos += word.length();
        return value;
    }

    // ======================= TIỆN ÍCH =========================

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private char next() throws JsonParseException {
        if (pos >= src.length()) {
            throw error("Unexpected end of input");
        }
        return src.charAt(pos++);
    }

    private void expect(char expected) throws JsonParseException {
        if (next() != expected) {
            throw error("Expected '" + expected + "'");
        }
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private void enterNested() throws JsonParseException {
        if (++depth > MAX_DEPTH) {
            throw error("Nesting too deep (max " + MAX_DEPTH + ")");
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private JsonParseException error(String message) {
        return new JsonParseException(message, pos);
    }

    // ======================= SERIALIZER =======================

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            writeObject(sb, m);
        } else if (v instanceof Collection<?> c) {
            writeArray(sb, c);
        } else {
            throw new IllegalArgumentException("Unsupported JSON type: " + v.getClass().getName());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : m.entrySet()) {
            if (!(entry.getKey() instanceof String k)) {
                throw new IllegalArgumentException("JSON object key must be String: " + entry.getKey());
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, k);
            sb.append(':');
            writeValue(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Collection<?> c) {
        sb.append('[');
        boolean first = true;
        for (Object item : c) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, item);
        }
        sb.append(']');
    }
}
