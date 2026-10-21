package e2ee.client.security;

import e2ee.common.json.JsonParseException;
import e2ee.common.json.MiniJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Kho lưu trữ metadata nhóm cục bộ phía Client (groupId, groupName, members).
 * Lưu trữ dưới dạng JSON bằng MiniJson, không chứa bản rõ tin nhắn hoặc khóa bí mật.
 */
public final class ClientGroupStore {
    private static final String DEFAULT_DIRECTORY = ".e2ee-system";
    private static final String DEFAULT_FILE = "groups.json";

    private final Path storeFile;
    private final ConcurrentMap<String, GroupRecord> groups = new ConcurrentHashMap<>();

    public record GroupRecord(String groupId, String groupName, List<String> members) {
        public GroupRecord {
            Objects.requireNonNull(groupId, "groupId cannot be null");
            groupName = groupName != null && !groupName.isBlank() ? groupName : groupId;
            members = members != null ? List.copyOf(members) : List.of();
        }
    }

    public ClientGroupStore() throws IOException {
        this(Path.of(System.getProperty("user.home"), DEFAULT_DIRECTORY, DEFAULT_FILE));
    }

    public ClientGroupStore(Path storeFile) throws IOException {
        this.storeFile = Objects.requireNonNull(storeFile, "storeFile cannot be null").toAbsolutePath().normalize();
        load();
    }

    public synchronized void load() throws IOException {
        groups.clear();
        if (!Files.exists(storeFile)) {
            return;
        }
        String json = Files.readString(storeFile, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            return;
        }
        try {
            Map<String, Object> root = MiniJson.parseObject(json);
            for (Map.Entry<String, Object> entry : root.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> map) {
                    String groupId = entry.getKey();
                    String groupName = map.get("groupName") instanceof String s ? s : groupId;
                    List<String> memberList = new ArrayList<>();
                    if (map.get("members") instanceof List<?> list) {
                        for (Object item : list) {
                            if (item != null) {
                                memberList.add(String.valueOf(item).trim());
                            }
                        }
                    }
                    groups.put(groupId, new GroupRecord(groupId, groupName, memberList));
                }
            }
        } catch (JsonParseException e) {
            throw new IOException("Loi parse file groups.json", e);
        }
    }

    public synchronized void save() throws IOException {
        Path parent = storeFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, GroupRecord> entry : groups.entrySet()) {
            GroupRecord record = entry.getValue();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("groupId", record.groupId());
            map.put("groupName", record.groupName());
            map.put("members", record.members());
            root.put(entry.getKey(), map);
        }
        String json = MiniJson.stringify(root);
        Path tempFile = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
        Files.writeString(tempFile, json, StandardCharsets.UTF_8);
        Files.move(tempFile, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public synchronized void putGroup(String groupId, String groupName, List<String> members) throws IOException {
        groups.put(groupId, new GroupRecord(groupId, groupName, members));
        save();
    }

    public GroupRecord getGroup(String groupId) {
        return groups.get(groupId);
    }

    public List<String> getGroupMembers(String groupId) {
        GroupRecord record = groups.get(groupId);
        return record != null ? record.members() : List.of();
    }

    public Map<String, GroupRecord> getAllGroups() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(groups));
    }

    public boolean hasGroup(String groupId) {
        return groups.containsKey(groupId);
    }

    public synchronized boolean removeGroup(String groupId) throws IOException {
        if (groups.remove(groupId) != null) {
            save();
            return true;
        }
        return false;
    }
}
