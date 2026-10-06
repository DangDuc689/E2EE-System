package e2ee.common.codec;

import e2ee.common.TestSupport;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class FrameCodecTest {

    public static void main(String[] args) {
        TestSupport.check("F1 - Don goi (100 frames lien nhau)", FrameCodecTest::testHundredFramesBackToBack);
        TestSupport.check("F2 - Vo goi (doc tung byte)", FrameCodecTest::testSplitIntoSingleBytes);
        TestSupport.check("F3 - Het du lieu nem EOFException", FrameCodecTest::testEofException);
        TestSupport.check("F4 - Header vuot qua MAX_FRAME_SIZE nem IOException", FrameCodecTest::testHeaderExceedsMaxSize);
        TestSupport.check("F5 - Header am nem IOException", FrameCodecTest::testHeaderNegative);
        TestSupport.check("F6 - Ghi payload vuot qua MAX_FRAME_SIZE nem IOException", FrameCodecTest::testWriteTooLarge);
        TestSupport.check("F7 - Dut giua frame nem EOFException", FrameCodecTest::testTruncatedFrame);
        TestSupport.check("F8 - Gui va nhan payload rong", FrameCodecTest::testEmptyPayload);

        TestSupport.summary();
    }

    private static void testHundredFramesBackToBack() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        byte[][] sentPayloads = new byte[100][];
        for (int i = 0; i < 100; i++) {
            sentPayloads[i] = ("Gói tin số #" + i + " kiểm tra dồn gói").getBytes(StandardCharsets.UTF_8);
            FrameCodec.writeFrame(dos, sentPayloads[i]);
        }

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        DataInputStream dis = new DataInputStream(bais);

        for (int i = 0; i < 100; i++) {
            byte[] received = FrameCodec.readFrame(dis);
            TestSupport.assertTrue(Arrays.equals(sentPayloads[i], received), "Khop payload goi thu " + i);
        }
    }

    private static void testSplitIntoSingleBytes() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        byte[] original = "TCP Stream fragmentation test payload with UTF-8: Tieng Viet khong dau".getBytes(StandardCharsets.UTF_8);
        FrameCodec.writeFrame(dos, original);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        OneByteInputStream oneByteIn = new OneByteInputStream(bais);
        DataInputStream dis = new DataInputStream(oneByteIn);

        byte[] received = FrameCodec.readFrame(dis);
        TestSupport.assertTrue(Arrays.equals(original, received), "Du lieu nhan duoc qua stream 1-byte phai hoan toan nguyen ven");
    }

    private static void testEofException() throws Exception {
        ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
        DataInputStream dis = new DataInputStream(bais);

        TestSupport.assertThrows(EOFException.class, () -> FrameCodec.readFrame(dis));
    }

    private static void testHeaderExceedsMaxSize() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(FrameCodec.MAX_FRAME_SIZE + 1);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        TestSupport.assertThrows(IOException.class, () -> FrameCodec.readFrame(dis));
    }

    private static void testHeaderNegative() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(-1);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        TestSupport.assertThrows(IOException.class, () -> FrameCodec.readFrame(dis));
    }

    private static void testWriteTooLarge() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        byte[] oversized = new byte[FrameCodec.MAX_FRAME_SIZE + 1];

        TestSupport.assertThrows(IOException.class, () -> FrameCodec.writeFrame(dos, oversized));
    }

    private static void testTruncatedFrame() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(10); // Khai báo payload 10 byte
        dos.write(new byte[]{1, 2, 3}); // Nhưng chỉ ghi 3 byte

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        TestSupport.assertThrows(EOFException.class, () -> FrameCodec.readFrame(dis));
    }

    private static void testEmptyPayload() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        byte[] empty = new byte[0];
        FrameCodec.writeFrame(dos, empty);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        byte[] received = FrameCodec.readFrame(dis);
        TestSupport.assertEquals(0, received.length);
    }

    /** Giả lập TCP "vỡ gói": mỗi lần read chỉ trả về tối đa 1 byte. */
    static final class OneByteInputStream extends FilterInputStream {
        OneByteInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return super.read(b, off, Math.min(len, 1));
        }
    }
}
