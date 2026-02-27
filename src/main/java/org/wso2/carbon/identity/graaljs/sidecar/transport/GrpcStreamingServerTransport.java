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

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.EvaluateRequest;
import org.wso2.carbon.identity.graaljs.proto.EvaluateResponse;
import org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest;
import org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse;
import org.wso2.carbon.identity.graaljs.proto.StreamMessage;
import org.wso2.carbon.identity.graaljs.proto.grpc.JsEngineStreamingServiceGrpc;
import org.wso2.carbon.identity.graaljs.sidecar.HostCallbackClient;
import org.wso2.carbon.identity.graaljs.sidecar.JsEngineServiceImpl;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Bidirectional streaming gRPC server transport for the sidecar.
 * Each stream represents a single session/request lifecycle (one stream per
 * sendEvaluate or sendExecuteCallback call from IS).
 * <p>
 * Message flow:
 * 1. IS opens a stream, sends EvaluateRequest/ExecuteCallbackRequest
 * 2. Sidecar dispatches to engine service on executor thread
 * 3. During JS execution, host function/context property callbacks are sent back on the stream
 * 4. IS responds with callback results on the same stream
 * 5. Sidecar completes execution, sends EvaluateResponse/ExecuteCallbackResponse
 * 6. Stream completes (onCompleted)
 */
public class GrpcStreamingServerTransport implements ServerTransport {

    private static final Logger log = LoggerFactory.getLogger(GrpcStreamingServerTransport.class);

    private final int port;
    private final JsEngineServiceImpl engineService;
    private final ExecutorService executorService;
    private Server server;

    public GrpcStreamingServerTransport(int port, JsEngineServiceImpl engineService) {
        this.port = port;
        this.engineService = engineService;
        this.executorService = Executors.newCachedThreadPool();
    }

