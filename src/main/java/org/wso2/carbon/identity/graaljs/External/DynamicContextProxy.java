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
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse;
import org.wso2.carbon.identity.graaljs.proto.SerializedValue;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Dynamic proxy that calls back to IS for every property access.
 * This ensures the External context behaves identically to the local context.
 */
class DynamicContextProxy implements ProxyObject {

    private static final Logger log = LoggerFactory.getLogger(DynamicContextProxy.class);

    private final String sessionId;
    private final HostCallbackClient callbackClient;
    private final String proxyType; // "context", "request", "steps", etc.
    private final String basePath; // For nested: "request", "steps.1", etc.

    // Cache disabled for data integrity — see getMember() for rationale.
    // TODO: re-enable with proper invalidation strategy.
    // private final Map<String, Object> cache = new ConcurrentHashMap<>();
    // private String[] memberKeys = null;

    public DynamicContextProxy(String sessionId, HostCallbackClient callbackClient,
            String proxyType, String basePath) {
        this(sessionId, callbackClient, proxyType, basePath, null);
    }

    public DynamicContextProxy(String sessionId, HostCallbackClient callbackClient,
            String proxyType, String basePath, String[] memberKeys) {
        this.sessionId = sessionId;
        this.callbackClient = callbackClient;
        this.proxyType = proxyType;
        this.basePath = basePath;
        // memberKeys parameter accepted for API compatibility but not cached.
        log.debug("[DynamicContextProxy] Created - type: {}, basePath: {}, keys: {}",
                proxyType, basePath, memberKeys != null ? memberKeys.length : "none");
    }

    /**
     * Get the proxy type (e.g., "context", "authenticateduser", "step").
     */
    public String getProxyType() {
        return proxyType;
    }

    /**
     * Get the base path for nested property access (e.g., "currentKnownSubject",
     * "steps.1").
     */
    public String getBasePath() {
        return basePath;
    }

    /**
     * Get the session ID this proxy belongs to.
     */
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public Object getMember(String key) {
        // Cache disabled: every read goes to IS for data integrity.
        // Host function side effects can mutate IS-side state without the sidecar
        // knowing, so cached values could become stale. This matches local mode
        // where JsClaims/JsAuthenticatedUser read live on every getMember() call.
        // TODO: re-enable with a proper invalidation strategy for better performance.

        try {
            // Build the full property path
            String propertyPath = basePath.isEmpty() ? key : basePath + "::" + key;
            if (log.isDebugEnabled()) {
                log.debug("[DynamicContextProxy] getMember '{}', full path: {}", key, propertyPath);
            }

            // Call back to IS for property value
            ContextPropertyResponse response = callbackClient.getContextProperty(propertyPath, proxyType);

            if (!response.getSuccess()) {
                log.debug("[DynamicContextProxy] Property '{}' not found: {}", key, response.getErrorMessage());
                return null;
            }

            Object value;
            if (response.getIsProxy()) {
                // Create nested proxy for complex objects, passing member keys if available
                String[] proxyMemberKeys = null;
                if (response.getMemberKeysCount() > 0) {
                    proxyMemberKeys = response.getMemberKeysList().toArray(new String[0]);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[DynamicContextProxy] Creating nested proxy for '{}', type: {}, keys: {}",
                            key, response.getProxyType(),
                            proxyMemberKeys != null ? proxyMemberKeys.length : "none");
                }
                value = new DynamicContextProxy(
                        sessionId, callbackClient,
                        response.getProxyType(), propertyPath, proxyMemberKeys);
            } else {
                // Deserialize the value
                value = ValueSerializationUtils.deserialize(response.getValue());
                if (log.isDebugEnabled()) {
                    log.debug("[DynamicContextProxy] Deserialized '{}' = {}", key,
                            value != null ? value.getClass().getSimpleName() : "null");
                }
            }

            return value;

        } catch (IOException e) {
            log.error("[DynamicContextProxy] Error getting property '{}': {}", key, e.getMessage());
            throw new UncheckedIOException(
                    "[DynamicContextProxy] Failed to get property '" + key + "' from IS", e);
        }
    }

    @Override
    public Object getMemberKeys() {
        if (log.isDebugEnabled()) {
            log.debug("[DynamicContextProxy] getMemberKeys() called for path: {}", basePath);
        }

        // memberKeys cache disabled: host functions may add/remove properties on IS side.
        // Always fetch fresh keys to match local mode behavior.
        // Constructor-provided memberKeys are also skipped to stay consistent.
        // TODO: re-enable with proper invalidation for better performance.

        try {
            // Get member keys from IS - use special path "__keys__"
            String propertyPath = basePath.isEmpty() ? ExternalConstants.KEYS_PROPERTY : basePath + ExternalConstants.PATH_SEPARATOR + ExternalConstants.KEYS_PROPERTY;
            ContextPropertyResponse response = callbackClient.getContextProperty(propertyPath, proxyType);

            if (response.getSuccess() && response.getMemberKeysCount() > 0) {
                String[] keys = response.getMemberKeysList().toArray(new String[0]);
                if (log.isDebugEnabled()) {
                    log.debug("[DynamicContextProxy] Retrieved {} member keys: {}", keys.length,
                            java.util.Arrays.toString(keys));
                }
                return keys;
            }
        } catch (IOException e) {
            log.error("[DynamicContextProxy] Error getting member keys: {}", e.getMessage());
            throw new UncheckedIOException(
                    "[DynamicContextProxy] Failed to get member keys for path '" + basePath + "' from IS", e);
        }

        // Return empty array if we can't get keys
        return new String[0];
    }

    @Override
    public boolean hasMember(String key) {
        // Try to get the member and check if it exists
        Object member = getMember(key);
        return member != null;
    }

    @Override
    public void putMember(String key, Value value) {
        if (callbackClient == null) {
            log.warn("[DynamicContextProxy] Cannot write '{}' - no callback client", key);
            return;
        }

        try {
            // Build the full property path
            String propertyPath = basePath.isEmpty() ? key : basePath + "::" + key;
            if (log.isDebugEnabled()) {
                log.debug("[DynamicContextProxy] putMember '{}' = {}", propertyPath,
                        value != null ? value.toString() : "null");
            }

            // Serialize the value
            SerializedValue serializedValue = ValueSerializationUtils.serializeGraalValue(value);

            // Send write request to IS
            ContextPropertySetResponse response = callbackClient.setContextProperty(
                    propertyPath, proxyType, serializedValue);

            if (response.getSuccess()) {
                // Cache disabled: see getMember() comment for rationale.
                log.debug("[DynamicContextProxy] Successfully set '{}'", key);
            } else {
                log.error("[DynamicContextProxy] Failed to set '{}': {}",
                        key, response.getErrorMessage());
            }
        } catch (IOException e) {
            log.error("[DynamicContextProxy] Error setting property '{}': {}", key, e.getMessage());
            throw new UncheckedIOException(
                    "[DynamicContextProxy] Failed to set property '" + key + "' on IS", e);
        }
    }
}
