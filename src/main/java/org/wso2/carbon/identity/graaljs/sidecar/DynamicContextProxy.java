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
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse;
import org.wso2.carbon.identity.graaljs.proto.SerializedValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Dynamic proxy that calls back to IS for every property access.
 * This ensures the sidecar context behaves identically to the local context.
 */
class DynamicContextProxy implements ProxyObject {

    private static final Logger log = LoggerFactory.getLogger(DynamicContextProxy.class);

    private final String sessionId;
    private final HostCallbackClient callbackClient;
    private final String proxyType; // "context", "request", "steps", etc.
    private final String basePath; // For nested: "request", "steps.1", etc.

    // Cache for properties within this request
    private final Map<String, Object> cache = new java.util.concurrent.ConcurrentHashMap<>();
    // Store member keys once retrieved
    private String[] memberKeys = null;

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
        this.memberKeys = memberKeys;
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
        // Check cache first
        if (cache.containsKey(key)) {
            log.debug("[DynamicContextProxy] Cache hit for key: {}", key);
            return cache.get(key);
        }

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
                value = deserializeValue(response.getValue());
                if (log.isDebugEnabled()) {
                    log.debug("[DynamicContextProxy] Deserialized '{}' = {}", key,
                            value != null ? value.getClass().getSimpleName() : "null");
                }
            }

            // Cache the value (only if non-null, ConcurrentHashMap doesn't allow null
            // values)
            if (value != null) {
                cache.put(key, value);
            }
            return value;

        } catch (java.io.IOException e) {
            log.error("[DynamicContextProxy] Error getting property '{}': {}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public Object getMemberKeys() {
        if (log.isDebugEnabled()) {
            log.debug("[DynamicContextProxy] getMemberKeys() called for path: {}", basePath);
        }

        if (memberKeys != null) {
            return memberKeys;
        }

        try {
            // Get member keys from IS - use special path "__keys__"
            String propertyPath = basePath.isEmpty() ? SidecarConstants.KEYS_PROPERTY : basePath + SidecarConstants.PATH_SEPARATOR + SidecarConstants.KEYS_PROPERTY;
            ContextPropertyResponse response = callbackClient.getContextProperty(propertyPath, proxyType);

            if (response.getSuccess() && response.getMemberKeysCount() > 0) {
                memberKeys = response.getMemberKeysList().toArray(new String[0]);
                if (log.isDebugEnabled()) {
                    log.debug("[DynamicContextProxy] Retrieved {} member keys: {}", memberKeys.length,
                            java.util.Arrays.toString(memberKeys));
                }
                return memberKeys;
            }
        } catch (java.io.IOException e) {
            log.error("[DynamicContextProxy] Error getting member keys: {}", e.getMessage());
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
            SerializedValue serializedValue = serializeGraalValue(value);

            // Send write request to IS
            ContextPropertySetResponse response = callbackClient.setContextProperty(
                    propertyPath, proxyType, serializedValue);

            if (response.getSuccess()) {
                // ONLY update cache after confirmed success (cache consistency)
                cache.put(key, convertGraalValue(value));
                log.debug("[DynamicContextProxy] Successfully set '{}' and updated cache", key);
            } else {
                log.error("[DynamicContextProxy] Failed to set '{}': {}",
                        key, response.getErrorMessage());
            }
        } catch (java.io.IOException e) {
            log.error("[DynamicContextProxy] Error setting property '{}': {}", key, e.getMessage());
        }
    }

    /**
     * Convert a GraalVM Value to a Java object for caching.
     */
    private Object convertGraalValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        } else if (value.isString()) {
            return value.asString();
        } else if (value.isNumber()) {
            if (value.fitsInLong()) {
                return value.asLong();
            }
            return value.asDouble();
        } else if (value.isBoolean()) {
            return value.asBoolean();
        }
        return value.toString();
    }

    /**
     * Serialize a GraalVM Value to protobuf SerializedValue.
     */
    private SerializedValue serializeGraalValue(Value val) {
        if (val == null || val.isNull()) {
            return SerializedValue.newBuilder()
                    .setNullValue(com.google.protobuf.ByteString.EMPTY)
                    .build();
        } else if (val.isString()) {
            return SerializedValue.newBuilder()
                    .setStringValue(val.asString())
                    .build();
        } else if (val.isNumber()) {
            if (val.fitsInLong()) {
                return SerializedValue.newBuilder()
                        .setIntValue(val.asLong())
                        .build();
            }
            return SerializedValue.newBuilder()
                    .setDoubleValue(val.asDouble())
                    .build();
        } else if (val.isBoolean()) {
            return SerializedValue.newBuilder()
                    .setBoolValue(val.asBoolean())
                    .build();
        }
        // Default to string representation
        return SerializedValue.newBuilder()
                .setStringValue(val.toString())
                .build();
    }

    /**
     * Deserialize a SerializedValue from protobuf.
     */
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
                java.util.List<Object> list = new java.util.ArrayList<>();
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
