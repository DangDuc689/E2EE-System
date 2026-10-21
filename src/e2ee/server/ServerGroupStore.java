package e2ee.server;

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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Kho lưu trữ metadata nhóm phía Server (Blind Relay).
 * Chỉ lưu metadata thành viên (groupId, groupName, members), tuyệt đối không lưu khóa bí mật hay plaintext.
 */
public final class ServerGroupStore {
    private static final String DEFAULT_DIRECTORY = ".e2ee-system";
    private static final String DEFAULT_FILE = "server_groups.json";

    private final Path storeFile;
    private final ConcurrentMap<String, Set<String>> groupRegistry;
    private final ConcurrentMap<String, String> groupNames = new ConcurrentHashMap<>();

    public ServerGroupStore(ConcurrentMap<String, Set<String>> groupRegistry) throws IOException {
        this(groupRegistry, Path.of(System.getProperty("user.home"), DEFAULT_DIRECTORY, DEFAULT_FILE));
    }

    public ServerGroupStore(ConcurrentMap<String, Set<String>> groupRegistry, Path storeFile) throws IOException {
        this.groupRegistry = Objects.requireNonNull(groupRegistry, "groupRegistry cannot be null");
        this.storeFile = Objects.requireNonNull(storeFile, "storeFile cannot be null").toAbsolutePath().normalize();
        load();
    }

    public synchronized void load() throws IOException {
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
                    Set<String> memberSet = ConcurrentHashMap.newKeySet();
                    if (map.get("members") instanceof List<?> list) {
                        for (Object item : list) {
                            if (item != null) {
                                String m = String.valueOf(item).trim();
                                if (!m.isEmpty()) {
                                    memberSet.add(m);
                                }
                            }
                        }
                    }
                    groupRegistry.put(groupId, memberSet);
                    groupNames.put(groupId, groupName);
                }
            }
        } catch (JsonParseException e) {
            throw new IOException("Loi parse file server_groups.json", e);
        }
    }

    public synchronized void save() throws IOException {
        Path parent = storeFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : groupRegistry.entrySet()) {
            String groupId = entry.getKey();
            Set<String> members = entry.getValue();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("groupId", groupId);
            map.put("groupName", groupNames.getOrDefault(groupId, groupId));
            map.put("members", new ArrayList<>(members));
            root.put(groupId, map);
        }
        String json = MiniJson.stringify(root);
        Path tempFile = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
        Files.writeString(tempFile, json, StandardCharsets.UTF_8);
        try {
            Files.move(tempFile, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tempFile, storeFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized void putGroup(String groupId, String groupName, Set<String> members) throws IOException {
        String effectiveName = groupName != null && !groupName.isBlank() ? groupName : groupId;
        Path parent = storeFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : groupRegistry.entrySet()) {
            String gid = entry.getKey();
            if (gid.equals(groupId)) {
                continue;
            }
            Set<String> mems = entry.getValue();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("groupId", gid);
            map.put("groupName", groupNames.getOrDefault(gid, gid));
            map.put("members", new ArrayList<>(mems));
            root.put(gid, map);
        }
        Map<String, Object> newGroupMap = new LinkedHashMap<>();
        newGroupMap.put("groupId", groupId);
        newGroupMap.put("groupName", effectiveName);
        newGroupMap.put("members", new ArrayList<>(members));
        root.put(groupId, newGroupMap);

        String json = MiniJson.stringify(root);
        Path tempFile = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
        Files.writeString(tempFile, json, StandardCharsets.UTF_8);
        try {
            Files.move(tempFile, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tempFile, storeFile, StandardCopyOption.REPLACE_EXISTING);
        }

        // Cập nhật RAM sau khi ghi đĩa thành công
        groupRegistry.put(groupId, members);
        groupNames.put(groupId, effectiveName);
    }

    public String getGroupName(String groupId) {
        return groupNames.getOrDefault(groupId, groupId);
    }

    public ConcurrentMap<String, String> getGroupNames() {
        return groupNames;
    }

    public ConcurrentMap<String, Set<String>> getGroupRegistry() {
        return groupRegistry;
    }
}
