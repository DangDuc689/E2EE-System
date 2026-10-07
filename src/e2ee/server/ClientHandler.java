package e2ee.server;

import e2ee.common.contract.Envelope;
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

    ClientHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
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
            }
        } catch (SocketTimeoutException e) {
            System.out.println("[E2EE-SERVER] Client khong hoat dong trong 90 giay: "
                    + clientAddress);
        } catch (EOFException e) {
            System.out.println("[E2EE-SERVER] Client da dong ket noi (EOF): "
                    + clientAddress);
        } catch (IOException e) {
            System.err.println("[E2EE-SERVER] Loi ket noi client: "
                    + clientAddress + " - " + e.getMessage());
        } finally {
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
