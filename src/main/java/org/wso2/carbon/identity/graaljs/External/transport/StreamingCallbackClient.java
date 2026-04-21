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

package org.wso2.carbon.identity.graaljs.External.transport;

import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse;
import org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest;
import org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse;
import org.wso2.carbon.identity.graaljs.proto.StreamMessage;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CallbackClient implementation that uses the bidirectional gRPC stream for callbacks.
 * Instead of creating a separate gRPC connection back to IS, this sends callback requests
 * (host function invocations, context property access) on the same bidi stream.
 * <p>
 * Thread model:
 * - JS thread (in engine service) calls invokeHostFunction() / getContextProperty()
 * - Request is sent on the outbound stream (synchronized)
 * - JS thread blocks on CompletableFuture
 * - gRPC event thread receives response via deliverResponse() and completes the future
 * - JS thread unblocks and returns result
 * <p>
 * Since GraalJS is single-threaded, at most one callback is pending at any time.
 * A single AtomicReference<CompletableFuture> suffices.
 */
public class StreamingCallbackClient implements CallbackClient {

    private static final Logger log = LoggerFactory.getLogger(StreamingCallbackClient.class);

    private final StreamObserver<StreamMessage> outbound;
    private final Object streamLock;
    private final AtomicReference<CompletableFuture<StreamMessage>> pendingResponse = new AtomicReference<>();

    public StreamingCallbackClient(StreamObserver<StreamMessage> outbound, Object streamLock) {
        this.outbound = outbound;
        this.streamLock = streamLock;
        if (log.isDebugEnabled()) {
            log.debug("[StreamingCallback] Created streaming callback client");
        }
    }

    /**
     * Called by the gRPC event thread when a callback response arrives from IS.
     * Completes the pending future so the blocked JS thread can continue.
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
     * Called when the stream encounters an error.
     * Completes any pending future exceptionally so blocked threads can unblock.
     */
    public void onStreamError(Throwable t) {
        CompletableFuture<StreamMessage> future = pendingResponse.get();
        if (future != null) {
            future.completeExceptionally(new IOException("Stream error: " + t.getMessage(), t));
        }
    }

    /**
     * Called when IS closes its half of the bidirectional stream (onCompleted).
     * If a callback is pending (JS thread blocked on future.get()), this means IS
     * will not send any more messages — the pending callback will never get a response.
     * Complete the future exceptionally so the JS thread unblocks.
     * <p>
     * This is the primary mechanism that replaces the old per-callback timeout:
     * IS owns the deadline (processMessageLoop's deadlineNanos). When IS times out,
     * it closes the stream, which flows here and terminates the External's wait.
     */
    public void onStreamCompleted() {
        CompletableFuture<StreamMessage> future = pendingResponse.get();
        if (future != null) {
            future.completeExceptionally(
                    new IOException("Stream closed by IS — no response will arrive for pending callback"));
        }
    }

