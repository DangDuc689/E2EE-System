package e2ee.client.security;

import e2ee.client.crypto.ClientCryptoEngine;
import e2ee.client.crypto.ClientCryptoService;
import e2ee.common.json.JsonParseException;
import e2ee.common.json.MiniJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Sổ tin cậy cục bộ lưu public key và fingerprint của bạn chat.
 *
 * <p>Fingerprint luôn được Client tự tính từ public key. Khóa mới chỉ thay thế
 * khóa đã ghim khi người dùng chủ động gọi {@link #acceptNewKey}.</p>
 */
public final class LocalTrustStore {
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[a-z0-9_]{3,20}$");
    private static final String DEFAULT_DIRECTORY = ".e2ee-system";
    private static final String DEFAULT_FILE = "truststore.json";

    private final Path storeFile;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * Mở trust store mặc định trong thư mục người dùng, không đặt dữ liệu runtime
     * vào repository.
     */
    public LocalTrustStore() throws IOException {
        this(Path.of(System.getProperty("user.home"),
                DEFAULT_DIRECTORY, DEFAULT_FILE));
    }

    /**
     * Mở trust store tại đường dẫn chỉ định. Constructor này phục vụ kiểm thử và
     * trường hợp ứng dụng cần chọn nơi lưu riêng.
     */
    public LocalTrustStore(Path storeFile) throws IOException {
        if (storeFile == null) {
            throw new IllegalArgumentException("storeFile khong duoc null");
        }
        this.storeFile = storeFile.toAbsolutePath().normalize();
        load();
    }

    /**
     * Kiểm tra khóa mới theo nguyên tắc TOFU phía Client.
     *
     * @return true nếu đây là khóa đầu tiên hoặc khóa khớp với bản đã lưu;
     *         false nếu phát hiện khóa thay đổi. Trường hợp false không ghi đè
     *         khóa đang tin cậy.
     */
    public synchronized boolean checkPeerKey(String username, String newPubkey)
            throws IOException, GeneralSecurityException {
        validateUsername(username);
        KeyMaterial supplied = parsePublicKey(newPubkey);
        Entry current = entries.get(username);

        if (current == null) {
            Map<String, Entry> updated = copyEntries();
            updated.put(username, new Entry(
                    supplied.publicKeyBase64, supplied.fingerprint, false));
            replaceEntriesAfterSave(updated);
            return true;
        }

        return current.publicKeyBase64.equals(supplied.publicKeyBase64);
    }

    /**
     * Chấp nhận khóa mới sau khi người dùng quyết định đối soát lại. Khóa vừa
     * nhận luôn ở trạng thái chưa xác minh cho đến khi gọi {@link #markVerified}.
     */
    public synchronized void acceptNewKey(String username, String newPubkey)
            throws IOException, GeneralSecurityException {
        validateUsername(username);
        KeyMaterial supplied = parsePublicKey(newPubkey);

        Map<String, Entry> updated = copyEntries();
        updated.put(username, new Entry(
                supplied.publicKeyBase64, supplied.fingerprint, false));
        replaceEntriesAfterSave(updated);
    }

    /**
     * Đánh dấu khóa hiện tại đã được đối soát qua một kênh ngoài.
     */
    public synchronized void markVerified(String username) throws IOException {
        validateUsername(username);
        Entry current = entries.get(username);
        if (current == null) {
            throw new IllegalStateException(
                    "Chua co public key cua user: " + username);
        }

        if (current.verified) {
            return;
        }

        Map<String, Entry> updated = copyEntries();
        updated.put(username, new Entry(
                current.publicKeyBase64, current.fingerprint, true));
        replaceEntriesAfterSave(updated);
    }

    public synchronized Entry getEntry(String username) {
        validateUsername(username);
        return entries.get(username);
    }

    public synchronized String getPublicKeyBase64(String username) {
        Entry entry = getEntry(username);
        return entry != null ? entry.publicKeyBase64 : null;
    }

    public synchronized String getFingerprint(String username) {
        Entry entry = getEntry(username);
        return entry != null ? entry.fingerprint : null;
    }

    public synchronized boolean isVerified(String username) {
        Entry entry = getEntry(username);
        return entry != null && entry.verified;
    }

    public synchronized Set<String> getUsernames() {
        return Collections.unmodifiableSet(Set.copyOf(entries.keySet()));
    }

    private void load() throws IOException {
        if (!Files.exists(storeFile)) {
            return;
        }

        Map<String, Object> root;
        try {
            root = MiniJson.parseObject(
                    Files.readString(storeFile, StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            throw new IOException("File LocalTrustStore khong hop le", e);
        }

        Map<String, Entry> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, Object> item : root.entrySet()) {
            String username = item.getKey();
            validateUsernameForLoad(username);
            if (!(item.getValue() instanceof Map<?, ?> peerMap)) {
                throw new IOException("Du lieu trust store cua '"
                        + username + "' khong phai JSON object");
            }

            Object pubkeyValue = peerMap.get("pubkey");
            Object fingerprintValue = peerMap.get("fingerprint");
            Object verifiedValue = peerMap.get("verified");
            if (!(pubkeyValue instanceof String pubkey)
                    || !(fingerprintValue instanceof String fingerprint)
                    || !(verifiedValue instanceof Boolean verified)) {
                throw new IOException("Du lieu trust store cua '"
                        + username + "' thieu hoac sai kieu");
            }

            try {
                KeyMaterial material = parsePublicKey(pubkey);
                if (!material.fingerprint.equals(fingerprint)) {
                    throw new IOException("Fingerprint khong khop public key cua user: "
                            + username);
                }
                loaded.put(username, new Entry(
                        material.publicKeyBase64, material.fingerprint, verified));
            } catch (GeneralSecurityException e) {
                throw new IOException("Public key khong hop le cua user: "
                        + username, e);
            }
        }

        entries.putAll(loaded);
    }

    private void replaceEntriesAfterSave(Map<String, Entry> updated)
            throws IOException {
        save(updated);
        entries.clear();
        entries.putAll(updated);
    }

    private void save(Map<String, Entry> data) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> item
                : new TreeMap<>(data).entrySet()) {
            Entry entry = item.getValue();
            Map<String, Object> peerMap = new LinkedHashMap<>();
            peerMap.put("pubkey", entry.publicKeyBase64);
            peerMap.put("fingerprint", entry.fingerprint);
            peerMap.put("verified", entry.verified);
            root.put(item.getKey(), peerMap);
        }

        Path parent = storeFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tempFile = Files.createTempFile(
                parent, storeFile.getFileName().toString() + ".", ".tmp");
        try {
            Files.writeString(tempFile, MiniJson.stringify(root),
                    StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, storeFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, storeFile,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private Map<String, Entry> copyEntries() {
        return new LinkedHashMap<>(entries);
    }

    private static KeyMaterial parsePublicKey(String publicKeyBase64)
            throws GeneralSecurityException {
        if (publicKeyBase64 == null || publicKeyBase64.isBlank()) {
            throw new IllegalArgumentException("Public key khong duoc rong");
        }

        try {
            byte[] encoded = Base64.getDecoder().decode(publicKeyBase64);
            PublicKey publicKey = ClientCryptoEngine.decodeRsaPublicKey(encoded);
            String canonicalBase64 = Base64.getEncoder()
                    .encodeToString(publicKey.getEncoded());
            String fingerprint = ClientCryptoService
                    .calculateFingerprint(publicKey);
            return new KeyMaterial(canonicalBase64, fingerprint);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException(
                    "Public key Base64 khong hop le", e);
        }
    }

    private static void validateUsername(String username) {
        if (!isValidUsername(username)) {
            throw new IllegalArgumentException("Username khong hop le");
        }
    }

    private static void validateUsernameForLoad(String username)
            throws IOException {
        if (!isValidUsername(username)) {
            throw new IOException("Username khong hop le trong trust store: "
                    + username);
        }
    }

    private static boolean isValidUsername(String username) {
        return username != null
                && !"server".equals(username)
                && !username.contains("|")
                && USERNAME_PATTERN.matcher(username).matches();
    }

    /**
     * Bản ghi bất biến để giao diện đọc trạng thái tin cậy của một người dùng.
     */
    public static final class Entry {
        private final String publicKeyBase64;
        private final String fingerprint;
        private final boolean verified;

        private Entry(String publicKeyBase64, String fingerprint,
                boolean verified) {
            this.publicKeyBase64 = publicKeyBase64;
            this.fingerprint = fingerprint;
            this.verified = verified;
        }

        public String getPublicKeyBase64() {
            return publicKeyBase64;
        }

        public String getFingerprint() {
            return fingerprint;
        }

        public boolean isVerified() {
            return verified;
        }
    }

    private static final class KeyMaterial {
        private final String publicKeyBase64;
        private final String fingerprint;

        private KeyMaterial(String publicKeyBase64, String fingerprint) {
            this.publicKeyBase64 = publicKeyBase64;
            this.fingerprint = fingerprint;
        }
    }
}
