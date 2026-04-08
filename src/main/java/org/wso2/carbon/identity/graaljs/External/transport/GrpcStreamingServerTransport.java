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

import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.StreamMessage;
import org.wso2.carbon.identity.graaljs.proto.grpc.JsEngineStreamingServiceGrpc;
import org.wso2.carbon.identity.graaljs.External.JsEngineServiceImpl;
import org.wso2.carbon.identity.graaljs.External.ExternalConstants;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bidirectional streaming gRPC server transport for the External.
 * Manages server lifecycle (start/stop/mTLS) and routes incoming stream messages
 * to the appropriate handler.
 * <p>
 * Each stream represents a single session/request lifecycle (one stream per
 * sendEvaluate or sendExecuteCallback call from IS).
 * <p>
 * Request handling (evaluate/callback execution) is delegated to
 * {@link ScriptRequestHandler}, keeping this class focused on server infrastructure.
 * <p>
 * Message flow:
 * <ol>
 *   <li>IS opens a stream, sends EvaluateRequest/ExecuteCallbackRequest</li>
 *   <li>This class routes the message to {@link ScriptRequestHandler} on the executor</li>
 *   <li>During JS execution, host function/context property callbacks are sent back on the stream</li>
 *   <li>IS responds with callback results on the same stream</li>
 *   <li>Engine completes, ScriptRequestHandler sends response and closes the stream</li>
 * </ol>
 */
public class GrpcStreamingServerTransport implements ServerTransport {

    private static final Logger log = LoggerFactory.getLogger(GrpcStreamingServerTransport.class);

    private final int port;
    private final ScriptRequestHandler requestHandler;
    private final ExecutorService executorService;
    private Server server;

    public GrpcStreamingServerTransport(int port, JsEngineServiceImpl engineService) {
        this.port = port;
        this.requestHandler = new ScriptRequestHandler(engineService);
        this.executorService = Executors.newCachedThreadPool();
    }