    /**
     * Send a StreamMessage on the outbound stream and block until IS responds.
     * Handles the CompletableFuture lifecycle, synchronized send, and compareAndSet
     * cleanup to prevent clearing a future set by a subsequent callback.
     * <p>
     * No timeout is applied here — timeout ownership belongs to IS, which enforces
     * the overall request deadline via processMessageLoop's deadlineNanos. When IS
     * times out or closes the stream, the gRPC onError()/onCompleted() callback
     * completes this future exceptionally via {@link #onStreamError(Throwable)},
     * unblocking the JS thread. This avoids a split-brain where the External
     * times out independently while IS is still processing (e.g., registering
     * async events for httpPost), which would cause stale callbacks to fire later.
     *
     * @param streamMsg The message to send.
     * @return The response StreamMessage from IS.
     * @throws IOException On interruption, stream error, or execution failure.
     */
    private StreamMessage sendAndAwait(StreamMessage streamMsg) throws IOException {
        CompletableFuture<StreamMessage> future = new CompletableFuture<>();
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
            // Use compareAndSet to only clear if it's still OUR future.
            // Prevents clearing a future that was set by a subsequent callback
            // in edge cases with out-of-order gRPC message delivery.
            pendingResponse.compareAndSet(future, null);
        }
    }

    @Override
    public HostFunctionResponse invokeHostFunction(HostFunctionRequest request) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("[StreamingCallback] invokeHostFunction: " + request.getFunctionName() +
                    ", session: " + request.getSessionId());
        }
        long t0 = System.currentTimeMillis();
        System.out.println("[PERF] [" + t0 + "] External HOST_FN_CALLBACK_START session=" +
                request.getSessionId() + " function=" + request.getFunctionName() +
                " startTs=" + t0);

        StreamMessage streamMsg = StreamMessage.newBuilder()
                .setSessionId(request.getSessionId())
                .setHostFunctionRequest(request)
                .build();

        StreamMessage response;
        try {
            response = sendAndAwait(streamMsg);
        } catch (IOException e) {
            long tErr = System.currentTimeMillis();
            System.out.println("[PERF] [" + tErr +
                    "] External HOST_FN_CALLBACK_ERROR session=" + request.getSessionId() +
                    " function=" + request.getFunctionName() +
                    " error=" + e.getMessage() +
                    " startTs=" + t0 + " errorTs=" + tErr +
                    " elapsedMs=" + (tErr - t0));
            throw e;
        }
        long t2 = System.currentTimeMillis();

        if (response.getPayloadCase() == StreamMessage.PayloadCase.HOST_FUNCTION_RESPONSE) {
            HostFunctionResponse hfResponse = response.getHostFunctionResponse();
            System.out.println("[PERF] [" + t2 + "] External HOST_FN_CALLBACK_RESPONSE session=" +
                    request.getSessionId() + " function=" + request.getFunctionName() +
                    " success=" + hfResponse.getSuccess() +
                    " startTs=" + t0 + " responseTs=" + t2 +
                    " totalRoundtripMs=" + (t2 - t0));
            if (log.isDebugEnabled()) {
                log.debug("[StreamingCallback] Received HostFunctionResponse, success: " +
                        hfResponse.getSuccess());
            }
            return hfResponse;
        } else {
            throw new IOException("Unexpected response type: " + response.getPayloadCase());
        }
    }

    @Override
    public ContextPropertyResponse getContextProperty(ContextPropertyRequest request) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("[StreamingCallback] getContextProperty: " + request.getPropertyPath() +
                    ", session: " + request.getSessionId());
        }
        long t0 = System.currentTimeMillis();
        System.out.println("[PERF] [" + t0 + "] External CTX_PROP_CALLBACK_START session=" +
                request.getSessionId() + " path=" + request.getPropertyPath() +
                " startTs=" + t0);

        StreamMessage streamMsg = StreamMessage.newBuilder()
                .setSessionId(request.getSessionId())
                .setContextPropertyRequest(request)
                .build();

        StreamMessage response;
        try {
            response = sendAndAwait(streamMsg);
        } catch (IOException e) {
            long tErr = System.currentTimeMillis();
            System.out.println("[PERF] [" + tErr +
                    "] External CTX_PROP_CALLBACK_ERROR session=" + request.getSessionId() +
                    " path=" + request.getPropertyPath() +
                    " error=" + e.getMessage() +
                    " startTs=" + t0 + " errorTs=" + tErr +
                    " elapsedMs=" + (tErr - t0));
            throw e;
        }
        long t2 = System.currentTimeMillis();

        if (response.getPayloadCase() == StreamMessage.PayloadCase.CONTEXT_PROPERTY_RESPONSE) {
            ContextPropertyResponse cpResponse = response.getContextPropertyResponse();
            System.out.println("[PERF] [" + t2 + "] External CTX_PROP_CALLBACK_RESPONSE session=" +
                    request.getSessionId() + " path=" + request.getPropertyPath() +
                    " success=" + cpResponse.getSuccess() +
                    " startTs=" + t0 + " responseTs=" + t2 +
                    " totalRoundtripMs=" + (t2 - t0));
            if (log.isDebugEnabled()) {
                log.debug("[StreamingCallback] Received ContextPropertyResponse, success: " +
                        cpResponse.getSuccess());
            }
            return cpResponse;
        } else {
            throw new IOException("Unexpected response type: " + response.getPayloadCase());
        }
    }

    @Override
    public ContextPropertySetResponse setContextProperty(ContextPropertySetRequest request) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("[StreamingCallback] setContextProperty: " + request.getPropertyPath() +
                    ", session: " + request.getSessionId());
        }
        long t0 = System.currentTimeMillis();
        System.out.println("[PERF] [" + t0 + "] External CTX_PROP_SET_CALLBACK_START session=" +
                request.getSessionId() + " path=" + request.getPropertyPath() +
                " startTs=" + t0);

        StreamMessage streamMsg = StreamMessage.newBuilder()
                .setSessionId(request.getSessionId())
                .setContextPropertySetRequest(request)
                .build();

        StreamMessage response;
        try {
            response = sendAndAwait(streamMsg);
        } catch (IOException e) {
            long tErr = System.currentTimeMillis();
            System.out.println("[PERF] [" + tErr +
                    "] External CTX_PROP_SET_CALLBACK_ERROR session=" + request.getSessionId() +
                    " path=" + request.getPropertyPath() +
                    " error=" + e.getMessage() +
                    " startTs=" + t0 + " errorTs=" + tErr +
                    " elapsedMs=" + (tErr - t0));
            throw e;
        }
        long t2 = System.currentTimeMillis();

        if (response.getPayloadCase() == StreamMessage.PayloadCase.CONTEXT_PROPERTY_SET_RESPONSE) {
            ContextPropertySetResponse cpsResponse = response.getContextPropertySetResponse();
            System.out.println("[PERF] [" + t2 + "] External CTX_PROP_SET_CALLBACK_RESPONSE session=" +
                    request.getSessionId() + " path=" + request.getPropertyPath() +
                    " success=" + cpsResponse.getSuccess() +
                    " startTs=" + t0 + " responseTs=" + t2 +
                    " totalRoundtripMs=" + (t2 - t0));
            if (log.isDebugEnabled()) {
                log.debug("[StreamingCallback] Received ContextPropertySetResponse, success: " +
                        cpsResponse.getSuccess());
            }
            return cpsResponse;
        } else {
            throw new IOException("Unexpected response type: " + response.getPayloadCase());
        }
    }

    @Override
    public void connect() throws IOException {
        // No-op: stream is already open
        if (log.isDebugEnabled()) {
            log.debug("[StreamingCallback] connect() - no-op (stream already open)");
        }
    }

    @Override
    public boolean isConnected() {
        return true; // Always connected while stream is open
    }

    @Override
    public void close() throws IOException {
        // No-op: stream lifecycle is managed by the server transport
        if (log.isDebugEnabled()) {
            log.debug("[StreamingCallback] close() - no-op (stream managed by server)");
        }
    }
}
