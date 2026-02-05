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

import org.graalvm.polyglot.Value;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.*;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for sending host function callbacks back to the Identity Server.
 * When JavaScript in the sidecar calls a host function (executeStep, sendError,
 * etc.),
 * this client sends the call back to IS for execution.
 */
public class HostCallbackClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(HostCallbackClient.class);

    private static final int HOST_FUNCTION_REQUEST = 5;
    private static final int HOST_FUNCTION_RESPONSE = 6;
    private static final int CONTEXT_PROPERTY_REQUEST = 7;
    private static final int CONTEXT_PROPERTY_RESPONSE = 8;
    private static final int CONTEXT_PROPERTY_SET_REQUEST = 9;
    private static final int CONTEXT_PROPERTY_SET_RESPONSE = 10;

    private final String socketPath;
    private final String sessionId;
    private AFUNIXSocket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private boolean connected = false;

    public HostCallbackClient(String socketPath, String sessionId) {
        this.socketPath = socketPath;
        this.sessionId = sessionId;
    }

    /**
     * Connect to the IS callback server.
     */
    public void connect() throws IOException {
        if (connected) {
            log.info("[HostCallbackClient] Already connected to: {}", socketPath);
            return;
        }

        log.info("[HostCallbackClient] Connecting to IS callback server at: {}", socketPath);
        File socketFile = new File(socketPath);
        if (!socketFile.exists()) {
            log.error("[HostCallbackClient] Socket file does not exist: {}", socketPath);
            throw new IOException("Socket file does not exist: " + socketPath);
        }

        AFUNIXSocketAddress address = AFUNIXSocketAddress.of(socketFile);
        socket = AFUNIXSocket.newInstance();
        socket.connect(address);
        output = new DataOutputStream(socket.getOutputStream());
        input = new DataInputStream(socket.getInputStream());
        connected = true;

        log.info("[HostCallbackClient] Connected to IS callback server at: {}", socketPath);
    }

    /**
     * Check if connected.
     */
    public boolean isConnected() {
        return connected && socket != null && socket.isConnected();
    }

    /**
     * Invoke a host function on the IS side.
     *
     * @param functionName Name of the host function (e.g., "executeStep",
     *                     "sendError").
     * @param arguments    Arguments to pass.
     * @return Result from the host function.
     * @throws IOException If communication fails.
     */
    public Object invokeHostFunction(String functionName, Object... arguments) throws IOException {
        log.info("[HostCallbackClient] invokeHostFunction '{}' with {} args, session: {}",
                functionName, arguments.length, sessionId);
        ensureConnected();

        // Build request
        HostFunctionRequest.Builder requestBuilder = HostFunctionRequest.newBuilder()
                .setSessionId(sessionId)
                .setFunctionName(functionName);

        // Serialize arguments
        for (int i = 0; i < arguments.length; i++) {
            log.info("[HostCallbackClient] Serializing arg[{}]: {}", i,
                    arguments[i] != null ? arguments[i].getClass().getSimpleName() : "null");
            requestBuilder.addArguments(serializeValue(arguments[i]));
        }

        HostFunctionRequest request = requestBuilder.build();
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] HostFunctionRequest: {}", request);
        }

        // Send request
        byte[] requestBytes = request.toByteArray();
        log.info("[HostCallbackClient] Sending request: {} bytes", requestBytes.length);
        output.writeByte(HOST_FUNCTION_REQUEST);
        output.writeInt(requestBytes.length);
        output.write(requestBytes);
        output.flush();
        log.info("[HostCallbackClient] Request sent, waiting for response...");

        // Read response
        int responseType = input.readByte();
        log.info("[HostCallbackClient] Received response type: {}", responseType);
        if (responseType != HOST_FUNCTION_RESPONSE) {
            throw new IOException("Unexpected response type: " + responseType);
        }

        int length = input.readInt();
        log.info("[HostCallbackClient] Reading {} bytes of response", length);
        byte[] responseBytes = new byte[length];
        input.readFully(responseBytes);

        HostFunctionResponse response = HostFunctionResponse.parseFrom(responseBytes);
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] HostFunctionResponse: {}", response);
        }
        log.info("[HostCallbackClient] Response parsed - success: {}", response.getSuccess());

        if (!response.getSuccess()) {
            log.error("[HostCallbackClient] Host function failed: {}", response.getErrorMessage());
            throw new IOException("Host function failed: " + response.getErrorMessage());
        }

        Object result = deserializeValue(response.getResult());
        log.info("[HostCallbackClient] Returning result: {}",
                result != null ? result.getClass().getSimpleName() : "null");
        return result;
    }

    /**
     * Get a context property value from IS.
     * This enables the dynamic context proxy to call back for any property access.
     *
     * @param propertyPath Path to the property (e.g., "request", "request.params").
     * @param proxyType    Type of the proxy object.
     * @return ContextPropertyResponse containing the value.
     * @throws IOException If communication fails.
     */
    public ContextPropertyResponse getContextProperty(String propertyPath, String proxyType) throws IOException {
        log.info("[HostCallbackClient] getContextProperty '{}' (type: {}), session: {}",
                propertyPath, proxyType, sessionId);
        ensureConnected();

        // Build request
        ContextPropertyRequest request = ContextPropertyRequest.newBuilder()
                .setSessionId(sessionId)
                .setPropertyPath(propertyPath)
                .setProxyType(proxyType)
                .build();
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] ContextPropertyRequest: {}", request);
        }

        // Send request
        byte[] requestBytes = request.toByteArray();
        log.debug("[HostCallbackClient] Sending context property request: {} bytes", requestBytes.length);
        output.writeByte(CONTEXT_PROPERTY_REQUEST);
        output.writeInt(requestBytes.length);
        output.write(requestBytes);
        output.flush();

        // Read response
        int responseType = input.readByte();
        if (responseType != CONTEXT_PROPERTY_RESPONSE) {
            throw new IOException(
                    "Unexpected response type: " + responseType + ", expected: " + CONTEXT_PROPERTY_RESPONSE);
        }

        int length = input.readInt();
        byte[] responseBytes = new byte[length];
        input.readFully(responseBytes);

        ContextPropertyResponse response = ContextPropertyResponse.parseFrom(responseBytes);
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] ContextPropertyResponse: {}", response);
        }
        log.info("[HostCallbackClient] Context property '{}' fetched - success: {}, isProxy: {}",
                propertyPath, response.getSuccess(), response.getIsProxy());

        return response;
    }

    /**
     * Set a context property value on IS (write-back).
     * This enables scripts to modify context properties like user.localClaims.
     *
     * @param propertyPath Path to the property (e.g.,
     *                     "currentKnownSubject.localClaims.email").
     * @param proxyType    Type of the proxy object.
     * @param value        The value to set.
     * @return ContextPropertySetResponse containing success status.
     * @throws IOException If communication fails.
     */
    public ContextPropertySetResponse setContextProperty(String propertyPath, String proxyType,
            SerializedValue value) throws IOException {
        log.info("[HostCallbackClient] setContextProperty '{}' (type: {}), session: {}",
                propertyPath, proxyType, sessionId);
        ensureConnected();

        // Build request
        ContextPropertySetRequest request = ContextPropertySetRequest.newBuilder()
                .setSessionId(sessionId)
                .setPropertyPath(propertyPath)
                .setValue(value)
                .build();
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] ContextPropertySetRequest: {}", request);
        }

        // Send request
        byte[] requestBytes = request.toByteArray();
        log.info("[HostCallbackClient] Sending context property SET request: {} bytes", requestBytes.length);
        output.writeByte(CONTEXT_PROPERTY_SET_REQUEST);
        output.writeInt(requestBytes.length);
        output.write(requestBytes);
        output.flush();

        // Read response
        int responseType = input.readByte();
        if (responseType != CONTEXT_PROPERTY_SET_RESPONSE) {
            throw new IOException(
                    "Unexpected response type: " + responseType + ", expected: " + CONTEXT_PROPERTY_SET_RESPONSE);
        }

        int length = input.readInt();
        byte[] responseBytes = new byte[length];
        input.readFully(responseBytes);

        ContextPropertySetResponse response = ContextPropertySetResponse.parseFrom(responseBytes);
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] ContextPropertySetResponse: {}", response);
        }
        log.info("[HostCallbackClient] Context property SET response - success: {}",
                response.getSuccess());

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
    }

    private void ensureConnected() throws IOException {
        if (!isConnected()) {
            connect();
        }
    }

    // ============ Serialization Helpers ============

    private SerializedValue serializeValue(Object val) {
        if (val == null) {
            return SerializedValue.newBuilder()
                    .setNullValue(com.google.protobuf.ByteString.EMPTY)
                    .build();
        }

        // Handle GraalVM Value objects (JavaScript values).
        if (val instanceof Value) {
            return serializeGraalValue((Value) val);
        }

        if (val instanceof String) {
            return SerializedValue.newBuilder().setStringValue((String) val).build();
        }
        if (val instanceof Integer) {
            return SerializedValue.newBuilder().setIntValue(((Integer) val).longValue()).build();
        }
        if (val instanceof Long) {
            return SerializedValue.newBuilder().setIntValue((Long) val).build();
        }
        if (val instanceof Double) {
            return SerializedValue.newBuilder().setDoubleValue((Double) val).build();
        }
        if (val instanceof Float) {
            return SerializedValue.newBuilder().setDoubleValue(((Float) val).doubleValue()).build();
        }
        if (val instanceof Boolean) {
            return SerializedValue.newBuilder().setBoolValue((Boolean) val).build();
        }
        // Handle arrays.
        if (val instanceof Object[]) {
            SerializedArray.Builder arrayBuilder = SerializedArray.newBuilder();
            for (Object element : (Object[]) val) {
                arrayBuilder.addElements(serializeValue(element));
            }
            return SerializedValue.newBuilder().setArrayValue(arrayBuilder.build()).build();
        }
        // Handle lists.
        if (val instanceof List) {
            SerializedArray.Builder arrayBuilder = SerializedArray.newBuilder();
            for (Object element : (List<?>) val) {
                arrayBuilder.addElements(serializeValue(element));
            }
            return SerializedValue.newBuilder().setArrayValue(arrayBuilder.build()).build();
        }
        // Handle maps.
        if (val instanceof Map) {
            SerializedMap.Builder mapBuilder = SerializedMap.newBuilder();
            @SuppressWarnings("unchecked")
            Map<String, Object> mapVal = (Map<String, Object>) val;
            for (Map.Entry<String, Object> entry : mapVal.entrySet()) {
                mapBuilder.putEntries(entry.getKey(), serializeValue(entry.getValue()));
            }
            return SerializedValue.newBuilder().setMapValue(mapBuilder.build()).build();
        }
        // Default to string representation for unknown types.
        log.warn("[HostCallbackClient] Unknown type for serialization: {}, using toString()",
                val.getClass().getName());
        return SerializedValue.newBuilder().setStringValue(val.toString()).build();
    }

    /**
     * Serializes a GraalVM Value object to protobuf.
     * Handles JavaScript functions by extracting their source code.
     */
    private SerializedValue serializeGraalValue(Value val) {
        if (val.isNull()) {
            return SerializedValue.newBuilder()
                    .setNullValue(com.google.protobuf.ByteString.EMPTY)
                    .build();
        }
        if (val.isString()) {
            return SerializedValue.newBuilder().setStringValue(val.asString()).build();
        }
        if (val.isNumber()) {
            if (val.fitsInInt()) {
                return SerializedValue.newBuilder().setIntValue(val.asInt()).build();
            } else if (val.fitsInLong()) {
                return SerializedValue.newBuilder().setIntValue(val.asLong()).build();
            } else {
                return SerializedValue.newBuilder().setDoubleValue(val.asDouble()).build();
            }
        }
        if (val.isBoolean()) {
            return SerializedValue.newBuilder().setBoolValue(val.asBoolean()).build();
        }
        // Handle JavaScript functions - extract source code.
        if (val.canExecute()) {
            String source = null;
            // First try getSourceLocation() - works for top-level named functions.
            try {
                if (val.getSourceLocation() != null &&
                        val.getSourceLocation().getCharacters() != null) {
                    source = val.getSourceLocation().getCharacters().toString();
                    log.info("[HostCallbackClient] Extracted function source via getSourceLocation: {}...",
                            source.substring(0, Math.min(60, source.length())));
                }
            } catch (Exception e) {
                log.debug("[HostCallbackClient] Could not get source location for function: {}", e.getMessage());
            }
            // If getSourceLocation() failed, use toString() which calls
            // Function.prototype.toString().
            // For GraalJS, this returns the full function definition including body.
            if (source == null || source.isEmpty()) {
                try {
                    source = val.toString();
                    log.info("[HostCallbackClient] Using toString() for function, got: {}...",
                            source.substring(0, Math.min(60, source.length())));
                } catch (Exception e) {
                    log.warn("[HostCallbackClient] Could not get function toString(): {}", e.getMessage());
                }
            }
            if (source != null && !source.isEmpty() &&
                    (source.contains("function") || source.contains("=>"))) {
                log.info("[HostCallbackClient] Serializing function with source: {}...",
                        source.substring(0, Math.min(60, source.length())));
                // Return function source as a string - the IS side expects this.
                return SerializedValue.newBuilder().setStringValue(source).build();
            } else {
                log.error("[HostCallbackClient] Could not extract valid function source. Got: {}", source);
                return SerializedValue.newBuilder().setStringValue(source != null ? source : "function(){}").build();
            }
        }
        // Handle JavaScript arrays.
        if (val.hasArrayElements()) {
            SerializedArray.Builder arrayBuilder = SerializedArray.newBuilder();
            long size = val.getArraySize();
            for (long i = 0; i < size; i++) {
                arrayBuilder.addElements(serializeGraalValue(val.getArrayElement(i)));
            }
            return SerializedValue.newBuilder().setArrayValue(arrayBuilder.build()).build();
        }
        // Handle JavaScript objects (maps).
        if (val.hasMembers()) {
            SerializedMap.Builder mapBuilder = SerializedMap.newBuilder();
            for (String key : val.getMemberKeys()) {
                Value memberVal = val.getMember(key);
                log.debug("[HostCallbackClient] Serializing member '{}': {}", key,
                        memberVal != null ? (memberVal.canExecute() ? "function" : memberVal.getClass().getSimpleName())
                                : "null");
                mapBuilder.putEntries(key, serializeGraalValue(memberVal));
            }
            return SerializedValue.newBuilder().setMapValue(mapBuilder.build()).build();
        }
        // Default - try to convert to string.
        log.warn("[HostCallbackClient] Unknown GraalVM Value type, using toString()");
        return SerializedValue.newBuilder().setStringValue(val.toString()).build();
    }

    private Object deserializeValue(SerializedValue sv) {
        if (sv == null) {
            return null;
        }
        switch (sv.getValueCase()) {
            case STRING_VALUE:
                return sv.getStringValue();
            case INT_VALUE:
                return sv.getIntValue();
            case DOUBLE_VALUE:
                return sv.getDoubleValue();
            case BOOL_VALUE:
                return sv.getBoolValue();
            case NULL_VALUE:
                return null;
            case ARRAY_VALUE:
                List<Object> list = new ArrayList<>();
                for (SerializedValue element : sv.getArrayValue().getElementsList()) {
                    list.add(deserializeValue(element));
                }
                return list;
            case MAP_VALUE:
                Map<String, Object> map = new HashMap<>();
                for (Map.Entry<String, SerializedValue> entry : sv.getMapValue().getEntriesMap().entrySet()) {
                    map.put(entry.getKey(), deserializeValue(entry.getValue()));
                }
                return map;
            default:
                return null;
        }
    }
}
