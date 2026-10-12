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
import java.util.concurrent.atomic.AtomicReference;

public final class ServerIssue8AcceptanceTest {
    private static final String HOST = "127.0.0.1";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int RESPONSE_TIMEOUT_MS = 30_000;

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
                    Base64.getEncoder().encodeToString(new byte[]{1}),
                    Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})));
            assertError(session.client.receive(), "AUTH_FAILED");
            TestSupport.assertThrows(IOException.class, session.client::receive);
        }
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
        private final SocketPacketChannel client;

        private HandlerSession(ServerSocket serverSocket, Thread handlerThread,
                AtomicReference<Throwable> handlerFailure, SocketPacketChannel client) {
            this.serverSocket = serverSocket;
            this.handlerThread = handlerThread;
            this.handlerFailure = handlerFailure;
            this.client = client;
        }

        private static HandlerSession open() throws Exception {
            ServerSocket serverSocket = new ServerSocket(
                    0, 1, InetAddress.getByName(HOST));
            AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
            Thread handlerThread = new Thread(() -> {
                try {
                    new ClientHandler(serverSocket.accept()).run();
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
                        serverSocket, handlerThread, handlerFailure, client);
            } catch (Exception e) {
                socket.close();
                serverSocket.close();
                throw e;
            }
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
