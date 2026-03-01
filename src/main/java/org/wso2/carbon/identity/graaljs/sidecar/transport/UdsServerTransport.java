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

package org.wso2.carbon.identity.graaljs.sidecar.transport;

import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.EvaluateRequest;
import org.wso2.carbon.identity.graaljs.proto.EvaluateResponse;
import org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest;
import org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse;
import org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest;
import org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse;
import org.wso2.carbon.identity.graaljs.sidecar.JsEngineServiceImpl;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unix Domain Socket implementation of ServerTransport.
 * Wraps the existing UDS server logic from Main.java into the transport abstraction.
 */
public class UdsServerTransport implements ServerTransport {

    private static final Logger log = LoggerFactory.getLogger(UdsServerTransport.class);

    // Message type constants
    private static final int EVALUATE_REQUEST = 1;
    private static final int EVALUATE_RESPONSE = 2;
    private static final int EXECUTE_CALLBACK_REQUEST = 3;
    private static final int EXECUTE_CALLBACK_RESPONSE = 4;
    private static final int HOST_FUNCTION_REQUEST = 5;
    private static final int HOST_FUNCTION_RESPONSE = 6;
    private static final int CONTEXT_PROPERTY_REQUEST = 7;
    private static final int CONTEXT_PROPERTY_RESPONSE = 8;
    private static final int CONTEXT_PROPERTY_SET_REQUEST = 9;
    private static final int CONTEXT_PROPERTY_SET_RESPONSE = 10;

    private final String socketPath;
    private final JsEngineServiceImpl engineService;
    private final int threadPoolSize;

    private AFUNIXServerSocket serverSocket;
    private ExecutorService executor;
    private Thread acceptThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Create a new UDS server transport.
     *
     * @param socketPath      Path to the Unix Domain Socket.
     * @param engineService   JavaScript engine service implementation.
     * @param threadPoolSize  Number of threads for handling connections.
     */
    public UdsServerTransport(String socketPath, JsEngineServiceImpl engineService, int threadPoolSize) {
        this.socketPath = socketPath;
        this.engineService = engineService;
        this.threadPoolSize = threadPoolSize;
    }

    @Override
    public void start() throws IOException {
        if (running.get()) {
            log.warn("[UDS-Server] Already running");
            return;
        }

        // Delete existing socket file if present
        File socketFile = new File(socketPath);
        if (socketFile.exists()) {
            if (!socketFile.delete()) {
                log.warn("[UDS-Server] Failed to delete existing socket file: " + socketPath);
            }
        }

        // Create server socket
        AFUNIXSocketAddress address = AFUNIXSocketAddress.of(socketFile);
        serverSocket = AFUNIXServerSocket.newInstance();
        serverSocket.bind(address);

        // Create thread pool
        executor = Executors.newFixedThreadPool(threadPoolSize);

        // Start accepting connections
        running.set(true);
        acceptThread = new Thread(this::acceptConnections, "UDS-Server-Accept");
        acceptThread.setDaemon(false);
        acceptThread.start();

        if (log.isDebugEnabled()) {
            log.debug("[UDS-Server] Started on socket: " + socketPath);
        }
    }

