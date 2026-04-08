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

package org.wso2.carbon.identity.graaljs.External;

import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.*;
import org.wso2.carbon.identity.graaljs.External.transport.CallbackClient;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Convenience wrapper for callback clients that provides a simple API
 * for JsEngineServiceImpl to invoke host functions and access context
 * properties on the Identity Server.
 * <p>
 * Wraps a {@link CallbackClient} delegate (typically a
 * {@code StreamingCallbackClient} that sends callbacks over the
 * bidirectional gRPC stream) and adds protobuf serialization and
 * callback time tracking.
 */
public class HostCallbackClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(HostCallbackClient.class);

    private final String callbackAddress;
    private final String sessionId;
    private final CallbackClient delegate;

    // Tracks cumulative time (ms) spent waiting for IS callbacks during a single
    // request.
    // This allows the External to decompose total elapsed into pure-JS vs
    // callback-roundtrip time.
    private final AtomicLong totalCallbackTimeMs = new AtomicLong(0);

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
            requestBuilder.addArguments(ValueSerializationUtils.serialize(arguments[i]));
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

        Object result = ValueSerializationUtils.deserialize(response.getResult());
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
}