    @Override
    public void start() throws IOException {
        if (server != null && !server.isShutdown()) {
            log.warn("[gRPC-Streaming-Server] Already running");
            return;
        }

        server = ServerBuilder.forPort(port)
                .addService(new JsEngineStreamingServiceImpl())
                .build()
                .start();

        log.info("[gRPC-Streaming-Server] Started on port: " + server.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[gRPC-Streaming-Server] Shutting down via shutdown hook");
            try {
                GrpcStreamingServerTransport.this.stop();
            } catch (IOException e) {
                log.error("[gRPC-Streaming-Server] Error during shutdown", e);
            }
        }));
    }

    @Override
    public void stop() throws IOException {
        if (server != null) {
            log.info("[gRPC-Streaming-Server] Stopping server...");
            try {
                server.shutdown().awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("[gRPC-Streaming-Server] Interrupted during shutdown", e);
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("[gRPC-Streaming-Server] Stopped");
        }
        executorService.shutdown();
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    @Override
    public String getAddress() {
        if (server != null) {
            return "localhost:" + server.getPort();
        }
        return "localhost:" + port;
    }

    /**
     * Bidirectional streaming service implementation.
     * Each stream represents a single script execution lifecycle.
     */
    private class JsEngineStreamingServiceImpl
            extends JsEngineStreamingServiceGrpc.JsEngineStreamingServiceImplBase {

        @Override
        public StreamObserver<StreamMessage> executeScript(StreamObserver<StreamMessage> responseObserver) {
            long streamOpenTime = System.currentTimeMillis();
            System.out.println("[PERF] [" + streamOpenTime + "] SIDECAR STREAM_OPENED" +
                    " streamOpenTs=" + streamOpenTime);
            log.info("[gRPC-Streaming-Server] New stream opened");

            return new StreamObserver<StreamMessage>() {
                // For receiving callback responses from IS during script execution
                private volatile StreamingCallbackClient streamingCallbackClient;
                private final Object streamLock = new Object();

                @Override
                public void onNext(StreamMessage message) {
                    String sessionId = message.getSessionId();
                    long now = System.currentTimeMillis();
                    log.info("[gRPC-Streaming-Server] Received message type: " + message.getPayloadCase() +
                            ", session: " + sessionId);

                    switch (message.getPayloadCase()) {
                        case EVALUATE_REQUEST:
                            System.out.println("[PERF] [" + now + "] SIDECAR EVALUATE_REQUEST_RECEIVED session=" +
                                    sessionId + " streamOpenTs=" + streamOpenTime +
                                    " receivedTs=" + now +
                                    " sinceStreamOpenMs=" + (now - streamOpenTime));
                            executorService.submit(() -> handleEvaluate(
                                    sessionId, message.getEvaluateRequest(), responseObserver));
                            break;

                        case EXECUTE_CALLBACK_REQUEST:
                            System.out.println("[PERF] [" + now + "] SIDECAR EXEC_CALLBACK_REQUEST_RECEIVED session=" +
                                    sessionId + " streamOpenTs=" + streamOpenTime +
                                    " receivedTs=" + now +
                                    " sinceStreamOpenMs=" + (now - streamOpenTime));
                            executorService.submit(() -> handleExecuteCallback(
                                    sessionId, message.getExecuteCallbackRequest(), responseObserver));
                            break;

                        case HOST_FUNCTION_RESPONSE:
                        case CONTEXT_PROPERTY_RESPONSE:
                        case CONTEXT_PROPERTY_SET_RESPONSE:
                            System.out.println("[PERF] [" + now + "] SIDECAR CALLBACK_RESPONSE_RECEIVED session=" +
                                    sessionId + " type=" + message.getPayloadCase() +
                                    " streamOpenTs=" + streamOpenTime +
                                    " receivedTs=" + now +
                                    " sinceStreamOpenMs=" + (now - streamOpenTime));
                            // Deliver callback response to the blocked JS thread
                            if (streamingCallbackClient != null) {
                                streamingCallbackClient.deliverResponse(message);
                            } else {
                                log.error("[gRPC-Streaming-Server] Received callback response but no " +
                                        "StreamingCallbackClient, session: " + sessionId);
                            }
                            break;

                        default:
                            log.warn("[gRPC-Streaming-Server] Unexpected message type: " +
                                    message.getPayloadCase());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    long errTs = System.currentTimeMillis();
                    System.out.println("[PERF] [" + errTs +
                            "] SIDECAR STREAM_ERROR" +
                            " streamOpenTs=" + streamOpenTime +
                            " errorTs=" + errTs +
                            " error=" + t.getMessage() +
                            " sinceStreamOpenMs=" + (errTs - streamOpenTime));
                    log.error("[gRPC-Streaming-Server] Stream error", t);
                    if (streamingCallbackClient != null) {
                        streamingCallbackClient.onStreamError(t);
                    }
                }

                @Override
                public void onCompleted() {
                    long completedTs = System.currentTimeMillis();
                    System.out.println("[PERF] [" + completedTs +
                            "] SIDECAR STREAM_COMPLETED_BY_CLIENT" +
                            " streamOpenTs=" + streamOpenTime +
                            " completedTs=" + completedTs +
                            " streamLifetimeMs=" + (completedTs - streamOpenTime));
                    log.info("[gRPC-Streaming-Server] Stream completed by client");
                }

                private void handleEvaluate(String sessionId, EvaluateRequest request,
                                             StreamObserver<StreamMessage> outbound) {
                    log.info("[gRPC-Streaming-Server] handleEvaluate - session: " + sessionId);
                    long startTime = System.currentTimeMillis();
                    System.out.println("[PERF] [" + startTime + "] SIDECAR EVALUATE_HANDLE_START session=" +
                            sessionId + " streamOpenTs=" + streamOpenTime +
                            " handleStartTs=" + startTime +
                            " sinceStreamOpenMs=" + (startTime - streamOpenTime));

                    try {
                        // Create streaming callback client that uses the bidi stream
                        streamingCallbackClient = new StreamingCallbackClient(outbound, streamLock);

                        // Create HostCallbackClient with the streaming delegate
                        HostCallbackClient callbackClient = new HostCallbackClient(
                                streamingCallbackClient, sessionId);

                        // Delegate to engine service with the streaming callback client
                        byte[] requestBytes = request.toByteArray();
                        long engineStart = System.currentTimeMillis();
                        System.out.println("[PERF] [" + engineStart + "] SIDECAR EVALUATE_ENGINE_START session=" +
                                sessionId + " handleStartTs=" + startTime +
                                " engineStartTs=" + engineStart +
                                " setupMs=" + (engineStart - startTime));
                        byte[] responseBytes = engineService.handleEvaluate(requestBytes, callbackClient);
                        long engineEnd = System.currentTimeMillis();
                        System.out.println("[PERF] [" + engineEnd + "] SIDECAR EVALUATE_ENGINE_DONE session=" +
                                sessionId + " engineStartTs=" + engineStart +
                                " engineEndTs=" + engineEnd +
                                " engineMs=" + (engineEnd - engineStart));

                        EvaluateResponse response = EvaluateResponse.parseFrom(responseBytes);
                        long parseEnd = System.currentTimeMillis();

                        log.info("[gRPC-Streaming-Server] Evaluate completed in " +
                                (parseEnd - startTime) + "ms, success: " + response.getSuccess());

                        // Send response back on stream
                        synchronized (streamLock) {
                            outbound.onNext(StreamMessage.newBuilder()
                                    .setSessionId(sessionId)
                                    .setEvaluateResponse(response)
                                    .build());
                            outbound.onCompleted();
                        }
                        long sendTime = System.currentTimeMillis();
                        System.out.println("[PERF] [" + sendTime + "] SIDECAR EVALUATE_RESPONSE_SENT session=" +
                                sessionId + " success=" + response.getSuccess() +
                                " handleStartTs=" + startTime + " engineStartTs=" + engineStart +
                                " engineEndTs=" + engineEnd + " parseEndTs=" + parseEnd +
                                " sentTs=" + sendTime +
                                " setupMs=" + (engineStart - startTime) +
                                " engineMs=" + (engineEnd - engineStart) +
                                " parseMs=" + (parseEnd - engineEnd) +
                                " sendMs=" + (sendTime - parseEnd) +
                                " totalMs=" + (sendTime - startTime) +
                                " streamLifetimeMs=" + (sendTime - streamOpenTime));

                    } catch (Exception e) {
                        long errTime = System.currentTimeMillis();
                        System.out.println("[PERF] [" + errTime + "] SIDECAR EVALUATE_ERROR session=" +
                                sessionId + " error=" + e.getMessage() +
                                " handleStartTs=" + startTime + " errorTs=" + errTime +
                                " totalMs=" + (errTime - startTime));
                        log.error("[gRPC-Streaming-Server] Error during evaluate, session: " + sessionId, e);
                        try {
                            EvaluateResponse errorResponse = EvaluateResponse.newBuilder()
                                    .setSuccess(false)
                                    .setErrorMessage(e.getMessage() != null ? e.getMessage() :
                                            e.getClass().getName())
                                    .setErrorType(e.getClass().getName())
                                    .setElapsedMs(errTime - startTime)
                                    .build();
                            synchronized (streamLock) {
                                outbound.onNext(StreamMessage.newBuilder()
                                        .setSessionId(sessionId)
                                        .setEvaluateResponse(errorResponse)
                                        .build());
                                outbound.onCompleted();
                            }
                        } catch (Exception ex) {
                            log.error("[gRPC-Streaming-Server] Error sending error response", ex);
                        }
                    } finally {
                        streamingCallbackClient = null;
                    }
                }

                private void handleExecuteCallback(String sessionId, ExecuteCallbackRequest request,
                                                     StreamObserver<StreamMessage> outbound) {
                    log.info("[gRPC-Streaming-Server] handleExecuteCallback - session: " + sessionId);
                    long startTime = System.currentTimeMillis();
                    System.out.println("[PERF] [" + startTime + "] SIDECAR EXEC_CALLBACK_HANDLE_START session=" +
                            sessionId + " streamOpenTs=" + streamOpenTime +
                            " handleStartTs=" + startTime +
                            " sinceStreamOpenMs=" + (startTime - streamOpenTime));

                    try {
                        // Create streaming callback client that uses the bidi stream
                        streamingCallbackClient = new StreamingCallbackClient(outbound, streamLock);

                        // Create HostCallbackClient with the streaming delegate
                        HostCallbackClient callbackClient = new HostCallbackClient(
                                streamingCallbackClient, sessionId);

                        // Delegate to engine service with the streaming callback client
                        byte[] requestBytes = request.toByteArray();
                        long engineStart = System.currentTimeMillis();
                        System.out.println("[PERF] [" + engineStart + "] SIDECAR EXEC_CALLBACK_ENGINE_START session=" +
                                sessionId + " handleStartTs=" + startTime +
                                " engineStartTs=" + engineStart +
                                " setupMs=" + (engineStart - startTime));
                        byte[] responseBytes = engineService.handleExecuteCallback(requestBytes, callbackClient);
                        long engineEnd = System.currentTimeMillis();
                        System.out.println("[PERF] [" + engineEnd + "] SIDECAR EXEC_CALLBACK_ENGINE_DONE session=" +
                                sessionId + " engineStartTs=" + engineStart +
                                " engineEndTs=" + engineEnd +
                                " engineMs=" + (engineEnd - engineStart));

                        ExecuteCallbackResponse response = ExecuteCallbackResponse.parseFrom(responseBytes);
                        long parseEnd = System.currentTimeMillis();

                        log.info("[gRPC-Streaming-Server] ExecuteCallback completed in " +
                                (parseEnd - startTime) + "ms, success: " + response.getSuccess());

                        // Send response back on stream
                        synchronized (streamLock) {
                            outbound.onNext(StreamMessage.newBuilder()
                                    .setSessionId(sessionId)
                                    .setExecuteCallbackResponse(response)
                                    .build());
                            outbound.onCompleted();
                        }
                        long sendTime = System.currentTimeMillis();
                        System.out.println("[PERF] [" + sendTime + "] SIDECAR EXEC_CALLBACK_RESPONSE_SENT session=" +
                                sessionId + " success=" + response.getSuccess() +
                                " handleStartTs=" + startTime + " engineStartTs=" + engineStart +
                                " engineEndTs=" + engineEnd + " parseEndTs=" + parseEnd +
                                " sentTs=" + sendTime +
                                " setupMs=" + (engineStart - startTime) +
                                " engineMs=" + (engineEnd - engineStart) +
                                " parseMs=" + (parseEnd - engineEnd) +
                                " sendMs=" + (sendTime - parseEnd) +
                                " totalMs=" + (sendTime - startTime) +
                                " streamLifetimeMs=" + (sendTime - streamOpenTime));

                    } catch (Exception e) {
                        long errTime = System.currentTimeMillis();
                        System.out.println("[PERF] [" + errTime + "] SIDECAR EXEC_CALLBACK_ERROR session=" +
                                sessionId + " error=" + e.getMessage() +
                                " handleStartTs=" + startTime + " errorTs=" + errTime +
                                " totalMs=" + (errTime - startTime));
                        log.error("[gRPC-Streaming-Server] Error during executeCallback, session: " +
                                sessionId, e);
                        try {
                            ExecuteCallbackResponse errorResponse = ExecuteCallbackResponse.newBuilder()
                                    .setSuccess(false)
                                    .setErrorMessage(e.getMessage() != null ? e.getMessage() :
                                            e.getClass().getName())
                                    .setElapsedMs(errTime - startTime)
                                    .build();
                            synchronized (streamLock) {
                                outbound.onNext(StreamMessage.newBuilder()
                                        .setSessionId(sessionId)
                                        .setExecuteCallbackResponse(errorResponse)
                                        .build());
                                outbound.onCompleted();
                            }
                        } catch (Exception ex) {
                            log.error("[gRPC-Streaming-Server] Error sending error response", ex);
                        }
                    } finally {
                        streamingCallbackClient = null;
                    }
                }
            };
        }
    }
}