    @Override
    public void start() throws IOException {
        if (server != null && !server.isShutdown()) {
            log.warn("[gRPC-Streaming-Server] Already running");
            return;
        }

        if (ExternalConstants.MTLS_ENABLED) {
            String certPath = "/certs/";
            System.out.println("[gRPC-Streaming-Server] mTLS enabled - loading certs from classpath: " + certPath);

            InputStream serverCertStream = getClass().getResourceAsStream(certPath + ExternalConstants.MTLS_SERVER_CERT);
            InputStream serverKeyStream = getClass().getResourceAsStream(certPath + ExternalConstants.MTLS_SERVER_KEY);
            InputStream caCertStream = getClass().getResourceAsStream(certPath + ExternalConstants.MTLS_CA_CERT);

            if (serverCertStream == null || serverKeyStream == null || caCertStream == null) {
                throw new IOException("[gRPC-Streaming-Server] mTLS cert resources not found in classpath. " +
                        "Expected: " + certPath + ExternalConstants.MTLS_SERVER_CERT + ", " +
                        certPath + ExternalConstants.MTLS_SERVER_KEY + ", " +
                        certPath + ExternalConstants.MTLS_CA_CERT);
            }

            ServerCredentials credentials = TlsServerCredentials.newBuilder()
                    .keyManager(serverCertStream, serverKeyStream)
                    .trustManager(caCertStream)
                    .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
                    .build();

            server = Grpc.newServerBuilderForPort(port, credentials)
                    .addService(new JsEngineStreamingServiceImpl())
                    .build()
                    .start();

            System.out.println("[gRPC-Streaming-Server] mTLS server started on port: " + server.getPort());
        } else {
            server = ServerBuilder.forPort(port)
                    .addService(new JsEngineStreamingServiceImpl())
                    .build()
                    .start();

            System.out.println("[gRPC-Streaming-Server] Plaintext server started on port: " + server.getPort());
        }

        if (log.isDebugEnabled()) {
            log.debug("[gRPC-Streaming-Server] Started on port: " + server.getPort() +
                    ", mTLS: " + ExternalConstants.MTLS_ENABLED);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Streaming-Server] Shutting down via shutdown hook");
            }
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
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Streaming-Server] Stopping server...");
            }
            try {
                server.shutdown().awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("[gRPC-Streaming-Server] Interrupted during shutdown", e);
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Streaming-Server] Stopped");
            }
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

    // ============ gRPC Service — Message Routing ============

    /**
     * Bidirectional streaming service implementation.
     * Each stream represents a single script execution lifecycle.
     * Routes incoming messages to the appropriate handler based on payload type.
     */
    private class JsEngineStreamingServiceImpl
            extends JsEngineStreamingServiceGrpc.JsEngineStreamingServiceImplBase {

        @Override
        public StreamObserver<StreamMessage> executeScript(StreamObserver<StreamMessage> responseObserver) {
            long streamOpenTime = System.currentTimeMillis();
            System.out.println("[PERF] [" + streamOpenTime + "] External STREAM_OPENED" +
                    " streamOpenTs=" + streamOpenTime);
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Streaming-Server] New stream opened");
            }

            return new StreamObserver<StreamMessage>() {
                // For receiving callback responses from IS during script execution.
                // AtomicReference ensures thread-safe access between the executor thread
                // (which sets/clears it in ScriptRequestHandler) and the
                // gRPC event thread (which reads it in onNext to deliver callback responses).
                private final AtomicReference<StreamingCallbackClient> streamingCallbackClientRef =
                        new AtomicReference<>();
                private final Object streamLock = new Object();

                @Override
                public void onNext(StreamMessage message) {
                    String sessionId = message.getSessionId();
                    long now = System.currentTimeMillis();
                    if (log.isDebugEnabled()) {
                        log.debug("[gRPC-Streaming-Server] Received message type: " + message.getPayloadCase() +
                                ", session: " + sessionId);
                    }

                    switch (message.getPayloadCase()) {
                        case EVALUATE_REQUEST:
                            System.out.println("[PERF] [" + now + "] External EVALUATE_REQUEST_RECEIVED session=" +
                                    sessionId + " streamOpenTs=" + streamOpenTime +
                                    " receivedTs=" + now +
                                    " sinceStreamOpenMs=" + (now - streamOpenTime));
                            executorService.submit(() -> requestHandler.handleEvaluate(
                                    sessionId, message.getEvaluateRequest(),
                                    responseObserver, streamLock,
                                    streamingCallbackClientRef, streamOpenTime));
                            break;

                        case EXECUTE_CALLBACK_REQUEST:
                            System.out.println("[PERF] [" + now + "] External EXEC_CALLBACK_REQUEST_RECEIVED session=" +
                                    sessionId + " streamOpenTs=" + streamOpenTime +
                                    " receivedTs=" + now +
                                    " sinceStreamOpenMs=" + (now - streamOpenTime));
                            executorService.submit(() -> requestHandler.handleExecuteCallback(
                                    sessionId, message.getExecuteCallbackRequest(),
                                    responseObserver, streamLock,
                                    streamingCallbackClientRef, streamOpenTime));
                            break;

                        case HOST_FUNCTION_RESPONSE:
                        case CONTEXT_PROPERTY_RESPONSE:
                        case CONTEXT_PROPERTY_SET_RESPONSE:
                            System.out.println("[PERF] [" + now + "] External CALLBACK_RESPONSE_RECEIVED session=" +
                                    sessionId + " type=" + message.getPayloadCase() +
                                    " streamOpenTs=" + streamOpenTime +
                                    " receivedTs=" + now +
                                    " sinceStreamOpenMs=" + (now - streamOpenTime));
                            // Deliver callback response to the blocked JS thread.
                            // Snapshot the reference once to avoid TOCTOU race between
                            // this gRPC event thread and the executor thread that may
                            // clear it in the finally block.
                            StreamingCallbackClient client = streamingCallbackClientRef.get();
                            if (client != null) {
                                client.deliverResponse(message);
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
                            "] External STREAM_ERROR" +
                            " streamOpenTs=" + streamOpenTime +
                            " errorTs=" + errTs +
                            " error=" + t.getMessage() +
                            " sinceStreamOpenMs=" + (errTs - streamOpenTime));
                    log.error("[gRPC-Streaming-Server] Stream error", t);
                    StreamingCallbackClient client = streamingCallbackClientRef.get();
                    if (client != null) {
                        client.onStreamError(t);
                    }
                }

                @Override
                public void onCompleted() {
                    long completedTs = System.currentTimeMillis();
                    System.out.println("[PERF] [" + completedTs +
                            "] External STREAM_COMPLETED_BY_CLIENT" +
                            " streamOpenTs=" + streamOpenTime +
                            " completedTs=" + completedTs +
                            " streamLifetimeMs=" + (completedTs - streamOpenTime));
                    if (log.isDebugEnabled()) {
                        log.debug("[gRPC-Streaming-Server] Stream completed by client");
                    }
                }
            };
        }
    }
}
