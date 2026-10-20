package e2ee.client.security;

import e2ee.client.crypto.ClientCryptoEngine;
import e2ee.client.crypto.ClientCryptoService;
import e2ee.common.TestSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Kiểm thử LocalTrustStore bằng hàm main(), không phụ thuộc thư viện ngoài.
 */
public final class LocalTrustStoreTest {

    private LocalTrustStoreTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("LTS1 - TOFU luu khoa moi va chap nhan khoa trung",
                LocalTrustStoreTest::testFirstUseAndMatchingKey);
        TestSupport.check("LTS2 - Khoa thay doi bi tu choi va khong ghi de",
                LocalTrustStoreTest::testChangedKeyIsRejected);
        TestSupport.check("LTS3 - Chap nhan khoa moi va danh dau verified",
                LocalTrustStoreTest::testAcceptAndVerify);
        TestSupport.check("LTS4 - Du lieu duoc luu va nap lai tu JSON",
                LocalTrustStoreTest::testPersistence);
        TestSupport.check("LTS5 - Tu choi username va public key khong hop le",
                LocalTrustStoreTest::testInvalidInput);
        TestSupport.check("LTS6 - Tu choi file JSON bi hong",
                LocalTrustStoreTest::testCorruptedFile);
        TestSupport.check("LTS7 - Tu choi khoa RSA khong dung 2048 bit",
                LocalTrustStoreTest::testInvalidRsaKeySize);

