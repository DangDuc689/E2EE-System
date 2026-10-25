package e2ee.server;

import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Định tuyến quản lý nhóm (GROUP_CREATE) và chuyển tiếp tin nhắn nhóm E2EE (CHAT_GROUP).
 *
 * <p>Server đóng vai trò Blind Relay:
 * 1. Quản lý danh bạ nhóm trong RAM & Store: groupId -> Set&lt;String&gt; memberUsernames.
 * 2. Khi nhận GROUP_CREATE: Từ chối nếu groupId trùng; Lưu nhóm và phát snapshot tới các thành viên online.
 * 3. Khi nhận CHAT_GROUP: Chuyển tiếp mù nguyên vẹn Envelope tới toàn bộ thành viên
 *    trong nhóm đang online (ngoại trừ người gửi) hoặc đưa vào hàng đợi offline.
 * 4. Chuyển tiếp các gói tin khác (CHAT_1TO1, KEY_LOOKUP) sang MessageRouter kế tiếp.</p>
 */
public final class GroupRouter implements MessageRouter {
    private final ConcurrentMap<String, ClientHandler> onlineUsers;
    private final ConcurrentMap<String, Set<String>> groupRegistry;
    private final ConcurrentMap<String, String> groupNames = new ConcurrentHashMap<>();
    private final OfflineMessageQueue offlineQueue;
    private final MessageRouter nextRouter;
    private final ServerGroupStore groupStore;

    public GroupRouter(
            ConcurrentMap<String, ClientHandler> onlineUsers,
            MessageRouter nextRouter) {
        this(onlineUsers, new ConcurrentHashMap<>(), null, nextRouter);
    }

    public GroupRouter(
            ConcurrentMap<String, ClientHandler> onlineUsers,
            ConcurrentMap<String, Set<String>> groupRegistry,
            MessageRouter nextRouter) {
        this(onlineUsers, groupRegistry, null, nextRouter);
    }

    public GroupRouter(
            ConcurrentMap<String, ClientHandler> onlineUsers,
            ConcurrentMap<String, Set<String>> groupRegistry,
            OfflineMessageQueue offlineQueue,
            MessageRouter nextRouter) {
        this(onlineUsers, groupRegistry, offlineQueue, nextRouter, null);
    }

    public GroupRouter(
            ConcurrentMap<String, ClientHandler> onlineUsers,
            ConcurrentMap<String, Set<String>> groupRegistry,
            OfflineMessageQueue offlineQueue,
            MessageRouter nextRouter,
            ServerGroupStore groupStore) {
        this.onlineUsers = onlineUsers;
        this.groupRegistry = groupRegistry;
        this.offlineQueue = offlineQueue;
        this.nextRouter = nextRouter;
        this.groupStore = groupStore;
        if (groupStore != null) {
            this.groupNames.putAll(groupStore.getGroupNames());
        }
    }

    @Override
    public void route(Envelope env, ClientHandler sender) throws IOException {
        if (env.getOp() == Opcode.GROUP_CREATE) {
            handleGroupCreate(env, sender);
        } else if (env.getOp() == Opcode.CHAT_GROUP) {
            handleChatGroup(env, sender);
        } else if (nextRouter != null) {
            nextRouter.route(env, sender);
        }
    }

    private void handleGroupCreate(Envelope env, ClientHandler sender) throws IOException {
        String groupId = env.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            groupId = env.getString("groupId");
        }

        if (groupId == null || groupId.isBlank()) {
            sender.sendRouterError("INVALID_GROUP_ID", "groupId cannot be empty");
            return;
        }

        Object membersObj = env.get("members");
        if (membersObj == null && groupRegistry.containsKey(groupId)) {
            Set<String> members = groupRegistry.get(groupId);
            String gName = getGroupName(groupId);
            String targetUser = sender != null ? sender.authenticatedUsername() : env.getFrom();
            Envelope snapshot = createSnapshotEnvelope(groupId, gName, members, targetUser);
            if (sender != null) {
                sender.sendRouterEnvelope(snapshot);
            }
            return;
        }

        // Từ chối nếu ID nhóm đã tồn tại để tránh âm thầm ghi đè thành viên
        if (groupRegistry.containsKey(groupId)) {
            sender.sendRouterError("GROUP_ALREADY_EXISTS", "Group already exists: " + groupId);
            return;
        }

        if (!(membersObj instanceof Collection<?> rawMembers)) {
            sender.sendRouterError("INVALID_MEMBERS", "members list cannot be empty");
            return;
        }

        Set<String> memberSet = ConcurrentHashMap.newKeySet();
        for (Object m : rawMembers) {
            if (m != null) {
                String member = String.valueOf(m).trim();
                if (!member.isEmpty()) {
                    memberSet.add(member);
                }
            }
        }

        // Đảm bảo người tạo từ session đã xác thực cũng nằm trong danh sách thành viên nhóm
        String creator = sender != null ? sender.authenticatedUsername() : env.getFrom();
        if (creator != null && !creator.isBlank()) {
            memberSet.add(creator);
        }

        String groupName = env.getString("groupName");
        if (groupName == null || groupName.isBlank()) {
            groupName = groupId;
        }

