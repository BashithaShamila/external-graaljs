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

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.*;

import java.util.HashMap;
import java.util.Map;

/**
 * JavaScript engine service implementation for the sidecar.
 * Handles evaluate and callback execution requests via protobuf messages.
 * Host function calls are forwarded back to IS via HostCallbackClient.
 */
public class JsEngineServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(JsEngineServiceImpl.class);
    private static final String JS_LANG = "js";

    // ThreadLocal to store the current callback client for proxy object deserialization
    private static final ThreadLocal<HostCallbackClient> currentCallbackClient = new ThreadLocal<>();

    private final int defaultStatementLimit;

    public JsEngineServiceImpl(int defaultStatementLimit) {
        this.defaultStatementLimit = defaultStatementLimit;
    }

    /**
     * Handle an evaluate request with a pre-created callback client.
     * Used by streaming transport where the callback client uses the bidi stream.
     *
     * @param requestBytes   Protobuf-encoded EvaluateRequest.
     * @param callbackClient Pre-created callback client for host function
     *                       callbacks.
     * @return Protobuf-encoded EvaluateResponse.
     */
    public byte[] handleEvaluate(byte[] requestBytes, HostCallbackClient callbackClient) throws java.io.IOException {
        if (log.isDebugEnabled()) {
            log.debug("[Sidecar] handleEvaluate (streaming) called");
        }
        long startTime = System.currentTimeMillis();

        // Phase A: Request parse
        EvaluateRequest request = EvaluateRequest.parseFrom(requestBytes);
        long tRequestParsed = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug("[Sidecar] handleEvaluate (streaming) - session: {}, sourceId: {}",
                    request.getSessionId(), request.getSourceIdentifier());
        }
        if (log.isDebugEnabled()) {
            log.debug("[Sidecar] Script length: {}, bindings: {}, hostFunctions: {}",
                    request.getScript().length(), request.getBindingsCount(), request.getHostFunctionsCount());
        }

        try {
            // Reset callback timer for reused streaming clients
            if (callbackClient != null) {
                callbackClient.resetCallbackTimeMs();
            }

            try (Context context = createContext()) {
                Value bindings = context.getBindings(JS_LANG);

                // Phase B: Context setup (create context + register stubs)
                // Use dynamic list from the request, mirroring the executeCallback
                // registration logic. This ensures host functions like
                // getMaskedValue, httpGet, etc. are available during initial
                // script evaluation rather than only after the first callback.
                registerHostFunctionStubsFromRequest(bindings, callbackClient,
                        request.getHostFunctionsList());
                long tContextSetup = System.currentTimeMillis();

                // Phase C: Binding restore
                currentCallbackClient.set(callbackClient);
                try {
                    for (Map.Entry<String, SerializedValue> entry : request.getBindingsMap().entrySet()) {
                        bindings.putMember(entry.getKey(), deserializeValue(entry.getValue(), context));
                    }
                } finally {
                    currentCallbackClient.remove();
                }
                long tBindingsRestored = System.currentTimeMillis();

                // Phase D: Proxy create
                if (request.hasContextData()) {
                    Value contextProxy = createContextProxy(context, request.getContextData(), callbackClient);
                    bindings.putMember("context", contextProxy);
                    if (log.isDebugEnabled()) {
                        log.debug("[Sidecar] Bound DYNAMIC context proxy for session: {}", request.getSessionId());
                    }
                } else {
                    Value emptyContext = context.eval(JS_LANG, "({})");
                    bindings.putMember("context", emptyContext);
                    log.warn("[Sidecar] No ContextData provided, binding empty context for session: {}",
                            request.getSessionId());
                }
                long tProxyCreated = System.currentTimeMillis();

                // Phase E: JS evaluate
                if (log.isDebugEnabled()) {
                    log.debug("[Sidecar] Starting script evaluation (streaming)...");
                }
                Value result = context.eval(JS_LANG, request.getScript());
                long tJsEvaluated = System.currentTimeMillis();
                if (log.isDebugEnabled()) {
                    log.debug("[Sidecar] Script evaluation completed successfully (streaming)");
                }

                // Phase F: Binding extract
                Map<String, SerializedValue> updatedBindings = new HashMap<>();
                for (String key : bindings.getMemberKeys()) {
                    Value val = bindings.getMember(key);
                    // Skip "context" -- it is an unserializable JsGraalAuthenticationContext proxy.
                    // Context mutations are handled via live DynamicContextProxy callbacks,
                    // and structured ContextData is sent separately. Serializing it here
                    // causes a ProtobufSerializer toString() fallback with WARN log.
                    // If context binding is ever needed here, implement a proper toProto()
                    // conversion for JsGraalAuthenticationContext first.
                    if (!SidecarConstants.CONTEXT_BINDING_KEY.equals(key) && !val.canExecute() && !isHostFunction(key)) {
                        updatedBindings.put(key, serializeValue(val));
                    }
                }
                long tBindingsExtracted = System.currentTimeMillis();

                // Phase G: Response build
                long callbackMs = callbackClient != null ? callbackClient.getCallbackTimeMs() : 0;
                long elapsed = System.currentTimeMillis() - startTime;
                long pureProcessingMs = elapsed - callbackMs;
                byte[] responseBytes = EvaluateResponse.newBuilder()
                        .setSuccess(true)
                        .setElapsedMs(elapsed)
                        .setResult(serializeValue(result))
                        .putAllUpdatedBindings(updatedBindings)
                        .build()
                        .toByteArray();
                long tResponseBuilt = System.currentTimeMillis();
                if (log.isDebugEnabled()) {
                    log.debug(
                            "[Sidecar] Time breakdown (streaming): totalElapsed={}ms, pureProcessing={}ms, callbackRoundTrips={}ms",
                            elapsed, pureProcessingMs, callbackMs);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[Sidecar] Phase timing (streaming): requestParse={}ms, contextSetup={}ms, " +
                            "bindingRestore={}ms, proxyCreate={}ms, jsEvaluate={}ms, " +
                            "bindingExtract={}ms, responseBuild={}ms, total={}ms",
                            tRequestParsed - startTime,
                            tContextSetup - tRequestParsed,
                            tBindingsRestored - tContextSetup,
                            tProxyCreated - tBindingsRestored,
                            tJsEvaluated - tProxyCreated,
                            tBindingsExtracted - tJsEvaluated,
                            tResponseBuilt - tBindingsExtracted,
                            elapsed);
                }
                return responseBytes;
            }

        } catch (PolyglotException e) {
            log.error("PolyglotException during evaluation (streaming)", e);
            return EvaluateResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(e.getMessage() != null ? e.getMessage() : "Unknown PolyglotException")
                    .setErrorType("PolyglotException")
                    .setElapsedMs(System.currentTimeMillis() - startTime)
                    .build()
                    .toByteArray();

        } catch (Throwable t) {
            log.error("FATAL: Throwable during evaluation (streaming)", t);
            String errorMessage = t.getClass().getName() + ": " +
                    (t.getMessage() != null ? t.getMessage() : "No error message");
            return EvaluateResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(errorMessage)
                    .setErrorType(t.getClass().getName())
                    .setElapsedMs(System.currentTimeMillis() - startTime)
                    .build()
                    .toByteArray();
        } finally {
            currentRegisteredFunctions.remove();
            // NOTE: Do NOT close callbackClient here - it's managed by the streaming
            // transport
        }
    }

    /**
     * Handle an execute callback request with a pre-created callback client.
     * Used by streaming transport where the callback client uses the bidi stream.
     *
     * @param requestBytes   Protobuf-encoded ExecuteCallbackRequest.
     * @param callbackClient Pre-created callback client for host function
     *                       callbacks.
     * @return Protobuf-encoded ExecuteCallbackResponse.
     */
    public byte[] handleExecuteCallback(byte[] requestBytes, HostCallbackClient callbackClient)
            throws java.io.IOException {
        long startTime = System.currentTimeMillis();

        // Phase A: Request parse
        ExecuteCallbackRequest request = ExecuteCallbackRequest.parseFrom(requestBytes);
        long tRequestParsed = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug("[Sidecar] handleExecuteCallback (streaming) - session: {}", request.getSessionId());
        }
        if (log.isDebugEnabled()) {
            log.debug("[Sidecar] Function source length: {}, args: {}, bindings: {}",
                    request.getFunctionSource().length(), request.getArgumentsCount(), request.getBindingsCount());
        }

        try {
            // Reset callback timer for reused streaming clients
            if (callbackClient != null) {
                callbackClient.resetCallbackTimeMs();
            }

            try (Context context = createContext()) {
                Value bindings = context.getBindings(JS_LANG);

                // Phase B: Context setup (create context + register stubs)
                registerHostFunctionStubsFromRequest(bindings, callbackClient, request.getHostFunctionsList());
                long tContextSetup = System.currentTimeMillis();

                // Declare variables outside try block so they're accessible later
                Value contextProxy = null;
                Object[] args = new Object[request.getArgumentsCount()];

                // Phase C: Binding restore
                currentCallbackClient.set(callbackClient);
                try {
                    for (Map.Entry<String, SerializedValue> entry : request.getBindingsMap().entrySet()) {
                        Object deserialized = deserializeValue(entry.getValue(), context);
                        bindings.putMember(entry.getKey(), deserialized);
                    }

                    // Phase D: Proxy create + argument deserialization
                    if (request.hasContextData()) {
                        contextProxy = createContextProxy(context, request.getContextData(), callbackClient);
                        bindings.putMember(SidecarConstants.CALLBACK_CONTEXT_KEY, contextProxy);
                    }

                    // Deserialize arguments
                    for (int i = 0; i < args.length; i++) {
                        SerializedValue sv = request.getArguments(i);
                        if (sv.getValueCase() == SerializedValue.ValueCase.STRING_VALUE &&
                                sv.getStringValue().contains(SidecarConstants.CONTEXT_PLACEHOLDER) &&
                                contextProxy != null) {
                            args[i] = contextProxy;
                        } else {
                            args[i] = deserializeValue(sv, context);
                        }
                    }
                } finally {
                    currentCallbackClient.remove();
                }
                long tBindingsRestored = System.currentTimeMillis();
                long tProxyAndArgsReady = System.currentTimeMillis();

                // Phase E: JS evaluate (function execution)
                Value function = context.eval(JS_LANG, "(" + request.getFunctionSource() + ")");

                Value result;
                if (args.length > 0) {
                    result = function.execute(args);
                } else if (contextProxy != null) {
                    result = function.execute(contextProxy);
                } else {
                    result = function.execute();
                }
                long tJsEvaluated = System.currentTimeMillis();

                // Phase F: Binding extract
                Map<String, SerializedValue> updatedBindings = new HashMap<>();
                for (String key : bindings.getMemberKeys()) {
                    Value val = bindings.getMember(key);
                    // Skip "context" -- it is an unserializable JsGraalAuthenticationContext proxy.
                    // Context mutations are handled via live DynamicContextProxy callbacks,
                    // and structured ContextData is sent separately. Serializing it here
                    // causes a ProtobufSerializer toString() fallback with WARN log.
                    // If context binding is ever needed here, implement a proper toProto()
                    // conversion for JsGraalAuthenticationContext first.
                    if (!SidecarConstants.CONTEXT_BINDING_KEY.equals(key) && !key.startsWith("__") && !val.canExecute() && !isHostFunction(key)) {
                        updatedBindings.put(key, serializeValue(val));
                    }
                }
                long tBindingsExtracted = System.currentTimeMillis();

                // Phase G: Response build
                long callbackMs = callbackClient != null ? callbackClient.getCallbackTimeMs() : 0;
                long elapsed = System.currentTimeMillis() - startTime;
                long pureProcessingMs = elapsed - callbackMs;
                byte[] responseBytes = ExecuteCallbackResponse.newBuilder()
                        .setSuccess(true)
                        .setElapsedMs(elapsed)
                        .setResult(serializeValue(result))
                        .putAllUpdatedBindings(updatedBindings)
                        .build()
                        .toByteArray();
                long tResponseBuilt = System.currentTimeMillis();
                if (log.isDebugEnabled()) {
                    log.debug(
                            "[Sidecar] Time breakdown (streaming): totalElapsed={}ms, pureProcessing={}ms, callbackRoundTrips={}ms",
                            elapsed, pureProcessingMs, callbackMs);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[Sidecar] Phase timing (streaming): requestParse={}ms, contextSetup={}ms, " +
                            "bindingRestore={}ms, proxyAndArgs={}ms, jsEvaluate={}ms, " +
                            "bindingExtract={}ms, responseBuild={}ms, total={}ms",
                            tRequestParsed - startTime,
                            tContextSetup - tRequestParsed,
                            tBindingsRestored - tContextSetup,
                            tProxyAndArgsReady - tBindingsRestored,
                            tJsEvaluated - tProxyAndArgsReady,
                            tBindingsExtracted - tJsEvaluated,
                            tResponseBuilt - tBindingsExtracted,
                            elapsed);
                }
                return responseBytes;
            }

        } catch (PolyglotException e) {
            log.error("PolyglotException during callback execution (streaming)", e);
            return ExecuteCallbackResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(e.getMessage())
                    .setElapsedMs(System.currentTimeMillis() - startTime)
                    .build()
                    .toByteArray();

        } catch (Throwable t) {
            log.error("FATAL: Throwable during callback execution (streaming)", t);
            String errorMessage = t.getClass().getName() + ": " +
                    (t.getMessage() != null ? t.getMessage() : "No error message");
            return ExecuteCallbackResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(errorMessage)
                    .setElapsedMs(System.currentTimeMillis() - startTime)
                    .build()
                    .toByteArray();
        } finally {
            currentRegisteredFunctions.remove();
            // NOTE: Do NOT close callbackClient here - it's managed by the streaming
            // transport
        }
    }

    private Context createContext() {
        ResourceLimits.Builder limitsBuilder = ResourceLimits.newBuilder();
        if (defaultStatementLimit > 0) {
            limitsBuilder.statementLimit(defaultStatementLimit, null);
        }

        return Context.newBuilder(JS_LANG)
                .allowHostAccess(HostAccess.ALL)
                .resourceLimits(limitsBuilder.build())
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    /**
     * Register host function stubs dynamically based on the list from the request.
     * This allows all JsFunctionRegistry functions to be callable from JavaScript.
     */
    private void registerHostFunctionStubsFromRequest(Value bindings, HostCallbackClient callbackClient,
            java.util.List<HostFunctionDefinition> hostFunctions) {
        // Track registered function names for isHostFunction check
        java.util.Set<String> registeredFunctions = new java.util.HashSet<>();

        // Always register Log as a special case (local logging)
        bindings.putMember("Log", new LoggerProxy());
        registeredFunctions.add("Log");

        // If the request did not include any host‑function definitions (older
        // client), fall back to the legacy hard‑coded list so that basic
        // functions like executeStep continue to work.
        if (hostFunctions == null || hostFunctions.isEmpty()) {
            String[] defaultFuncNames = { "executeStep", "sendError", "fail", "showPrompt",
                    "loadLocalLibrary", "getSecretByName", "selectAcrFrom" };
            for (String funcName : defaultFuncNames) {
                bindings.putMember(funcName, new HostFunctionStub(funcName, callbackClient));
                registeredFunctions.add(funcName);
            }
        } else {
            // Register stubs for all host functions from the request
            for (HostFunctionDefinition funcDef : hostFunctions) {
                String funcName = funcDef.getName();
                if (log.isDebugEnabled()) {
                    log.debug("[Sidecar] Registering host function stub: {}", funcName);
                }
                bindings.putMember(funcName, new HostFunctionStub(funcName, callbackClient));
                registeredFunctions.add(funcName);
            }
        }

        // Store for isHostFunction checks
        currentRegisteredFunctions.set(registeredFunctions);
        if (log.isDebugEnabled()) {
            log.debug("[Sidecar] Registered {} host function stubs", registeredFunctions.size());
        }
    }

    // Thread-local to track registered functions for current request
    private static final ThreadLocal<java.util.Set<String>> currentRegisteredFunctions = ThreadLocal
            .withInitial(java.util.HashSet::new);

    private boolean isHostFunction(String name) {
        java.util.Set<String> registered = currentRegisteredFunctions.get();
        return registered != null && registered.contains(name);
    }

    /**
     * Create a JavaScript proxy object representing the authentication context.
     * This version supports callbacks to the host for dynamic property access.
     *
     * @param context        The GraalJS context.
     * @param data           The context data from the request.
     * @param callbackClient The callback client for host function calls.
     * @return A JavaScript Value representing the context proxy.
     */
    private Value createContextProxy(Context context, ContextData data, HostCallbackClient callbackClient) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "[Sidecar] Creating DYNAMIC context proxy with data: username={}, userStoreDomain={}, tenantDomain={}, step={}",
                    data.getUsername(), data.getUserStoreDomain(), data.getTenantDomain(), data.getCurrentStep());
        }

        // Use DynamicContextProxy which calls back to IS for every property access
        // This ensures the sidecar context behaves identically to the local
        // JsGraalAuthenticationContext
        DynamicContextProxy dynamicProxy = new DynamicContextProxy(
                callbackClient != null ? data.getSessionContextKey() : "unknown",
                callbackClient,
                "context", // Root proxy type
                "" // Empty base path (root level)
        );

        return context.asValue(dynamicProxy);
    }
    // ============ Serialization Helpers ============

    private SerializedValue serializeValue(Value val) {
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
                marker.putEntries(SidecarConstants.IS_CONTEXT_PROXY,
                        SerializedValue.newBuilder().setBoolValue(true).build());
                marker.putEntries(SidecarConstants.PROXY_TYPE_FIELD,
                        SerializedValue.newBuilder().setStringValue(proxy.getProxyType()).build());
                marker.putEntries(SidecarConstants.BASE_PATH_FIELD,
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

    private Object deserializeValue(SerializedValue sv, Context context) {
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
                // Handle proxy object markers - create a DynamicContextProxy that lazily fetches properties
                // This is CRITICAL for arrays of complex objects (e.g., getUsersWithClaimValues returning
                // 100 User objects). Instead of eagerly serializing all properties (causing timeouts),
                // we create a proxy that fetches properties on-demand when accessed.
                org.wso2.carbon.identity.graaljs.proto.SerializedProxyObject proxyObj = sv.getProxyObject();
                String proxyType = proxyObj.getType();
                String referenceId = proxyObj.getReferenceId();

                if (log.isDebugEnabled()) {
                    log.debug("[Sidecar] Creating proxy for POJO: type={}, refId={}", proxyType, referenceId);
                }

                // Use __proxyref__ prefix to distinguish from context proxies (__hostref__ pattern)
                String basePath = SidecarConstants.PROXY_REF_PREFIX + referenceId;

                // Get callback client from ThreadLocal (set by calling methods)
                HostCallbackClient callbackClient = currentCallbackClient.get();
                if (callbackClient == null) {
                    log.warn("[Sidecar] No callback client available for proxy object, returning null");
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

    /**
     * Handle a host function request (placeholder implementation).
     * These requests are typically handled by the callback mechanism.
     *
     * @param requestBytes Protobuf-encoded HostFunctionRequest.
     * @return Protobuf-encoded HostFunctionResponse.
     */
    public byte[] handleHostFunction(byte[] requestBytes) throws java.io.IOException {
        HostFunctionRequest request = HostFunctionRequest.parseFrom(requestBytes);
        if (log.isDebugEnabled()) {
            log.debug("[Sidecar] handleHostFunction - session: {}, function: {}, args: {}",
                    request.getSessionId(), request.getFunctionName(), request.getArgumentsCount());
        }

        // This is typically handled via callback mechanism during script execution
        // This direct handler is mainly for logging purposes
        HostFunctionResponse response = HostFunctionResponse.newBuilder()
                .setSuccess(false)
                .setErrorMessage("Host function calls should be handled via callback mechanism during script execution")
                .build();

        return response.toByteArray();
    }

    /**
     * Handle a context property request (placeholder implementation).
     * These requests are typically handled by the proxy mechanism during script
     * execution.
     *
     * @param requestBytes Protobuf-encoded ContextPropertyRequest.
     * @return Protobuf-encoded ContextPropertyResponse.
     */
    public byte[] handleContextProperty(byte[] requestBytes) throws java.io.IOException {
        ContextPropertyRequest request = ContextPropertyRequest.parseFrom(requestBytes);
        if (log.isDebugEnabled()) {
            log.debug("[Sidecar] handleContextProperty - session: {}, property: {}, proxyType: {}",
                    request.getSessionId(), request.getPropertyPath(), request.getProxyType());
        }

        // This is typically handled via proxy mechanism during script execution
        // This direct handler is mainly for logging purposes
        ContextPropertyResponse response = ContextPropertyResponse.newBuilder()
                .setSuccess(false)
                .setErrorMessage(
                        "Context property access should be handled via proxy mechanism during script execution")
                .build();

        return response.toByteArray();
    }

    /**
     * Handle a context property set request (placeholder implementation).
     * These requests are typically handled by the proxy mechanism during script
     * execution.
     *
     * @param requestBytes Protobuf-encoded ContextPropertySetRequest.
     * @return Protobuf-encoded ContextPropertySetResponse.
     */
    public byte[] handleContextPropertySet(byte[] requestBytes) throws java.io.IOException {
        ContextPropertySetRequest request = ContextPropertySetRequest.parseFrom(requestBytes);
        if (log.isDebugEnabled()) {
            log.debug("[Sidecar] handleContextPropertySet - session: {}, property: {}, value: {}",
                    request.getSessionId(), request.getPropertyPath(), request.getValue());
        }

        // This is typically handled via proxy mechanism during script execution
        // This direct handler is mainly for logging purposes
        ContextPropertySetResponse response = ContextPropertySetResponse.newBuilder()
                .setSuccess(false)
                .setErrorMessage(
                        "Context property setting should be handled via proxy mechanism during script execution")
                .build();

        return response.toByteArray();
    }
}
