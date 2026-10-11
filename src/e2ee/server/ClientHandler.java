package e2ee.server;

import e2ee.common.contract.Envelope;
import e2ee.common.contract.Opcode;
import e2ee.common.json.MiniJson;
import e2ee.common.net.SocketPacketChannel;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

final class ClientHandler implements Runnable {
    private static final int CLIENT_TIMEOUT_MS = 90_000;
    private static final int AUTH_NONCE_BYTES = 32;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9_]{3,20}$");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Socket clientSocket;
    private final Runnable onClose;
    private byte[] challengeNonce;

    ClientHandler(Socket clientSocket) {
        this(clientSocket, () -> {
        });
    }

    ClientHandler(Socket clientSocket, Runnable onClose) {
        this.clientSocket = clientSocket;
        this.onClose = onClose;
    }

    @Override
    public void run() {
        /*
         * ==============================================
         * CONNECTION LIFECYCLE
         * CLIENT TIMEOUT - 90s
         * ==============================================
         */
        String clientAddress = String.valueOf(clientSocket.getRemoteSocketAddress());

        try (Socket socket = clientSocket;
                SocketPacketChannel channel = new SocketPacketChannel(socket)) {
            sendAuthChallenge(channel);
            socket.setSoTimeout(CLIENT_TIMEOUT_MS);
            System.out.println("[E2EE-SERVER] Client da ket noi: "
                    + clientAddress
                    + " | Thread: " + Thread.currentThread().getName());

            /*
             * ==============================================
             * ENVELOPE RECEIVE LOOP
             * SOCKET PACKET CHANNEL
             * ==============================================
             */
            while (true) {
                Envelope envelope = channel.receive();
                logEnvelopeMetadata(envelope);

                /*
                 * ===================================================================
                 * ____ ___ _ _ ____ __ ____ ___ _ _ ____
                 * | _ \|_ _| \ | |/ ___| / / | _ \ / _ \| \ | |/ ___|
                 * | |_) || || \| | | _ / / | |_) | | | | \| | | _
                 * | __/ | || |\ | |_| | / / | __/| |_| | |\ | |_| |
                 * |_| |___|_| \_|\____| /_/ |_| \___/|_| \_|\____|
                 * ===================================================================
                 */
                if (envelope.getOp() == Opcode.PING) {
                    Envelope pong = Envelope.create(Opcode.PONG, "server", envelope.getFrom());
                    pong.setId(envelope.getId());
                    channel.send(pong);
                }
            }
        } catch (SocketTimeoutException e) {
            System.out.println("[E2EE-SERVER] Client khong hoat dong trong 90 giay: "
                    + clientAddress);
        } catch (EOFException e) {
            System.out.println("[E2EE-SERVER] Client da dong ket noi (EOF): "
                    + clientAddress);
        } catch (IOException e) {
            if (!clientSocket.isClosed()) {
                System.err.println("[E2EE-SERVER] Loi ket noi client: "
                        + clientAddress + " - " + e.getMessage());
            }
        } finally {
            onClose.run();
            System.out.println("[E2EE-SERVER] Da cleanup client: " + clientAddress);
        }
    }

    static boolean isValidUsername(String username) {
        return username != null
                && !"server".equals(username)
                && !username.contains("|")
                && USERNAME_PATTERN.matcher(username).matches();
    }

    private void sendAuthChallenge(SocketPacketChannel channel) throws IOException {
        challengeNonce = new byte[AUTH_NONCE_BYTES];
        SECURE_RANDOM.nextBytes(challengeNonce);

        Envelope challenge = Envelope.create(Opcode.AUTH_CHALLENGE, "server", null)
                .put("nonce", Base64.getEncoder().encodeToString(challengeNonce));
        channel.send(challenge);
    }

    private static void logEnvelopeMetadata(Envelope envelope) {
        int bodyBytes = MiniJson.stringify(envelope.getBody())
                .getBytes(StandardCharsets.UTF_8).length;
        System.out.println("[E2EE-SERVER] Envelope: op=" + envelope.getOp()
                + ", from=" + envelope.getFrom()
                + ", to=" + envelope.getTo()
                + ", id=" + envelope.getId()
                + ", ts=" + envelope.getTs()
                + ", bodyBytes=" + bodyBytes);
    }
}
