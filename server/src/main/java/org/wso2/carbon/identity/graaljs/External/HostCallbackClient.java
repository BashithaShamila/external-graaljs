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

import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse;
import org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest;
import org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse;
import org.wso2.carbon.identity.graaljs.proto.SerializedValue;
import org.wso2.carbon.identity.graaljs.proto.StreamMessage;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bidirectional gRPC stream-backed callback client for a single script
 * execution lifecycle.
 * <p>
 * Previously this responsibility was split across {@code HostCallbackClient}
 * (high-level API + timing) and {@code StreamingCallbackClient} (transport-
 * level send-and-await). gRPC is now the only transport, so the indirection
 * is gone — both concerns live here.
 * <p>
 * <b>Thread model</b>
 * <ul>
 *   <li>The executor thread running the GraalJS script calls
 *       {@link #invokeHostFunction}, {@link #getContextProperty} and
 *       {@link #setContextProperty}. These send a request on the outbound
 *       stream and block on a {@link CompletableFuture} until IS responds.</li>
 *   <li>The gRPC event thread that delivers IS-originated messages on the
 *       same bidi stream calls {@link #deliverResponse} to complete the
 *       pending future, or {@link #onStreamError} / {@link #onStreamCompleted}
 *       to abort the blocked JS thread.</li>
 * </ul>
 * <p>
 * GraalJS is single-threaded per session, so at most one callback is in flight
 * at any time. A single {@link AtomicReference} of {@link CompletableFuture}
 * is therefore sufficient.
 * <p>
 * <b>Edge cases preserved (do not regress)</b>
 * <ul>
 *   <li>{@code pendingResponse.set} runs <i>before</i> {@code outbound.onNext}
 *       — IS may answer immediately; the receiver must already see the
 *       future.</li>
 *   <li>{@code synchronized(streamLock)} guards every {@code outbound.onNext}
 *       — gRPC's {@code StreamObserver} is not concurrency-safe, and the
 *       transport's response sender shares this lock.</li>
 *   <li>{@code compareAndSet} on cleanup — prevents wiping a future a
 *       <i>subsequent</i> callback already published, in race scenarios with
 *       out-of-order gRPC delivery.</li>
 *   <li>{@link #onStreamCompleted} completes the pending future
 *       exceptionally — IS owns the request deadline and signals timeout via
 *       {@code onCompleted}; without unblocking here the JS thread would hang
 *       forever.</li>
 *   <li>{@link InterruptedException} restores the interrupt flag.</li>
 *   <li>{@link ExecutionException} is unwrapped (cause carries the real
 *       failure).</li>
 *   <li>Response payload-case mismatch is rejected — prevents silent type
 *       confusion if message routing ever drifts.</li>
 *   <li>Callback-time accumulation matches the legacy contract — a failed
 *       round-trip (IOException out of {@code sendAndAwait}) is <i>not</i>
 *       added to {@link #totalCallbackTimeMs}, just like the previous
 *       two-class split where the host-side timer never closed when the
 *       transport raised.</li>
 * </ul>
 */
public class HostCallbackClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(HostCallbackClient.class);

    private final String sessionId;
    private final StreamObserver<StreamMessage> outbound;
    private final Object streamLock;

    /**
     * Holds the future the executor thread is waiting on. The gRPC event
     * thread completes it via {@link #deliverResponse} / {@link #onStreamError}
     * / {@link #onStreamCompleted}.
     */
    private final AtomicReference<CompletableFuture<StreamMessage>> pendingResponse = new AtomicReference<>();

    /**
     * Cumulative wall-clock time (ms) spent waiting for IS callbacks during a
     * single request. Lets {@link JsEngineServiceImpl} decompose total elapsed
     * into pure-JS vs callback-roundtrip time. Reset by the engine at the
     * start of each request via {@link #resetCallbackTimeMs}.
     */
    private final AtomicLong totalCallbackTimeMs = new AtomicLong(0);

    public HostCallbackClient(StreamObserver<StreamMessage> outbound, Object streamLock, String sessionId) {
        this.outbound = outbound;
        this.streamLock = streamLock;
        this.sessionId = sessionId;
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] Created for session: {}", sessionId);
        }
    }

    /**
     * Get the session ID for this callback client.
     */
    public String getSessionId() {
        return sessionId;
    }

    // ============ Engine-facing API (executor thread) ============

    /**
     * Invoke a host function on the IS side. Serialises arguments, sends a
     * {@link HostFunctionRequest} on the bidi stream and blocks until IS
     * responds with the matching {@link HostFunctionResponse}.
     *
     * @param functionName Name of the host function (e.g. "executeStep",
     *                     "sendError").
     * @param arguments    Arguments to pass.
     * @return Deserialised result from the host function.
     * @throws IOException If the stream fails or the host function reports
     *                     {@code success=false}.
     */
    public Object invokeHostFunction(String functionName, Object... arguments) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] invokeHostFunction '{}' with {} args, session: {}",
                    functionName, arguments.length, sessionId);
        }

        // ---- Build request (formerly HostCallbackClient.invokeHostFunction) ----
        HostFunctionRequest.Builder requestBuilder = HostFunctionRequest.newBuilder()
                .setSessionId(sessionId)
                .setFunctionName(functionName);

        for (int i = 0; i < arguments.length; i++) {
            log.debug("[HostCallbackClient] Serializing arg[{}]: {}", i,
                    arguments[i] != null ? arguments[i].getClass().getSimpleName() : "null");
            requestBuilder.addArguments(ValueSerializationUtils.serialize(arguments[i]));
        }
        HostFunctionRequest request = requestBuilder.build();

        // ---- Stream send + await (formerly StreamingCallbackClient.invokeHostFunction) ----
        if (log.isDebugEnabled()) {
            log.debug("[StreamingCallback] invokeHostFunction: " + functionName +
                    ", session: " + sessionId);
        }
        long t0 = System.currentTimeMillis();
        System.out.println("[PERF] [" + t0 + "] External HOST_FN_CALLBACK_START session=" +
                sessionId + " function=" + functionName +
                " startTs=" + t0);

        StreamMessage streamMsg = StreamMessage.newBuilder()
                .setSessionId(sessionId)
                .setHostFunctionRequest(request)
                .build();

        long cbStart = System.currentTimeMillis();
        StreamMessage response;
        try {
            response = sendAndAwait(streamMsg);
        } catch (IOException e) {
            // Legacy parity: PERF error logged, but callback time NOT
            // accumulated for failed round-trips (matches the pre-merge
            // behaviour where the wrapper's addAndGet was skipped on throw).
            long tErr = System.currentTimeMillis();
            System.out.println("[PERF] [" + tErr +
                    "] External HOST_FN_CALLBACK_ERROR session=" + sessionId +
                    " function=" + functionName +
                    " error=" + e.getMessage() +
                    " startTs=" + t0 + " errorTs=" + tErr +
                    " elapsedMs=" + (tErr - t0));
            throw e;
        }
        long t2 = System.currentTimeMillis();
        long cbElapsed = t2 - cbStart;
        totalCallbackTimeMs.addAndGet(cbElapsed);

        if (response.getPayloadCase() != StreamMessage.PayloadCase.HOST_FUNCTION_RESPONSE) {
            throw new IOException("Unexpected response type: " + response.getPayloadCase());
        }
        HostFunctionResponse hfResponse = response.getHostFunctionResponse();

        System.out.println("[PERF] [" + t2 + "] External HOST_FN_CALLBACK_RESPONSE session=" +
                sessionId + " function=" + functionName +
                " success=" + hfResponse.getSuccess() +
                " startTs=" + t0 + " responseTs=" + t2 +
                " totalRoundtripMs=" + (t2 - t0));
        if (log.isDebugEnabled()) {
            log.debug("[StreamingCallback] Received HostFunctionResponse, success: " +
                    hfResponse.getSuccess());
            log.debug("[HostCallbackClient] invokeHostFunction '{}' round-trip: {}ms", functionName, cbElapsed);
        }

        if (!hfResponse.getSuccess()) {
            log.error("[HostCallbackClient] Host function failed: {}", hfResponse.getErrorMessage());
            throw new IOException("Host function failed: " + hfResponse.getErrorMessage());
        }

        Object result = ValueSerializationUtils.deserialize(hfResponse.getResult());
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] Returning result: {}",
                    result != null ? result.getClass().getSimpleName() : "null");
        }
        return result;
    }

    /**
     * Read a context property from IS. Used by {@code DynamicContextProxy} for
     * lazy property access.
     *
     * @param propertyPath Path to the property (e.g. "request",
     *                     "request.params").
     * @param proxyType    Type of the proxy object.
     * @return The {@link ContextPropertyResponse} from IS (caller inspects
     *         success / value / proxy info).
     */
    public ContextPropertyResponse getContextProperty(String propertyPath, String proxyType) throws IOException {
        log.debug("[HostCallbackClient] getContextProperty '{}' (type: {}), session: {}",
                propertyPath, proxyType, sessionId);

        ContextPropertyRequest request = ContextPropertyRequest.newBuilder()
                .setSessionId(sessionId)
                .setPropertyPath(propertyPath)
                .setProxyType(proxyType)
                .build();

        if (log.isDebugEnabled()) {
            log.debug("[StreamingCallback] getContextProperty: " + propertyPath +
                    ", session: " + sessionId);
        }
        long t0 = System.currentTimeMillis();
        System.out.println("[PERF] [" + t0 + "] External CTX_PROP_CALLBACK_START session=" +
                sessionId + " path=" + propertyPath +
                " startTs=" + t0);

        StreamMessage streamMsg = StreamMessage.newBuilder()
                .setSessionId(sessionId)
                .setContextPropertyRequest(request)
                .build();

        long cbStart = System.currentTimeMillis();
        StreamMessage response;
        try {
            response = sendAndAwait(streamMsg);
        } catch (IOException e) {
            long tErr = System.currentTimeMillis();
            System.out.println("[PERF] [" + tErr +
                    "] External CTX_PROP_CALLBACK_ERROR session=" + sessionId +
                    " path=" + propertyPath +
                    " error=" + e.getMessage() +
                    " startTs=" + t0 + " errorTs=" + tErr +
                    " elapsedMs=" + (tErr - t0));
            throw e;
        }
        long t2 = System.currentTimeMillis();
        totalCallbackTimeMs.addAndGet(t2 - cbStart);

        if (response.getPayloadCase() != StreamMessage.PayloadCase.CONTEXT_PROPERTY_RESPONSE) {
            throw new IOException("Unexpected response type: " + response.getPayloadCase());
        }
        ContextPropertyResponse cpResponse = response.getContextPropertyResponse();

        System.out.println("[PERF] [" + t2 + "] External CTX_PROP_CALLBACK_RESPONSE session=" +
                sessionId + " path=" + propertyPath +
                " success=" + cpResponse.getSuccess() +
                " startTs=" + t0 + " responseTs=" + t2 +
                " totalRoundtripMs=" + (t2 - t0));
        if (log.isDebugEnabled()) {
            log.debug("[StreamingCallback] Received ContextPropertyResponse, success: " +
                    cpResponse.getSuccess());
        }
        return cpResponse;
    }

    /**
     * Write a context property back to IS. Used by script property
     * modifications.
     *
     * @param propertyPath Path to the property.
     * @param proxyType    Type of the proxy object.
     * @param value        Value to set.
     * @return The {@link ContextPropertySetResponse} from IS.
     */
    public ContextPropertySetResponse setContextProperty(String propertyPath, String proxyType,
                                                         SerializedValue value) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] setContextProperty '{}' (type: {}), session: {}",
                    propertyPath, proxyType, sessionId);
        }

        ContextPropertySetRequest request = ContextPropertySetRequest.newBuilder()
                .setSessionId(sessionId)
                .setPropertyPath(propertyPath)
                .setValue(value)
                .build();

        if (log.isDebugEnabled()) {
            log.debug("[StreamingCallback] setContextProperty: " + propertyPath +
                    ", session: " + sessionId);
        }
        long t0 = System.currentTimeMillis();
        System.out.println("[PERF] [" + t0 + "] External CTX_PROP_SET_CALLBACK_START session=" +
                sessionId + " path=" + propertyPath +
                " startTs=" + t0);

        StreamMessage streamMsg = StreamMessage.newBuilder()
                .setSessionId(sessionId)
                .setContextPropertySetRequest(request)
                .build();

        long cbStart = System.currentTimeMillis();
        StreamMessage response;
        try {
            response = sendAndAwait(streamMsg);
        } catch (IOException e) {
            long tErr = System.currentTimeMillis();
            System.out.println("[PERF] [" + tErr +
                    "] External CTX_PROP_SET_CALLBACK_ERROR session=" + sessionId +
                    " path=" + propertyPath +
                    " error=" + e.getMessage() +
                    " startTs=" + t0 + " errorTs=" + tErr +
                    " elapsedMs=" + (tErr - t0));
            throw e;
        }
        long t2 = System.currentTimeMillis();
        totalCallbackTimeMs.addAndGet(t2 - cbStart);

        if (response.getPayloadCase() != StreamMessage.PayloadCase.CONTEXT_PROPERTY_SET_RESPONSE) {
            throw new IOException("Unexpected response type: " + response.getPayloadCase());
        }
        ContextPropertySetResponse cpsResponse = response.getContextPropertySetResponse();

        System.out.println("[PERF] [" + t2 + "] External CTX_PROP_SET_CALLBACK_RESPONSE session=" +
                sessionId + " path=" + propertyPath +
                " success=" + cpsResponse.getSuccess() +
                " startTs=" + t0 + " responseTs=" + t2 +
                " totalRoundtripMs=" + (t2 - t0));
        if (log.isDebugEnabled()) {
            log.debug("[StreamingCallback] Received ContextPropertySetResponse, success: " +
                    cpsResponse.getSuccess());
        }
        return cpsResponse;
    }

    /**
     * Get the cumulative time spent waiting for IS callbacks during this
     * request.
     */
    public long getCallbackTimeMs() {
        return totalCallbackTimeMs.get();
    }

    /**
     * Reset the callback time tracker. The engine calls this at the start of
     * every request because the callback client instance is reused for the
     * lifetime of a single bidi stream.
     */
    public void resetCallbackTimeMs() {
        totalCallbackTimeMs.set(0);
    }

    @Override
    public void close() {
        // Stream lifecycle is owned by the transport (it calls onCompleted /
        // onError on the outbound observer). Nothing to release here, but the
        // method is preserved so engine code that wraps the client in
        // try-with-resources continues to compile and behave identically.
        if (log.isDebugEnabled()) {
            log.debug("[HostCallbackClient] close() — no-op (stream managed by transport)");
        }
    }

    // ============ Transport-facing API (gRPC event thread) ============

    /**
     * Called by the gRPC event thread when a callback response arrives from
     * IS. Completes the pending future so the blocked JS thread can continue.
     */
    public void deliverResponse(StreamMessage message) {
        CompletableFuture<StreamMessage> future = pendingResponse.get();
        if (future != null) {
            if (log.isDebugEnabled()) {
                log.debug("[StreamingCallback] Delivering response type: " + message.getPayloadCase());
            }
            future.complete(message);
        } else {
            log.warn("[StreamingCallback] Received response but no pending future: " +
                    message.getPayloadCase());
        }
    }

    /**
     * Called when the stream encounters an error. Completes any pending
     * future exceptionally so the blocked JS thread unblocks.
     */
    public void onStreamError(Throwable t) {
        CompletableFuture<StreamMessage> future = pendingResponse.get();
        if (future != null) {
            future.completeExceptionally(new IOException("Stream error: " + t.getMessage(), t));
        }
    }

    /**
     * Called when IS closes its half of the bidi stream ({@code onCompleted}).
     * If a callback is pending (the JS thread is blocked on
     * {@link #sendAndAwait}), no response will ever arrive — IS already
     * decided the request is over (typically because its
     * {@code processMessageLoop} deadline fired). Completing the future
     * exceptionally surfaces that as an {@link IOException} on the JS
     * thread, which propagates through GraalVM into the script.
     * <p>
     * This is the primary mechanism that replaces a per-callback timeout:
     * IS owns the deadline; the External never times out independently. A
     * split-brain timeout (External giving up while IS is still processing
     * an async event registration like {@code httpPost}) would surface
     * stale callbacks later.
     */
    public void onStreamCompleted() {
        CompletableFuture<StreamMessage> future = pendingResponse.get();
        if (future != null) {
            future.completeExceptionally(
                    new IOException("Stream closed by IS — no response will arrive for pending callback"));
        }
    }

    // ============ Internal: send-and-await plumbing ============

    /**
     * Send a {@link StreamMessage} on the outbound stream and block until IS
     * responds. Handles the future lifecycle, synchronised send, and the
     * compareAndSet cleanup that prevents wiping a future a subsequent
     * callback already published.
     * <p>
     * No timeout is applied here — timeout ownership belongs to IS, which
     * enforces the overall request deadline via {@code processMessageLoop}.
     * When IS times out (or closes the stream), the gRPC
     * {@code onError() / onCompleted()} flows through
     * {@link #onStreamError} / {@link #onStreamCompleted} and completes
     * this future exceptionally, unblocking the JS thread. This avoids a
     * split-brain where the External times out independently while IS is
     * still processing async events.
     */
    private StreamMessage sendAndAwait(StreamMessage streamMsg) throws IOException {
        CompletableFuture<StreamMessage> future = new CompletableFuture<>();
        // Publish BEFORE sending: IS may answer synchronously, and the
        // gRPC event thread must already see the future.
        pendingResponse.set(future);

        try {
            synchronized (streamLock) {
                outbound.onNext(streamMsg);
            }
            if (log.isDebugEnabled()) {
                log.debug("[StreamingCallback] Sent {} on stream", streamMsg.getPayloadCase());
            }

            return future.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Callback interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("Callback failed: " + e.getCause().getMessage(), e.getCause());
        } finally {
            // compareAndSet — only clear if it's still OUR future. Prevents
            // clearing a future that was set by a subsequent callback in edge
            // cases with out-of-order gRPC delivery.
            pendingResponse.compareAndSet(future, null);
        }
    }
}
