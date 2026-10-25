package e2ee.server;

import e2ee.common.TestSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Kiểm thử ServerGroupStore: lưu trữ bền vững, nạp lại sau khởi động lại,
 * atomic write và xử lý lỗi đọc/ghi an toàn.
 */
public final class ServerGroupStoreTest {

    public static void main(String[] args) {
        TestSupport.check("SGS-1 - Luu nhom va nap lai sau khi khoi dong lai store",
                ServerGroupStoreTest::testSaveAndReload);
        TestSupport.check("SGS-2 - File bi hong thi load phai nem IOException khong nuot loi",
                ServerGroupStoreTest::testCorruptedFileThrowsException);
        TestSupport.check("SGS-3 - Ghi that bai khong cap nhat RAM cache do dang",
                ServerGroupStoreTest::testWriteFailurePreservesRam);
        TestSupport.check("SGS-4 - Cap nhat ten nhom va danh sach thanh vien",
                ServerGroupStoreTest::testUpdateGroupMetadata);

        TestSupport.summary();
    }

    private static void testSaveAndReload() throws Exception {
        Path tempDir = Files.createTempDirectory("sgs-test-");
        try {
            Path storeFile = tempDir.resolve("server_groups.json");
            ConcurrentMap<String, Set<String>> registry1 = new ConcurrentHashMap<>();
            ServerGroupStore store1 = new ServerGroupStore(registry1, storeFile);

            Set<String> members = new HashSet<>(Arrays.asList("alice", "bob", "charlie"));
            store1.putGroup("g-100", "Nhom KTPM", members);

            TestSupport.assertTrue(Files.exists(storeFile), "File store phai duoc tao tren dia");
            TestSupport.assertEquals("Nhom KTPM", store1.getGroupName("g-100"));
            TestSupport.assertEquals(3, store1.getGroupRegistry().get("g-100").size());

            // Khoi dong lai store voi instance moi tu cung file
            ConcurrentMap<String, Set<String>> registry2 = new ConcurrentHashMap<>();
            ServerGroupStore store2 = new ServerGroupStore(registry2, storeFile);

            TestSupport.assertTrue(registry2.containsKey("g-100"), "Registry moi phai chua g-100");
            TestSupport.assertEquals("Nhom KTPM", store2.getGroupName("g-100"));
            Set<String> reloadedMembers = registry2.get("g-100");
            TestSupport.assertTrue(reloadedMembers.contains("alice"), "Phai chua alice");
            TestSupport.assertTrue(reloadedMembers.contains("bob"), "Phai chua bob");
            TestSupport.assertTrue(reloadedMembers.contains("charlie"), "Phai chua charlie");
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static void testCorruptedFileThrowsException() throws Exception {
        Path tempDir = Files.createTempDirectory("sgs-test-corrupt-");
        try {
            Path storeFile = tempDir.resolve("server_groups.json");
            Files.writeString(storeFile, "{ invalid json content !!!", StandardCharsets.UTF_8);

            ConcurrentMap<String, Set<String>> registry = new ConcurrentHashMap<>();
            TestSupport.assertThrows(IOException.class, () -> {
                new ServerGroupStore(registry, storeFile);
            });
            TestSupport.assertTrue(registry.isEmpty(), "Registry phai rong khi load loi va bi chan");
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static void testWriteFailurePreservesRam() throws Exception {
        Path tempDir = Files.createTempDirectory("sgs-test-fail-");
        try {
            // Tao store file tro vao mot thu muc khong the ghi
            Path invalidPath = tempDir.resolve("non_existent_subdir").resolve("groups.json");
            // Khoa duong dan bang cach tao file cung ten lam parent
            Files.writeString(tempDir.resolve("non_existent_subdir"), "blocking file", StandardCharsets.UTF_8);

            ConcurrentMap<String, Set<String>> registry = new ConcurrentHashMap<>();
            ServerGroupStore store = new ServerGroupStore(registry, invalidPath);

            Set<String> members = new HashSet<>(Arrays.asList("alice", "bob"));
            TestSupport.assertThrows(IOException.class, () -> {
                store.putGroup("g-fail", "Nhom Loi", members);
            });

            TestSupport.assertTrue(!registry.containsKey("g-fail"),
                    "RAM registry khong duoc chua nhom neu ghi dia that bai");
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static void testUpdateGroupMetadata() throws Exception {
        Path tempDir = Files.createTempDirectory("sgs-test-update-");
        try {
            Path storeFile = tempDir.resolve("server_groups.json");
            ConcurrentMap<String, Set<String>> registry = new ConcurrentHashMap<>();
            ServerGroupStore store = new ServerGroupStore(registry, storeFile);

            Set<String> members1 = new HashSet<>(Arrays.asList("alice", "bob"));
            store.putGroup("g-200", "Ten Cu", members1);

            Set<String> members2 = new HashSet<>(Arrays.asList("alice", "bob", "david"));
            store.putGroup("g-200", "Ten Moi", members2);

            TestSupport.assertEquals("Ten Moi", store.getGroupName("g-200"));
            TestSupport.assertEquals(3, store.getGroupRegistry().get("g-200").size());
            TestSupport.assertTrue(store.getGroupRegistry().get("g-200").contains("david"), "Phai co david");
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static void deleteDirectory(Path dir) {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        }
    }
}
