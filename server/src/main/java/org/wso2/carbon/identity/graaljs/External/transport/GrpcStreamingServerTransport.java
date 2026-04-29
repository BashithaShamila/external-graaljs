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
import io.grpc.ServerCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.External.ExternalConstants;
import org.wso2.carbon.identity.graaljs.External.HostCallbackClient;
import org.wso2.carbon.identity.graaljs.External.JsEngineServiceImpl;
import org.wso2.carbon.identity.graaljs.proto.EvaluateRequest;
import org.wso2.carbon.identity.graaljs.proto.EvaluateResponse;
import org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest;
import org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse;
import org.wso2.carbon.identity.graaljs.proto.StreamMessage;
import org.wso2.carbon.identity.graaljs.proto.grpc.JsEngineStreamingServiceGrpc;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bidirectional streaming gRPC server transport for the runtime.
 * <p>
 * Owns server lifecycle (start / stop / mTLS), routes incoming stream
 * messages to per-stream callback clients, and runs evaluate /
 * executeCallback requests on a worker executor (off the gRPC event loop).
 * <p>
 * Each stream represents a single session/request lifecycle (one stream per
 * {@code sendEvaluate} or {@code sendExecuteCallback} call from IS). The
 * previous {@code ScriptRequestHandler} indirection has been folded into
 * {@link #handleEvaluate} / {@link #handleExecuteCallback} below — the
 * transport is the only consumer of that orchestration so an extra class
 * was pure indirection.
 * <p>
 * <b>Message flow</b>
 * <ol>
 *   <li>IS opens a stream and sends an {@code EvaluateRequest} or
 *       {@code ExecuteCallbackRequest}.</li>
 *   <li>{@link JsEngineStreamingServiceImpl#executeScript} routes the
 *       message to {@link #handleEvaluate} / {@link #handleExecuteCallback}
 *       on the worker executor.</li>
 *   <li>The handler creates a {@link HostCallbackClient} for the stream,
 *       publishes it on a shared {@link AtomicReference} (so the gRPC
 *       event thread can deliver responses), and invokes the engine.</li>
 *   <li>During JS execution, host-function and context-property callbacks
 *       are sent back on the stream; IS replies on the same stream and
 *       the gRPC event thread routes those replies via
 *       {@link HostCallbackClient#deliverResponse}.</li>
 *   <li>Engine completes; the handler sends the typed response and closes
 *       the outbound half via {@code onCompleted}.</li>
 * </ol>
 * <p>
 * <b>TOCTOU prevention</b>: {@code clientRef.compareAndSet(localCallbackClient,
 * null)} in the handler {@code finally} ensures we only clear our own client
 * — not one a concurrent (out-of-order) handler already replaced it with.
 */
public class GrpcStreamingServerTransport {

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

    public void start() throws IOException {
        if (server != null && !server.isShutdown()) {
            log.warn("[gRPC-Streaming-Server] Already running");
            return;
        }

        // mTLS is mandatory: the stream carries the full JsAuthenticationContext
        // and host-function payloads. The previous plaintext branch was removed
        // to make sure a misconfiguration cannot silently expose that data.
        ServerCredentials credentials = buildMtlsCredentials();

        server = Grpc.newServerBuilderForPort(port, credentials)
                .addService(new JsEngineStreamingServiceImpl())
                .build()
                .start();

        System.out.println("[gRPC-Streaming-Server] mTLS server started on port: " + server.getPort());

        if (log.isDebugEnabled()) {
            log.debug("[gRPC-Streaming-Server] Started on port: " + server.getPort());
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

    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    public String getAddress() {
        if (server != null) {
            return "localhost:" + server.getPort();
        }
        return "localhost:" + port;
    }

    // ============ mTLS Credential Building ============

    /**
     * Build TLS server credentials from a PKCS#12 keystore + truststore.
     * <p>
     * The bundled defaults are {@code /certs/wso2carbon.p12} and
     * {@code /certs/client-truststore.p12} on the classpath — identical to the files
     * shipped in the IS pack. Operator can override paths via the
     * {@code mtls.keystore.path} / {@code mtls.truststore.path} system properties to
     * point at the IS pack directly when co-located. Failures surface immediately.
     */
    private ServerCredentials buildMtlsCredentials() throws IOException {

        String ksPath = System.getProperty(ExternalConstants.MTLS_KEYSTORE_PATH_PROP);
        String ksPassword = System.getProperty(
                ExternalConstants.MTLS_KEYSTORE_PASSWORD_PROP, ExternalConstants.DEFAULT_KEYSTORE_PASSWORD);
        String ksKeyPassword = System.getProperty(
                ExternalConstants.MTLS_KEYSTORE_KEY_PASSWORD_PROP, ksPassword);
        String tsPath = System.getProperty(ExternalConstants.MTLS_TRUSTSTORE_PATH_PROP);
        String tsPassword = System.getProperty(
                ExternalConstants.MTLS_TRUSTSTORE_PASSWORD_PROP, ExternalConstants.DEFAULT_KEYSTORE_PASSWORD);

        System.out.println("[gRPC-Streaming-Server] mTLS PKCS#12 — keystore: " +
                (ksPath != null ? ksPath : "classpath:" + ExternalConstants.DEFAULT_KEYSTORE_RESOURCE) +
                ", truststore: " +
                (tsPath != null ? tsPath : "classpath:" + ExternalConstants.DEFAULT_TRUSTSTORE_RESOURCE));

        try {
            KeyManager[] keyManagers = loadKeyManagers(ksPath,
                    ExternalConstants.DEFAULT_KEYSTORE_RESOURCE, ksPassword, ksKeyPassword);
            TrustManager[] trustManagers = loadTrustManagers(tsPath,
                    ExternalConstants.DEFAULT_TRUSTSTORE_RESOURCE, tsPassword);

            return TlsServerCredentials.newBuilder()
                    .keyManager(keyManagers)
                    .trustManager(trustManagers)
                    .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
                    .build();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to build mTLS credentials from PKCS#12 keystore: " +
                    e.getMessage(), e);
        }
    }

    private KeyManager[] loadKeyManagers(String overridePath, String classpathDefault,
                                         String storePassword, String keyPassword) throws Exception {

        KeyStore ks = KeyStore.getInstance(ExternalConstants.DEFAULT_KEYSTORE_TYPE);
        try (InputStream in = openStore(overridePath, classpathDefault)) {
            ks.load(in, storePassword.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyPassword.toCharArray());
        return kmf.getKeyManagers();
    }

    private TrustManager[] loadTrustManagers(String overridePath, String classpathDefault,
                                             String password) throws Exception {

        KeyStore ts = KeyStore.getInstance(ExternalConstants.DEFAULT_KEYSTORE_TYPE);
        try (InputStream in = openStore(overridePath, classpathDefault)) {
            ts.load(in, password.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        return tmf.getTrustManagers();
    }

    /**
     * Open the keystore stream — operator override path first, classpath default second.
     */
    private InputStream openStore(String overridePath, String classpathDefault) throws IOException {

        if (overridePath != null && !overridePath.isEmpty()) {
            if (!Files.isRegularFile(Paths.get(overridePath))) {
                throw new IOException("Configured keystore path does not exist: " + overridePath);
            }
            return new FileInputStream(overridePath);
        }
        InputStream cp = getClass().getResourceAsStream(classpathDefault);
        if (cp == null) {
            throw new IOException("Bundled keystore resource not found on classpath: " + classpathDefault);
        }
        return cp;
    }

    // ============ Per-stream request handling (formerly ScriptRequestHandler) ============

    /**
     * Handle an evaluate request from IS.
     * <p>
     * Creates a {@link HostCallbackClient} for this stream, publishes it on
     * the shared {@code clientRef} so the gRPC event thread can deliver
     * callback responses, invokes the engine, and ships the
     * {@link EvaluateResponse} back on the outbound half. On error a typed
     * error response is sent before the stream closes so IS sees a clean
     * termination either way.
     * <p>
     * Runs on the worker executor (not the gRPC event thread).
     */
    private void handleEvaluate(String sessionId, EvaluateRequest request,
                                StreamObserver<StreamMessage> outbound, Object streamLock,
                                AtomicReference<HostCallbackClient> clientRef,
                                long streamOpenTime) {

        if (log.isDebugEnabled()) {
            log.debug("[gRPC-Streaming-Server] handleEvaluate - session: " + sessionId);
        }
        long startTime = System.currentTimeMillis();
        System.out.println("[PERF] [" + startTime + "] External EVALUATE_HANDLE_START session=" +
                sessionId + " streamOpenTs=" + streamOpenTime +
                " handleStartTs=" + startTime +
                " sinceStreamOpenMs=" + (startTime - streamOpenTime));

        HostCallbackClient localCallbackClient = null;
        try {
            // Single allocation: HostCallbackClient now owns both the
            // stream send-and-await and the host-side serialisation/timing.
            localCallbackClient = new HostCallbackClient(outbound, streamLock, sessionId);
            clientRef.set(localCallbackClient);

            byte[] requestBytes = request.toByteArray();
            long engineStart = System.currentTimeMillis();
            System.out.println("[PERF] [" + engineStart + "] External EVALUATE_ENGINE_START session=" +
                    sessionId + " handleStartTs=" + startTime +
                    " engineStartTs=" + engineStart +
                    " setupMs=" + (engineStart - startTime));
            byte[] responseBytes = engineService.handleEvaluate(requestBytes, localCallbackClient);
            long engineEnd = System.currentTimeMillis();
            System.out.println("[PERF] [" + engineEnd + "] External EVALUATE_ENGINE_DONE session=" +
                    sessionId + " engineStartTs=" + engineStart +
                    " engineEndTs=" + engineEnd +
                    " engineMs=" + (engineEnd - engineStart));

            EvaluateResponse response = EvaluateResponse.parseFrom(responseBytes);
            long parseEnd = System.currentTimeMillis();

            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Streaming-Server] Evaluate completed in " +
                        (parseEnd - startTime) + "ms, success: " + response.getSuccess());
            }

            // Send response back on stream. streamLock is the same monitor
            // HostCallbackClient holds during outbound.onNext for callbacks —
            // ordering against any in-flight callback writes is preserved.
            synchronized (streamLock) {
                outbound.onNext(StreamMessage.newBuilder()
                        .setSessionId(sessionId)
                        .setEvaluateResponse(response)
                        .build());
                outbound.onCompleted();
            }
            long sendTime = System.currentTimeMillis();
            System.out.println("[PERF] [" + sendTime + "] External EVALUATE_RESPONSE_SENT session=" +
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
            System.out.println("[PERF] [" + errTime + "] External EVALUATE_ERROR session=" +
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
            // compareAndSet — only clear if it's still our client. Avoids
            // wiping a reference a concurrent / out-of-order handler already
            // replaced it with.
            clientRef.compareAndSet(localCallbackClient, null);
        }
    }

    /**
     * Handle an execute callback request from IS. Same orchestration as
     * {@link #handleEvaluate}, just for the {@code ExecuteCallbackRequest}
     * code path.
     * <p>
     * Runs on the worker executor (not the gRPC event thread).
     */
    private void handleExecuteCallback(String sessionId, ExecuteCallbackRequest request,
                                       StreamObserver<StreamMessage> outbound, Object streamLock,
                                       AtomicReference<HostCallbackClient> clientRef,
                                       long streamOpenTime) {

        if (log.isDebugEnabled()) {
            log.debug("[gRPC-Streaming-Server] handleExecuteCallback - session: " + sessionId);
        }
        long startTime = System.currentTimeMillis();
        System.out.println("[PERF] [" + startTime + "] External EXEC_CALLBACK_HANDLE_START session=" +
                sessionId + " streamOpenTs=" + streamOpenTime +
                " handleStartTs=" + startTime +
                " sinceStreamOpenMs=" + (startTime - streamOpenTime));

        HostCallbackClient localCallbackClient = null;
        try {
            localCallbackClient = new HostCallbackClient(outbound, streamLock, sessionId);
            clientRef.set(localCallbackClient);

            byte[] requestBytes = request.toByteArray();
            long engineStart = System.currentTimeMillis();
            System.out.println("[PERF] [" + engineStart + "] External EXEC_CALLBACK_ENGINE_START session=" +
                    sessionId + " handleStartTs=" + startTime +
                    " engineStartTs=" + engineStart +
                    " setupMs=" + (engineStart - startTime));
            byte[] responseBytes = engineService.handleExecuteCallback(requestBytes, localCallbackClient);
            long engineEnd = System.currentTimeMillis();
            System.out.println("[PERF] [" + engineEnd + "] External EXEC_CALLBACK_ENGINE_DONE session=" +
                    sessionId + " engineStartTs=" + engineStart +
                    " engineEndTs=" + engineEnd +
                    " engineMs=" + (engineEnd - engineStart));

            ExecuteCallbackResponse response = ExecuteCallbackResponse.parseFrom(responseBytes);
            long parseEnd = System.currentTimeMillis();

            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Streaming-Server] ExecuteCallback completed in " +
                        (parseEnd - startTime) + "ms, success: " + response.getSuccess());
            }

            synchronized (streamLock) {
                outbound.onNext(StreamMessage.newBuilder()
                        .setSessionId(sessionId)
                        .setExecuteCallbackResponse(response)
                        .build());
                outbound.onCompleted();
            }
            long sendTime = System.currentTimeMillis();
            System.out.println("[PERF] [" + sendTime + "] External EXEC_CALLBACK_RESPONSE_SENT session=" +
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
            System.out.println("[PERF] [" + errTime + "] External EXEC_CALLBACK_ERROR session=" +
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
            clientRef.compareAndSet(localCallbackClient, null);
        }
    }

    // ============ gRPC Service — Message Routing ============

    /**
     * Bidirectional streaming service implementation.
     * <p>
     * Each stream represents a single script execution lifecycle. Routes
     * incoming messages to the appropriate handler based on payload type.
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
                // For receiving callback responses from IS during script
                // execution. AtomicReference ensures thread-safe access
                // between the executor thread (which sets/clears it inside
                // handleEvaluate / handleExecuteCallback) and the gRPC
                // event thread (which reads it in onNext to deliver
                // callback responses).
                private final AtomicReference<HostCallbackClient> callbackClientRef =
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
                            executorService.submit(() -> handleEvaluate(
                                    sessionId, message.getEvaluateRequest(),
                                    responseObserver, streamLock,
                                    callbackClientRef, streamOpenTime));
                            break;

                        case EXECUTE_CALLBACK_REQUEST:
                            System.out.println("[PERF] [" + now + "] External EXEC_CALLBACK_REQUEST_RECEIVED session=" +
                                    sessionId + " streamOpenTs=" + streamOpenTime +
                                    " receivedTs=" + now +
                                    " sinceStreamOpenMs=" + (now - streamOpenTime));
                            executorService.submit(() -> handleExecuteCallback(
                                    sessionId, message.getExecuteCallbackRequest(),
                                    responseObserver, streamLock,
                                    callbackClientRef, streamOpenTime));
                            break;

                        case HOST_FUNCTION_RESPONSE:
                        case CONTEXT_PROPERTY_RESPONSE:
                        case CONTEXT_PROPERTY_SET_RESPONSE:
                            System.out.println("[PERF] [" + now + "] External CALLBACK_RESPONSE_RECEIVED session=" +
                                    sessionId + " type=" + message.getPayloadCase() +
                                    " streamOpenTs=" + streamOpenTime +
                                    " receivedTs=" + now +
                                    " sinceStreamOpenMs=" + (now - streamOpenTime));
                            // Deliver callback response to the blocked JS
                            // thread. Snapshot the reference once to avoid
                            // a TOCTOU race between this gRPC event thread
                            // and the executor thread that may clear it in
                            // its finally block.
                            HostCallbackClient client = callbackClientRef.get();
                            if (client != null) {
                                client.deliverResponse(message);
                            } else {
                                log.error("[gRPC-Streaming-Server] Received callback response but no " +
                                        "HostCallbackClient, session: " + sessionId);
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
                    HostCallbackClient client = callbackClientRef.get();
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
                    // IS closed its half of the stream. If the JS thread is
                    // blocked waiting for a callback response (e.g., IS
                    // timed out via processMessageLoop's deadline), unblock
                    // it so the error propagates through GraalVM to the
                    // script.
                    HostCallbackClient client = callbackClientRef.get();
                    if (client != null) {
                        client.onStreamCompleted();
                    }
                }
            };
        }
    }
}
