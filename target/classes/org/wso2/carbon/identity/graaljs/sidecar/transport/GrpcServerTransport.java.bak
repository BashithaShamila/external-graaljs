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
import org.wso2.carbon.identity.graaljs.proto.grpc.JsEngineServiceGrpc;
import org.wso2.carbon.identity.graaljs.sidecar.JsEngineServiceImpl;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * gRPC implementation of ServerTransport.
 * Runs a gRPC server for remote JavaScript engine evaluation.
 */
public class GrpcServerTransport implements ServerTransport {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerTransport.class);

    private final int port;
    private final JsEngineServiceImpl engineService;
    private Server server;

    /**
     * Create a new gRPC server transport.
     *
     * @param port          Port to bind (0 for automatic selection).
     * @param engineService JavaScript engine service implementation.
     */
    public GrpcServerTransport(int port, JsEngineServiceImpl engineService) {
        this.port = port;
        this.engineService = engineService;
    }

    @Override
    public void start() throws IOException {
        if (server != null && !server.isShutdown()) {
            log.warn("[gRPC-Server] Already running");
            return;
        }

        server = ServerBuilder.forPort(port)
                .addService(new JsEngineServiceGrpcImpl())
                .build()
                .start();

        log.info("[gRPC-Server] Started on port: " + server.getPort());

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[gRPC-Server] Shutting down via shutdown hook");
            try {
                GrpcServerTransport.this.stop();
            } catch (IOException e) {
                log.error("[gRPC-Server] Error during shutdown", e);
            }
        }));
    }

    @Override
    public void stop() throws IOException {
        if (server != null) {
            log.info("[gRPC-Server] Stopping server...");
            try {
                server.shutdown().awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("[gRPC-Server] Interrupted during shutdown", e);
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("[gRPC-Server] Stopped");
        }
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
     * gRPC service implementation for JavaScript engine.
     */
    private class JsEngineServiceGrpcImpl extends JsEngineServiceGrpc.JsEngineServiceImplBase {

        @Override
        public void evaluate(EvaluateRequest request, StreamObserver<EvaluateResponse> responseObserver) {
            log.info("[gRPC-Server] ========== evaluate() RECEIVED ==========");
            log.info("[gRPC-Server] Session: " + request.getSessionId());
            log.info("[gRPC-Server] Script length: " + request.getScript().length() + " chars");
            log.info("[gRPC-Server] Script preview: " +
                    request.getScript().substring(0, Math.min(150, request.getScript().length())) + "...");
            log.info("[gRPC-Server] Bindings count: " + request.getBindingsCount());
            log.info("[gRPC-Server] Bindings keys: " + request.getBindingsMap().keySet());
            log.info("[gRPC-Server] Host functions count: " + request.getHostFunctionsCount());
            log.info("[gRPC-Server] Callback socket path: " + request.getCallbackSocketPath());
            log.info("[gRPC-Server] Source identifier: " + request.getSourceIdentifier());
            if (request.hasContextData()) {
                log.info("[gRPC-Server] ContextData - step: " + request.getContextData().getCurrentStep() +
                        ", username: " + request.getContextData().getUsername() +
                        ", tenant: " + request.getContextData().getTenantDomain());
            }

            long startTime = System.currentTimeMillis();
            try {
                // Serialize request and delegate to engine service
                log.info("[gRPC-Server] Serializing request to bytes...");
                byte[] requestBytes = request.toByteArray();
                log.info("[gRPC-Server] Request bytes: " + requestBytes.length + " bytes");

                log.info("[gRPC-Server] >>> Delegating to JsEngineServiceImpl.handleEvaluate()...");
                byte[] responseBytes = engineService.handleEvaluate(requestBytes);
                log.info("[gRPC-Server] <<< JsEngineServiceImpl returned " + responseBytes.length + " bytes");

                EvaluateResponse response = EvaluateResponse.parseFrom(responseBytes);
                long elapsed = System.currentTimeMillis() - startTime;
                long engineTime = response.getElapsedMs();
                long transportOverhead = elapsed - engineTime;

                log.info("[gRPC-Server] ========== evaluate() RESPONSE ==========");
                log.info(
                        "[gRPC-Server] Time breakdown: totalElapsed={}ms, engineProcessing={}ms, transportOverhead={}ms",
                        elapsed, engineTime, transportOverhead);
                log.info("[gRPC-Server] Success: " + response.getSuccess());
                if (!response.getSuccess()) {
                    log.error("[gRPC-Server] Error message: " + response.getErrorMessage());
                    log.error("[gRPC-Server] Error type: " + response.getErrorType());
                }
                log.info("[gRPC-Server] Updated bindings count: " + response.getUpdatedBindingsCount());
                log.info("[gRPC-Server] Result valueCase: " + response.getResult().getValueCase());

                responseObserver.onNext(response);
                responseObserver.onCompleted();

                log.info("[gRPC-Server] ========== evaluate() COMPLETED ==========");

            } catch (Exception e) {
                log.error("[gRPC-Server] ========== evaluate() FAILED ==========");
                log.error("[gRPC-Server] Exception type: " + e.getClass().getName());
                log.error("[gRPC-Server] Error during evaluate", e);
                responseObserver.onError(e);
            }
        }

        @Override
        public void executeCallback(ExecuteCallbackRequest request,
                StreamObserver<ExecuteCallbackResponse> responseObserver) {
            log.info("[gRPC-Server] ========== executeCallback() RECEIVED ==========");
            log.info("[gRPC-Server] Session: " + request.getSessionId());
            log.info("[gRPC-Server] Function source length: " + request.getFunctionSource().length() + " chars");
            log.info("[gRPC-Server] Function preview: " +
                    request.getFunctionSource().substring(0, Math.min(150, request.getFunctionSource().length()))
                    + "...");
            log.info("[gRPC-Server] Arguments count: " + request.getArgumentsCount());
            log.info("[gRPC-Server] Bindings count: " + request.getBindingsCount());
            log.info("[gRPC-Server] Host functions count: " + request.getHostFunctionsCount());
            log.info("[gRPC-Server] Callback socket path: " + request.getCallbackSocketPath());

            long startTime = System.currentTimeMillis();
            try {
                // Serialize request and delegate to engine service
                log.info("[gRPC-Server] Serializing request to bytes...");
                byte[] requestBytes = request.toByteArray();
                log.info("[gRPC-Server] Request bytes: " + requestBytes.length + " bytes");

                log.info("[gRPC-Server] >>> Delegating to JsEngineServiceImpl.handleExecuteCallback()...");
                byte[] responseBytes = engineService.handleExecuteCallback(requestBytes);
                log.info("[gRPC-Server] <<< JsEngineServiceImpl returned " + responseBytes.length + " bytes");

                ExecuteCallbackResponse response = ExecuteCallbackResponse.parseFrom(responseBytes);
                long elapsed = System.currentTimeMillis() - startTime;
                long engineTime = response.getElapsedMs();
                long transportOverhead = elapsed - engineTime;

                log.info("[gRPC-Server] ========== executeCallback() RESPONSE ==========");
                log.info(
                        "[gRPC-Server] Time breakdown: totalElapsed={}ms, engineProcessing={}ms, transportOverhead={}ms",
                        elapsed, engineTime, transportOverhead);
                log.info("[gRPC-Server] Success: " + response.getSuccess());
                if (!response.getSuccess()) {
                    log.error("[gRPC-Server] Error message: " + response.getErrorMessage());
                }
                log.info("[gRPC-Server] Updated bindings count: " + response.getUpdatedBindingsCount());
                log.info("[gRPC-Server] Result valueCase: " + response.getResult().getValueCase());

                responseObserver.onNext(response);
                responseObserver.onCompleted();

                log.info("[gRPC-Server] ========== executeCallback() COMPLETED ==========");

            } catch (Exception e) {
                log.error("[gRPC-Server] ========== executeCallback() FAILED ==========");
                log.error("[gRPC-Server] Exception type: " + e.getClass().getName());
                log.error("[gRPC-Server] Error during executeCallback", e);
                responseObserver.onError(e);
            }
        }
    }
}
