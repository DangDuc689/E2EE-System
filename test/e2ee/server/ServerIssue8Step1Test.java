package e2ee.server;

import e2ee.common.TestSupport;
import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.net.SocketPacketChannel;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class ServerIssue8Step1Test {
    private static final String HOST = "127.0.0.1";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int RESPONSE_TIMEOUT_MS = 30_000;

    private ServerIssue8Step1Test() {
    }

    public static void main(String[] args) {
        TestSupport.check("Username hop le", ServerIssue8Step1Test::testValidUsernames);
        TestSupport.check("Username khong hop le", ServerIssue8Step1Test::testInvalidUsernames);
        TestSupport.check("AUTH_CHALLENGE rieng cho tung connection",
                ServerIssue8Step1Test::testAuthChallenge);
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
        List<Thread> handlerThreads = new ArrayList<>();

        try (PrintStream capture = new PrintStream(capturedOutput, true, StandardCharsets.UTF_8);
                ServerSocket serverSocket = new ServerSocket(
                        0, 2, InetAddress.getByName(HOST))) {
            System.setOut(capture);
            System.setErr(capture);

            Thread acceptThread = new Thread(() -> acceptClients(serverSocket, handlerThreads),
                    "issue-8-step-1-accept");
            acceptThread.start();

            try (SocketPacketChannel firstClient = connectClient(serverSocket.getLocalPort())) {
                Envelope firstChallenge = firstClient.receive();
                firstNonce = assertChallenge(firstChallenge);
            }

            try (SocketPacketChannel secondClient = connectClient(serverSocket.getLocalPort())) {
                Envelope secondChallenge = secondClient.receive();
                secondNonce = assertChallenge(secondChallenge);
            }

            TestSupport.assertTrue(!firstNonce.equals(secondNonce),
                    "Hai connection phai nhan nonce khac nhau");

            acceptThread.join(5_000);
            TestSupport.assertTrue(!acceptThread.isAlive(),
                    "Accept thread phai ket thuc sau hai connection");
            for (Thread handlerThread : handlerThreads) {
                handlerThread.join(5_000);
                TestSupport.assertTrue(!handlerThread.isAlive(),
                        "ClientHandler phai cleanup sau khi client dong");
            }
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

    private static void acceptClients(ServerSocket serverSocket, List<Thread> handlerThreads) {
        try {
            for (int index = 0; index < 2; index++) {
                Socket acceptedSocket = serverSocket.accept();
                Thread handlerThread = new Thread(new ClientHandler(acceptedSocket),
                        "issue-8-step-1-handler-" + index);
                handlerThreads.add(handlerThread);
                handlerThread.start();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static SocketPacketChannel connectClient(int port) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(
                    HOST, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(RESPONSE_TIMEOUT_MS);
            return new SocketPacketChannel(socket);
        } catch (Exception e) {
            socket.close();
            throw e;
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
}
