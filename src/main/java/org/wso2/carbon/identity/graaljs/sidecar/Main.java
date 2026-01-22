/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.graaljs.sidecar;

import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main entry point for the GraalJS sidecar server.
 * Listens on a Unix Domain Socket for requests from the Identity Server.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final String DEFAULT_SOCKET_PATH = "/tmp/graaljs-sidecar.sock";
    private static final int DEFAULT_STATEMENT_LIMIT = 5000;
    private static final int DEFAULT_THREAD_POOL_SIZE = 10;

    private AFUNIXServerSocket serverSocket;
    private ExecutorService executor;
    private AtomicBoolean running = new AtomicBoolean(false);
    private JsEngineServiceImpl engineService;

    public static void main(String[] args) throws Exception {
        String socketPath = args.length > 0 ? args[0] : DEFAULT_SOCKET_PATH;
        int statementLimit = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_STATEMENT_LIMIT;
        int threadPoolSize = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_THREAD_POOL_SIZE;

        Main main = new Main();
        main.start(socketPath, statementLimit, threadPoolSize);
    }

    public void start(String socketPath, int statementLimit, int threadPoolSize) throws IOException {
        // Delete existing socket file if present
        File socketFile = new File(socketPath);
        if (socketFile.exists()) {
            if (!socketFile.delete()) {
                log.warn("Could not delete existing socket file: {}", socketPath);
            }
        }

        engineService = new JsEngineServiceImpl(statementLimit);
        executor = Executors.newFixedThreadPool(threadPoolSize);

        AFUNIXSocketAddress address = AFUNIXSocketAddress.of(socketFile);
        serverSocket = AFUNIXServerSocket.newInstance();
        serverSocket.bind(address);
        running.set(true);

        log.info("GraalJS Sidecar started on UDS: {}", socketPath);
        log.info("Statement limit: {}, Thread pool size: {}", statementLimit, threadPoolSize);

        // Set up shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down GraalJS Sidecar...");
            stop();
        }));

        // Accept connections in main thread
        acceptConnections();
    }

    private void acceptConnections() {
        while (running.get()) {
            try {
                AFUNIXSocket clientSocket = serverSocket.accept();
                log.debug("Accepted connection from client");
                executor.submit(new ClientHandler(clientSocket, engineService));
            } catch (IOException e) {
                if (running.get()) {
                    log.error("Error accepting connection", e);
                }
            }
        }
    }

    public void stop() {
        running.set(false);

        if (executor != null) {
            executor.shutdownNow();
        }

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log.debug("Error closing server socket", e);
            }
        }

        log.info("GraalJS Sidecar stopped");
    }

    /**
     * Handles a single client connection.
     */
    private static class ClientHandler implements Runnable {
        private final AFUNIXSocket socket;
        private final JsEngineServiceImpl engineService;

        ClientHandler(AFUNIXSocket socket, JsEngineServiceImpl engineService) {
            this.socket = socket;
            this.engineService = engineService;
        }

        @Override
        public void run() {
            try (DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {

                // Keep handling messages until connection is closed
                while (!socket.isClosed()) {
                    try {
                        // Read message type (1 byte)
                        int messageType = input.readByte();

                        // Read message length (4 bytes)
                        int length = input.readInt();

                        // Read message body
                        byte[] messageBytes = new byte[length];
                        input.readFully(messageBytes);

                        // Process the message
                        byte[] response = processMessage(messageType, messageBytes);

                        // Write response
                        output.writeByte(messageType + 1); // Response type
                        output.writeInt(response.length);
                        output.write(response);
                        output.flush();

                    } catch (java.io.EOFException e) {
                        // Client closed connection
                        break;
                    }
                }
            } catch (IOException e) {
                log.debug("Client connection error", e);
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    log.debug("Error closing client socket", e);
                }
            }
        }

        private byte[] processMessage(int messageType, byte[] messageBytes) throws IOException {
            System.out.println("[DEBUG-MAIN] processMessage called with type: " + messageType + ", length: "
                    + messageBytes.length);
            System.out.flush();
            switch (messageType) {
                case 1: // EVALUATE_REQUEST
                    System.out.println("[DEBUG-MAIN] Routing to handleEvaluate");
                    System.out.flush();
                    return engineService.handleEvaluate(messageBytes);
                case 3: // EXECUTE_CALLBACK_REQUEST
                    System.out.println("[DEBUG-MAIN] Routing to handleExecuteCallback");
                    System.out.flush();
                    return engineService.handleExecuteCallback(messageBytes);
                default:
                    System.out.println("[DEBUG-MAIN] Unknown message type: " + messageType);
                    log.warn("Unknown message type: {}", messageType);
                    return new byte[0];
            }
        }
    }
}