        TestSupport.summary();
    }

    private static void testFirstUseAndMatchingKey() throws Exception {
        TestStoreFiles files = TestStoreFiles.create();
        try {
            KeyPair alice = ClientCryptoEngine.generateRsaKeyPair();
            String publicKey = encodePublicKey(alice);
            LocalTrustStore store = new LocalTrustStore(files.storeFile);

            TestSupport.assertTrue(store.checkPeerKey("alice", publicKey),
                    "Khoa dau tien phai duoc tin cay theo TOFU");
            TestSupport.assertTrue(store.checkPeerKey("alice", publicKey),
                    "Khoa trung voi ban da ghim phai hop le");
            TestSupport.assertEquals(publicKey,
                    store.getPublicKeyBase64("alice"));
            TestSupport.assertEquals(
                    ClientCryptoService.calculateFingerprint(alice.getPublic()),
                    store.getFingerprint("alice"));
            TestSupport.assertTrue(!store.isVerified("alice"),
                    "Khoa TOFU chua duoc doi soat qua kenh ngoai");
        } finally {
            files.delete();
        }
    }

    private static void testChangedKeyIsRejected() throws Exception {
        TestStoreFiles files = TestStoreFiles.create();
        try {
            String originalKey = encodePublicKey(
                    ClientCryptoEngine.generateRsaKeyPair());
            String changedKey = encodePublicKey(
                    ClientCryptoEngine.generateRsaKeyPair());
            LocalTrustStore store = new LocalTrustStore(files.storeFile);

            store.checkPeerKey("alice", originalKey);
            TestSupport.assertTrue(!store.checkPeerKey("alice", changedKey),
                    "Khoa khac ban da ghim phai bi canh bao");
            TestSupport.assertEquals(originalKey,
                    store.getPublicKeyBase64("alice"));
        } finally {
            files.delete();
        }
    }

    private static void testAcceptAndVerify() throws Exception {
        TestStoreFiles files = TestStoreFiles.create();
        try {
            String oldKey = encodePublicKey(
                    ClientCryptoEngine.generateRsaKeyPair());
            KeyPair replacement = ClientCryptoEngine.generateRsaKeyPair();
            String replacementKey = encodePublicKey(replacement);
            LocalTrustStore store = new LocalTrustStore(files.storeFile);

            store.checkPeerKey("alice", oldKey);
            store.acceptNewKey("alice", replacementKey);
            TestSupport.assertTrue(!store.isVerified("alice"),
                    "Khoa moi phai chua verified truoc khi doi soat");

            store.markVerified("alice");
            TestSupport.assertTrue(store.isVerified("alice"),
                    "Khoa phai verified sau khi nguoi dung xac nhan");
            TestSupport.assertEquals(
                    ClientCryptoService.calculateFingerprint(
                            replacement.getPublic()),
                    store.getFingerprint("alice"));
        } finally {
            files.delete();
        }
    }

    private static void testPersistence() throws Exception {
        TestStoreFiles files = TestStoreFiles.create();
        try {
            String aliceKey = encodePublicKey(
                    ClientCryptoEngine.generateRsaKeyPair());
            String bobKey = encodePublicKey(
                    ClientCryptoEngine.generateRsaKeyPair());
            LocalTrustStore first = new LocalTrustStore(files.storeFile);
            first.checkPeerKey("alice", aliceKey);
            first.checkPeerKey("bob", bobKey);
            first.markVerified("bob");

            LocalTrustStore restored = new LocalTrustStore(files.storeFile);
            TestSupport.assertEquals(aliceKey,
                    restored.getPublicKeyBase64("alice"));
            TestSupport.assertEquals(bobKey,
                    restored.getPublicKeyBase64("bob"));
            TestSupport.assertTrue(!restored.isVerified("alice"),
                    "Trang thai alice phai duoc bao toan");
            TestSupport.assertTrue(restored.isVerified("bob"),
                    "Trang thai bob phai duoc bao toan");
            TestSupport.assertEquals(2, restored.getUsernames().size());
        } finally {
            files.delete();
        }
    }

    private static void testInvalidInput() throws Exception {
        TestStoreFiles files = TestStoreFiles.create();
        try {
            LocalTrustStore store = new LocalTrustStore(files.storeFile);
            String validKey = encodePublicKey(
                    ClientCryptoEngine.generateRsaKeyPair());

            TestSupport.assertThrows(IllegalArgumentException.class,
                    () -> store.checkPeerKey("Alice!", validKey));
            TestSupport.assertThrows(IllegalArgumentException.class,
                    () -> store.checkPeerKey("server", validKey));
            TestSupport.assertThrows(java.security.GeneralSecurityException.class,
                    () -> store.checkPeerKey("alice", "not-base64"));
            TestSupport.assertThrows(IllegalStateException.class,
                    () -> store.markVerified("alice"));
        } finally {
            files.delete();
        }
    }

    private static void testCorruptedFile() throws Exception {
        TestStoreFiles files = TestStoreFiles.create();
        try {
            Files.writeString(files.storeFile, "{invalid-json",
                    StandardCharsets.UTF_8);
            TestSupport.assertThrows(java.io.IOException.class,
                    () -> new LocalTrustStore(files.storeFile));
        } finally {
            files.delete();
        }
    }

    private static void testInvalidRsaKeySize() throws Exception {
        TestStoreFiles files = TestStoreFiles.create();
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024);
            String weakKey = encodePublicKey(generator.generateKeyPair());
            LocalTrustStore store = new LocalTrustStore(files.storeFile);

            TestSupport.assertThrows(
                    java.security.GeneralSecurityException.class,
                    () -> store.checkPeerKey("alice", weakKey));
        } finally {
            files.delete();
        }
    }

    private static String encodePublicKey(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(
                keyPair.getPublic().getEncoded());
    }

    private static final class TestStoreFiles {
        private final Path directory;
        private final Path storeFile;

        private TestStoreFiles(Path directory) {
            this.directory = directory;
            this.storeFile = directory.resolve("truststore.json");
        }

        private static TestStoreFiles create() throws Exception {
            return new TestStoreFiles(
                    Files.createTempDirectory("e2ee-local-trust-store-test-"));
        }

        private void delete() throws Exception {
            Files.deleteIfExists(storeFile);
            Files.deleteIfExists(directory);
        }
    }
}
