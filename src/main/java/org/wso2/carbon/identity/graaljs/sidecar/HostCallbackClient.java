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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.*;
import org.wso2.carbon.identity.graaljs.sidecar.transport.CallbackClient;
import org.wso2.carbon.identity.graaljs.sidecar.transport.CallbackClientFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Convenience wrapper for callback clients that provides a simple API.
 * Mirrors the IS framework pattern where UdsCallbackServerImpl wraps
 * HostCallbackServer.
 * <p>
 * This class uses CallbackClientFactory internally to create the appropriate
 * transport
 * implementation (UDS or gRPC), then adapts the CallbackClient interface to
 * provide
 * a simpler API for JsEngineServiceImpl.
 * <p>
 * Transport Selection:
 * - Uses CallbackClientFactory to auto-detect transport type from address
 * format
 * - UDS: /path/to/socket or file:///path/to/socket
 * - gRPC: localhost:port or grpc://localhost:port
 * <p>
 * This mirrors the IS pattern where UdsCallbackServerImpl adapts CallbackServer
 * interface to the HostCallbackServer singleton implementation.
 */
public class HostCallbackClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(HostCallbackClient.class);

    private final String callbackAddress;
    private final String sessionId;
    private final CallbackClient delegate;

    // Tracks cumulative time (ms) spent waiting for IS callbacks during a single
    // request.
    // This allows the sidecar to decompose total elapsed into pure-JS vs
    // callback-roundtrip time.
    private final AtomicLong totalCallbackTimeMs = new AtomicLong(0);

    /**
     * Create a new callback client using factory pattern.
     *
     * @param callbackAddress Address where IS callback server is listening (UDS
     *                        path or gRPC address).
     * @param sessionId       Session identifier.
     * @throws IOException if address format is invalid or transport is not
     *                     supported.
     */
    public HostCallbackClient(String callbackAddress, String sessionId) throws IOException {
        this.callbackAddress = callbackAddress;
        this.sessionId = sessionId;

        // Use factory to create appropriate callback client based on address format
        this.delegate = CallbackClientFactory.createClient(callbackAddress, sessionId);

        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] Created callback client for address: {}, session: {}",
                    callbackAddress, sessionId);
        }
    }

    /**
     * Create a new callback client using an externally provided delegate.
     * Used by streaming transport where the CallbackClient is a
     * StreamingCallbackClient
     * that sends callbacks over the bidirectional stream.
     *
     * @param delegate  The pre-created callback client (e.g.,
     *                  StreamingCallbackClient).
     * @param sessionId Session identifier.
     */
    public HostCallbackClient(CallbackClient delegate, String sessionId) {
        this.delegate = delegate;
        this.sessionId = sessionId;
        this.callbackAddress = "streaming";

        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] Created with external delegate for session: {}", sessionId);
        }
    }

    /**
     * Get the session ID for this callback client.
     *
     * @return Session identifier.
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Connect to the IS callback server.
     */
    public void connect() throws IOException {
        delegate.connect();
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] Connected to: {}", callbackAddress);
        }
    }

    /**
     * Check if connected.
     */
    public boolean isConnected() {
        return delegate.isConnected();
    }

    /**
     * Invoke a host function on the IS side.
     * Convenience method that handles proto serialization.
     *
     * @param functionName Name of the host function (e.g., "executeStep",
     *                     "sendError").
     * @param arguments    Arguments to pass.
     * @return Result from the host function.
     * @throws IOException If communication fails.
     */
    public Object invokeHostFunction(String functionName, Object... arguments) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] invokeHostFunction '{}' with {} args, session: {}",
                    functionName, arguments.length, sessionId);
        }
        ensureConnected();

        // Build request
        HostFunctionRequest.Builder requestBuilder = HostFunctionRequest.newBuilder()
                .setSessionId(sessionId)
                .setFunctionName(functionName);

        // Serialize arguments
        for (int i = 0; i < arguments.length; i++) {
            log.debug("[HostCallbackClient] Serializing arg[{}]: {}", i,
                    arguments[i] != null ? arguments[i].getClass().getSimpleName() : "null");
            requestBuilder.addArguments(serializeValue(arguments[i]));
        }

        HostFunctionRequest request = requestBuilder.build();

        // Delegate to transport implementation — track round-trip time
        long cbStart = System.currentTimeMillis();
        HostFunctionResponse response = delegate.invokeHostFunction(request);
        long cbElapsed = System.currentTimeMillis() - cbStart;
        totalCallbackTimeMs.addAndGet(cbElapsed);
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] invokeHostFunction '{}' round-trip: {}ms", functionName, cbElapsed);
        }

        if (!response.getSuccess()) {
            log.error("[HostCallbackClient] Host function failed: {}", response.getErrorMessage());
            throw new IOException("Host function failed: " + response.getErrorMessage());
        }

        Object result = deserializeValue(response.getResult());
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] Returning result: {}",
                    result != null ? result.getClass().getSimpleName() : "null");
        }
        return result;
    }

    /**
     * Get a context property value from IS.
     * Convenience method for dynamic context proxy.
     *
     * @param propertyPath Path to the property (e.g., "request", "request.params").
     * @param proxyType    Type of the proxy object.
     * @return ContextPropertyResponse containing the value.
     * @throws IOException If communication fails.
     */
    public ContextPropertyResponse getContextProperty(String propertyPath, String proxyType) throws IOException {
        log.debug("[HostCallbackClient] getContextProperty '{}' (type: {}), session: {}",
                propertyPath, proxyType, sessionId);
        ensureConnected();

        // Build request
        ContextPropertyRequest request = ContextPropertyRequest.newBuilder()
                .setSessionId(sessionId)
                .setPropertyPath(propertyPath)
                .setProxyType(proxyType)
                .build();

        // Delegate to transport implementation — track round-trip time
        long cbStart = System.currentTimeMillis();
        ContextPropertyResponse response = delegate.getContextProperty(request);
        long cbElapsed = System.currentTimeMillis() - cbStart;
        totalCallbackTimeMs.addAndGet(cbElapsed);
        return response;
    }

    /**
     * Set a context property value on IS (write-back).
     * Convenience method for script property modifications.
     *
     * @param propertyPath Path to the property.
     * @param proxyType    Type of the proxy object.
     * @param value        The value to set.
     * @return ContextPropertySetResponse containing success status.
     * @throws IOException If communication fails.
     */
    public ContextPropertySetResponse setContextProperty(String propertyPath, String proxyType,
            SerializedValue value) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] setContextProperty '{}' (type: {}), session: {}",
                    propertyPath, proxyType, sessionId);
        }
        ensureConnected();

        // Build request
        ContextPropertySetRequest request = ContextPropertySetRequest.newBuilder()
                .setSessionId(sessionId)
                .setPropertyPath(propertyPath)
                .setValue(value)
                .build();

        // Delegate to transport implementation — track round-trip time
        long cbStart = System.currentTimeMillis();
        ContextPropertySetResponse response = delegate.setContextProperty(request);
        long cbElapsed = System.currentTimeMillis() - cbStart;
        totalCallbackTimeMs.addAndGet(cbElapsed);
        return response;
    }

    /**
     * Get the cumulative time spent waiting for IS callbacks during this request.
     *
     * @return Total callback round-trip time in milliseconds.
     */
    public long getCallbackTimeMs() {
        return totalCallbackTimeMs.get();
    }

    /**
     * Reset the callback time tracker. Call at the start of each request
     * when the callback client is reused (e.g., streaming transport).
     */
    public void resetCallbackTimeMs() {
        totalCallbackTimeMs.set(0);
    }

    @Override
    public void close() throws IOException {
        if (delegate != null) {
            delegate.close();
        }
        log.debug("[HostCallbackClient] Closed");
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
                    log.debug("[HostCallbackClient] Extracted function source via getSourceLocation");
                }
            } catch (Exception e) {
                log.debug("[HostCallbackClient] Could not get source location for function: {}", e.getMessage());
            }
            // If getSourceLocation() failed, use toString().
            if (source == null || source.isEmpty()) {
                try {
                    source = val.toString();
                    log.debug("[HostCallbackClient] Using toString() for function");
                } catch (Exception e) {
                    log.warn("[HostCallbackClient] Could not get function toString(): {}", e.getMessage());
                }
            }
            if (source != null && !source.isEmpty() &&
                    (source.contains("function") || source.contains("=>"))) {
                // Return function source as a string - the IS side expects this.
                return SerializedValue.newBuilder().setStringValue(source).build();
            } else {
                log.error("[HostCallbackClient] Could not extract valid function source");
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
            case PROXY_OBJECT:
                SerializedProxyObject proxy = sv.getProxyObject();
                Map<String, Object> proxyMarker = new HashMap<>();
                proxyMarker.put(SidecarConstants.IS_HOST_REF, true);
                proxyMarker.put(SidecarConstants.PROXY_TYPE_FIELD, proxy.getType());
                proxyMarker.put(SidecarConstants.REFERENCE_ID_FIELD, proxy.getReferenceId());
                if (log.isDebugEnabled()) {
                    log.debug("[HostCallbackClient] Deserialized proxy object: type={}, refId={}",
                            proxy.getType(), proxy.getReferenceId());
                }
                return proxyMarker;
            default:
                return null;
        }
    }
}
