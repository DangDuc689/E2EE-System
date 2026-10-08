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

final class ClientHandler implements Runnable {
    private static final int CLIENT_TIMEOUT_MS = 90_000;

    private final Socket clientSocket;
    private final Runnable onClose;

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
