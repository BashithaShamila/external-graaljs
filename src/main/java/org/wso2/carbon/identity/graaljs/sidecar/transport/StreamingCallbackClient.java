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

package org.wso2.carbon.identity.graaljs.sidecar.transport;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private static final long CALLBACK_TIMEOUT_SECONDS = 5;

    private final StreamObserver<StreamMessage> outbound;
    private final Object streamLock;
    private final AtomicReference<CompletableFuture<StreamMessage>> pendingResponse = new AtomicReference<>();

    public StreamingCallbackClient(StreamObserver<StreamMessage> outbound, Object streamLock) {
        this.outbound = outbound;
        this.streamLock = streamLock;
        log.info("[StreamingCallback] Created streaming callback client");
    }

    /**
     * Called by the gRPC event thread when a callback response arrives from IS.
     * Completes the pending future so the blocked JS thread can continue.
     */
    public void deliverResponse(StreamMessage message) {
        CompletableFuture<StreamMessage> future = pendingResponse.get();
        if (future != null) {
            log.info("[StreamingCallback] Delivering response type: " + message.getPayloadCase());
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

    @Override
    public HostFunctionResponse invokeHostFunction(HostFunctionRequest request) throws IOException {
        log.info("[StreamingCallback] invokeHostFunction: " + request.getFunctionName() +
                ", session: " + request.getSessionId());

        CompletableFuture<StreamMessage> future = new CompletableFuture<>();
        pendingResponse.set(future);

        try {
            // Send host function request on the stream
            StreamMessage streamMsg = StreamMessage.newBuilder()
                    .setSessionId(request.getSessionId())
                    .setHostFunctionRequest(request)
                    .build();

            synchronized (streamLock) {
                outbound.onNext(streamMsg);
            }
            log.info("[StreamingCallback] Sent HostFunctionRequest on stream");

            // Block until IS responds
            StreamMessage response = future.get(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (response.getPayloadCase() == StreamMessage.PayloadCase.HOST_FUNCTION_RESPONSE) {
                HostFunctionResponse hfResponse = response.getHostFunctionResponse();
                log.info("[StreamingCallback] Received HostFunctionResponse, success: " +
                        hfResponse.getSuccess());
                return hfResponse;
            } else {
                throw new IOException("Unexpected response type: " + response.getPayloadCase());
            }

        } catch (TimeoutException e) {
            throw new IOException("Host function callback timed out after " +
                    CALLBACK_TIMEOUT_SECONDS + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Host function callback interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("Host function callback failed: " + e.getCause().getMessage(),
                    e.getCause());
        } finally {
            pendingResponse.set(null);
        }
    }

    @Override
    public ContextPropertyResponse getContextProperty(ContextPropertyRequest request) throws IOException {
        log.info("[StreamingCallback] getContextProperty: " + request.getPropertyPath() +
                ", session: " + request.getSessionId());

        CompletableFuture<StreamMessage> future = new CompletableFuture<>();
        pendingResponse.set(future);

        try {
            StreamMessage streamMsg = StreamMessage.newBuilder()
                    .setSessionId(request.getSessionId())
                    .setContextPropertyRequest(request)
                    .build();

            synchronized (streamLock) {
                outbound.onNext(streamMsg);
            }
            log.info("[StreamingCallback] Sent ContextPropertyRequest on stream");

            StreamMessage response = future.get(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (response.getPayloadCase() == StreamMessage.PayloadCase.CONTEXT_PROPERTY_RESPONSE) {
                ContextPropertyResponse cpResponse = response.getContextPropertyResponse();
                log.info("[StreamingCallback] Received ContextPropertyResponse, success: " +
                        cpResponse.getSuccess());
                return cpResponse;
            } else {
                throw new IOException("Unexpected response type: " + response.getPayloadCase());
            }

        } catch (TimeoutException e) {
            throw new IOException("Context property callback timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Context property callback interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("Context property callback failed: " + e.getCause().getMessage(),
                    e.getCause());
        } finally {
            pendingResponse.set(null);
        }
    }

    @Override
    public ContextPropertySetResponse setContextProperty(ContextPropertySetRequest request) throws IOException {
        log.info("[StreamingCallback] setContextProperty: " + request.getPropertyPath() +
                ", session: " + request.getSessionId());

        CompletableFuture<StreamMessage> future = new CompletableFuture<>();
        pendingResponse.set(future);

        try {
            StreamMessage streamMsg = StreamMessage.newBuilder()
                    .setSessionId(request.getSessionId())
                    .setContextPropertySetRequest(request)
                    .build();

            synchronized (streamLock) {
                outbound.onNext(streamMsg);
            }
            log.info("[StreamingCallback] Sent ContextPropertySetRequest on stream");

            StreamMessage response = future.get(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (response.getPayloadCase() == StreamMessage.PayloadCase.CONTEXT_PROPERTY_SET_RESPONSE) {
                ContextPropertySetResponse cpsResponse = response.getContextPropertySetResponse();
                log.info("[StreamingCallback] Received ContextPropertySetResponse, success: " +
                        cpsResponse.getSuccess());
                return cpsResponse;
            } else {
                throw new IOException("Unexpected response type: " + response.getPayloadCase());
            }

        } catch (TimeoutException e) {
            throw new IOException("Context property set callback timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Context property set callback interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("Context property set callback failed: " +
                    e.getCause().getMessage(), e.getCause());
        } finally {
            pendingResponse.set(null);
        }
    }

    @Override
    public void connect() throws IOException {
        // No-op: stream is already open
        log.info("[StreamingCallback] connect() - no-op (stream already open)");
    }

    @Override
    public boolean isConnected() {
        return true; // Always connected while stream is open
    }

    @Override
    public void close() throws IOException {
        // No-op: stream lifecycle is managed by the server transport
        log.info("[StreamingCallback] close() - no-op (stream managed by server)");
    }
}
