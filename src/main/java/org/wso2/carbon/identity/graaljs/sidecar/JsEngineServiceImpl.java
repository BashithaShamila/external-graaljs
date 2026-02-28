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
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * JavaScript engine service implementation for the sidecar.
 * Handles evaluate and callback execution requests via protobuf messages.
 * Host function calls are forwarded back to IS via HostCallbackClient.
 */
public class JsEngineServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(JsEngineServiceImpl.class);
    private static final String JS_LANG = "js";

    private final int defaultStatementLimit;

    public JsEngineServiceImpl(int defaultStatementLimit) {
        this.defaultStatementLimit = defaultStatementLimit;
    }

    /**
     * Handle an evaluate request.
     *
     * @param requestBytes Protobuf-encoded EvaluateRequest.
     * @return Protobuf-encoded EvaluateResponse.
     */
    public byte[] handleEvaluate(byte[] requestBytes) throws java.io.IOException {
        System.out.println("[DEBUG-SIDECAR] ===== handleEvaluate called =====");
        System.out.flush();
        long startTime = System.currentTimeMillis();
        EvaluateRequest request = EvaluateRequest.parseFrom(requestBytes);
        System.out.println("[DEBUG-SIDECAR] Session: " + request.getSessionId() + ", Script length: "
                + request.getScript().length());
        System.out.println("[DEBUG-SIDECAR] Callback socket: " + request.getCallbackSocketPath());
        System.out.flush();
        log.info("[Sidecar] handleEvaluate - session: {}, sourceId: {}, callbackSocket: {}",
                request.getSessionId(), request.getSourceIdentifier(), request.getCallbackSocketPath());
        log.info("[Sidecar] Script length: {}, bindings: {}, hostFunctions: {}",
                request.getScript().length(), request.getBindingsCount(), request.getHostFunctionsCount());

        HostCallbackClient callbackClient = null;
        try {
            // Create callback client using factory (auto-detects transport type from
            // address)
            if (request.getCallbackSocketPath() != null && !request.getCallbackSocketPath().isEmpty()) {
                log.info("[Sidecar] Creating callback client to: {}", request.getCallbackSocketPath());
                // Use factory to create appropriate callback client based on address format
                // For now, this will create UDS client (current working implementation)
                // Future: Will auto-detect and create gRPC/HTTP/WebSocket clients
                callbackClient = new HostCallbackClient(request.getCallbackSocketPath(), request.getSessionId());
                // TODO: Replace with factory when other transports are ready:
                // CallbackClient client = CallbackClientFactory.createClient(
                // request.getCallbackSocketPath(), request.getSessionId());
                // client.connect();
                // callbackClient = adaptToHostCallbackClient(client);
            } else {
                log.warn("[Sidecar] No callback socket path provided!");
            }

            try (Context context = createContext()) {
                Value bindings = context.getBindings(JS_LANG);

                // Register host function stubs that call back to IS
                registerHostFunctionStubs(bindings, callbackClient);

                // Restore bindings from request
                for (Map.Entry<String, SerializedValue> entry : request.getBindingsMap().entrySet()) {
                    bindings.putMember(entry.getKey(), deserializeValue(entry.getValue(), context));
                }

                // Create and bind context proxy if ContextData is present
                // FIX: Use dynamic proxy with callbackClient for full context access
                if (request.hasContextData()) {
                    Value contextProxy = createContextProxy(context, request.getContextData(), callbackClient);
                    bindings.putMember("context", contextProxy);
                    log.info("[Sidecar] Bound DYNAMIC context proxy for session: {}", request.getSessionId());
                } else {
                    // Create a minimal empty context object to prevent ReferenceError
                    Value emptyContext = context.eval(JS_LANG, "({})");
                    bindings.putMember("context", emptyContext);
                    log.warn("[Sidecar] No ContextData provided, binding empty context for session: {}",
                            request.getSessionId());
                }

                // Evaluate the script
                System.out.println("[DEBUG-SIDECAR] About to evaluate script...");
                System.out.flush();
                log.info("[Sidecar] Starting script evaluation...");

                Value result = context.eval(JS_LANG, request.getScript());

                System.out.println("[DEBUG-SIDECAR] Script evaluation completed successfully");
                System.out.flush();
                log.info("[Sidecar] Script evaluation completed successfully");

                // Extract updated bindings
                Map<String, SerializedValue> updatedBindings = new HashMap<>();
                for (String key : bindings.getMemberKeys()) {
                    Value val = bindings.getMember(key);
                    if (!val.canExecute() && !isHostFunction(key)) {
                        updatedBindings.put(key, serializeValue(val));
                    }
                }

                long elapsed = System.currentTimeMillis() - startTime;
                long callbackMs = callbackClient != null ? callbackClient.getCallbackTimeMs() : 0;
                long pureProcessingMs = elapsed - callbackMs;
                System.out.println("[DEBUG-SIDECAR] Building success response, elapsed: " + elapsed + "ms");
                System.out.flush();
                log.info("[Sidecar] Time breakdown: totalElapsed={}ms, pureProcessing={}ms, callbackRoundTrips={}ms",
                        elapsed, pureProcessingMs, callbackMs);

                byte[] responseBytes = EvaluateResponse.newBuilder()
                        .setSuccess(true)
                        .setElapsedMs(elapsed)
                        .setResult(serializeValue(result))
                        .putAllUpdatedBindings(updatedBindings)
                        .build()
                        .toByteArray();

                System.out.println("[DEBUG-SIDECAR] Success response built, size: " + responseBytes.length + " bytes");
                System.out.flush();
                log.info("[Sidecar] Success response built, returning {} bytes", responseBytes.length);

                return responseBytes;
            }

        } catch (PolyglotException e) {
            System.err.println("[ERROR-SIDECAR] PolyglotException during evaluation: " + e.getMessage());
            e.printStackTrace(System.err);
            System.err.flush();
            log.error("PolyglotException during evaluation", e);

            String errorMsg = e.getMessage() != null ? e.getMessage()
                    : (e.getCause() != null ? e.getCause().toString() : "Unknown PolyglotException");
            byte[] errorResponse = EvaluateResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(errorMsg)
                    .setErrorType("PolyglotException")
                    .setElapsedMs(System.currentTimeMillis() - startTime)
                    .build()
                    .toByteArray();

            System.err.println("[ERROR-SIDECAR] Built error response: " + errorResponse.length + " bytes");
            System.err.flush();
            return errorResponse;

        } catch (Throwable t) {
            // CRITICAL: Catch ALL throwables including OutOfMemoryError,
            // StackOverflowError, etc.
            System.err.println("[FATAL-SIDECAR] Throwable during evaluation: " + t.getClass().getName());
            System.err.println("[FATAL-SIDECAR] Error message: " + t.getMessage());
            t.printStackTrace(System.err);
            System.err.flush();
            log.error("FATAL: Throwable during evaluation", t);

            String errorMessage = t.getClass().getName() + ": " +
                    (t.getMessage() != null ? t.getMessage() : "No error message");

            byte[] errorResponse = EvaluateResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(errorMessage)
                    .setErrorType(t.getClass().getName())
                    .setElapsedMs(System.currentTimeMillis() - startTime)
                    .build()
                    .toByteArray();

            System.err.println("[FATAL-SIDECAR] Built error response: " + errorResponse.length + " bytes");
            System.err.flush();
            return errorResponse;
        } finally {
            // CRITICAL: Clean up ThreadLocal to prevent context leakage between pooled
            // thread requests
            currentRegisteredFunctions.remove();

            if (callbackClient != null) {
                try {
                    callbackClient.close();
                } catch (Exception e) {
                    log.debug("Error closing callback client", e);
                }
            }
        }
    }

    /**
     * Handle an execute callback request.
     *
     * @param requestBytes Protobuf-encoded ExecuteCallbackRequest.
     * @return Protobuf-encoded ExecuteCallbackResponse.
     */
    public byte[] handleExecuteCallback(byte[] requestBytes) throws java.io.IOException {
        long startTime = System.currentTimeMillis();
        ExecuteCallbackRequest request = ExecuteCallbackRequest.parseFrom(requestBytes);
        log.info("[Sidecar] handleExecuteCallback - session: {}, callbackSocket: {}",
                request.getSessionId(), request.getCallbackSocketPath());
        log.info("[Sidecar] Function source length: {}, args: {}, bindings: {}",
                request.getFunctionSource().length(), request.getArgumentsCount(), request.getBindingsCount());
        log.info("[Sidecar] Function source preview: {}",
                request.getFunctionSource().substring(0, Math.min(200, request.getFunctionSource().length())));

        HostCallbackClient callbackClient = null;
        try {
            // Create callback client using factory (auto-detects transport type from
            // address)
            if (request.getCallbackSocketPath() != null && !request.getCallbackSocketPath().isEmpty()) {
                log.info("[Sidecar] Creating callback client to: {}", request.getCallbackSocketPath());
                // Use factory to create appropriate callback client based on address format
                // For now, this will create UDS client (current working implementation)
                // Future: Will auto-detect and create gRPC/HTTP/WebSocket clients
                callbackClient = new HostCallbackClient(request.getCallbackSocketPath(), request.getSessionId());
                // TODO: Replace with factory when other transports are ready:
                // CallbackClient client = CallbackClientFactory.createClient(
                // request.getCallbackSocketPath(), request.getSessionId());
                // client.connect();
                // callbackClient = adaptToHostCallbackClient(client);
            } else {
                log.warn("[Sidecar] No callback socket path provided for executeCallback!");
            }

            try (Context context = createContext()) {
                log.info("[Sidecar] GraalJS Context created");
                Value bindings = context.getBindings(JS_LANG);

                // Register host function stubs dynamically based on the request
                log.info("[Sidecar] Registering host function stubs from request: {} functions",
                        request.getHostFunctionsCount());
                registerHostFunctionStubsFromRequest(bindings, callbackClient, request.getHostFunctionsList());

                // Restore bindings
                log.info("[Sidecar] Restoring {} bindings from request", request.getBindingsCount());
                for (Map.Entry<String, SerializedValue> entry : request.getBindingsMap().entrySet()) {
                    Object deserialized = deserializeValue(entry.getValue(), context);
                    log.info("[Sidecar] Restoring binding: {} = {} (type: {})",
                            entry.getKey(),
                            deserialized,
                            deserialized != null ? deserialized.getClass().getSimpleName() : "null");
                    bindings.putMember(entry.getKey(), deserialized);
                }

                // Create context proxy from context data (before deserializing args).
                Value contextProxy = null;
                if (request.hasContextData()) {
                    log.info("[Sidecar] Creating context proxy for step: {}, username: {}",
                            request.getContextData().getCurrentStep(),
                            request.getContextData().getUsername());
                    contextProxy = createContextProxy(context, request.getContextData(), callbackClient);
                    bindings.putMember("__callbackContext", contextProxy);
                }

                // Deserialize arguments.
                Object[] args = new Object[request.getArgumentsCount()];
                log.info("[Sidecar] Deserializing {} arguments", args.length);
                for (int i = 0; i < args.length; i++) {
                    SerializedValue sv = request.getArguments(i);
                    // Check if this argument is a context placeholder (string containing
                    // JsGraalAuthenticationContext).
                    if (sv.getValueCase() == SerializedValue.ValueCase.STRING_VALUE &&
                            sv.getStringValue().contains("JsGraalAuthenticationContext") &&
                            contextProxy != null) {
                        // Replace with the actual context proxy.
                        log.info("[Sidecar] Arg[{}] is context placeholder, using context proxy", i);
                        args[i] = contextProxy;
                    } else {
                        args[i] = deserializeValue(sv, context);
                        log.info("[Sidecar] Arg[{}] = {}", i,
                                args[i] != null ? args[i].getClass().getSimpleName() : "null");
                    }
                }

                // Evaluate and execute callback function.
                log.info("[Sidecar] Evaluating callback function...");
                Value function = context.eval(JS_LANG, "(" + request.getFunctionSource() + ")");
                log.info("[Sidecar] Function is executable: {}", function.canExecute());

                Value result;
                if (args.length > 0) {
                    log.info("[Sidecar] Executing function with {} args", args.length);
                    result = function.execute(args);
                } else if (contextProxy != null) {
                    log.info("[Sidecar] Executing function with context proxy");
                    result = function.execute(contextProxy);
                } else {
                    log.info("[Sidecar] Executing function with no args");
                    result = function.execute();
                }
                log.info("[Sidecar] Function execution completed, result type: {}",
                        result != null && !result.isNull() ? result.getMetaObject() : "null");

                // Extract updated bindings
                Map<String, SerializedValue> updatedBindings = new HashMap<>();
                for (String key : bindings.getMemberKeys()) {
                    Value val = bindings.getMember(key);
                    if (!key.startsWith("__") && !val.canExecute() && !isHostFunction(key)) {
                        updatedBindings.put(key, serializeValue(val));
                    }
                }
                log.info("[Sidecar] Extracted {} updated bindings", updatedBindings.size());

                long elapsed = System.currentTimeMillis() - startTime;
                long callbackMs = callbackClient != null ? callbackClient.getCallbackTimeMs() : 0;
                long pureProcessingMs = elapsed - callbackMs;
                log.info("[Sidecar] executeCallback completed in {}ms", elapsed);
                log.info("[Sidecar] Time breakdown: totalElapsed={}ms, pureProcessing={}ms, callbackRoundTrips={}ms",
                        elapsed, pureProcessingMs, callbackMs);

                return ExecuteCallbackResponse.newBuilder()
                        .setSuccess(true)
                        .setElapsedMs(elapsed)
                        .setResult(serializeValue(result))
                        .putAllUpdatedBindings(updatedBindings)
                        .build()
                        .toByteArray();
            }

        } catch (PolyglotException e) {
            System.err.println("[ERROR-SIDECAR] PolyglotException during callback: " + e.getMessage());
            e.printStackTrace(System.err);
            System.err.flush();
            log.error("PolyglotException during callback execution", e);

            byte[] errorResponse = ExecuteCallbackResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(e.getMessage())
                    .setElapsedMs(System.currentTimeMillis() - startTime)
                    .build()
                    .toByteArray();

            System.err.println("[ERROR-SIDECAR] Built callback error response: " + errorResponse.length + " bytes");
            System.err.flush();
            return errorResponse;

        } catch (Throwable t) {
            // CRITICAL: Catch ALL throwables including OutOfMemoryError,
            // StackOverflowError, etc.
            System.err.println("[FATAL-SIDECAR] Throwable during callback: " + t.getClass().getName());
            System.err.println("[FATAL-SIDECAR] Error message: " + t.getMessage());
            t.printStackTrace(System.err);
            System.err.flush();
            log.error("FATAL: Throwable during callback execution", t);

            String errorMessage = t.getClass().getName() + ": " +
                    (t.getMessage() != null ? t.getMessage() : "No error message");

            byte[] errorResponse = ExecuteCallbackResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(errorMessage)
                    .setElapsedMs(System.currentTimeMillis() - startTime)
                    .build()
                    .toByteArray();

            System.err.println("[FATAL-SIDECAR] Built callback error response: " + errorResponse.length + " bytes");
            System.err.flush();
            return errorResponse;
        } finally {
            // CRITICAL: Clean up ThreadLocal to prevent context leakage between pooled
            // thread requests
            currentRegisteredFunctions.remove();

            if (callbackClient != null) {
                try {
                    callbackClient.close();
                } catch (Exception e) {
                    log.debug("Error closing callback client", e);
                }
            }
        }
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
        log.info("[Sidecar] handleEvaluate (streaming) called");
        long startTime = System.currentTimeMillis();

        // Phase A: Request parse
        EvaluateRequest request = EvaluateRequest.parseFrom(requestBytes);
        long tRequestParsed = System.currentTimeMillis();
        log.info("[Sidecar] handleEvaluate (streaming) - session: {}, sourceId: {}",
                request.getSessionId(), request.getSourceIdentifier());
        log.info("[Sidecar] Script length: {}, bindings: {}, hostFunctions: {}",
                request.getScript().length(), request.getBindingsCount(), request.getHostFunctionsCount());

        try {
            // Reset callback timer for reused streaming clients
            if (callbackClient != null) {
                callbackClient.resetCallbackTimeMs();
            }

            try (Context context = createContext()) {
                Value bindings = context.getBindings(JS_LANG);

                // Phase B: Context setup (create context + register stubs)
                registerHostFunctionStubs(bindings, callbackClient);
                long tContextSetup = System.currentTimeMillis();

                // Phase C: Binding restore
                for (Map.Entry<String, SerializedValue> entry : request.getBindingsMap().entrySet()) {
                    bindings.putMember(entry.getKey(), deserializeValue(entry.getValue(), context));
                }
                long tBindingsRestored = System.currentTimeMillis();

                // Phase D: Proxy create
                if (request.hasContextData()) {
                    Value contextProxy = createContextProxy(context, request.getContextData(), callbackClient);
                    bindings.putMember("context", contextProxy);
                    log.info("[Sidecar] Bound DYNAMIC context proxy for session: {}", request.getSessionId());
                } else {
                    Value emptyContext = context.eval(JS_LANG, "({})");
                    bindings.putMember("context", emptyContext);
                    log.warn("[Sidecar] No ContextData provided, binding empty context for session: {}",
                            request.getSessionId());
                }
                long tProxyCreated = System.currentTimeMillis();

                // Phase E: JS evaluate
                log.info("[Sidecar] Starting script evaluation (streaming)...");
                Value result = context.eval(JS_LANG, request.getScript());
                long tJsEvaluated = System.currentTimeMillis();
                log.info("[Sidecar] Script evaluation completed successfully (streaming)");

                // Phase F: Binding extract
                Map<String, SerializedValue> updatedBindings = new HashMap<>();
                for (String key : bindings.getMemberKeys()) {
                    Value val = bindings.getMember(key);
                    if (!val.canExecute() && !isHostFunction(key)) {
                        updatedBindings.put(key, serializeValue(val));
                    }
                }
                long tBindingsExtracted = System.currentTimeMillis();

                // Phase G: Response build
                long callbackMs = callbackClient != null ? callbackClient.getCallbackTimeMs() : 0;
                byte[] responseBytes = EvaluateResponse.newBuilder()
                        .setSuccess(true)
                        .setElapsedMs(tBindingsExtracted - startTime)
                        .setResult(serializeValue(result))
                        .putAllUpdatedBindings(updatedBindings)
                        .build()
                        .toByteArray();
                long tResponseBuilt = System.currentTimeMillis();

                long elapsed = tResponseBuilt - startTime;
                long pureProcessingMs = elapsed - callbackMs;
                log.info(
                        "[Sidecar] Time breakdown (streaming): totalElapsed={}ms, pureProcessing={}ms, callbackRoundTrips={}ms",
                        elapsed, pureProcessingMs, callbackMs);
                log.info("[Sidecar] Phase timing (streaming): requestParse={}ms, contextSetup={}ms, " +
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
        log.info("[Sidecar] handleExecuteCallback (streaming) - session: {}", request.getSessionId());
        log.info("[Sidecar] Function source length: {}, args: {}, bindings: {}",
                request.getFunctionSource().length(), request.getArgumentsCount(), request.getBindingsCount());

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

                // Phase C: Binding restore
                for (Map.Entry<String, SerializedValue> entry : request.getBindingsMap().entrySet()) {
                    Object deserialized = deserializeValue(entry.getValue(), context);
                    bindings.putMember(entry.getKey(), deserialized);
                }
                long tBindingsRestored = System.currentTimeMillis();

                // Phase D: Proxy create + argument deserialization
                Value contextProxy = null;
                if (request.hasContextData()) {
                    contextProxy = createContextProxy(context, request.getContextData(), callbackClient);
                    bindings.putMember("__callbackContext", contextProxy);
                }

                // Deserialize arguments
                Object[] args = new Object[request.getArgumentsCount()];
                for (int i = 0; i < args.length; i++) {
                    SerializedValue sv = request.getArguments(i);
                    if (sv.getValueCase() == SerializedValue.ValueCase.STRING_VALUE &&
                            sv.getStringValue().contains("JsGraalAuthenticationContext") &&
                            contextProxy != null) {
                        args[i] = contextProxy;
                    } else {
                        args[i] = deserializeValue(sv, context);
                    }
                }
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
                    if (!key.startsWith("__") && !val.canExecute() && !isHostFunction(key)) {
                        updatedBindings.put(key, serializeValue(val));
                    }
                }
                long tBindingsExtracted = System.currentTimeMillis();

                // Phase G: Response build
                long callbackMs = callbackClient != null ? callbackClient.getCallbackTimeMs() : 0;
                byte[] responseBytes = ExecuteCallbackResponse.newBuilder()
                        .setSuccess(true)
                        .setElapsedMs(tBindingsExtracted - startTime)
                        .setResult(serializeValue(result))
                        .putAllUpdatedBindings(updatedBindings)
                        .build()
                        .toByteArray();
                long tResponseBuilt = System.currentTimeMillis();

                long elapsed = tResponseBuilt - startTime;
                long pureProcessingMs = elapsed - callbackMs;
                log.info(
                        "[Sidecar] Time breakdown (streaming): totalElapsed={}ms, pureProcessing={}ms, callbackRoundTrips={}ms",
                        elapsed, pureProcessingMs, callbackMs);
                log.info("[Sidecar] Phase timing (streaming): requestParse={}ms, contextSetup={}ms, " +
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

        // Register stubs for all host functions from the request
        for (HostFunctionDefinition funcDef : hostFunctions) {
            String funcName = funcDef.getName();
            log.info("[Sidecar] Registering host function stub: {}", funcName);
            bindings.putMember(funcName, new HostFunctionStub(funcName, callbackClient));
            registeredFunctions.add(funcName);
        }

        // Store for isHostFunction checks
        currentRegisteredFunctions.set(registeredFunctions);
        log.info("[Sidecar] Registered {} host function stubs", registeredFunctions.size());
    }

    // Thread-local to track registered functions for current request
    private static final ThreadLocal<java.util.Set<String>> currentRegisteredFunctions = ThreadLocal
            .withInitial(java.util.HashSet::new);

    private void registerHostFunctionStubs(Value bindings, HostCallbackClient callbackClient) {
        // Legacy method for evaluate requests - register default set
        java.util.Set<String> defaultFunctions = new java.util.HashSet<>();
        String[] defaultFuncNames = { "executeStep", "sendError", "fail", "showPrompt",
                "loadLocalLibrary", "getSecretByName", "selectAcrFrom" };

        for (String funcName : defaultFuncNames) {
            bindings.putMember(funcName, new HostFunctionStub(funcName, callbackClient));
            defaultFunctions.add(funcName);
        }
        bindings.putMember("Log", new LoggerProxy());
        defaultFunctions.add("Log");

        currentRegisteredFunctions.set(defaultFunctions);
    }

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
        log.info(
                "[Sidecar] Creating DYNAMIC context proxy with data: username={}, userStoreDomain={}, tenantDomain={}, step={}",
                data.getUsername(), data.getUserStoreDomain(), data.getTenantDomain(), data.getCurrentStep());

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
                marker.putEntries("__isContextProxy",
                        SerializedValue.newBuilder().setBoolValue(true).build());
                marker.putEntries("__proxyType",
                        SerializedValue.newBuilder().setStringValue(proxy.getProxyType()).build());
                marker.putEntries("__basePath",
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
            default:
                return null;
        }
    }

    // ============ Inner Classes ============

    /**
     * Stub for host functions that calls back to IS.
     */
    private static class HostFunctionStub implements ProxyExecutable {
        private final String functionName;
        private final HostCallbackClient callbackClient;

        HostFunctionStub(String functionName, HostCallbackClient callbackClient) {
            this.functionName = functionName;
            this.callbackClient = callbackClient;
            log.info("[Sidecar-Stub] Created HostFunctionStub for: {}, callbackClient: {}",
                    functionName, callbackClient != null ? "available" : "null");
        }

        @Override
        public Object execute(Value... args) {
            System.out.println(
                    "[DEBUG-SIDECAR] Host function '" + functionName + "' called with " + args.length + " args");
            log.info("[Sidecar-Stub] Host function '{}' called with {} args", functionName, args.length);
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
                    log.info("[Sidecar-Stub] Converted arg[{}] to: {}", i,
                            javaArgs[i] != null ? javaArgs[i].getClass().getSimpleName() : "null");
                }

                System.out.println("[DEBUG-SIDECAR] Invoking callback to IS for '" + functionName + "'");
                log.info("[Sidecar-Stub] Invoking callback to IS for '{}' with {} args", functionName, javaArgs.length);
                Object result = callbackClient.invokeHostFunction(functionName, javaArgs);
                System.out.println("[DEBUG-SIDECAR] Callback returned: "
                        + (result != null ? result.getClass().getSimpleName() : "null"));
                log.info("[Sidecar-Stub] Callback returned: {}",
                        result != null ? result.getClass().getSimpleName() : "null");

                // Check if the result is a proxy marker from a complex host function return
                if (result instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resultMap = (Map<String, Object>) result;
                    if (Boolean.TRUE.equals(resultMap.get("__isHostRef"))) {
                        String proxyType = (String) resultMap.get("__proxyType");
                        String referenceId = (String) resultMap.get("__referenceId");
                        String basePath = "__hostref__::" + referenceId;
                        log.info("[Sidecar-Stub] Creating DynamicContextProxy for host function return: " +
                                "type={}, refId={}, basePath={}", proxyType, referenceId, basePath);
                        return new DynamicContextProxy(
                                callbackClient.getSessionId(), callbackClient, proxyType, basePath);
                    }
                }

                return result;
            } catch (Exception e) {
                System.out.println("[DEBUG-SIDECAR] ERROR: " + e.getMessage());
                log.error("[Sidecar-Stub] Error calling host function: " + functionName, e);
                throw new RuntimeException("Host function call failed: " + e.getMessage(), e);
            }
        }

        private Object convertToJava(Value val) {
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
                String source = null;
                // First try getSourceLocation() - works for top-level named functions.
                try {
                    if (val.getSourceLocation() != null &&
                            val.getSourceLocation().getCharacters() != null) {
                        source = val.getSourceLocation().getCharacters().toString();
                    }
                } catch (Exception e) {
                    log.debug("[Sidecar-Stub] Could not get source location for function: {}", e.getMessage());
                }
                // If getSourceLocation() failed, use toString() which calls
                // Function.prototype.toString().
                // For GraalJS, this returns the full function definition including body.
                if (source == null || source.isEmpty()) {
                    try {
                        source = val.toString();
                        // val.toString() for functions returns the full source like:
                        // "function(context) { ... }" or "(context) => { ... }"
                        log.info("[Sidecar-Stub] Using toString() for function, got: {}...",
                                source.substring(0, Math.min(80, source.length())));
                    } catch (Exception e) {
                        log.warn("[Sidecar-Stub] Could not get function toString(): {}", e.getMessage());
                    }
                } else {
                    log.info("[Sidecar-Stub] Extracted function source via getSourceLocation: {}...",
                            source.substring(0, Math.min(80, source.length())));
                }
                if (source != null && !source.isEmpty() &&
                        (source.contains("function") || source.contains("=>"))) {
                    return source;
                } else {
                    log.error("[Sidecar-Stub] Could not extract valid function source. Got: {}", source);
                    // Return whatever we have, even if it's not ideal.
                    return source != null ? source : "function(){}";
                }
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
                    marker.put("__isContextProxy", true);
                    marker.put("__proxyType", proxy.getProxyType());
                    marker.put("__basePath", proxy.getBasePath());
                    System.out.println("[DEBUG-SIDECAR] Converting DynamicContextProxy to marker: type=" +
                            proxy.getProxyType() + ", basePath=" + proxy.getBasePath());
                    log.info("[Sidecar-Stub] Converting DynamicContextProxy to marker: type={}, basePath={}",
                            proxy.getProxyType(), proxy.getBasePath());
                    return marker;
                }
            }
            if (val.hasMembers()) {
                Map<String, Object> map = new HashMap<>();
                Set<String> memberKeys = val.getMemberKeys();
                System.out.println(
                        "[DEBUG-SIDECAR] Converting object with " + memberKeys.size() + " members: " + memberKeys);
                log.info("[Sidecar-Stub] Converting object with {} members: {}",
                        memberKeys.size(), memberKeys);
                for (String key : memberKeys) {
                    Value memberVal = val.getMember(key);
                    System.out.println("[DEBUG-SIDECAR] Member '" + key + "': isNull="
                            + (memberVal == null || memberVal.isNull()) +
                            ", canExecute=" + (memberVal != null && memberVal.canExecute()) +
                            ", hasMembers=" + (memberVal != null && memberVal.hasMembers()));
                    log.info("[Sidecar-Stub] Member '{}': isNull={}, canExecute={}, hasMembers={}, hasArrayElements={}",
                            key,
                            memberVal == null || memberVal.isNull(),
                            memberVal != null && memberVal.canExecute(),
                            memberVal != null && memberVal.hasMembers(),
                            memberVal != null && memberVal.hasArrayElements());
                    Object converted = convertToJava(memberVal);
                    System.out.println("[DEBUG-SIDECAR] Member '" + key + "' converted to type: " +
                            (converted != null ? converted.getClass().getSimpleName() : "null"));
                    log.info("[Sidecar-Stub] Member '{}' converted to: {} (type: {})",
                            key,
                            converted instanceof String
                                    ? ((String) converted).substring(0, Math.min(60, ((String) converted).length()))
                                            + "..."
                                    : converted,
                            converted != null ? converted.getClass().getSimpleName() : "null");
                    map.put(key, converted);
                }
                System.out.println("[DEBUG-SIDECAR] Final map has " + map.size() + " entries: " + map.keySet());
                log.info("[Sidecar-Stub] Final map has {} entries: {}", map.size(), map.keySet());
                return map;
            }
            return val.toString();
        }
    }

    /**
     * Logger proxy for JS Log object.
     */
    private static class LoggerProxy implements ProxyObject {
        @Override
        public Object getMember(String key) {
            return (ProxyExecutable) args -> {
                String message = args.length > 0 ? args[0].toString() : "";
                switch (key) {
                    case "info":
                        log.info("[JS] {}", message);
                        break;
                    case "warn":
                        log.warn("[JS] {}", message);
                        break;
                    case "error":
                        log.error("[JS] {}", message);
                        break;
                    case "debug":
                        log.debug("[JS] {}", message);
                        break;
                    default:
                        log.info("[JS-{}] {}", key, message);
                }
                return null;
            };
        }

        @Override
        public Object getMemberKeys() {
            return new String[] { "info", "warn", "error", "debug" };
        }

        @Override
        public boolean hasMember(String key) {
            return true;
        }

        @Override
        public void putMember(String key, Value value) {
        }
    }

    /**
     * Dynamic proxy that calls back to IS for every property access.
     * This ensures the sidecar context behaves identically to the local context.
     */
    private static class DynamicContextProxy implements ProxyObject {

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
                log.info("[DynamicContextProxy] getMember '{}', full path: {}", key, propertyPath);

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
                    log.info("[DynamicContextProxy] Creating nested proxy for '{}', type: {}, keys: {}",
                            key, response.getProxyType(),
                            proxyMemberKeys != null ? proxyMemberKeys.length : "none");
                    value = new DynamicContextProxy(
                            sessionId, callbackClient,
                            response.getProxyType(), propertyPath, proxyMemberKeys);
                } else {
                    // Deserialize the value
                    value = deserializeValue(response.getValue());
                    log.info("[DynamicContextProxy] Deserialized '{}' = {}", key,
                            value != null ? value.getClass().getSimpleName() : "null");
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
            log.info("[DynamicContextProxy] getMemberKeys() called for path: {}", basePath);

            if (memberKeys != null) {
                return memberKeys;
            }

            try {
                // Get member keys from IS - use special path "__keys__"
                String propertyPath = basePath.isEmpty() ? "__keys__" : basePath + "::__keys__";
                ContextPropertyResponse response = callbackClient.getContextProperty(propertyPath, proxyType);

                if (response.getSuccess() && response.getMemberKeysCount() > 0) {
                    memberKeys = response.getMemberKeysList().toArray(new String[0]);
                    log.info("[DynamicContextProxy] Retrieved {} member keys: {}", memberKeys.length,
                            java.util.Arrays.toString(memberKeys));
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
                log.info("[DynamicContextProxy] putMember '{}' = {}", propertyPath,
                        value != null ? value.toString() : "null");

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

    /**
     * Handle a host function request (placeholder implementation).
     * These requests are typically handled by the callback mechanism.
     *
     * @param requestBytes Protobuf-encoded HostFunctionRequest.
     * @return Protobuf-encoded HostFunctionResponse.
     */
    public byte[] handleHostFunction(byte[] requestBytes) throws java.io.IOException {
        HostFunctionRequest request = HostFunctionRequest.parseFrom(requestBytes);
        log.info("[Sidecar] handleHostFunction - session: {}, function: {}, args: {}",
                request.getSessionId(), request.getFunctionName(), request.getArgumentsCount());

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
        log.info("[Sidecar] handleContextProperty - session: {}, property: {}, proxyType: {}",
                request.getSessionId(), request.getPropertyPath(), request.getProxyType());

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
        log.info("[Sidecar] handleContextPropertySet - session: {}, property: {}, value: {}",
                request.getSessionId(), request.getPropertyPath(), request.getValue());

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
