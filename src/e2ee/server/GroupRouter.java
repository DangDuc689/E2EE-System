package e2ee.server;

import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Định tuyến quản lý nhóm (GROUP_CREATE) và chuyển tiếp tin nhắn nhóm E2EE (CHAT_GROUP).
 *
 * <p>Server đóng vai trò Blind Relay:
 * 1. Quản lý danh bạ nhóm trong RAM: groupId -> Set&lt;String&gt; memberUsernames.
 * 2. Khi nhận GROUP_CREATE: Lưu nhóm và danh sách thành viên vào sổ.
 * 3. Khi nhận CHAT_GROUP: Chuyển tiếp mù nguyên vẹn Envelope tới toàn bộ thành viên
 *    trong nhóm đang online (ngoại trừ người gửi).
 * 4. Chuyển tiếp các gói tin khác (CHAT_1TO1, KEY_LOOKUP) sang MessageRouter kế tiếp.</p>
 */
public final class GroupRouter implements MessageRouter {
    private final ConcurrentMap<String, ClientHandler> onlineUsers;
    private final ConcurrentMap<String, Set<String>> groupRegistry;
    private final MessageRouter nextRouter;

    public GroupRouter(
            ConcurrentMap<String, ClientHandler> onlineUsers,
            MessageRouter nextRouter) {
        this(onlineUsers, new ConcurrentHashMap<>(), nextRouter);
    }

    public GroupRouter(
            ConcurrentMap<String, ClientHandler> onlineUsers,
            ConcurrentMap<String, Set<String>> groupRegistry,
            MessageRouter nextRouter) {
        this.onlineUsers = onlineUsers;
        this.groupRegistry = groupRegistry;
        this.nextRouter = nextRouter;
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

        // Đảm bảo người tạo cũng nằm trong danh sách thành viên nhóm
        String creator = sender.authenticatedUsername();
        if (creator != null && !creator.isBlank()) {
            memberSet.add(creator);
        }

        groupRegistry.put(groupId, memberSet);

        // Phản hồi thông báo đã tạo nhóm thành công cho client tạo nhóm
        Envelope confirm = Envelope.createGroup(Opcode.GROUP_CREATE, "server", groupId)
                .put("status", "CREATED")
                .put("groupId", groupId);
        sender.sendRouterEnvelope(confirm);
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

        String senderUsername = sender.authenticatedUsername();
        if (senderUsername != null && !members.contains(senderUsername)) {
            sender.sendRouterError("NOT_IN_GROUP", "You are not a member of this group");
            return;
        }

        // Chuyển tiếp mù Envelope tới tất cả thành viên trong nhóm đang online (trừ người gửi)
        for (String member : members) {
            if (member.equals(senderUsername)) {
                continue;
            }
            ClientHandler recipient = onlineUsers.get(member);
            if (recipient != null) {
                try {
                    recipient.sendRoutedEnvelopeIfCurrent(member, env);
                } catch (IOException e) {
                    recipient.closeRoutedSession();
                }
            }
        }
    }

    public Set<String> getGroupMembers(String groupId) {
        Set<String> members = groupRegistry.get(groupId);
        return members != null ? Collections.unmodifiableSet(members) : Collections.emptySet();
    }

    public ConcurrentMap<String, Set<String>> getGroupRegistry() {
        return groupRegistry;
    }
}
