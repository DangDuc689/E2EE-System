package e2ee.client.identity;

import e2ee.client.crypto.ClientCryptoEngine;
import e2ee.client.crypto.ClientCryptoService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Lưu và mở danh tính RSA của Client trên máy cục bộ.
 * Private key được bảo vệ bởi ClientCryptoEngine; public key được lưu ở dạng
 * X.509 SPKI Base64 để mọi lần AUTH luôn gửi kèm đúng khóa công khai.
 */
public final class ClientIdentityStore {
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[a-z0-9_]{3,20}$");
    private static final String DEFAULT_DIRECTORY = ".e2ee-system/identities";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Path identitiesDirectory;

    public ClientIdentityStore() {
        this(Path.of(System.getProperty("user.home"), DEFAULT_DIRECTORY));
    }

    public ClientIdentityStore(Path identitiesDirectory) {
        if (identitiesDirectory == null) {
            throw new IllegalArgumentException(
                    "identitiesDirectory khong duoc null");
        }
        this.identitiesDirectory = identitiesDirectory
                .toAbsolutePath().normalize();
    }

    /**
     * Tạo danh tính mới. Không ghi đè bất kỳ file khóa nào đã tồn tại.
     */
    public Identity create(String username, char[] passphrase)
            throws IOException, GeneralSecurityException {
        validateUsername(username);
        validatePassphrase(passphrase);
        Path privateKeyFile = privateKeyFile(username);
        Path publicKeyFile = publicKeyFile(username);
        if (Files.exists(privateKeyFile) || Files.exists(publicKeyFile)) {
            throw new IOException("Danh tinh da ton tai: " + username);
        }

        Files.createDirectories(identitiesDirectory);
        KeyPair keyPair = ClientCryptoEngine.generateRsaKeyPair();
        boolean privateKeyCreated = false;
        try {
            ClientCryptoEngine.savePrivateKey(
                    privateKeyFile.toFile(), keyPair.getPrivate(), passphrase);
            privateKeyCreated = true;
            String publicKeyBase64 = Base64.getEncoder()
                    .encodeToString(keyPair.getPublic().getEncoded());
            Files.writeString(publicKeyFile, publicKeyBase64,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            return new Identity(username,
                    keyPair.getPublic(), keyPair.getPrivate());
        } catch (IOException | GeneralSecurityException e) {
            if (privateKeyCreated) {
                Files.deleteIfExists(privateKeyFile);
            }
            Files.deleteIfExists(publicKeyFile);
            throw e;
        }
    }

    /**
     * Sinh cặp khóa mới và ghi đè danh tính cũ để đổi khóa.
     */
    public Identity rotate(String username, char[] passphrase)
            throws IOException, GeneralSecurityException {
        validateUsername(username);
        validatePassphrase(passphrase);
        Path privateKeyFile = privateKeyFile(username);
        Path publicKeyFile = publicKeyFile(username);
        if (!Files.isRegularFile(privateKeyFile)
                || !Files.isRegularFile(publicKeyFile)) {
            throw new IOException("Khong tim thay danh tinh de doi khoa: " + username);
        }

        Files.createDirectories(identitiesDirectory);
        KeyPair keyPair = ClientCryptoEngine.generateRsaKeyPair();
        ClientCryptoEngine.savePrivateKey(
                privateKeyFile.toFile(), keyPair.getPrivate(), passphrase);
        String publicKeyBase64 = Base64.getEncoder()
                .encodeToString(keyPair.getPublic().getEncoded());
        Files.writeString(publicKeyFile, publicKeyBase64,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        return new Identity(username, keyPair.getPublic(), keyPair.getPrivate());
    }

    /**
     * Mở danh tính cũ và kiểm tra public/private key thực sự là một cặp.
     */
    public Identity load(String username, char[] passphrase)
            throws IOException, GeneralSecurityException {
        validateUsername(username);
        validatePassphrase(passphrase);
        Path privateKeyFile = privateKeyFile(username);
        Path publicKeyFile = publicKeyFile(username);
        if (!Files.isRegularFile(privateKeyFile)
                || !Files.isRegularFile(publicKeyFile)) {
            throw new IOException("Khong tim thay danh tinh: " + username);
        }

        PrivateKey privateKey = ClientCryptoEngine.loadPrivateKey(
                privateKeyFile.toFile(), passphrase);
        String publicKeyBase64 = Files.readString(
                publicKeyFile, StandardCharsets.UTF_8).trim();
        PublicKey publicKey;
        try {
            publicKey = ClientCryptoEngine.decodeRsaPublicKey(
                    Base64.getDecoder().decode(publicKeyBase64));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException(
                    "File public key khong hop le", e);
        }

        verifyKeyPair(publicKey, privateKey);
        return new Identity(username, publicKey, privateKey);
    }

    public boolean exists(String username) {
        validateUsername(username);
        return Files.isRegularFile(privateKeyFile(username))
                && Files.isRegularFile(publicKeyFile(username));
    }

    private static void verifyKeyPair(PublicKey publicKey,
            PrivateKey privateKey) throws GeneralSecurityException {
        byte[] challenge = new byte[32];
        SECURE_RANDOM.nextBytes(challenge);
        byte[] signatureBytes = null;
        try {
            signatureBytes = ClientCryptoService.signChallenge(
                    challenge, privateKey);
            Signature verifier = Signature.getInstance(
                    ClientCryptoService.SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(challenge);
            if (!verifier.verify(signatureBytes)) {
                throw new GeneralSecurityException(
                        "Public key va Private key khong cung mot cap");
            }
        } finally {
            ClientCryptoEngine.wipeMemory(challenge);
            ClientCryptoEngine.wipeMemory(signatureBytes);
        }
    }

    private Path privateKeyFile(String username) {
        return identitiesDirectory.resolve(username + ".private.key");
    }

    private Path publicKeyFile(String username) {
        return identitiesDirectory.resolve(username + ".public.key");
    }

    private static void validateUsername(String username) {
        if (username == null
                || "server".equals(username)
                || username.contains("|")
                || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("Username khong hop le");
        }
    }

    private static void validatePassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException(
                    "Passphrase khong duoc de trong");
        }
    }

    public static Identity createEphemeral(String username, PublicKey publicKey,
            PrivateKey privateKey) {
        validateUsername(username);
        return new Identity(username, publicKey, privateKey);
    }

    public static final class Identity {
        private final String username;
        private final PublicKey publicKey;
        private final PrivateKey privateKey;

        public Identity(String username, PublicKey publicKey,
                PrivateKey privateKey) {
            this.username = username;
            this.publicKey = publicKey;
            this.privateKey = privateKey;
        }

        public String getUsername() {
            return username;
        }

        public PublicKey getPublicKey() {
            return publicKey;
        }

        public PrivateKey getPrivateKey() {
            return privateKey;
        }

        public String getPublicKeyBase64() {
            return Base64.getEncoder().encodeToString(publicKey.getEncoded());
        }
    }
}
