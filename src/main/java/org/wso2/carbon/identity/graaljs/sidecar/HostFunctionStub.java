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
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stub for host functions that calls back to IS.
 */
class HostFunctionStub implements ProxyExecutable {

    private static final Logger log = LoggerFactory.getLogger(HostFunctionStub.class);

    private final String functionName;
    private final HostCallbackClient callbackClient;

    HostFunctionStub(String functionName, HostCallbackClient callbackClient) {
        this.functionName = functionName;
        this.callbackClient = callbackClient;
        if (log.isDebugEnabled()) {
            log.debug("[Sidecar-Stub] Created HostFunctionStub for: {}, callbackClient: {}",
                    functionName, callbackClient != null ? "available" : "null");
        }
    }

    @Override
    public Object execute(Value... args) {
        System.out.println(
                "[DEBUG-SIDECAR] Host function '" + functionName + "' called with " + args.length + " args");
        if (log.isDebugEnabled()) {
            log.debug("[Sidecar-Stub] Host function '{}' called with {} args", functionName, args.length);
        }
        if (callbackClient == null) {
            System.out.println("[DEBUG-SIDECAR] ERROR: No callback client!");
            log.error("[Sidecar-Stub] Host function '{}' called but no callback client available!", functionName);
            return null;
        }

        try {
            // Convert args to Java objects
            Object[] javaArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                System.out.println("[DEBUG-SIDECAR] Converting arg[" + i + "]: " + args[i]);
                javaArgs[i] = convertToJava(args[i]);
                System.out.println("[DEBUG-SIDECAR] Converted arg[" + i + "] to: " +
                        (javaArgs[i] != null ? javaArgs[i].getClass().getSimpleName() : "null"));
                if (log.isDebugEnabled()) {
                    log.debug("[Sidecar-Stub] Converted arg[{}] to: {}", i,
                            javaArgs[i] != null ? javaArgs[i].getClass().getSimpleName() : "null");
                }
            }

            System.out.println("[DEBUG-SIDECAR] Invoking callback to IS for '" + functionName + "'");
            if (log.isDebugEnabled()) {
                log.debug("[Sidecar-Stub] Invoking callback to IS for '{}' with {} args", functionName, javaArgs.length);
            }
            Object result = callbackClient.invokeHostFunction(functionName, javaArgs);
            System.out.println("[DEBUG-SIDECAR] Callback returned: "
                    + (result != null ? result.getClass().getSimpleName() : "null"));
            if (log.isDebugEnabled()) {
                log.debug("[Sidecar-Stub] Callback returned: {}",
                        result != null ? result.getClass().getSimpleName() : "null");
            }

            // Check if the result is a proxy marker from a complex host function return
            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) result;
                if (Boolean.TRUE.equals(resultMap.get(SidecarConstants.IS_HOST_REF))) {
                    String proxyType = (String) resultMap.get(SidecarConstants.PROXY_TYPE_FIELD);
                    String referenceId = (String) resultMap.get(SidecarConstants.REFERENCE_ID_FIELD);
                    // "pojo" type uses proxyObjectCache (__proxyref__), others use hostRefCache (__hostref__)
                    String basePath = (SidecarConstants.PROXY_TYPE_POJO.equals(proxyType) ? SidecarConstants.PROXY_REF_PREFIX : SidecarConstants.HOST_REF_PREFIX) + referenceId;
                    if (log.isDebugEnabled()) {
                        log.debug("[Sidecar-Stub] Creating DynamicContextProxy for host function return: " +
                                "type={}, refId={}, basePath={}", proxyType, referenceId, basePath);
                    }
                    return new DynamicContextProxy(
                            callbackClient.getSessionId(), callbackClient, proxyType, basePath);
                }
            }

            // Handle arrays containing proxy markers (e.g., getUsersWithClaimValues
            // returning an array of User objects serialized as proxy markers).
            // Each proxy marker element becomes a DynamicContextProxy for lazy property access.
            if (result instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> resultList = (List<Object>) result;
                Object[] elements = new Object[resultList.size()];
                boolean hasProxyElements = false;
                for (int i = 0; i < resultList.size(); i++) {
                    Object element = resultList.get(i);
                    if (element instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> elementMap = (Map<String, Object>) element;
                        if (Boolean.TRUE.equals(elementMap.get(SidecarConstants.IS_HOST_REF))) {
                            String proxyType = (String) elementMap.get(SidecarConstants.PROXY_TYPE_FIELD);
                            String referenceId = (String) elementMap.get(SidecarConstants.REFERENCE_ID_FIELD);
                            String basePath = SidecarConstants.PROXY_REF_PREFIX + referenceId;
                            elements[i] = new DynamicContextProxy(
                                    callbackClient.getSessionId(), callbackClient, proxyType, basePath);
                            hasProxyElements = true;
                            System.out.println("[DEBUG-SIDECAR] List element[" + i +
                                    "] -> DynamicContextProxy refId=" + referenceId);
                            continue;
                        }
                    }
                    elements[i] = element;
                }
                if (hasProxyElements) {
                    System.out.println("[DEBUG-SIDECAR] Returning ProxyArray with " +
                            elements.length + " elements (proxy-wrapped)");
                    return ProxyArray.fromArray(elements);
                }
            }

            return result;
        } catch (Exception e) {
            System.out.println("[DEBUG-SIDECAR] ERROR: " + e.getMessage());
            log.error("[Sidecar-Stub] Error calling host function: " + functionName, e);
            throw new RuntimeException("Host function call failed: " + e.getMessage(), e);
        }
    }

    Object convertToJava(Value val) {
        if (val == null || val.isNull())
            return null;
        if (val.isString())
            return val.asString();
        if (val.isNumber())
            return val.fitsInLong() ? val.asLong() : val.asDouble();
        if (val.isBoolean())
            return val.asBoolean();
        // Handle JavaScript functions - extract source code.
        // IMPORTANT: Check canExecute() BEFORE hasMembers() because JS functions also
        // have members.
        if (val.canExecute()) {
            return ValueSerializationUtils.extractFunctionSource(val);
        }
        if (val.hasArrayElements()) {
            Object[] arr = new Object[(int) val.getArraySize()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = convertToJava(val.getArrayElement(i));
            }
            return arr;
        }
        // IMPORTANT: Check if this is a DynamicContextProxy BEFORE the generic
        // hasMembers() check.
        // DynamicContextProxy returns empty member keys when serializing, so we need to
        // send a
        // marker that tells IS to reconstruct the object from stored context.
        if (val.isProxyObject()) {
            Object proxyObj = val.asProxyObject();
            if (proxyObj instanceof DynamicContextProxy) {
                DynamicContextProxy proxy = (DynamicContextProxy) proxyObj;
                Map<String, Object> marker = new HashMap<>();
                marker.put(SidecarConstants.IS_CONTEXT_PROXY, true);
                marker.put(SidecarConstants.PROXY_TYPE_FIELD, proxy.getProxyType());
                marker.put(SidecarConstants.BASE_PATH_FIELD, proxy.getBasePath());
                System.out.println("[DEBUG-SIDECAR] Converting DynamicContextProxy to marker: type=" +
                        proxy.getProxyType() + ", basePath=" + proxy.getBasePath());
                if (log.isDebugEnabled()) {
                    log.debug("[Sidecar-Stub] Converting DynamicContextProxy to marker: type={}, basePath={}",
                            proxy.getProxyType(), proxy.getBasePath());
                }
                return marker;
            }
        }
        if (val.hasMembers()) {
            Map<String, Object> map = new HashMap<>();
            Set<String> memberKeys = val.getMemberKeys();
            System.out.println(
                    "[DEBUG-SIDECAR] Converting object with " + memberKeys.size() + " members: " + memberKeys);
            if (log.isDebugEnabled()) {
                log.debug("[Sidecar-Stub] Converting object with {} members: {}",
                        memberKeys.size(), memberKeys);
            }
            for (String key : memberKeys) {
                Value memberVal = val.getMember(key);
                System.out.println("[DEBUG-SIDECAR] Member '" + key + "': isNull="
                        + (memberVal == null || memberVal.isNull()) +
                        ", canExecute=" + (memberVal != null && memberVal.canExecute()) +
                        ", hasMembers=" + (memberVal != null && memberVal.hasMembers()));
                if (log.isDebugEnabled()) {
                    log.debug("[Sidecar-Stub] Member '{}': isNull={}, canExecute={}, hasMembers={}, hasArrayElements={}",
                            key,
                            memberVal == null || memberVal.isNull(),
                            memberVal != null && memberVal.canExecute(),
                            memberVal != null && memberVal.hasMembers(),
                            memberVal != null && memberVal.hasArrayElements());
                }
                Object converted = convertToJava(memberVal);
                System.out.println("[DEBUG-SIDECAR] Member '" + key + "' converted to type: " +
                        (converted != null ? converted.getClass().getSimpleName() : "null"));
                if (log.isDebugEnabled()) {
                    log.debug("[Sidecar-Stub] Member '{}' converted to: {} (type: {})",
                            key,
                            converted instanceof String
                                    ? ((String) converted).substring(0, Math.min(60, ((String) converted).length()))
                                            + "..."
                                    : converted,
                            converted != null ? converted.getClass().getSimpleName() : "null");
                }
                map.put(key, converted);
            }
            System.out.println("[DEBUG-SIDECAR] Final map has " + map.size() + " entries: " + map.keySet());
            if (log.isDebugEnabled()) {
                log.debug("[Sidecar-Stub] Final map has {} entries: {}", map.size(), map.keySet());
            }
            return map;
        }
        return val.toString();
    }
}