        // Lưu bền vững trước khi xác nhận tạo nhóm thành công
        if (groupStore != null) {
            try {
                groupStore.putGroup(groupId, groupName, memberSet);
            } catch (IOException e) {
                if (sender != null) {
                    sender.sendRouterError("STORE_ERROR", "Failed to persist group: " + e.getMessage());
                }
                return;
            }
        }
        groupRegistry.put(groupId, memberSet);
        groupNames.put(groupId, groupName);

        // Phản hồi thông báo đã tạo nhóm thành công cho client tạo nhóm
        if (sender != null) {
            Envelope confirm = Envelope.createGroup(Opcode.GROUP_CREATE, "server", groupId)
                    .put("status", "CREATED")
                    .put("groupId", groupId)
                    .put("groupName", groupName)
                    .put("members", new ArrayList<>(memberSet));
            if (creator != null) {
                confirm.setTo(creator);
            }
            sender.sendRouterEnvelope(confirm);
        }

        // Gửi SNAPSHOT ngay cho các thành viên khác đang online
        for (String member : memberSet) {
            if (member.equals(creator)) {
                continue; // Tránh gửi lặp snapshot cho người tạo nếu đã nhận CREATED
            }
            ClientHandler recipient = onlineUsers.get(member);
            if (recipient != null) {
                Envelope snapshot = createSnapshotEnvelope(groupId, groupName, memberSet, member);
                try {
                    recipient.sendRoutedEnvelopeIfCurrent(member, snapshot);
                } catch (IOException e) {
                    recipient.closeRoutedSession();
                }
            }
        }
    }

    private void handleChatGroup(Envelope env, ClientHandler sender) throws IOException {
        String groupId = env.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            sender.sendRouterError("INVALID_GROUP_ID", "groupId cannot be empty");
            return;
        }

        Set<String> members = groupRegistry.get(groupId);
        if (members == null) {
            sender.sendRouterError("GROUP_NOT_FOUND", "Group does not exist: " + groupId);
            return;
        }

        String senderUsername = sender != null ? sender.authenticatedUsername() : env.getFrom();
        if (senderUsername != null && !members.contains(senderUsername)) {
            if (sender != null) {
                sender.sendRouterError("NOT_IN_GROUP", "You are not a member of this group");
            }
            return;
        }

        // Chuyển tiếp mù Envelope tới tất cả thành viên trong nhóm (trừ người gửi)
        for (String member : members) {
            if (member.equals(senderUsername)) {
                continue;
            }
            ClientHandler recipient = onlineUsers.get(member);
            if (recipient != null) {
                try {
                    if (!recipient.sendRoutedEnvelopeIfCurrent(member, env)) {
                        if (offlineQueue != null) {
                            offlineQueue.enqueue(member, env);
                        }
                    }
                } catch (IOException e) {
                    recipient.closeRoutedSession();
                    if (offlineQueue != null) {
                        offlineQueue.enqueue(member, env);
                    }
                }
            } else {
                // Thành viên nhóm offline -> đưa vào hàng đợi offline
                if (offlineQueue != null) {
                    offlineQueue.enqueue(member, env);
                    System.out.println("[E2EE-SERVER] [Issue 12] Thanh vien nhom '" + member
                            + "' offline. Da luu Envelope nhom id=" + env.getId() + " vao OfflineMessageQueue.");
                }
            }
        }
    }

    /**
     * Đồng bộ danh sách các nhóm mà user tham gia khi họ vừa kết nối / đăng nhập thành công.
     */
    public void syncUserGroups(String username, e2ee.common.net.SocketPacketChannel channel) throws IOException {
        if (username == null || channel == null) {
            return;
        }
        for (Map.Entry<String, Set<String>> entry : groupRegistry.entrySet()) {
            String gid = entry.getKey();
            Set<String> members = entry.getValue();
            if (members != null && members.contains(username)) {
                String gName = getGroupName(gid);
                Envelope snapshot = createSnapshotEnvelope(gid, gName, members, username);
                channel.send(snapshot);
            }
        }
    }

    public static Envelope createSnapshotEnvelope(String groupId, String groupName,
            Collection<String> members, String recipient) {
        Envelope snapshot = Envelope.createGroup(Opcode.GROUP_CREATE, "server", groupId)
                .put("status", "SNAPSHOT")
                .put("groupId", groupId)
                .put("groupName", groupName != null && !groupName.isBlank() ? groupName : groupId)
                .put("members", new ArrayList<>(members));
        if (recipient != null) {
            snapshot.setTo(recipient);
        }
        return snapshot;
    }

    public static GroupRouter findIn(MessageRouter router) {
        MessageRouter current = router;
        while (current != null) {
            if (current instanceof GroupRouter gr) {
                return gr;
            }
            if (current instanceof OfflineQueueRouter oqr) {
                current = oqr.getNextRouter();
            } else {
                break;
            }
        }
        return null;
    }

    public Set<String> getGroupMembers(String groupId) {
        Set<String> members = groupRegistry.get(groupId);
        return members != null ? Collections.unmodifiableSet(members) : Collections.emptySet();
    }

    public String getGroupName(String groupId) {
        String name = groupNames.get(groupId);
        if (name != null) {
            return name;
        }
        return groupStore != null ? groupStore.getGroupName(groupId) : groupId;
    }

    public ConcurrentMap<String, Set<String>> getGroupRegistry() {
        return groupRegistry;
    }

    public MessageRouter getNextRouter() {
        return nextRouter;
    }
}
