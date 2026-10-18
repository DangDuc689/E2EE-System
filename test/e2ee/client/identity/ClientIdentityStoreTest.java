package e2ee.client.identity;

import e2ee.client.crypto.ClientCryptoEngine;
import e2ee.common.TestSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Base64;

/**
 * Kiểm thử lưu danh tính Client bằng main() và TestSupport.
 */
public final class ClientIdentityStoreTest {

    private ClientIdentityStoreTest() {
    }

    public static void main(String[] args) {
        TestSupport.check("CIS1 - Tao va mo lai danh tinh",
                ClientIdentityStoreTest::testCreateAndLoad);
        TestSupport.check("CIS2 - Khong ghi de danh tinh da ton tai",
                ClientIdentityStoreTest::testDuplicateIdentity);
        TestSupport.check("CIS3 - Sai passphrase bi tu choi",
                ClientIdentityStoreTest::testWrongPassphrase);
        TestSupport.check("CIS4 - Public key khong khop private key bi tu choi",
                ClientIdentityStoreTest::testMismatchedPublicKey);
        TestSupport.check("CIS5 - Validate username va passphrase",
                ClientIdentityStoreTest::testInvalidInput);

        TestSupport.summary();
    }

    private static void testCreateAndLoad() throws Exception {
        TestFiles files = TestFiles.create();
        char[] createPassphrase = "mat-khau-rat-manh".toCharArray();
        char[] loadPassphrase = "mat-khau-rat-manh".toCharArray();
        try {
            ClientIdentityStore store = new ClientIdentityStore(files.directory);
            ClientIdentityStore.Identity created = store.create(
                    "alice", createPassphrase);

            TestSupport.assertTrue(store.exists("alice"),
                    "Danh tinh phai ton tai sau khi tao");
            ClientIdentityStore.Identity loaded = store.load(
                    "alice", loadPassphrase);
            TestSupport.assertEquals("alice", loaded.getUsername());
            TestSupport.assertTrue(Arrays.equals(
                    created.getPublicKey().getEncoded(),
                    loaded.getPublicKey().getEncoded()),
                    "Public key sau khi load phai khop");
            TestSupport.assertTrue(Arrays.equals(
                    created.getPrivateKey().getEncoded(),
                    loaded.getPrivateKey().getEncoded()),
                    "Private key sau khi load phai khop");
        } finally {
            ClientCryptoEngine.wipeMemory(createPassphrase);
            ClientCryptoEngine.wipeMemory(loadPassphrase);
            files.deleteAliceFiles();
        }
    }

    private static void testDuplicateIdentity() throws Exception {
        TestFiles files = TestFiles.create();
        char[] passphrase = "passphrase".toCharArray();
        try {
            ClientIdentityStore store = new ClientIdentityStore(files.directory);
            store.create("alice", passphrase);
            TestSupport.assertThrows(java.io.IOException.class,
                    () -> store.create("alice", passphrase));
        } finally {
            ClientCryptoEngine.wipeMemory(passphrase);
            files.deleteAliceFiles();
        }
    }

    private static void testWrongPassphrase() throws Exception {
        TestFiles files = TestFiles.create();
        char[] correct = "correct-passphrase".toCharArray();
        char[] wrong = "wrong-passphrase".toCharArray();
        try {
            ClientIdentityStore store = new ClientIdentityStore(files.directory);
            store.create("alice", correct);
            TestSupport.assertThrows(GeneralSecurityException.class,
                    () -> store.load("alice", wrong));
        } finally {
            ClientCryptoEngine.wipeMemory(correct);
            ClientCryptoEngine.wipeMemory(wrong);
            files.deleteAliceFiles();
        }
    }

    private static void testMismatchedPublicKey() throws Exception {
        TestFiles files = TestFiles.create();
        char[] passphrase = "passphrase".toCharArray();
        try {
            ClientIdentityStore store = new ClientIdentityStore(files.directory);
            store.create("alice", passphrase);
            KeyPair other = ClientCryptoEngine.generateRsaKeyPair();
            Files.writeString(files.publicKeyFile,
                    Base64.getEncoder().encodeToString(
                            other.getPublic().getEncoded()),
                    StandardCharsets.UTF_8);

            TestSupport.assertThrows(GeneralSecurityException.class,
                    () -> store.load("alice", passphrase));
        } finally {
            ClientCryptoEngine.wipeMemory(passphrase);
            files.deleteAliceFiles();
        }
    }

    private static void testInvalidInput() throws Exception {
        TestFiles files = TestFiles.create();
        try {
            ClientIdentityStore store = new ClientIdentityStore(files.directory);
            TestSupport.assertThrows(IllegalArgumentException.class,
                    () -> store.create("Alice!", "pass".toCharArray()));
            TestSupport.assertThrows(IllegalArgumentException.class,
                    () -> store.create("server", "pass".toCharArray()));
            TestSupport.assertThrows(IllegalArgumentException.class,
                    () -> store.create("alice", new char[0]));
            TestSupport.assertTrue(!store.exists("alice"),
                    "Danh tinh chua tao khong duoc bao ton tai");
        } finally {
            files.deleteAliceFiles();
        }
    }

    private static final class TestFiles {
        private final Path directory;
        private final Path privateKeyFile;
        private final Path publicKeyFile;

        private TestFiles(Path directory) {
            this.directory = directory;
            this.privateKeyFile = directory.resolve("alice.private.key");
            this.publicKeyFile = directory.resolve("alice.public.key");
        }

        private static TestFiles create() throws Exception {
            return new TestFiles(
                    Files.createTempDirectory("e2ee-identity-store-test-"));
        }

        private void deleteAliceFiles() throws Exception {
            Files.deleteIfExists(privateKeyFile);
            Files.deleteIfExists(publicKeyFile);
            Files.deleteIfExists(directory);
        }
    }
}
