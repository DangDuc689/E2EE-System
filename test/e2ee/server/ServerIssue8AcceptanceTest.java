package e2ee.server;

import e2ee.common.TestSupport;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.net.SocketPacketChannel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class ServerIssue8AcceptanceTest {
        private static final String HOST = "127.0.0.1";
        private static final int CONNECT_TIMEOUT_MS = 5_000;
        private static final int RESPONSE_TIMEOUT_MS = 30_000;
        private static final Map<ConcurrentMap<String, ClientHandler>, PresenceDispatcher>
                        PRESENCE_DISPATCHERS = Collections.synchronizedMap(new IdentityHashMap<>());

        private ServerIssue8AcceptanceTest() {
        }

        public static void main(String[] args) {
                TestSupport.check("Username hop le",
                                ServerIssue8AcceptanceTest::testValidUsernames);
                TestSupport.check("Username khong hop le",
                                ServerIssue8AcceptanceTest::testInvalidUsernames);
                TestSupport.check("AUTH_CHALLENGE rieng cho tung connection",
                                ServerIssue8AcceptanceTest::testAuthChallenge);
                TestSupport.check("AUTH dung va PING sau AUTH_OK",
                                ServerIssue8AcceptanceTest::testSuccessfulAuthAndPing);
                TestSupport.check("AUTH sai chu ky bi dong connection",
                                ServerIssue8AcceptanceTest::testInvalidSignature);
                TestSupport.check("AUTH voi username khong hop le",
                                ServerIssue8AcceptanceTest::testInvalidAuthUsername);
                TestSupport.check("PING truoc AUTH bi chan",
                                ServerIssue8AcceptanceTest::testUnauthenticatedPing);
                TestSupport.check("AUTH body sai kieu du lieu",
                                ServerIssue8AcceptanceTest::testInvalidAuthBodyType);
                TestSupport.check("AUTH co Base64 hoac public key khong hop le",
                                ServerIssue8AcceptanceTest::testMalformedBase64AndPublicKey);
                TestSupport.check("TOFU ghim khoa o lan AUTH dau tien",
                                ServerIssue8AcceptanceTest::testTrustOnFirstUse);
                TestSupport.check("TOFU tu choi private key khac cho username da co",
                                ServerIssue8AcceptanceTest::testRegisteredKeyRejectsImpersonation);
                TestSupport.check("Session duoc dang ky va cleanup sau disconnect",
                                ServerIssue8AcceptanceTest::testSessionRegistrationAndCleanup);
                TestSupport.check("Duplicate username: session moi thang",
                                ServerIssue8AcceptanceTest::testDuplicateUsernameLatestSessionWins);
                TestSupport.check("Hai connection canh tranh cung username",
                                ServerIssue8AcceptanceTest::testConcurrentDuplicateUsername);
        TestSupport.check("Session cu khong dispatch sau khi bi thay the",
                ServerIssue8AcceptanceTest::testRevokedSessionCannotDispatch);
        TestSupport.check("Thay session khong cho dispatch cu hoan tat",
                ServerIssue8AcceptanceTest::testReplacementDoesNotWaitForOldDispatch);
        TestSupport.check("Presence snapshot sau AUTH_OK va khong co self",
                ServerIssue8AcceptanceTest::testPresenceSnapshotAfterAuthOk);
        TestSupport.check("Presence snapshot bo qua user da disconnect",
                ServerIssue8AcceptanceTest::testPresenceSnapshotExcludesDisconnectedUser);
        TestSupport.check("Presence snapshot chi co duplicate session moi",
                ServerIssue8AcceptanceTest::testPresenceSnapshotUsesLatestSession);
        TestSupport.check("Presence broadcast online va offline",
                ServerIssue8AcceptanceTest::testPresenceOnlineAndOfflineBroadcast);
        TestSupport.check("Presence event duoc dem trong luc snapshot",
                ServerIssue8AcceptanceTest::testPresenceBufferedDuringSnapshot);
        TestSupport.check("Duplicate va reconnect giu dung thu tu Presence",
                ServerIssue8AcceptanceTest::testDuplicateAndReconnectOrdering);
        TestSupport.check("Disconnect va reconnect dong thoi giu final state",
                ServerIssue8AcceptanceTest::testConcurrentDisconnectAndReconnect);
        TestSupport.check("Client cham duoc co lap va mailbox overflow cleanup",
                ServerIssue8AcceptanceTest::testSlowClientIsolationAndOverflow);
        TestSupport.check("Presence writer shutdown best-effort",
                ServerIssue8AcceptanceTest::testPresenceShutdownBestEffort);
        TestSupport.check("KEY_PUBLISH truoc AUTH bi chan",
                ServerIssue8AcceptanceTest::testUnauthenticatedKeyPublish);
        TestSupport.check("KEY_PUBLISH cap nhat registry va broadcast KEY_CHANGED",
                ServerIssue8AcceptanceTest::testKeyPublishUpdatesRegistryAndBroadcasts);
        TestSupport.check("KEY_PUBLISH tu choi public key khong phai RSA X.509",
                ServerIssue8AcceptanceTest::testInvalidPublishedKeyIsIgnored);
        TestSupport.check("Session bi thay the khong duoc KEY_PUBLISH",
                ServerIssue8AcceptanceTest::testReplacedSessionCannotPublishKey);
        TestSupport.check("TOFU dung public key moi sau KEY_PUBLISH",
                ServerIssue8AcceptanceTest::testTofuUsesPublishedKey);
        TestSupport.check("KEY_CHANGED khong gui khoa cu sau khoa moi",
                ServerIssue8AcceptanceTest::testStaleKeyChangedIsNotSentAfterNewKey);
        TestSupport.check("CHAT_1TO1 relay nguyen ven va chong gia mao from",
                ServerIssue8AcceptanceTest::testChatRelayPreservesEnvelopeAndNormalizesSender);
        TestSupport.check("CHAT_1TO1 toi recipient offline",
                ServerIssue8AcceptanceTest::testChatToOfflineRecipient);
        TestSupport.check("KEY_LOOKUP tim thay public key",
                ServerIssue8AcceptanceTest::testKeyLookupFound);
        TestSupport.check("KEY_LOOKUP khong tim thay public key",
                ServerIssue8AcceptanceTest::testKeyLookupNotFound);
        TestSupport.check("KEY_LOOKUP tra public key moi sau KEY_PUBLISH",
                ServerIssue8AcceptanceTest::testKeyLookupUsesPublishedKey);
        TestSupport.check("Route request truoc AUTH bi chan",
                ServerIssue8AcceptanceTest::testUnauthenticatedRouteRequests);
        TestSupport.check("Session cu khong route sau replacement",
                ServerIssue8AcceptanceTest::testReplacedSessionCannotRoute);
        TestSupport.check("Relay dung session recipient moi sau reconnect",
                ServerIssue8AcceptanceTest::testRelayUsesReconnectedRecipient);
        TestSupport.check("CHAT_1TO1 khong retry sau IOException",
                ServerIssue8AcceptanceTest::testChatIOExceptionDoesNotRetry);
                TestSupport.summary();
        }

        private static void testValidUsernames() {
                TestSupport.assertTrue(ClientHandler.isValidUsername("alice"),
                                "alice phai hop le");
                TestSupport.assertTrue(ClientHandler.isValidUsername("bob_123"),
                                "bob_123 phai hop le");
                TestSupport.assertTrue(ClientHandler.isValidUsername("user001"),
                                "user001 phai hop le");
        }

        private static void testInvalidUsernames() {
                TestSupport.assertTrue(!ClientHandler.isValidUsername("ab"),
                                "Username ngan hon 3 ky tu phai bi tu choi");
                TestSupport.assertTrue(!ClientHandler.isValidUsername("Alice"),
                                "Username co chu hoa phai bi tu choi");
                TestSupport.assertTrue(!ClientHandler.isValidUsername("alice-bob"),
                                "Username co dau gach ngang phai bi tu choi");
                TestSupport.assertTrue(!ClientHandler.isValidUsername("alice|bob"),
                                "Username co ky tu | phai bi tu choi");
                TestSupport.assertTrue(!ClientHandler.isValidUsername("server"),
                                "Username server phai bi tu choi");
        }

        private static void testAuthChallenge() throws Exception {
                PrintStream originalOut = System.out;
                PrintStream originalErr = System.err;
                ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream();
                String firstNonce = null;
                String secondNonce = null;

                try (PrintStream capture = new PrintStream(capturedOutput, true, StandardCharsets.UTF_8)) {
                        System.setOut(capture);
                        System.setErr(capture);

                        try (HandlerSession firstSession = HandlerSession.open()) {
                                firstNonce = assertChallenge(firstSession.client.receive());
                        }
                        try (HandlerSession secondSession = HandlerSession.open()) {
                                secondNonce = assertChallenge(secondSession.client.receive());
                        }

                        TestSupport.assertTrue(!firstNonce.equals(secondNonce),
                                        "Hai connection phai nhan nonce khac nhau");
                } finally {
                        System.setOut(originalOut);
                        System.setErr(originalErr);
                }

                String logs = capturedOutput.toString(StandardCharsets.UTF_8);
                TestSupport.assertTrue(firstNonce != null && !logs.contains(firstNonce),
                                "Server khong duoc log nonce cua connection thu nhat");
                TestSupport.assertTrue(secondNonce != null && !logs.contains(secondNonce),
                                "Server khong duoc log nonce cua connection thu hai");
        }

        private static void testSuccessfulAuthAndPing() throws Exception {
                PrintStream originalOut = System.out;
                PrintStream originalErr = System.err;
                ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream();
                String nonceBase64 = null;
                String signatureBase64 = null;
                String publicKeyBase64 = null;

                try (PrintStream capture = new PrintStream(capturedOutput, true, StandardCharsets.UTF_8)) {
                        System.setOut(capture);
                        System.setErr(capture);

                        try (HandlerSession session = HandlerSession.open()) {
                                nonceBase64 = assertChallenge(session.client.receive());
                                byte[] nonce = Base64.getDecoder().decode(nonceBase64);
                                KeyPair keyPair = generateRsaKeyPair();

                                signatureBase64 = signNonce(nonce, keyPair);
                                publicKeyBase64 = Base64.getEncoder()
                                                .encodeToString(keyPair.getPublic().getEncoded());
                                session.client.send(createAuth(
                                                "alice", signatureBase64, publicKeyBase64));

                                Envelope authOk = session.client.receive();
                                TestSupport.assertEquals(Opcode.AUTH_OK, authOk.getOp());
                                TestSupport.assertEquals("server", authOk.getFrom());
                                TestSupport.assertEquals("alice", authOk.getTo());
                                TestSupport.assertEquals("Authenticated successfully", authOk.getString("msg"));

                                Envelope ping = Envelope.create(Opcode.PING, "alice", "server");
                                ping.setId("issue-8-acceptance-ping");
                                session.client.send(ping);
                                Envelope pong = session.client.receive();
                                TestSupport.assertEquals(Opcode.PONG, pong.getOp());
                                TestSupport.assertEquals(ping.getId(), pong.getId());
                        }
                } finally {
                        System.setOut(originalOut);
                        System.setErr(originalErr);
                }

                String logs = capturedOutput.toString(StandardCharsets.UTF_8);
                TestSupport.assertTrue(nonceBase64 != null && !logs.contains(nonceBase64),
                                "Server khong duoc log nonce");
                TestSupport.assertTrue(signatureBase64 != null && !logs.contains(signatureBase64),
                                "Server khong duoc log chu ky AUTH");
                TestSupport.assertTrue(publicKeyBase64 != null && !logs.contains(publicKeyBase64),
                                "Server khong duoc log public key AUTH");
                TestSupport.assertTrue(!logs.contains("\"sig\"") && !logs.contains("\"pubkey\""),
                                "Server khong duoc log body AUTH");
        }

        private static void testInvalidSignature() throws Exception {
                try (HandlerSession session = HandlerSession.open()) {
                        byte[] nonce = Base64.getDecoder().decode(
                                        assertChallenge(session.client.receive()));
                        KeyPair keyPair = generateRsaKeyPair();
                        nonce[0] ^= 1;

                        session.client.send(createAuth(
                                        "alice",
                                        signNonce(nonce, keyPair),
                                        Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())));

                        assertError(session.client.receive(), "AUTH_FAILED");
                        TestSupport.assertThrows(IOException.class, session.client::receive);
                }
        }

        private static void testInvalidAuthUsername() throws Exception {
                try (HandlerSession session = HandlerSession.open()) {
                        assertChallenge(session.client.receive());
                        Envelope auth = Envelope.create(Opcode.AUTH, "Alice", "server")
                                        .put("sig", "invalid")
                                        .put("pubkey", "invalid");
                        session.client.send(auth);

                        assertError(session.client.receive(), "INVALID_USERNAME");
                        TestSupport.assertThrows(IOException.class, session.client::receive);
                }
        }

        private static void testUnauthenticatedPing() throws Exception {
                try (HandlerSession session = HandlerSession.open()) {
                        assertChallenge(session.client.receive());
                        session.client.send(Envelope.create(Opcode.PING, "alice", "server"));

                        Envelope error = session.client.receive();
                        assertError(error, "UNAUTHENTICATED");
                        TestSupport.assertEquals("Authentication is required", error.getString("msg"));
                }
        }

        private static void testInvalidAuthBodyType() throws Exception {
                try (HandlerSession session = HandlerSession.open()) {
                        assertChallenge(session.client.receive());
                        Envelope auth = Envelope.create(Opcode.AUTH, "alice", "server")
                                        .put("sig", 123L)
                                        .put("pubkey", "not-a-public-key");
                        session.client.send(auth);

                        assertError(session.client.receive(), "AUTH_FAILED");
                        TestSupport.assertThrows(IOException.class, session.client::receive);
                }
        }

        private static void testMalformedBase64AndPublicKey() throws Exception {
                try (HandlerSession session = HandlerSession.open()) {
                        assertChallenge(session.client.receive());
                        session.client.send(createAuth("alice", "%%%", "%%%"));
                        assertError(session.client.receive(), "AUTH_FAILED");
                        TestSupport.assertThrows(IOException.class, session.client::receive);
                }

                try (HandlerSession session = HandlerSession.open()) {
                        assertChallenge(session.client.receive());
                        session.client.send(createAuth(
                                        "alice",
                                        Base64.getEncoder().encodeToString(new byte[] { 1 }),
                                        Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 })));
                        assertError(session.client.receive(), "AUTH_FAILED");
                        TestSupport.assertThrows(IOException.class, session.client::receive);
                }
        }

        private static void testTrustOnFirstUse() throws Exception {
                ConcurrentMap<String, String> registry = new ConcurrentHashMap<>();
                KeyPair registeredKeyPair = generateRsaKeyPair();
                String registeredPublicKey = Base64.getEncoder()
                                .encodeToString(registeredKeyPair.getPublic().getEncoded());

                try (HandlerSession session = HandlerSession.open(registry)) {
                        byte[] nonce = Base64.getDecoder().decode(
                                        assertChallenge(session.client.receive()));
                        session.client.send(createAuth("alice",
                                        signNonce(nonce, registeredKeyPair), registeredPublicKey));
                        TestSupport.assertEquals(Opcode.AUTH_OK, session.client.receive().getOp());
                }
                TestSupport.assertEquals(registeredPublicKey, registry.get("alice"));

                try (HandlerSession session = HandlerSession.open(registry)) {
                        byte[] nonce = Base64.getDecoder().decode(
                                        assertChallenge(session.client.receive()));
                        session.client.send(createAuth("alice",
                                        signNonce(nonce, registeredKeyPair), "ignored-pubkey"));
                        TestSupport.assertEquals(Opcode.AUTH_OK, session.client.receive().getOp());
                }
                TestSupport.assertEquals(registeredPublicKey, registry.get("alice"));
        }

        private static void testRegisteredKeyRejectsImpersonation() throws Exception {
                ConcurrentMap<String, String> registry = new ConcurrentHashMap<>();
                KeyPair registeredKeyPair = generateRsaKeyPair();
                KeyPair attackerKeyPair = generateRsaKeyPair();
                String registeredPublicKey = Base64.getEncoder()
                                .encodeToString(registeredKeyPair.getPublic().getEncoded());
                registry.put("alice", registeredPublicKey);

                try (HandlerSession session = HandlerSession.open(registry)) {
                        byte[] nonce = Base64.getDecoder().decode(
                                        assertChallenge(session.client.receive()));
                        session.client.send(createAuth("alice",
                                        signNonce(nonce, attackerKeyPair),
                                        Base64.getEncoder().encodeToString(
                                                        attackerKeyPair.getPublic().getEncoded())));
                        assertError(session.client.receive(), "AUTH_FAILED");
                        TestSupport.assertThrows(IOException.class, session.client::receive);
                }
                TestSupport.assertEquals(registeredPublicKey, registry.get("alice"));
        }

        private static void testSessionRegistrationAndCleanup() throws Exception {
                ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
                ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
                KeyPair keyPair = generateRsaKeyPair();

                HandlerSession session = HandlerSession.open(keyRegistry, onlineUsers);
                try {
                        authenticate(session, "alice", keyPair);
                        TestSupport.assertTrue(onlineUsers.get("alice") == session.handler(),
                                        "Session phai duoc dang ky sau AUTH_OK");
                } finally {
                        session.close();
                }

                waitUntil(() -> !onlineUsers.containsKey("alice"),
                                "Session phai duoc cleanup sau disconnect");
        }

        private static void testDuplicateUsernameLatestSessionWins() throws Exception {
                ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
                ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
                KeyPair keyPair = generateRsaKeyPair();

                try (HandlerSession oldSession = HandlerSession.open(keyRegistry, onlineUsers)) {
                        authenticate(oldSession, "alice", keyPair);

                        try (HandlerSession newSession = HandlerSession.open(keyRegistry, onlineUsers)) {
                                authenticate(newSession, "alice", keyPair);
                                TestSupport.assertTrue(onlineUsers.get("alice") == newSession.handler(),
                                                "Session moi phai thay the session cu");
                                TestSupport.assertThrows(IOException.class, oldSession.client::receive);
                                waitUntil(() -> !oldSession.handlerThread.isAlive(),
                                                "Session cu phai dung sau khi bi thay the");
                                TestSupport.assertTrue(onlineUsers.get("alice") == newSession.handler(),
                                                "Cleanup session cu khong duoc xoa session moi");

                                assertPingPong(newSession, "duplicate-session-ping");
                        }
                }
        }

        private static void testConcurrentDuplicateUsername() throws Exception {
                ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
                ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
                KeyPair keyPair = generateRsaKeyPair();

                try (HandlerSession first = HandlerSession.open(keyRegistry, onlineUsers);
                                HandlerSession second = HandlerSession.open(keyRegistry, onlineUsers)) {
                        byte[] firstNonce = Base64.getDecoder().decode(
                                        assertChallenge(first.client.receive()));
                        byte[] secondNonce = Base64.getDecoder().decode(
                                        assertChallenge(second.client.receive()));
                        String publicKey = Base64.getEncoder()
                                        .encodeToString(keyPair.getPublic().getEncoded());
                        Envelope firstAuth = createAuth(
                                        "alice", signNonce(firstNonce, keyPair), publicKey);
                        Envelope secondAuth = createAuth(
                                        "alice", signNonce(secondNonce, keyPair), publicKey);

                        AtomicReference<Throwable> sendFailure = new AtomicReference<>();
                        Thread firstSender = new Thread(
                                        () -> sendUnchecked(first.client, firstAuth, sendFailure),
                                        "issue-8-first-auth-sender");
                        Thread secondSender = new Thread(
                                        () -> sendUnchecked(second.client, secondAuth, sendFailure),
                                        "issue-8-second-auth-sender");
                        firstSender.start();
                        secondSender.start();
                        firstSender.join();
                        secondSender.join();
                        if (sendFailure.get() != null) {
                                throw new AssertionError("Khong the gui AUTH canh tranh",
                                                sendFailure.get());
                        }

                        waitUntil(() -> first.handlerThread.isAlive() != second.handlerThread.isAlive(),
                                        "Chi mot session canh tranh duoc tiep tuc hoat dong");
                        ClientHandler winner = onlineUsers.get("alice");
                        TestSupport.assertTrue(winner != null,
                                        "Phai co mot session thang cuoc");
                        HandlerSession winningSession = winner == first.handler() ? first : second;
                        HandlerSession losingSession = winningSession == first ? second : first;

                        Envelope winnerResponse = winningSession.client.receive();
                        TestSupport.assertEquals(Opcode.AUTH_OK, winnerResponse.getOp());
                        try {
                                Envelope ignoredAuthOk = losingSession.client.receive();
                                TestSupport.assertEquals(Opcode.AUTH_OK, ignoredAuthOk.getOp());
                        } catch (IOException expectedWhenClosedBeforeAuthOk) {
                                // Session thua có thể bị đóng trước khi AUTH_OK được gửi.
                        }
                        TestSupport.assertThrows(IOException.class, losingSession.client::receive);
                        waitUntil(() -> !losingSession.handlerThread.isAlive(),
                                        "Session thua phai dung");
                        TestSupport.assertTrue(onlineUsers.get("alice") == winner,
                                        "Cleanup session thua khong duoc xoa session thang");
                        assertPingPong(winningSession, "concurrent-session-ping");
                }
        }

    private static void testRevokedSessionCannotDispatch() throws Exception {
                ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
                ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
                KeyPair keyPair = generateRsaKeyPair();
                CountDownLatch ownershipChecked = new CountDownLatch(1);
                CountDownLatch continueAfterReplacement = new CountDownLatch(1);
                AtomicBoolean oldSessionDispatched = new AtomicBoolean(false);

                Runnable pauseAfterOwnershipCheck = () -> {
                        ownershipChecked.countDown();
                        awaitLatch(continueAfterReplacement);
                };
                try (HandlerSession oldSession = HandlerSession.open(
                                keyRegistry, onlineUsers, pauseAfterOwnershipCheck,
                                () -> oldSessionDispatched.set(true))) {
                        authenticate(oldSession, "alice", keyPair);
                        oldSession.client.send(Envelope.create(Opcode.PING, "alice", "server"));
                        TestSupport.assertTrue(ownershipChecked.await(5, TimeUnit.SECONDS),
                                        "Session A phai dung sau khi kiem tra ownership");

                        try (HandlerSession newSession = HandlerSession.open(keyRegistry, onlineUsers)) {
                                authenticate(newSession, "alice", keyPair);
                                TestSupport.assertTrue(onlineUsers.get("alice") == newSession.handler(),
                                                "Session B phai thay the A truoc khi A dispatch");
                                continueAfterReplacement.countDown();

                                waitUntil(() -> !oldSession.handlerThread.isAlive(),
                                                "Session A phai dung sau khi bi thu hoi");
                                TestSupport.assertTrue(!oldSessionDispatched.get(),
                                                "Session A khong duoc dispatch request sau khi mat quyen");
                                TestSupport.assertThrows(IOException.class, oldSession.client::receive);
                                assertPingPong(newSession, "toctou-fixed-ping");
                        } finally {
                                continueAfterReplacement.countDown();
        }
    }
    }

    private static void testReplacementDoesNotWaitForOldDispatch() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        KeyPair keyPair = generateRsaKeyPair();
        CountDownLatch oldDispatchStarted = new CountDownLatch(1);
        CountDownLatch releaseOldDispatch = new CountDownLatch(1);
        CountDownLatch replacementFinished = new CountDownLatch(1);
        AtomicReference<Throwable> replacementFailure = new AtomicReference<>();

        Runnable holdActiveDispatch = () -> {
            oldDispatchStarted.countDown();
            awaitLatch(releaseOldDispatch);
        };
        try (HandlerSession oldSession = HandlerSession.open(
                keyRegistry, onlineUsers, () -> {
                }, holdActiveDispatch)) {
            authenticate(oldSession, "alice", keyPair);
            oldSession.client.send(Envelope.create(Opcode.PING, "alice", "server"));
            TestSupport.assertTrue(oldDispatchStarted.await(5, TimeUnit.SECONDS),
                    "Dispatch cu phai dang hoat dong");

            try (HandlerSession newSession = HandlerSession.open(keyRegistry, onlineUsers)) {
                Thread replacement = new Thread(() -> {
                    try {
                        authenticate(newSession, "alice", keyPair);
                    } catch (Throwable t) {
                        replacementFailure.set(t);
                    } finally {
                        replacementFinished.countDown();
                    }
                }, "issue-8-nonblocking-replacement");
                replacement.start();

                TestSupport.assertTrue(replacementFinished.await(5, TimeUnit.SECONDS),
                        "Session moi khong duoc cho dispatch cu vo han");
                if (replacementFailure.get() != null) {
                    throw new AssertionError("Session moi AUTH that bai",
                            replacementFailure.get());
                }
                TestSupport.assertTrue(onlineUsers.get("alice") == newSession.handler(),
                        "Session moi phai thang khi dispatch cu dang bi giu");

                releaseOldDispatch.countDown();
                replacement.join(5_000);
                waitUntil(() -> !oldSession.handlerThread.isAlive(),
                        "Session cu phai dung sau khi dispatch duoc tha");
                TestSupport.assertThrows(IOException.class, oldSession.client::receive);
                TestSupport.assertTrue(onlineUsers.get("alice") == newSession.handler(),
                        "Cleanup session cu khong duoc xoa session moi");
                assertPingPong(newSession, "nonblocking-replacement-ping");
            } finally {
                releaseOldDispatch.countDown();
            }
        }
    }

    private static void testPresenceSnapshotAfterAuthOk() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        KeyPair aliceKey = generateRsaKeyPair();
        KeyPair bobKey = generateRsaKeyPair();
        KeyPair charlieKey = generateRsaKeyPair();

        try (HandlerSession alice = HandlerSession.open(keyRegistry, onlineUsers);
                HandlerSession bob = HandlerSession.open(keyRegistry, onlineUsers);
                HandlerSession charlie = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(alice, "alice", aliceKey);
            authenticate(bob, "bob", bobKey);
            assertPresence(bob.client.receive(), "bob", "alice");

            authenticate(charlie, "charlie", charlieKey);
            Set<String> snapshotUsers = new HashSet<>();
            snapshotUsers.add(assertPresence(charlie.client.receive(), "charlie", null));
            snapshotUsers.add(assertPresence(charlie.client.receive(), "charlie", null));
            TestSupport.assertEquals(Set.of("alice", "bob"), snapshotUsers);

            assertPingPong(charlie, "snapshot-order-ping");
        }
    }

    private static void testPresenceSnapshotExcludesDisconnectedUser() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        KeyPair aliceKey = generateRsaKeyPair();
        KeyPair bobKey = generateRsaKeyPair();

        HandlerSession alice = HandlerSession.open(keyRegistry, onlineUsers);
        authenticate(alice, "alice", aliceKey);
        alice.close();
        waitUntil(() -> !onlineUsers.containsKey("alice"),
                "User disconnect phai duoc xoa truoc snapshot");

        try (HandlerSession bob = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(bob, "bob", bobKey);
            assertPingPong(bob, "disconnected-user-not-in-snapshot");
        }
    }

    private static void testPresenceSnapshotUsesLatestSession() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        KeyPair aliceKey = generateRsaKeyPair();
        KeyPair bobKey = generateRsaKeyPair();
        KeyPair charlieKey = generateRsaKeyPair();

        try (HandlerSession bob = HandlerSession.open(keyRegistry, onlineUsers);
                HandlerSession oldAlice = HandlerSession.open(keyRegistry, onlineUsers);
                HandlerSession newAlice = HandlerSession.open(keyRegistry, onlineUsers);
                HandlerSession charlie = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(bob, "bob", bobKey);
            authenticate(oldAlice, "alice", aliceKey);
            assertPresence(oldAlice.client.receive(), "alice", "bob");

            authenticate(newAlice, "alice", aliceKey);
            assertPresence(newAlice.client.receive(), "alice", "bob");
            waitUntil(() -> !oldAlice.handlerThread.isAlive(),
                    "Duplicate session cu phai dung truoc snapshot tiep theo");

            authenticate(charlie, "charlie", charlieKey);
            Set<String> snapshotUsers = new HashSet<>();
            snapshotUsers.add(assertPresence(charlie.client.receive(), "charlie", null));
            snapshotUsers.add(assertPresence(charlie.client.receive(), "charlie", null));
            TestSupport.assertEquals(Set.of("alice", "bob"), snapshotUsers);
            assertPingPong(charlie, "latest-session-snapshot-ping");
        }
    }

    private static void testPresenceOnlineAndOfflineBroadcast() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        KeyPair aliceKey = generateRsaKeyPair();
        KeyPair bobKey = generateRsaKeyPair();

        try (HandlerSession alice = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(alice, "alice", aliceKey);
            HandlerSession bob = HandlerSession.open(keyRegistry, onlineUsers);
            try {
                authenticate(bob, "bob", bobKey);
                assertPresence(bob.client.receive(), "bob", "alice", true);
                assertPresence(alice.client.receive(), "alice", "bob", true);
            } finally {
                bob.close();
            }
            assertPresence(alice.client.receive(), "alice", "bob", false);
        }
    }

    private static void testPresenceBufferedDuringSnapshot() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        PresenceDispatcher dispatcher = new PresenceDispatcher(onlineUsers);
        KeyPair aliceKey = generateRsaKeyPair();
        KeyPair bobKey = generateRsaKeyPair();
        KeyPair charlieKey = generateRsaKeyPair();
        CountDownLatch snapshotPaused = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);

        try (HandlerSession alice = HandlerSession.open(
                keyRegistry, onlineUsers, dispatcher);
                HandlerSession charlie = HandlerSession.open(
                        keyRegistry, onlineUsers, dispatcher,
                        () -> {
                        }, () -> {
                        }, () -> {
                            snapshotPaused.countDown();
                            awaitLatch(releaseSnapshot);
                        }, () -> {
                        })) {
            authenticate(alice, "alice", aliceKey);
            authenticate(charlie, "charlie", charlieKey);
            TestSupport.assertTrue(snapshotPaused.await(5, TimeUnit.SECONDS),
                    "Snapshot phai dung sau AUTH_OK");

            HandlerSession bob = HandlerSession.open(keyRegistry, onlineUsers, dispatcher);
            try {
                authenticate(bob, "bob", bobKey);
                assertPresence(bob.client.receive(), "bob", "alice", true);
                bob.close();
                bob = null;
            } finally {
                if (bob != null) {
                    bob.close();
                }
            }

            releaseSnapshot.countDown();
            assertPresence(charlie.client.receive(), "charlie", "alice", true);
            assertPresence(charlie.client.receive(), "charlie", "bob", true);
            assertPresence(charlie.client.receive(), "charlie", "bob", false);
        } finally {
            releaseSnapshot.countDown();
            dispatcher.shutdown();
        }
    }

    private static void testDuplicateAndReconnectOrdering() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        PresenceDispatcher dispatcher = new PresenceDispatcher(onlineUsers);
        KeyPair observerKey = generateRsaKeyPair();
        KeyPair bobKey = generateRsaKeyPair();
        KeyPair charlieKey = generateRsaKeyPair();

        try (HandlerSession observer = HandlerSession.open(
                keyRegistry, onlineUsers, dispatcher);
                HandlerSession oldBob = HandlerSession.open(
                        keyRegistry, onlineUsers, dispatcher);
                HandlerSession newBob = HandlerSession.open(
                        keyRegistry, onlineUsers, dispatcher);
                HandlerSession charlie = HandlerSession.open(
                        keyRegistry, onlineUsers, dispatcher)) {
            authenticate(observer, "observer", observerKey);
            authenticate(oldBob, "bob", bobKey);
            assertPresence(oldBob.client.receive(), "bob", "observer", true);
            assertPresence(observer.client.receive(), "observer", "bob", true);

            authenticate(newBob, "bob", bobKey);
            assertPresence(newBob.client.receive(), "bob", "observer", true);
            assertPresence(observer.client.receive(), "observer", "bob", true);
            waitUntil(() -> !oldBob.handlerThread.isAlive(),
                    "Duplicate session cu phai dung");

            authenticate(charlie, "charlie", charlieKey);
            Set<String> charlieSnapshot = new HashSet<>();
            charlieSnapshot.add(assertPresence(
                    charlie.client.receive(), "charlie", null, true));
            charlieSnapshot.add(assertPresence(
                    charlie.client.receive(), "charlie", null, true));
            TestSupport.assertEquals(Set.of("observer", "bob"), charlieSnapshot);
            assertPresence(observer.client.receive(), "observer", "charlie", true);
        } finally {
            dispatcher.shutdown();
        }
    }

    private static void testSlowClientIsolationAndOverflow() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        PresenceDispatcher dispatcher = new PresenceDispatcher(onlineUsers, 2);
        CountDownLatch slowWriterStarted = new CountDownLatch(1);
        CountDownLatch releaseSlowWriter = new CountDownLatch(1);
        Runnable blockPresenceWriter = () -> {
            slowWriterStarted.countDown();
            awaitLatch(releaseSlowWriter);
        };

        try (HandlerSession slow = HandlerSession.open(
                keyRegistry, onlineUsers, dispatcher,
                () -> {
                }, () -> {
                }, () -> {
                }, blockPresenceWriter);
                HandlerSession observer = HandlerSession.open(
                        keyRegistry, onlineUsers, dispatcher);
                HandlerSession bob = HandlerSession.open(
                        keyRegistry, onlineUsers, dispatcher);
                HandlerSession charlie = HandlerSession.open(
                        keyRegistry, onlineUsers, dispatcher);
                HandlerSession dave = HandlerSession.open(
                        keyRegistry, onlineUsers, dispatcher)) {
            authenticate(slow, "alice", generateRsaKeyPair());
            authenticate(observer, "observer", generateRsaKeyPair());
            assertPresence(observer.client.receive(), "observer", "alice", true);
            TestSupport.assertTrue(slowWriterStarted.await(5, TimeUnit.SECONDS),
                    "Writer cham phai bi giu rieng");

            authenticate(bob, "bob", generateRsaKeyPair());
            Set<String> bobSnapshot = new HashSet<>();
            bobSnapshot.add(assertPresence(bob.client.receive(), "bob", null, true));
            bobSnapshot.add(assertPresence(bob.client.receive(), "bob", null, true));
            TestSupport.assertEquals(Set.of("alice", "observer"), bobSnapshot);
            assertPresence(observer.client.receive(), "observer", "bob", true);

            authenticate(charlie, "charlie", generateRsaKeyPair());
            assertPresence(observer.client.receive(), "observer", "charlie", true);

            authenticate(dave, "dave", generateRsaKeyPair());
            waitUntil(() -> !onlineUsers.containsKey("alice"),
                    "Mailbox day phai cleanup session cham");
            assertPresence(observer.client.receive(), "observer", "dave", true);
            assertPresence(observer.client.receive(), "observer", "alice", false);
        } finally {
            releaseSlowWriter.countDown();
            dispatcher.shutdown();
        }
    }

    private static void testConcurrentDisconnectAndReconnect() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        PresenceDispatcher dispatcher = new PresenceDispatcher(onlineUsers);
        KeyPair observerKey = generateRsaKeyPair();
        KeyPair bobKey = generateRsaKeyPair();
        KeyPair charlieKey = generateRsaKeyPair();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AtomicReference<Throwable> reconnectFailure = new AtomicReference<>();
        CountDownLatch startRace = new CountDownLatch(1);

        HandlerSession oldBob = HandlerSession.open(keyRegistry, onlineUsers, dispatcher);
        try (HandlerSession observer = HandlerSession.open(
                keyRegistry, onlineUsers, dispatcher);
                HandlerSession newBob = HandlerSession.open(
                        keyRegistry, onlineUsers, dispatcher);
                HandlerSession charlie = HandlerSession.open(
                        keyRegistry, onlineUsers, dispatcher)) {
            authenticate(observer, "observer", observerKey);
            authenticate(oldBob, "bob", bobKey);
            assertPresence(oldBob.client.receive(), "bob", "observer", true);
            assertPresence(observer.client.receive(), "observer", "bob", true);

            Thread disconnect = new Thread(() -> {
                awaitLatch(startRace);
                try {
                    oldBob.close();
                } catch (Throwable t) {
                    closeFailure.set(t);
                }
            }, "issue-8-presence-disconnect");
            Thread reconnect = new Thread(() -> {
                awaitLatch(startRace);
                try {
                    authenticate(newBob, "bob", bobKey);
                } catch (Throwable t) {
                    reconnectFailure.set(t);
                }
            }, "issue-8-presence-reconnect");
            disconnect.start();
            reconnect.start();
            startRace.countDown();
            disconnect.join(5_000);
            reconnect.join(5_000);
            TestSupport.assertTrue(!disconnect.isAlive() && !reconnect.isAlive(),
                    "Disconnect/reconnect race phai hoan tat");
            if (closeFailure.get() != null) {
                throw new AssertionError("Disconnect dong thoi that bai", closeFailure.get());
            }
            if (reconnectFailure.get() != null) {
                throw new AssertionError("Reconnect dong thoi that bai", reconnectFailure.get());
            }

            assertPresence(newBob.client.receive(), "bob", "observer", true);
            Envelope firstTransition = observer.client.receive();
            String firstUser = assertPresence(firstTransition, "observer", "bob",
                    Boolean.TRUE.equals(firstTransition.get("online")));
            TestSupport.assertEquals("bob", firstUser);
            if (Boolean.FALSE.equals(firstTransition.get("online"))) {
                assertPresence(observer.client.receive(), "observer", "bob", true);
            }

            authenticate(charlie, "charlie", charlieKey);
            Set<String> snapshot = new HashSet<>();
            snapshot.add(assertPresence(charlie.client.receive(), "charlie", null, true));
            snapshot.add(assertPresence(charlie.client.receive(), "charlie", null, true));
            TestSupport.assertEquals(Set.of("observer", "bob"), snapshot);
            assertPresence(observer.client.receive(), "observer", "charlie", true);
        } finally {
            try {
                oldBob.close();
            } finally {
                dispatcher.shutdown();
            }
        }
    }

    private static void testPresenceShutdownBestEffort() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        PresenceDispatcher dispatcher = new PresenceDispatcher(onlineUsers);
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);

        try (HandlerSession slow = HandlerSession.open(
                keyRegistry, onlineUsers, dispatcher,
                () -> {
                }, () -> {
                }, () -> {
                }, () -> {
                    writerStarted.countDown();
                    awaitLatch(releaseWriter);
                });
                HandlerSession trigger = HandlerSession.open(
                        keyRegistry, onlineUsers, dispatcher)) {
            authenticate(slow, "alice", generateRsaKeyPair());
            authenticate(trigger, "trigger", generateRsaKeyPair());
            TestSupport.assertTrue(writerStarted.await(5, TimeUnit.SECONDS),
                    "Writer phai dang xu ly event");

            long startedAt = System.nanoTime();
            dispatcher.shutdown();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAt);
            TestSupport.assertTrue(elapsedMs < 1_000,
                    "Shutdown khong duoc cho writer blocking");
            TestSupport.assertTrue(onlineUsers.isEmpty(),
                    "Shutdown phai cleanup online registry");
        } finally {
            releaseWriter.countDown();
            dispatcher.shutdown();
        }
    }

    private static void testUnauthenticatedKeyPublish() throws Exception {
        try (HandlerSession session = HandlerSession.open()) {
            assertChallenge(session.client.receive());
            String publicKey = Base64.getEncoder().encodeToString(
                    generateRsaKeyPair().getPublic().getEncoded());
            session.client.send(Envelope.create(
                    Opcode.KEY_PUBLISH, "alice", "server")
                    .put("pubkey", publicKey));

            assertError(session.client.receive(), "UNAUTHENTICATED");
        }
    }

    private static void testKeyPublishUpdatesRegistryAndBroadcasts() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        KeyPair aliceKey = generateRsaKeyPair();
        KeyPair bobKey = generateRsaKeyPair();
        String newAlicePublicKey = Base64.getEncoder().encodeToString(
                generateRsaKeyPair().getPublic().getEncoded());

        try (HandlerSession alice = HandlerSession.open(keyRegistry, onlineUsers);
                HandlerSession bob = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(alice, "alice", aliceKey);
            authenticate(bob, "bob", bobKey);
            assertPresence(bob.client.receive(), "bob", "alice");
            assertPresence(alice.client.receive(), "alice", "bob");

            alice.client.send(Envelope.create(
                    Opcode.KEY_PUBLISH, "mallory", "server")
                    .put("pubkey", newAlicePublicKey));

            assertKeyChanged(alice.client.receive(), "alice", "alice",
                    newAlicePublicKey);
            assertKeyChanged(bob.client.receive(), "alice", "bob",
                    newAlicePublicKey);
            TestSupport.assertEquals(newAlicePublicKey, keyRegistry.get("alice"));
            TestSupport.assertTrue(!keyRegistry.containsKey("mallory"),
                    "Server khong duoc tin envelope.from cua KEY_PUBLISH");
        }
    }

    private static void testInvalidPublishedKeyIsIgnored() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        KeyPair aliceKey = generateRsaKeyPair();

        try (HandlerSession alice = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(alice, "alice", aliceKey);
            String originalPublicKey = keyRegistry.get("alice");
            KeyPairGenerator ecGenerator = KeyPairGenerator.getInstance("EC");
            ecGenerator.initialize(256);
            String ecPublicKey = Base64.getEncoder().encodeToString(
                    ecGenerator.generateKeyPair().getPublic().getEncoded());

            alice.client.send(Envelope.create(
                    Opcode.KEY_PUBLISH, "alice", "server")
                    .put("pubkey", ecPublicKey));
            assertPingPong(alice, "invalid-published-key");

            TestSupport.assertEquals(originalPublicKey, keyRegistry.get("alice"));
        }
    }

    private static void testReplacedSessionCannotPublishKey() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        KeyPair trustedKey = generateRsaKeyPair();
        String trustedPublicKey = Base64.getEncoder().encodeToString(
                trustedKey.getPublic().getEncoded());
        String replacementKey = Base64.getEncoder().encodeToString(
                generateRsaKeyPair().getPublic().getEncoded());
        CountDownLatch dispatchReached = new CountDownLatch(1);
        CountDownLatch releaseDispatch = new CountDownLatch(1);

        try (HandlerSession oldSession = HandlerSession.open(
                keyRegistry, onlineUsers, () -> {
                }, () -> {
                    dispatchReached.countDown();
                    awaitLatch(releaseDispatch);
                });
                HandlerSession newSession = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(oldSession, "alice", trustedKey);
            oldSession.client.send(Envelope.create(
                    Opcode.KEY_PUBLISH, "alice", "server")
                    .put("pubkey", replacementKey));
            TestSupport.assertTrue(dispatchReached.await(5, TimeUnit.SECONDS),
                    "Session cu phai vao dispatch truoc replacement");

            authenticate(newSession, "alice", trustedKey);
            releaseDispatch.countDown();
            waitUntil(() -> onlineUsers.get("alice") == newSession.handler(),
                    "Session moi phai giu ownership");

            TestSupport.assertEquals(trustedPublicKey, keyRegistry.get("alice"));
            assertPingPong(newSession, "new-session-after-replacement");
        } finally {
            releaseDispatch.countDown();
        }
    }

    private static void testTofuUsesPublishedKey() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        KeyPair oldKey = generateRsaKeyPair();
        KeyPair newKey = generateRsaKeyPair();
        String newPublicKey = Base64.getEncoder().encodeToString(
                newKey.getPublic().getEncoded());

        try (HandlerSession first = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(first, "alice", oldKey);
            first.client.send(Envelope.create(
                    Opcode.KEY_PUBLISH, "alice", "server")
                    .put("pubkey", newPublicKey));
            assertKeyChanged(first.client.receive(), "alice", "alice", newPublicKey);
        }
        waitUntil(() -> !onlineUsers.containsKey("alice"),
                "Session dau phai cleanup truoc lan AUTH tiep theo");

        try (HandlerSession stale = HandlerSession.open(keyRegistry, onlineUsers)) {
            byte[] nonce = Base64.getDecoder().decode(
                    assertChallenge(stale.client.receive()));
            stale.client.send(createAuth("alice", signNonce(nonce, oldKey),
                    Base64.getEncoder().encodeToString(oldKey.getPublic().getEncoded())));
            assertError(stale.client.receive(), "AUTH_FAILED");
        }

        try (HandlerSession current = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(current, "alice", newKey);
            assertPingPong(current, "published-key-auth");
        }
    }

    private static void testStaleKeyChangedIsNotSentAfterNewKey() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        KeyPair observerKey = generateRsaKeyPair();
        KeyPair initialKey = generateRsaKeyPair();
        KeyPair firstPublishedKey = generateRsaKeyPair();
        KeyPair secondPublishedKey = generateRsaKeyPair();
        String firstPublicKey = Base64.getEncoder().encodeToString(
                firstPublishedKey.getPublic().getEncoded());
        String secondPublicKey = Base64.getEncoder().encodeToString(
                secondPublishedKey.getPublic().getEncoded());
        CountDownLatch firstKeyUpdated = new CountDownLatch(1);
        CountDownLatch releaseFirstBroadcast = new CountDownLatch(1);

        try (HandlerSession observer = HandlerSession.open(keyRegistry, onlineUsers);
                HandlerSession first = HandlerSession.open(
                        keyRegistry, onlineUsers, () -> {
                            firstKeyUpdated.countDown();
                            awaitLatch(releaseFirstBroadcast);
                        });
                HandlerSession second = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(observer, "observer", observerKey);
            authenticate(first, "alice", initialKey);
            assertPresence(first.client.receive(), "alice", "observer");
            assertPresence(observer.client.receive(), "observer", "alice");

            first.client.send(Envelope.create(
                    Opcode.KEY_PUBLISH, "alice", "server")
                    .put("pubkey", firstPublicKey));
            TestSupport.assertTrue(firstKeyUpdated.await(5, TimeUnit.SECONDS),
                    "K1 phai duoc cap nhat truoc khi tam dung broadcast");
            TestSupport.assertEquals(firstPublicKey, keyRegistry.get("alice"));

            authenticate(second, "alice", firstPublishedKey);
            assertPresence(second.client.receive(), "alice", "observer");
            assertPresence(observer.client.receive(), "observer", "alice");
            second.client.send(Envelope.create(
                    Opcode.KEY_PUBLISH, "alice", "server")
                    .put("pubkey", secondPublicKey));
            assertKeyChanged(second.client.receive(), "alice", "alice",
                    secondPublicKey);
            assertKeyChanged(observer.client.receive(), "alice", "observer",
                    secondPublicKey);

            releaseFirstBroadcast.countDown();
            waitUntil(() -> !first.handlerThread.isAlive(),
                    "Session A phai ket thuc sau khi tiep tuc broadcast");
            assertPingPong(observer, "stale-key-event-filtered");
            TestSupport.assertEquals(secondPublicKey, keyRegistry.get("alice"));
        } finally {
            releaseFirstBroadcast.countDown();
        }
    }

    private static void testChatRelayPreservesEnvelopeAndNormalizesSender()
            throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();

        try (HandlerSession alice = HandlerSession.open(keyRegistry, onlineUsers);
                HandlerSession bob = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(alice, "alice", generateRsaKeyPair());
            authenticate(bob, "bob", generateRsaKeyPair());
            assertPresence(bob.client.receive(), "bob", "alice");
            assertPresence(alice.client.receive(), "alice", "bob");

            Envelope chat = Envelope.create(Opcode.CHAT_1TO1, "mallory", "bob")
                    .put("ek", "wrapped-key")
                    .put("iv", "initialization-vector")
                    .put("ct", "ciphertext")
                    .put("sig", "signature");
            chat.setId("chat-preserved-id");
            chat.setTs(1_711_111_111_111L);
            Map<String, Object> expectedBody = Map.copyOf(chat.getBody());
            alice.client.send(chat);

            Envelope relayed = bob.client.receive();
            TestSupport.assertEquals(Opcode.CHAT_1TO1, relayed.getOp());
            TestSupport.assertEquals("alice", relayed.getFrom());
            TestSupport.assertEquals("bob", relayed.getTo());
            TestSupport.assertEquals("chat-preserved-id", relayed.getId());
            TestSupport.assertEquals(1_711_111_111_111L, relayed.getTs());
            TestSupport.assertEquals(expectedBody, relayed.getBody());
        }
    }

    private static void testChatToOfflineRecipient() throws Exception {
        try (HandlerSession alice = HandlerSession.open()) {
            authenticate(alice, "alice", generateRsaKeyPair());
            alice.client.send(Envelope.create(
                    Opcode.CHAT_1TO1, "mallory", "offline_user")
                    .put("ct", "ciphertext"));

            Envelope error = alice.client.receive();
            assertError(error, "RECIPIENT_OFFLINE");
            TestSupport.assertEquals("alice", error.getTo());
            TestSupport.assertEquals("User is offline", error.getString("msg"));
        }
    }

    private static void testKeyLookupFound() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        KeyPair bobKey = generateRsaKeyPair();
        String bobPublicKey = Base64.getEncoder().encodeToString(
                bobKey.getPublic().getEncoded());

        try (HandlerSession bob = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(bob, "bob", bobKey);
        }
        waitUntil(() -> !onlineUsers.containsKey("bob"),
                "Bob phai offline truoc KEY_LOOKUP");

        try (HandlerSession alice = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(alice, "alice", generateRsaKeyPair());
            alice.client.send(Envelope.create(
                    Opcode.KEY_LOOKUP, "mallory", "bob"));

            assertKeyLookupResult(
                    alice.client.receive(), "bob", "alice", bobPublicKey);
        }
    }

    private static void testKeyLookupNotFound() throws Exception {
        try (HandlerSession alice = HandlerSession.open()) {
            authenticate(alice, "alice", generateRsaKeyPair());
            alice.client.send(Envelope.create(
                    Opcode.KEY_LOOKUP, "mallory", "unknown_user"));

            Envelope error = alice.client.receive();
            assertError(error, "KEY_NOT_FOUND");
            TestSupport.assertEquals("alice", error.getTo());
            TestSupport.assertEquals("User not found", error.getString("msg"));
        }
    }

    private static void testKeyLookupUsesPublishedKey() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        KeyPair newBobKey = generateRsaKeyPair();
        String newBobPublicKey = Base64.getEncoder().encodeToString(
                newBobKey.getPublic().getEncoded());

        try (HandlerSession bob = HandlerSession.open(keyRegistry, onlineUsers);
                HandlerSession alice = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(bob, "bob", generateRsaKeyPair());
            authenticate(alice, "alice", generateRsaKeyPair());
            assertPresence(alice.client.receive(), "alice", "bob");
            assertPresence(bob.client.receive(), "bob", "alice");

            bob.client.send(Envelope.create(
                    Opcode.KEY_PUBLISH, "mallory", "server")
                    .put("pubkey", newBobPublicKey));
            assertKeyChanged(bob.client.receive(), "bob", "bob", newBobPublicKey);
            assertKeyChanged(alice.client.receive(), "bob", "alice", newBobPublicKey);

            alice.client.send(Envelope.create(
                    Opcode.KEY_LOOKUP, "mallory", "bob"));
            assertKeyLookupResult(
                    alice.client.receive(), "bob", "alice", newBobPublicKey);
        }
    }

    private static void testUnauthenticatedRouteRequests() throws Exception {
        try (HandlerSession session = HandlerSession.open()) {
            assertChallenge(session.client.receive());
            session.client.send(Envelope.create(
                    Opcode.CHAT_1TO1, "alice", "bob")
                    .put("ct", "ciphertext"));
            assertError(session.client.receive(), "UNAUTHENTICATED");

            session.client.send(Envelope.create(
                    Opcode.KEY_LOOKUP, "alice", "bob"));
            assertError(session.client.receive(), "UNAUTHENTICATED");
        }
    }

    private static void testReplacedSessionCannotRoute() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        KeyPair aliceKey = generateRsaKeyPair();
        CountDownLatch dispatchReached = new CountDownLatch(1);
        CountDownLatch releaseDispatch = new CountDownLatch(1);

        try (HandlerSession bob = HandlerSession.open(keyRegistry, onlineUsers);
                HandlerSession oldAlice = HandlerSession.open(
                        keyRegistry, onlineUsers, () -> {
                        }, () -> {
                            dispatchReached.countDown();
                            awaitLatch(releaseDispatch);
                        });
                HandlerSession newAlice = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(bob, "bob", generateRsaKeyPair());
            authenticate(oldAlice, "alice", aliceKey);
            assertPresence(oldAlice.client.receive(), "alice", "bob");
            assertPresence(bob.client.receive(), "bob", "alice");

            oldAlice.client.send(Envelope.create(
                    Opcode.CHAT_1TO1, "alice", "bob")
                    .put("ct", "old-session-message"));
            TestSupport.assertTrue(dispatchReached.await(5, TimeUnit.SECONDS),
                    "Session cu phai dung truoc route");

            authenticate(newAlice, "alice", aliceKey);
            assertPresence(newAlice.client.receive(), "alice", "bob");
            assertPresence(bob.client.receive(), "bob", "alice");
            releaseDispatch.countDown();
            waitUntil(() -> !oldAlice.handlerThread.isAlive(),
                    "Session cu phai ket thuc sau revoke");
            assertPingPong(bob, "revoked-route-filtered");

            newAlice.client.send(Envelope.create(
                    Opcode.CHAT_1TO1, "alice", "bob")
                    .put("ct", "new-session-message"));
            Envelope relayed = bob.client.receive();
            TestSupport.assertEquals(Opcode.CHAT_1TO1, relayed.getOp());
            TestSupport.assertEquals("new-session-message", relayed.getString("ct"));
        } finally {
            releaseDispatch.countDown();
        }
    }

    private static void testRelayUsesReconnectedRecipient() throws Exception {
        ConcurrentMap<String, String> keyRegistry = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
        KeyPair bobKey = generateRsaKeyPair();
        CountDownLatch dispatchReached = new CountDownLatch(1);
        CountDownLatch releaseDispatch = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean(true);

        try (HandlerSession alice = HandlerSession.open(
                keyRegistry, onlineUsers, () -> {
                }, () -> {
                    if (pauseOnce.compareAndSet(true, false)) {
                        dispatchReached.countDown();
                        awaitLatch(releaseDispatch);
                    }
                });
                HandlerSession oldBob = HandlerSession.open(keyRegistry, onlineUsers);
                HandlerSession newBob = HandlerSession.open(keyRegistry, onlineUsers)) {
            authenticate(alice, "alice", generateRsaKeyPair());
            authenticate(oldBob, "bob", bobKey);
            assertPresence(oldBob.client.receive(), "bob", "alice");
            assertPresence(alice.client.receive(), "alice", "bob");

            alice.client.send(Envelope.create(
                    Opcode.CHAT_1TO1, "alice", "bob")
                    .put("ct", "reconnect-message"));
            TestSupport.assertTrue(dispatchReached.await(5, TimeUnit.SECONDS),
                    "Relay phai dung truoc khi tra recipient");

            authenticate(newBob, "bob", bobKey);
            assertPresence(newBob.client.receive(), "bob", "alice");
            assertPresence(alice.client.receive(), "alice", "bob");
            releaseDispatch.countDown();

            Envelope relayed = newBob.client.receive();
            TestSupport.assertEquals(Opcode.CHAT_1TO1, relayed.getOp());
            TestSupport.assertEquals("alice", relayed.getFrom());
            TestSupport.assertEquals("reconnect-message", relayed.getString("ct"));
        } finally {
            releaseDispatch.countDown();
        }
    }

    private static void testChatIOExceptionDoesNotRetry() throws Exception {
        ConcurrentMap<String, String> senderKeys = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> senderOnline = new ConcurrentHashMap<>();
        ConcurrentMap<String, ClientHandler> routerOnline = new ConcurrentHashMap<>();
        AtomicInteger sendAttempts = new AtomicInteger();

        try (HandlerSession sender = HandlerSession.open(senderKeys, senderOnline);
                HandlerSession failedRecipient = HandlerSession.open();
                HandlerSession replacementRecipient = HandlerSession.open()) {
            authenticate(sender, "alice", generateRsaKeyPair());
            assertChallenge(failedRecipient.client.receive());
            assertChallenge(replacementRecipient.client.receive());
            ClientHandler failedHandler = failedRecipient.handler();
            ClientHandler replacementHandler = replacementRecipient.handler();
            routerOnline.put("bob", failedHandler);

            Default1to1Router router = new Default1to1Router(
                    routerOnline, new ConcurrentHashMap<>(),
                    (recipient, expectedUsername, envelope) -> {
                        sendAttempts.incrementAndGet();
                        TestSupport.assertTrue(recipient == failedHandler,
                                "Lan gui dau phai dung recipient cu");
                        routerOnline.put(expectedUsername, replacementHandler);
                        throw new IOException("Simulated ambiguous socket write");
                    });

            router.route(Envelope.create(
                    Opcode.CHAT_1TO1, "alice", "bob")
                    .put("ct", "ciphertext"), sender.handler());

            TestSupport.assertEquals(1, sendAttempts.get());
            TestSupport.assertTrue(routerOnline.get("bob") == replacementHandler,
                    "Mapping phai doi de chung minh router khong retry session moi");
            assertPingPong(sender, "sender-after-ambiguous-recipient-io");
        }
    }

        private static void awaitLatch(CountDownLatch latch) {
                boolean interrupted = false;
                while (true) {
                        try {
                                latch.await();
                                break;
                        } catch (InterruptedException e) {
                                interrupted = true;
                        }
                }
                if (interrupted) {
                        Thread.currentThread().interrupt();
                }
        }

        private static void authenticate(HandlerSession session, String username,
                        KeyPair keyPair) throws Exception {
                byte[] nonce = Base64.getDecoder().decode(
                                assertChallenge(session.client.receive()));
                session.client.send(createAuth(username, signNonce(nonce, keyPair),
                                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())));
                TestSupport.assertEquals(Opcode.AUTH_OK, session.client.receive().getOp());
        }

        private static void assertPingPong(HandlerSession session, String id) throws Exception {
                Envelope ping = Envelope.create(Opcode.PING, "alice", "server");
                ping.setId(id);
                session.client.send(ping);
                Envelope pong = session.client.receive();
                TestSupport.assertEquals(Opcode.PONG, pong.getOp());
                TestSupport.assertEquals(id, pong.getId());
        }

        private static String assertPresence(Envelope presence, String recipient,
                        String expectedUser) {
                return assertPresence(presence, recipient, expectedUser, true);
        }

        private static String assertPresence(Envelope presence, String recipient,
                        String expectedUser, boolean expectedOnline) {
                TestSupport.assertEquals(Opcode.PRESENCE, presence.getOp());
                TestSupport.assertEquals("server", presence.getFrom());
                TestSupport.assertEquals(recipient, presence.getTo());
                String user = presence.getString("user");
                TestSupport.assertTrue(user != null, "Presence phai co username");
                TestSupport.assertTrue(!recipient.equals(user),
                                "Snapshot khong duoc co self-presence");
                TestSupport.assertEquals(expectedOnline, presence.get("online"));
                if (expectedUser != null) {
                        TestSupport.assertEquals(expectedUser, user);
                }
                return user;
        }

        private static void assertKeyChanged(Envelope keyChanged, String owner,
                        String recipient, String expectedPublicKey) {
                TestSupport.assertEquals(Opcode.KEY_CHANGED, keyChanged.getOp());
                TestSupport.assertEquals(owner, keyChanged.getFrom());
                TestSupport.assertEquals(recipient, keyChanged.getTo());
                TestSupport.assertEquals(expectedPublicKey,
                                keyChanged.getString("new_pubkey"));
        }

        private static void assertKeyLookupResult(Envelope result, String owner,
                        String recipient, String expectedPublicKey) {
                TestSupport.assertEquals(Opcode.KEY_LOOKUP_RESULT, result.getOp());
                TestSupport.assertEquals(owner, result.getFrom());
                TestSupport.assertEquals(recipient, result.getTo());
                TestSupport.assertEquals(1, result.getBody().size());
                TestSupport.assertEquals(expectedPublicKey,
                                result.getString("pubkey"));
        }

        private static void sendUnchecked(SocketPacketChannel channel, Envelope envelope,
                        AtomicReference<Throwable> sendFailure) {
                try {
                        channel.send(envelope);
                } catch (IOException e) {
                        sendFailure.compareAndSet(null, e);
                }
        }

        private static void waitUntil(BooleanSupplier condition, String message) throws Exception {
                long deadline = System.currentTimeMillis() + 5_000;
                while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(10);
                }
                TestSupport.assertTrue(condition.getAsBoolean(), message);
        }

        private static String assertChallenge(Envelope challenge) {
                TestSupport.assertEquals(Opcode.AUTH_CHALLENGE, challenge.getOp());
                TestSupport.assertEquals("server", challenge.getFrom());
                String nonceBase64 = challenge.getString("nonce");
                TestSupport.assertTrue(nonceBase64 != null, "Challenge phai co nonce");
                byte[] nonce = Base64.getDecoder().decode(nonceBase64);
                TestSupport.assertEquals(32, nonce.length);
                return nonceBase64;
        }

        private static KeyPair generateRsaKeyPair() throws Exception {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                return generator.generateKeyPair();
        }

        private static String signNonce(byte[] nonce, KeyPair keyPair) throws Exception {
                Signature signer = Signature.getInstance("SHA256withRSA");
                signer.initSign(keyPair.getPrivate());
                signer.update(nonce);
                return Base64.getEncoder().encodeToString(signer.sign());
        }

        private static Envelope createAuth(String username, String signatureBase64,
                        String publicKeyBase64) {
                return Envelope.create(Opcode.AUTH, username, "server")
                                .put("sig", signatureBase64)
                                .put("pubkey", publicKeyBase64);
        }

        private static void assertError(Envelope error, String expectedCode) {
                TestSupport.assertEquals(Opcode.ERROR, error.getOp());
                TestSupport.assertEquals("server", error.getFrom());
                TestSupport.assertEquals(expectedCode, error.getString("code"));
        }

        private static final class HandlerSession implements AutoCloseable {
                private final ServerSocket serverSocket;
                private final Thread handlerThread;
                private final AtomicReference<Throwable> handlerFailure;
                private final AtomicReference<ClientHandler> handlerReference;
                private final SocketPacketChannel client;

                private HandlerSession(ServerSocket serverSocket, Thread handlerThread,
                                AtomicReference<Throwable> handlerFailure,
                                AtomicReference<ClientHandler> handlerReference,
                                SocketPacketChannel client) {
                        this.serverSocket = serverSocket;
                        this.handlerThread = handlerThread;
                        this.handlerFailure = handlerFailure;
                        this.handlerReference = handlerReference;
                        this.client = client;
                }

                private static HandlerSession open() throws Exception {
                        return open(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
                }

                private static HandlerSession open(
                                ConcurrentMap<String, String> publicKeyRegistry) throws Exception {
                        return open(publicKeyRegistry, new ConcurrentHashMap<>());
                }

                private static HandlerSession open(
                                ConcurrentMap<String, String> publicKeyRegistry,
                                ConcurrentMap<String, ClientHandler> onlineUsers) throws Exception {
                        return open(publicKeyRegistry, onlineUsers,
                                        dispatcherFor(onlineUsers), () -> {
                        }, () -> {
                        }, () -> {
                        }, () -> {
                        });
                }

                private static HandlerSession open(
                                ConcurrentMap<String, String> publicKeyRegistry,
                                ConcurrentMap<String, ClientHandler> onlineUsers,
                                Runnable afterOwnershipCheck,
                                Runnable beforeRequestDispatch) throws Exception {
                        return open(publicKeyRegistry, onlineUsers,
                                        dispatcherFor(onlineUsers),
                                        afterOwnershipCheck, beforeRequestDispatch, () -> {
                                        }, () -> {
                                        });
                }

                private static HandlerSession open(
                                ConcurrentMap<String, String> publicKeyRegistry,
                                ConcurrentMap<String, ClientHandler> onlineUsers,
                                PresenceDispatcher dispatcher) throws Exception {
                        return open(publicKeyRegistry, onlineUsers, dispatcher, () -> {
                        }, () -> {
                        }, () -> {
                        }, () -> {
                                        });
                }

                private static HandlerSession open(
                                ConcurrentMap<String, String> publicKeyRegistry,
                                ConcurrentMap<String, ClientHandler> onlineUsers,
                                Runnable beforeKeyChangedBroadcast) throws Exception {
                        return open(publicKeyRegistry, onlineUsers,
                                        dispatcherFor(onlineUsers), () -> {
                                        }, () -> {
                                        }, () -> {
                                        }, () -> {
                                        }, beforeKeyChangedBroadcast);
                }

                private static HandlerSession open(
                                ConcurrentMap<String, String> publicKeyRegistry,
                                ConcurrentMap<String, ClientHandler> onlineUsers,
                                PresenceDispatcher dispatcher,
                                Runnable afterOwnershipCheck,
                                Runnable beforeRequestDispatch,
                                Runnable beforePresenceSnapshot,
                                Runnable beforePresenceWrite) throws Exception {
                        return open(publicKeyRegistry, onlineUsers, dispatcher,
                                        afterOwnershipCheck, beforeRequestDispatch,
                                        beforePresenceSnapshot, beforePresenceWrite, () -> {
                                        });
                }

                private static HandlerSession open(
                                ConcurrentMap<String, String> publicKeyRegistry,
                                ConcurrentMap<String, ClientHandler> onlineUsers,
                                PresenceDispatcher presenceDispatcher,
                                Runnable afterOwnershipCheck,
                                Runnable beforeRequestDispatch,
                                Runnable beforePresenceSnapshot,
                                Runnable beforePresenceWrite,
                                Runnable beforeKeyChangedBroadcast) throws Exception {
                        ServerSocket serverSocket = new ServerSocket(
                                        0, 1, InetAddress.getByName(HOST));
                        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
                        AtomicReference<ClientHandler> handlerReference = new AtomicReference<>();
                        Thread handlerThread = new Thread(() -> {
                                try {
                                        ClientHandler handler = new ClientHandler(serverSocket.accept(), () -> {
                                        }, publicKeyRegistry, onlineUsers,
                                                        presenceDispatcher, afterOwnershipCheck,
                                                        beforeRequestDispatch, beforePresenceSnapshot,
                                                        beforePresenceWrite,
                                                        beforeKeyChangedBroadcast);
                                        handlerReference.set(handler);
                                        handler.run();
                                } catch (Throwable t) {
                                        handlerFailure.set(t);
                                }
                        }, "issue-8-acceptance-handler");
                        handlerThread.start();

                        Socket socket = new Socket();
                        try {
                                socket.connect(new InetSocketAddress(
                                                HOST, serverSocket.getLocalPort()), CONNECT_TIMEOUT_MS);
                                socket.setSoTimeout(RESPONSE_TIMEOUT_MS);
                                SocketPacketChannel client = new SocketPacketChannel(socket);
                                return new HandlerSession(
                                                serverSocket, handlerThread, handlerFailure,
                                                handlerReference, client);
                        } catch (Exception e) {
                                socket.close();
                                serverSocket.close();
                                throw e;
                        }
                }

                private static PresenceDispatcher dispatcherFor(
                                ConcurrentMap<String, ClientHandler> onlineUsers) {
                        synchronized (PRESENCE_DISPATCHERS) {
                                return PRESENCE_DISPATCHERS.computeIfAbsent(
                                                onlineUsers, PresenceDispatcher::new);
                        }
                }

                private ClientHandler handler() {
                        ClientHandler handler = handlerReference.get();
                        TestSupport.assertTrue(handler != null,
                                        "ClientHandler phai duoc khoi tao");
                        return handler;
                }

                @Override
                public void close() throws Exception {
                        client.close();
                        serverSocket.close();
                        handlerThread.join(5_000);
                        TestSupport.assertTrue(!handlerThread.isAlive(),
                                        "ClientHandler phai cleanup sau khi client dong");
                        Throwable failure = handlerFailure.get();
                        if (failure != null) {
                                throw new AssertionError("ClientHandler gap loi ngoai du kien", failure);
                        }
                }
        }
}
