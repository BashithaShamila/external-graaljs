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

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.SerializedArray;
import org.wso2.carbon.identity.graaljs.proto.SerializedFunction;
import org.wso2.carbon.identity.graaljs.proto.SerializedMap;
import org.wso2.carbon.identity.graaljs.proto.SerializedValue;

import java.util.Map;

/**
 * Serializes GraalVM {@link Value} objects to protobuf and deserializes protobuf back to
 * GraalVM-compatible objects (using {@code context.eval()} to create native JS values).
 * <p>
 * This differs from {@link ValueSerializationUtils} which handles Java ↔ protobuf conversions.
 * This class handles GraalVM Value ↔ protobuf conversions with:
 * <ul>
 *   <li>{@link DynamicContextProxy} marker detection (must NOT iterate proxy members)</li>
 *   <li>ThreadLocal callback client for proxy object deserialization</li>
 *   <li>Native JS value creation via {@code context.eval()} for numbers, arrays, objects</li>
 *   <li>Function source extraction and recompilation</li>
 * </ul>
 * <p>
 * Thread safety: The callback client ThreadLocal must be set before deserialization and
 * cleared after, using {@link #setCallbackClient}/{@link #clearCallbackClient}.
 * This is required because {@link #deserializeValue} is called recursively during
 * binding restore, and the callback client is needed deep in the recursion at the
 * PROXY_OBJECT case to create {@link DynamicContextProxy} instances.
 */
class EngineValueSerializer {

    private static final Logger log = LoggerFactory.getLogger(EngineValueSerializer.class);
    private static final String JS_LANG = "js";

    // ThreadLocal to store the current callback client for proxy object deserialization.
    // Set by the engine service before binding restore, cleared after.
    private static final ThreadLocal<HostCallbackClient> currentCallbackClient = new ThreadLocal<>();

    /**
     * Set the callback client for the current thread.
     * Must be called before {@link #deserializeValue} when proxy objects may be encountered.
     *
     * @param client The callback client for this session.
     */
    void setCallbackClient(HostCallbackClient client) {
        currentCallbackClient.set(client);
    }

    /**
     * Clear the callback client for the current thread.
     * Must be called in a finally block after deserialization completes.
     */
    void clearCallbackClient() {
        currentCallbackClient.remove();
    }

    /**
     * Serialize a GraalVM Value to protobuf SerializedValue.
     * <p>
     * CRITICAL ordering:
     * <ul>
     *   <li>DynamicContextProxy check ({@code isProxyObject()}) BEFORE {@code hasMembers()}
     *       — iterating proxy members triggers gRPC callbacks to IS for every property</li>
     *   <li>{@code canExecute()} BEFORE {@code hasMembers()}
     *       — JS functions also have members (name, length)</li>
     * </ul>
     *
     * @param val The GraalVM Value to serialize.
     * @return The serialized protobuf value.
     */
    SerializedValue serializeValue(Value val) {
        if (val == null || val.isNull()) {
            return SerializedValue.newBuilder()
                    .setNullValue(com.google.protobuf.ByteString.EMPTY)
                    .build();
        }
        if (val.isString()) {
            return SerializedValue.newBuilder().setStringValue(val.asString()).build();
        }
        if (val.isNumber()) {
            if (val.fitsInLong()) {
                return SerializedValue.newBuilder().setIntValue(val.asLong()).build();
            }
            return SerializedValue.newBuilder().setDoubleValue(val.asDouble()).build();
        }
        if (val.isBoolean()) {
            return SerializedValue.newBuilder().setBoolValue(val.asBoolean()).build();
        }
        if (val.hasArrayElements()) {
            SerializedArray.Builder arr = SerializedArray.newBuilder();
            for (long i = 0; i < val.getArraySize(); i++) {
                arr.addElements(serializeValue(val.getArrayElement(i)));
            }
            return SerializedValue.newBuilder().setArrayValue(arr).build();
        }
        if (val.canExecute()) {
            String source = val.getSourceLocation() != null
                    ? val.getSourceLocation().getCharacters().toString()
                    : val.toString();
            return SerializedValue.newBuilder()
                    .setFunctionValue(SerializedFunction.newBuilder().setSource(source))
                    .build();
        }
        // DynamicContextProxy is a lazy proxy backed by IS-side data.
        // Do NOT iterate its members (each triggers a gRPC callback to IS).
        // Send a marker so IS can reconstruct the reference from stored context.
        if (val.isProxyObject()) {
            Object proxyObj = val.asProxyObject();
            if (proxyObj instanceof DynamicContextProxy) {
                DynamicContextProxy proxy = (DynamicContextProxy) proxyObj;
                SerializedMap.Builder marker = SerializedMap.newBuilder();
                marker.putEntries(ExternalConstants.IS_CONTEXT_PROXY,
                        SerializedValue.newBuilder().setBoolValue(true).build());
                marker.putEntries(ExternalConstants.PROXY_TYPE_FIELD,
                        SerializedValue.newBuilder().setStringValue(proxy.getProxyType()).build());
                marker.putEntries(ExternalConstants.BASE_PATH_FIELD,
                        SerializedValue.newBuilder().setStringValue(proxy.getBasePath()).build());
                return SerializedValue.newBuilder().setMapValue(marker).build();
            }
        }
        if (val.hasMembers()) {
            SerializedMap.Builder map = SerializedMap.newBuilder();
            for (String key : val.getMemberKeys()) {
                map.putEntries(key, serializeValue(val.getMember(key)));
            }
            return SerializedValue.newBuilder().setMapValue(map).build();
        }
        return SerializedValue.newBuilder().setStringValue(val.toString()).build();
    }

