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

import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse;
import org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest;
import org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Pure Unix Domain Socket implementation of CallbackClient.
 * Mirrors the IS framework pattern where UdsCallbackServerImpl wraps HostCallbackServer.
 * <p>
 * This implementation contains the actual UDS socket communication code,
 * making it a clean, transport-specific implementation.
 */
public class UdsCallbackClient implements CallbackClient {

    private static final Logger log = LoggerFactory.getLogger(UdsCallbackClient.class);

    // Message type constants matching IS HostCallbackServer
    private static final int HOST_FUNCTION_REQUEST = 5;
    private static final int HOST_FUNCTION_RESPONSE = 6;
    private static final int CONTEXT_PROPERTY_REQUEST = 7;
    private static final int CONTEXT_PROPERTY_RESPONSE = 8;
    private static final int CONTEXT_PROPERTY_SET_REQUEST = 9;
    private static final int CONTEXT_PROPERTY_SET_RESPONSE = 10;

    private final String sessionId;
    private final String socketPath;
    private AFUNIXSocket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private boolean connected = false;

    /**
     * Create a new UDS callback client.
     *
     * @param sessionId        Session identifier.
     * @param socketPath      UDS socket path for callbacks.
     */
    public UdsCallbackClient(String sessionId, String socketPath) {
        this.sessionId = sessionId;
        this.socketPath = socketPath;
        log.debug("[UdsCallbackClient] Created for session: {}, socket: {}", sessionId, socketPath);
    }

    @Override
    public void connect() throws IOException {
        if (connected) {
            log.debug("[UdsCallbackClient] Already connected to: {}", socketPath);
            return;
        }

        File socketFile = new File(socketPath);
        if (!socketFile.exists()) {
            log.error("[UdsCallbackClient] Socket file does not exist: {}", socketPath);
            throw new IOException("Socket file does not exist: " + socketPath);
        }

        AFUNIXSocketAddress address = AFUNIXSocketAddress.of(socketFile);
        socket = AFUNIXSocket.newInstance();
        socket.connect(address);
        output = new DataOutputStream(socket.getOutputStream());
        input = new DataInputStream(socket.getInputStream());
        connected = true;
        if (log.isDebugEnabled()) {
            log.debug("[UdsCallbackClient] Connected to UDS socket: {}", socketPath);
        }
    }

    @Override
    public boolean isConnected() {
        return connected && socket != null && socket.isConnected();
    }

    @Override
    public HostFunctionResponse invokeHostFunction(HostFunctionRequest request) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("[UdsCallbackClient] invokeHostFunction: {}, session: {}",
                    request.getFunctionName(), request.getSessionId());
        }
        ensureConnected();

        // Send request
        byte[] requestBytes = request.toByteArray();
        log.debug("[UdsCallbackClient] Sending request: {} bytes", requestBytes.length);
        output.writeByte(HOST_FUNCTION_REQUEST);
        output.writeInt(requestBytes.length);
        output.write(requestBytes);
        output.flush();

        // Read response
        int responseType = input.readByte();
        if (responseType != HOST_FUNCTION_RESPONSE) {
            throw new IOException("Unexpected response type: " + responseType +
                    ", expected: " + HOST_FUNCTION_RESPONSE);
        }

        int length = input.readInt();
        byte[] responseBytes = new byte[length];
        input.readFully(responseBytes);

        HostFunctionResponse response = HostFunctionResponse.parseFrom(responseBytes);
        if (log.isDebugEnabled()) {
            log.debug("[UdsCallbackClient] Response received - success: {}", response.getSuccess());
        }
        return response;
    }

    @Override
    public ContextPropertyResponse getContextProperty(ContextPropertyRequest request) throws IOException {
        log.debug("[UdsCallbackClient] getContextProperty: {}, session: {}",
                request.getPropertyPath(), request.getSessionId());
        ensureConnected();

        // Send request
        byte[] requestBytes = request.toByteArray();
        output.writeByte(CONTEXT_PROPERTY_REQUEST);
        output.writeInt(requestBytes.length);
        output.write(requestBytes);
        output.flush();

        // Read response
        int responseType = input.readByte();
        if (responseType != CONTEXT_PROPERTY_RESPONSE) {
            throw new IOException("Unexpected response type: " + responseType +
                    ", expected: " + CONTEXT_PROPERTY_RESPONSE);
        }

        int length = input.readInt();
        byte[] responseBytes = new byte[length];
        input.readFully(responseBytes);

        ContextPropertyResponse response = ContextPropertyResponse.parseFrom(responseBytes);
        log.debug("[UdsCallbackClient] ContextProperty response - success: {}, isProxy: {}",
                response.getSuccess(), response.getIsProxy());
        return response;
    }

    @Override
    public ContextPropertySetResponse setContextProperty(ContextPropertySetRequest request) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("[UdsCallbackClient] setContextProperty: {}, session: {}",
                    request.getPropertyPath(), request.getSessionId());
        }
        ensureConnected();

        // Send request
        byte[] requestBytes = request.toByteArray();
        output.writeByte(CONTEXT_PROPERTY_SET_REQUEST);
        output.writeInt(requestBytes.length);
        output.write(requestBytes);
        output.flush();

        // Read response
        int responseType = input.readByte();
        if (responseType != CONTEXT_PROPERTY_SET_RESPONSE) {
            throw new IOException("Unexpected response type: " + responseType +
                    ", expected: " + CONTEXT_PROPERTY_SET_RESPONSE);
        }

        int length = input.readInt();
        byte[] responseBytes = new byte[length];
        input.readFully(responseBytes);

        ContextPropertySetResponse response = ContextPropertySetResponse.parseFrom(responseBytes);
        if (log.isDebugEnabled()) {
            log.debug("[UdsCallbackClient] SetProperty response - success: {}", response.getSuccess());
        }
        return response;
    }

    @Override
    public void close() throws IOException {
        connected = false;
        if (output != null) {
            try {
                output.close();
            } catch (IOException ignored) {
            }
        }
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        log.debug("[UdsCallbackClient] Closed connection to: {}", socketPath);
    }

    private void ensureConnected() throws IOException {
        if (!isConnected()) {
            connect();
        }
    }
}
