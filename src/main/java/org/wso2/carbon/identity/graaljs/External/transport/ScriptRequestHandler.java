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
import org.wso2.carbon.identity.graaljs.proto.EvaluateRequest;
import org.wso2.carbon.identity.graaljs.proto.EvaluateResponse;
import org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest;
import org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse;
import org.wso2.carbon.identity.graaljs.proto.StreamMessage;
import org.wso2.carbon.identity.graaljs.External.HostCallbackClient;
import org.wso2.carbon.identity.graaljs.External.JsEngineServiceImpl;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles evaluate and callback execution requests received on the bidirectional gRPC stream.
 * Each method creates the callback client chain, delegates to the engine service,
 * and sends the response (or error) back on the stream.
 * <p>
 * Thread model: Methods are invoked on the executor thread (not the gRPC event thread).
 * The streaming callback client reference is managed via an AtomicReference to ensure
 * TOCTOU-safe cleanup between the executor thread and the gRPC event thread.
 * <p>
 * This class holds only the engine service reference — all per-stream state (outbound stream,
 * lock, client ref) is passed per-call.
 */
class ScriptRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(ScriptRequestHandler.class);

    private final JsEngineServiceImpl engineService;

    ScriptRequestHandler(JsEngineServiceImpl engineService) {
        this.engineService = engineService;
    }

    /**
     * Handle an evaluate request from IS.
     * Creates a StreamingCallbackClient for this stream, delegates to the engine service,
     * and sends the EvaluateResponse back. On error, sends an error response.
     * <p>
     * CRITICAL: Uses compareAndSet in finally to avoid clearing a client reference that
     * was already replaced by a concurrent handler (TOCTOU race prevention).
     *
     * @param sessionId      The session identifier.
     * @param request        The evaluate request from IS.
     * @param outbound       The outbound stream observer for sending responses.
     * @param streamLock     The lock object for synchronized stream writes.
     * @param clientRef      AtomicReference to the streaming callback client (shared with gRPC event thread).
     * @param streamOpenTime Timestamp when the stream was opened (for PERF logging).
     */
    void handleEvaluate(String sessionId, EvaluateRequest request,
                        StreamObserver<StreamMessage> outbound, Object streamLock,
                        AtomicReference<StreamingCallbackClient> clientRef,
                        long streamOpenTime) {

        if (log.isDebugEnabled()) {
            log.debug("[gRPC-Streaming-Server] handleEvaluate - session: " + sessionId);
        }
        long startTime = System.currentTimeMillis();
        System.out.println("[PERF] [" + startTime + "] External EVALUATE_HANDLE_START session=" +
                sessionId + " streamOpenTs=" + streamOpenTime +
                " handleStartTs=" + startTime +
                " sinceStreamOpenMs=" + (startTime - streamOpenTime));

        StreamingCallbackClient localStreamingClient = null;
        try {
            // Create streaming callback client that uses the bidi stream
            localStreamingClient = new StreamingCallbackClient(outbound, streamLock);
            clientRef.set(localStreamingClient);

            // Create HostCallbackClient with the streaming delegate
            HostCallbackClient callbackClient = new HostCallbackClient(
                    localStreamingClient, sessionId);

            // Delegate to engine service with the streaming callback client
            byte[] requestBytes = request.toByteArray();
            long engineStart = System.currentTimeMillis();
            System.out.println("[PERF] [" + engineStart + "] External EVALUATE_ENGINE_START session=" +
                    sessionId + " handleStartTs=" + startTime +
                    " engineStartTs=" + engineStart +
                    " setupMs=" + (engineStart - startTime));
            byte[] responseBytes = engineService.handleEvaluate(requestBytes, callbackClient);
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

            // Send response back on stream
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
            // Only clear if it's still our client — avoids clearing a
            // reference that was already replaced by a concurrent handler.
            clientRef.compareAndSet(localStreamingClient, null);
        }
    }

    /**
     * Handle an execute callback request from IS.
     * Creates a StreamingCallbackClient for this stream, delegates to the engine service,
     * and sends the ExecuteCallbackResponse back. On error, sends an error response.
     * <p>
     * CRITICAL: Uses compareAndSet in finally to avoid clearing a client reference that
     * was already replaced by a concurrent handler (TOCTOU race prevention).
     *
     * @param sessionId      The session identifier.
     * @param request        The execute callback request from IS.
     * @param outbound       The outbound stream observer for sending responses.
     * @param streamLock     The lock object for synchronized stream writes.
     * @param clientRef      AtomicReference to the streaming callback client (shared with gRPC event thread).
     * @param streamOpenTime Timestamp when the stream was opened (for PERF logging).
     */
    void handleExecuteCallback(String sessionId, ExecuteCallbackRequest request,
                               StreamObserver<StreamMessage> outbound, Object streamLock,
                               AtomicReference<StreamingCallbackClient> clientRef,
                               long streamOpenTime) {

        if (log.isDebugEnabled()) {
            log.debug("[gRPC-Streaming-Server] handleExecuteCallback - session: " + sessionId);
        }
        long startTime = System.currentTimeMillis();
        System.out.println("[PERF] [" + startTime + "] External EXEC_CALLBACK_HANDLE_START session=" +
                sessionId + " streamOpenTs=" + streamOpenTime +
                " handleStartTs=" + startTime +
                " sinceStreamOpenMs=" + (startTime - streamOpenTime));

        StreamingCallbackClient localStreamingClient = null;
        try {
            // Create streaming callback client that uses the bidi stream
            localStreamingClient = new StreamingCallbackClient(outbound, streamLock);
            clientRef.set(localStreamingClient);

            // Create HostCallbackClient with the streaming delegate
            HostCallbackClient callbackClient = new HostCallbackClient(
                    localStreamingClient, sessionId);

            // Delegate to engine service with the streaming callback client
            byte[] requestBytes = request.toByteArray();
            long engineStart = System.currentTimeMillis();
            System.out.println("[PERF] [" + engineStart + "] External EXEC_CALLBACK_ENGINE_START session=" +
                    sessionId + " handleStartTs=" + startTime +
                    " engineStartTs=" + engineStart +
                    " setupMs=" + (engineStart - startTime));
            byte[] responseBytes = engineService.handleExecuteCallback(requestBytes, callbackClient);
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

            // Send response back on stream
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
            // Only clear if it's still our client — avoids clearing a
            // reference that was already replaced by a concurrent handler.
            clientRef.compareAndSet(localStreamingClient, null);
        }
    }
}
