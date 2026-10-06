package e2ee.common.codec;

import e2ee.common.TestSupport;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.json.JsonParseException;

import java.util.Arrays;
import java.util.Map;

public final class EnvelopeMapperTest {

    public static void main(String[] args) {
        TestSupport.check("M1 - Round-trip day du Envelope -> Map -> JSON -> Map -> Envelope", EnvelopeMapperTest::testFullRoundTrip);
        TestSupport.check("M2 - So sanh computeAADBytes() truoc va sau round-trip", EnvelopeMapperTest::testAadIntegrity);
        TestSupport.check("M3 - Kiem tra kieu du lieu sau round-trip", EnvelopeMapperTest::testDataTypesAfterRoundTrip);
        TestSupport.check("M4 - Envelope nhom (to = null, co groupId)", EnvelopeMapperTest::testGroupEnvelope);
        TestSupport.check("M5 - OFFLINE_SYNC long CHAT_1TO1 ben trong", EnvelopeMapperTest::testOfflineSyncNestedEnvelope);
        TestSupport.check("M6 - Opcode la (HACK) nem JsonParseException", EnvelopeMapperTest::testInvalidOpcode);
        TestSupport.check("M7 - Thieu cac truong bat buoc nem JsonParseException", EnvelopeMapperTest::testMissingRequiredFields);
        TestSupport.check("M8 - Body khong phai JSON object nem JsonParseException", EnvelopeMapperTest::testInvalidBodyType);

        TestSupport.summary();
    }

    private static void testFullRoundTrip() throws Exception {
        Envelope original = Envelope.create(Opcode.CHAT_1TO1, "alice", "bob");
        original.put("ct", "SGVsbG8gV29ybGQ=");
        original.put("counter", 42L); // Sử dụng Long để tương thích hoàn toàn với MiniJson

        String json = EnvelopeMapper.toJson(original);
        Envelope restored = EnvelopeMapper.fromJson(json);

        TestSupport.assertEquals(original.getV(), restored.getV());
        TestSupport.assertEquals(original.getOp(), restored.getOp());
        TestSupport.assertEquals(original.getId(), restored.getId());
        TestSupport.assertEquals(original.getTs(), restored.getTs());
        TestSupport.assertEquals(original.getFrom(), restored.getFrom());
        TestSupport.assertEquals(original.getTo(), restored.getTo());
        TestSupport.assertEquals(original.getGroupId(), restored.getGroupId());
        TestSupport.assertEquals("SGVsbG8gV29ybGQ=", restored.get("ct"));
        TestSupport.assertEquals(42L, restored.get("counter"));
    }

    private static void testAadIntegrity() throws Exception {
        Envelope original = Envelope.create(Opcode.KEY_PUBLISH, "alice", "bob");
        byte[] aadOriginal = original.computeAADBytes();

        String json = EnvelopeMapper.toJson(original);
        Envelope restored = EnvelopeMapper.fromJson(json);
        byte[] aadRestored = restored.computeAADBytes();

        TestSupport.assertTrue(Arrays.equals(aadOriginal, aadRestored), "AAD bytes truoc va sau round-trip phai hoan toan trung khop");
    }

    private static void testDataTypesAfterRoundTrip() throws Exception {
        Envelope original = Envelope.create(Opcode.PING, "client", null);
        String json = EnvelopeMapper.toJson(original);
        Envelope restored = EnvelopeMapper.fromJson(json);

        TestSupport.assertTrue(restored.getOp() == Opcode.PING, "Opcode phai la instance cua enum Opcode");
        TestSupport.assertTrue(restored.getTs() == original.getTs(), "Timestamp phai la kieu long chinh xac");
    }

    private static void testGroupEnvelope() throws Exception {
        Envelope groupEnv = Envelope.createGroup(Opcode.CHAT_GROUP, "alice", "group_cyber_sec");
        Map<String, Object> map = EnvelopeMapper.toMap(groupEnv);

        TestSupport.assertTrue(!map.containsKey("to"), "Khong duoc sinh key 'to' khi to == null");
        TestSupport.assertEquals("group_cyber_sec", map.get("groupId"));

        String json = EnvelopeMapper.toJson(groupEnv);
        Envelope restored = EnvelopeMapper.fromJson(json);

        TestSupport.assertEquals(null, restored.getTo());
        TestSupport.assertEquals("group_cyber_sec", restored.getGroupId());
    }

    private static void testOfflineSyncNestedEnvelope() throws Exception {
        Envelope innerChat = Envelope.create(Opcode.CHAT_1TO1, "alice", "bob");
        innerChat.put("msg", "Tin nhan cho dong bo");
        byte[] innerAadExpected = innerChat.computeAADBytes();

        Envelope outerSync = Envelope.create(Opcode.OFFLINE_SYNC, "server", "bob");
        outerSync.put("original_envelope", EnvelopeMapper.toMap(innerChat));

        String json = EnvelopeMapper.toJson(outerSync);
        Envelope restoredOuter = EnvelopeMapper.fromJson(json);

        @SuppressWarnings("unchecked")
        Map<String, Object> nestedMap = (Map<String, Object>) restoredOuter.get("original_envelope");
        TestSupport.assertTrue(nestedMap != null, "Phai khoi phuc duoc Map long nhau");

        Envelope restoredInner = EnvelopeMapper.fromMap(nestedMap);
        TestSupport.assertEquals(innerChat.getId(), restoredInner.getId());
        TestSupport.assertEquals("Tin nhan cho dong bo", restoredInner.get("msg"));
        TestSupport.assertTrue(Arrays.equals(innerAadExpected, restoredInner.computeAADBytes()), "AAD cua tin nhan long nhau phai hoan toan nguyen ven");
    }

    private static void testInvalidOpcode() {
        String badJson = "{\"v\":1,\"op\":\"HACK\",\"id\":\"abc\",\"ts\":1000}";
        TestSupport.assertThrows(JsonParseException.class, () -> EnvelopeMapper.fromJson(badJson));
    }

    private static void testMissingRequiredFields() {
        // Thiếu op
        TestSupport.assertThrows(JsonParseException.class, () -> EnvelopeMapper.fromJson("{\"v\":1,\"id\":\"abc\",\"ts\":1000}"));
        // Thiếu id
        TestSupport.assertThrows(JsonParseException.class, () -> EnvelopeMapper.fromJson("{\"v\":1,\"op\":\"PING\",\"ts\":1000}"));
        // Thiếu ts
        TestSupport.assertThrows(JsonParseException.class, () -> EnvelopeMapper.fromJson("{\"v\":1,\"op\":\"PING\",\"id\":\"abc\"}"));
        // Thiếu v
        TestSupport.assertThrows(JsonParseException.class, () -> EnvelopeMapper.fromJson("{\"op\":\"PING\",\"id\":\"abc\",\"ts\":1000}"));
    }

    private static void testInvalidBodyType() {
        String badJson = "{\"v\":1,\"op\":\"PING\",\"id\":\"abc\",\"ts\":1000,\"body\":\"not_a_map\"}";
        TestSupport.assertThrows(JsonParseException.class, () -> EnvelopeMapper.fromJson(badJson));
    }
}