    /**
     * Deserialize a protobuf SerializedValue to a GraalVM-compatible object.
     * Creates native JS values using {@code context.eval()} for numbers, arrays, and objects.
     * <p>
     * CRITICAL: The PROXY_OBJECT case creates a {@link DynamicContextProxy} using the
     * ThreadLocal callback client. This enables lazy-loading for arrays of complex objects
     * (e.g., getUsersWithClaimValues returning 100 User objects).
     *
     * @param sv      The protobuf SerializedValue to deserialize.
     * @param context The GraalJS context for creating native JS values.
     * @return The deserialized object, or null if the value is null or unrecognized.
     */
    Object deserializeValue(SerializedValue sv, Context context) {
        switch (sv.getValueCase()) {
            case STRING_VALUE:
                return sv.getStringValue();
            case INT_VALUE:
                return context.eval(JS_LANG, String.valueOf(sv.getIntValue()));
            case DOUBLE_VALUE:
                return context.eval(JS_LANG, String.valueOf(sv.getDoubleValue()));
            case BOOL_VALUE:
                return context.eval(JS_LANG, String.valueOf(sv.getBoolValue()));
            case NULL_VALUE:
                return null;
            case ARRAY_VALUE:
                // Create a proper JavaScript array instead of Java array.
                Value jsArray = context.eval(JS_LANG, "[]");
                int arraySize = sv.getArrayValue().getElementsCount();
                for (int i = 0; i < arraySize; i++) {
                    Object element = deserializeValue(sv.getArrayValue().getElements(i), context);
                    jsArray.setArrayElement(i, element);
                }
                return jsArray;
            case MAP_VALUE:
                // Create a proper JavaScript object instead of Java map.
                Value jsObject = context.eval(JS_LANG, "({})");
                for (Map.Entry<String, SerializedValue> e : sv.getMapValue().getEntriesMap().entrySet()) {
                    Object val = deserializeValue(e.getValue(), context);
                    jsObject.putMember(e.getKey(), val);
                }
                return jsObject;
            case FUNCTION_VALUE:
                return context.eval(JS_LANG, "(" + sv.getFunctionValue().getSource() + ")");
            case PROXY_OBJECT:
                // Handle proxy object markers - create a DynamicContextProxy that lazily fetches properties.
                // This is CRITICAL for arrays of complex objects (e.g., getUsersWithClaimValues returning
                // 100 User objects). Instead of eagerly serializing all properties (causing timeouts),
                // we create a proxy that fetches properties on-demand when accessed.
                org.wso2.carbon.identity.graaljs.proto.SerializedProxyObject proxyObj = sv.getProxyObject();
                String proxyType = proxyObj.getType();
                String referenceId = proxyObj.getReferenceId();

                if (log.isDebugEnabled()) {
                    log.debug("[External] Creating proxy for POJO: type={}, refId={}", proxyType, referenceId);
                }

                // Use __proxyref__ prefix to distinguish from context proxies (__hostref__ pattern)
                String basePath = ExternalConstants.PROXY_REF_PREFIX + referenceId;

                // Get callback client from ThreadLocal (set by calling methods)
                HostCallbackClient callbackClient = currentCallbackClient.get();
                if (callbackClient == null) {
                    log.warn("[External] No callback client available for proxy object, returning null");
                    return null;
                }

                return new DynamicContextProxy(
                        callbackClient.getSessionId(),
                        callbackClient,
                        proxyType, // "pojo" or specific type
                        basePath
                );
            default:
                return null;
        }
    }
}