    @Override
    public void stop() throws IOException {
        if (!running.get()) {
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("[UDS-Server] Stopping server...");
        }
        running.set(false);

        // Interrupt accept thread
        if (acceptThread != null) {
            acceptThread.interrupt();
        }

        // Shutdown executor
        if (executor != null) {
            executor.shutdownNow();
        }

        // Close server socket
        if (serverSocket != null) {
            serverSocket.close();
        }

        // Clean up socket file
        new File(socketPath).delete();

        if (log.isDebugEnabled()) {
            log.debug("[UDS-Server] Stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public String getAddress() {
        return socketPath;
    }

    /**
     * Accept incoming client connections.
     */
    private void acceptConnections() {
        if (log.isDebugEnabled()) {
            log.debug("[UDS-Server] Accepting connections...");
        }
        while (running.get()) {
            try {
                AFUNIXSocket clientSocket = serverSocket.accept();
                log.debug("[UDS-Server] Accepted client connection");
                executor.submit(new ClientHandler(clientSocket));
            } catch (IOException e) {
                if (running.get()) {
                    log.error("[UDS-Server] Error accepting connection", e);
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[UDS-Server] Stopped accepting connections");
        }
    }

    /**
     * Handles a single client connection.
     */
    private class ClientHandler implements Runnable {

        private final AFUNIXSocket socket;

        ClientHandler(AFUNIXSocket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (DataInputStream input = new DataInputStream(socket.getInputStream());
                 DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {

                while (!socket.isClosed() && running.get()) {
                    try {
                        // Read message type
                        int messageType = input.readByte();

                        // Read message length
                        int length = input.readInt();

                        // Read message bytes
                        byte[] messageBytes = new byte[length];
                        input.readFully(messageBytes);

                        if (log.isDebugEnabled()) {
                            log.debug("[UDS-Server] Received message type: " + messageType + ", length: " + length);
                        }

                        // Always log message details for testing
                        logDecodedMessage(messageType, messageBytes, "[UDS-Server] Request");

                        // Process message
                        byte[] responseBytes;
                        int responseType;

                        if (messageType == EVALUATE_REQUEST) {
                            responseBytes = engineService.handleEvaluate(messageBytes);
                            responseType = EVALUATE_RESPONSE;

                        } else if (messageType == EXECUTE_CALLBACK_REQUEST) {
                            responseBytes = engineService.handleExecuteCallback(messageBytes);
                            responseType = EXECUTE_CALLBACK_RESPONSE;

                        } else if (messageType == HOST_FUNCTION_REQUEST) {
                            responseBytes = engineService.handleHostFunction(messageBytes);
                            responseType = HOST_FUNCTION_RESPONSE;
                            
                        } else if (messageType == CONTEXT_PROPERTY_REQUEST) {
                            responseBytes = engineService.handleContextProperty(messageBytes);
                            responseType = CONTEXT_PROPERTY_RESPONSE;
                            
                        } else if (messageType == CONTEXT_PROPERTY_SET_REQUEST) {
                            responseBytes = engineService.handleContextPropertySet(messageBytes);
                            responseType = CONTEXT_PROPERTY_SET_RESPONSE;
                            
                        } else {
                            log.warn("[UDS-Server] Unknown message type: " + messageType);
                            continue;
                        }

                        // Send response
                        output.writeByte(responseType);
                        output.writeInt(responseBytes.length);
                        output.write(responseBytes);
                        output.flush();

                        // Always log response details for testing  
                        logDecodedMessage(responseType, responseBytes, "[UDS-Server] Response");
                        if (log.isDebugEnabled()) {
                            log.debug("[UDS-Server] Sent response type: " + responseType);
                        }

                    } catch (java.io.EOFException e) {
                        log.debug("[UDS-Server] Client disconnected");
                        break;
                    } catch (Exception e) {
                        log.error("[UDS-Server] Error processing request", e);
                        break;
                    }
                }

            } catch (IOException e) {
                log.error("[UDS-Server] Client handler error", e);
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    log.debug("[UDS-Server] Error closing client socket", e);
                }
            }
        }

        private void logDecodedMessage(int messageType, byte[] messageBytes, String prefix) {
            try {
                switch (messageType) {
                    case EVALUATE_REQUEST:
                        EvaluateRequest evaluateRequest = EvaluateRequest.parseFrom(messageBytes);
                        if (log.isDebugEnabled()) {
                            log.debug("{} EVALUATE_REQUEST: {}", prefix, evaluateRequest);
                        }
                        break;
                    case EXECUTE_CALLBACK_REQUEST:
                        ExecuteCallbackRequest executeCallbackRequest = ExecuteCallbackRequest.parseFrom(messageBytes);
                        if (log.isDebugEnabled()) {
                            log.debug("{} EXECUTE_CALLBACK_REQUEST: {}", prefix, executeCallbackRequest);
                        }
                        break;
                    case EVALUATE_RESPONSE:
                        EvaluateResponse evaluateResponse = EvaluateResponse.parseFrom(messageBytes);
                        if (log.isDebugEnabled()) {
                            log.debug("{} EVALUATE_RESPONSE: {}", prefix, evaluateResponse);
                        }
                        break;
                    case EXECUTE_CALLBACK_RESPONSE:
                        ExecuteCallbackResponse executeCallbackResponse = ExecuteCallbackResponse.parseFrom(messageBytes);
                        if (log.isDebugEnabled()) {
                            log.debug("{} EXECUTE_CALLBACK_RESPONSE: {}", prefix, executeCallbackResponse);
                        }
                        break;
                    case HOST_FUNCTION_REQUEST:
                        HostFunctionRequest hostFunctionRequest = HostFunctionRequest.parseFrom(messageBytes);
                        if (log.isDebugEnabled()) {
                            log.debug("{} HOST_FUNCTION_REQUEST: {}", prefix, hostFunctionRequest);
                        }
                        break;
                    case HOST_FUNCTION_RESPONSE:
                        HostFunctionResponse hostFunctionResponse = HostFunctionResponse.parseFrom(messageBytes);
                        if (log.isDebugEnabled()) {
                            log.debug("{} HOST_FUNCTION_RESPONSE: {}", prefix, hostFunctionResponse);
                        }
                        break;
                    case CONTEXT_PROPERTY_REQUEST:
                        ContextPropertyRequest contextPropertyRequest = ContextPropertyRequest.parseFrom(messageBytes);
                        if (log.isDebugEnabled()) {
                            log.debug("{} CONTEXT_PROPERTY_REQUEST: {}", prefix, contextPropertyRequest);
                        }
                        break;
                    case CONTEXT_PROPERTY_RESPONSE:
                        ContextPropertyResponse contextPropertyResponse = ContextPropertyResponse.parseFrom(messageBytes);
                        if (log.isDebugEnabled()) {
                            log.debug("{} CONTEXT_PROPERTY_RESPONSE: {}", prefix, contextPropertyResponse);
                        }
                        break;
                    case CONTEXT_PROPERTY_SET_REQUEST:
                        ContextPropertySetRequest contextPropertySetRequest = ContextPropertySetRequest.parseFrom(messageBytes);
                        if (log.isDebugEnabled()) {
                            log.debug("{} CONTEXT_PROPERTY_SET_REQUEST: {}", prefix, contextPropertySetRequest);
                        }
                        break;
                    case CONTEXT_PROPERTY_SET_RESPONSE:
                        ContextPropertySetResponse contextPropertySetResponse = ContextPropertySetResponse.parseFrom(messageBytes);
                        if (log.isDebugEnabled()) {
                            log.debug("{} CONTEXT_PROPERTY_SET_RESPONSE: {}", prefix, contextPropertySetResponse);
                        }
                        break;
                    default:
                        if (log.isDebugEnabled()) {
                            log.debug("{} Unknown message type: {}", prefix, messageType);
                        }
                        break;
                }
            } catch (Exception e) {
                log.debug("{} Failed to decode message type: {}", prefix, messageType, e);
            }
        }
    }
}
