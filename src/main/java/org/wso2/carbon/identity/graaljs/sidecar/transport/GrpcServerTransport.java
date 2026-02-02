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
     * @param port           Port to bind (0 for automatic selection).
     * @param engineService  JavaScript engine service implementation.
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
            log.debug("[gRPC-Server] Received evaluate request");
            try {
                // Serialize request and delegate to engine service
                byte[] requestBytes = request.toByteArray();
                byte[] responseBytes = engineService.handleEvaluate(requestBytes);
                EvaluateResponse response = EvaluateResponse.parseFrom(responseBytes);

                responseObserver.onNext(response);
                responseObserver.onCompleted();

                log.debug("[gRPC-Server] Evaluate completed successfully");

            } catch (Exception e) {
                log.error("[gRPC-Server] Error during evaluate", e);
                responseObserver.onError(e);
            }
        }

        @Override
        public void executeCallback(ExecuteCallbackRequest request,
                                     StreamObserver<ExecuteCallbackResponse> responseObserver) {
            log.debug("[gRPC-Server] Received executeCallback request");
            try {
                // Serialize request and delegate to engine service
                byte[] requestBytes = request.toByteArray();
                byte[] responseBytes = engineService.handleExecuteCallback(requestBytes);
                ExecuteCallbackResponse response = ExecuteCallbackResponse.parseFrom(responseBytes);

                responseObserver.onNext(response);
                responseObserver.onCompleted();

                log.debug("[gRPC-Server] ExecuteCallback completed successfully");

            } catch (Exception e) {
                log.error("[gRPC-Server] Error during executeCallback", e);
                responseObserver.onError(e);
            }
        }
    }
}
