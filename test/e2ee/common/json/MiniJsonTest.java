package e2ee.common.json;

import e2ee.common.TestSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MiniJsonTest {

    public static void main(String[] args) {
        // Nhom ca test hop le (J1 - J7)
        TestSupport.check("J1 - Parse chuoi tieng Viet co dau", MiniJsonTest::testVietnameseUnicode);
        TestSupport.check("J2 - Giai ma cac escape sequence", MiniJsonTest::testEscapes);
        TestSupport.check("J3 - Cau truc long nhau phuc tap", MiniJsonTest::testNestedStructures);
        TestSupport.check("J4 - Cac kieu nguyen thuy va so Long", MiniJsonTest::testPrimitivesAndLong);
        TestSupport.check("J5 - Object/Array rong va khoang trang thua", MiniJsonTest::testEmptyAndWhitespace);
        TestSupport.check("J6 - Round-trip Map -> JSON -> Map", MiniJsonTest::testRoundTrip);
        TestSupport.check("J7 - Stringify escape ky tu dac biet", MiniJsonTest::testStringifyEscapes);

        // Nhom ca test loi (E1 - E13)
        TestSupport.check("E1 - Thieu gia tri sau dau hai cham", MiniJsonTest::testMissingValue);
        TestSupport.check("E2 - Dau phay thua o cuoi", MiniJsonTest::testTrailingComma);
        TestSupport.check("E3 - Thieu dau phay ngan cach", MiniJsonTest::testMissingComma);
        TestSupport.check("E4 - Chuoi khong dong ngoac kep", MiniJsonTest::testUnterminatedString);
        TestSupport.check("E5 - Tu choi so thap phan va dang mu", MiniJsonTest::testDecimalsAndExponents);
        TestSupport.check("E6 - Noi dung rac o cuoi", MiniJsonTest::testTrailingContent);
        TestSupport.check("E7 - Key khong boc ngoac kep", MiniJsonTest::testUnquotedKey);
        TestSupport.check("E8 - Ky tu escape sai cu phap", MiniJsonTest::testInvalidEscapes);
        TestSupport.check("E9 - So co so 0 vo nghia o dau", MiniJsonTest::testLeadingZero);
        TestSupport.check("E10 - So vuot qua pham vi Long", MiniJsonTest::testNumberOverflow);
        TestSupport.check("E11 - Vuot qua do sau long nhau toi da", MiniJsonTest::testMaxDepthExceeded);
        TestSupport.check("E12 - Trung key trong Object", MiniJsonTest::testDuplicateKey);
        TestSupport.check("E13 - Chuoi rong, khoang trang hoac null", MiniJsonTest::testEmptyInputs);

        // Nhom ca test serializer tu choi kieu khong ho tro
        TestSupport.check("S1 - Serializer tu choi Double/Float", MiniJsonTest::testSerializerUnsupportedType);

        TestSupport.summary();
    }

    private static void testVietnameseUnicode() throws Exception {
        String json = "{\"msg\":\"Xin chào, Việt Nam! Đặng Đức\"}";
        Map<String, Object> map = MiniJson.parseObject(json);
        TestSupport.assertEquals("Xin chào, Việt Nam! Đặng Đức", map.get("msg"));
    }

    private static void testEscapes() throws Exception {
        String json = "\"\\\" \\\\ \\n \\r \\t \\b \\f \\u00e9\"";
        Object result = MiniJson.parse(json);
        TestSupport.assertEquals("\" \\ \n \r \t \b \f \u00e9", result);
    }

    private static void testNestedStructures() throws Exception {
        String json = "{\"level1\":{\"level2\":{\"level3\":[1, [2, 3]]}}}";
        Map<String, Object> map = MiniJson.parseObject(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> l1 = (Map<String, Object>) map.get("level1");
        @SuppressWarnings("unchecked")
        Map<String, Object> l2 = (Map<String, Object>) l1.get("level2");
        @SuppressWarnings("unchecked")
        List<Object> l3 = (List<Object>) l2.get("level3");
        TestSupport.assertEquals(1L, l3.get(0));
        @SuppressWarnings("unchecked")
        List<Object> inner = (List<Object>) l3.get(1);
        TestSupport.assertEquals(2L, inner.get(0));
        TestSupport.assertEquals(3L, inner.get(1));
    }

    private static void testPrimitivesAndLong() throws Exception {
        TestSupport.assertEquals(Boolean.TRUE, MiniJson.parse("true"));
        TestSupport.assertEquals(Boolean.FALSE, MiniJson.parse("false"));
        TestSupport.assertEquals(null, MiniJson.parse("null"));
        TestSupport.assertEquals(0L, MiniJson.parse("0"));
        TestSupport.assertEquals(-42L, MiniJson.parse("-42"));
        TestSupport.assertEquals(Long.MAX_VALUE, MiniJson.parse(String.valueOf(Long.MAX_VALUE)));
    }

    private static void testEmptyAndWhitespace() throws Exception {
        TestSupport.assertEquals(new LinkedHashMap<String, Object>(), MiniJson.parse(" \r\n {\t} \n"));
        TestSupport.assertEquals(new ArrayList<Object>(), MiniJson.parse(" [ \n\t] "));
    }

    private static void testRoundTrip() throws Exception {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("name", "Bob");
        original.put("age", 25L);
        original.put("active", true);
        original.put("data", null);

        List<Object> items = new ArrayList<>();
        items.add(100L);
        items.add("abc");
        original.put("items", items);

        String json = MiniJson.stringify(original);
        Map<String, Object> parsed = MiniJson.parseObject(json);
        TestSupport.assertEquals(original, parsed);
    }

    private static void testStringifyEscapes() throws Exception {
        String original = "line1\nline2\t\"quoted\"\\backslash";
        String json = MiniJson.stringify(original);
        TestSupport.assertEquals(original, MiniJson.parse(json));
    }

    private static void testMissingValue() {
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("{\"a\":}"));
    }

    private static void testTrailingComma() {
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("{\"a\":1,}"));
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("[1,2,]"));
    }

    private static void testMissingComma() {
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("[1 2]"));
    }

    private static void testUnterminatedString() {
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("\"abc"));
    }

    private static void testDecimalsAndExponents() {
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("1.5"));
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("1e3"));
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("-2.0"));
    }

    private static void testTrailingContent() {
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("{\"a\":1} xyz"));
    }

    private static void testUnquotedKey() {
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("{a:1}"));
    }

    private static void testInvalidEscapes() {
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("\"\\x\""));
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("\"\\u12\""));
    }

    private static void testLeadingZero() {
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("0123"));
    }

    private static void testNumberOverflow() {
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("99999999999999999999"));
    }

    private static void testMaxDepthExceeded() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            sb.append('[');
        }
        for (int i = 0; i < 70; i++) {
            sb.append(']');
        }
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse(sb.toString()));
    }

    private static void testDuplicateKey() {
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("{\"a\":1,\"a\":2}"));
    }

    private static void testEmptyInputs() throws Exception {
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse(""));
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse("   "));
        TestSupport.assertThrows(JsonParseException.class, () -> MiniJson.parse(null));

        // Chuỗi rỗng JSON hợp lệ ("") phải parse ra chuỗi rỗng ""
        TestSupport.assertEquals("", MiniJson.parse("\"\""));
    }

    private static void testSerializerUnsupportedType() {
        TestSupport.assertThrows(IllegalArgumentException.class, () -> MiniJson.stringify(1.5));
        TestSupport.assertThrows(IllegalArgumentException.class, () -> MiniJson.stringify(new Object()));
    }
}
