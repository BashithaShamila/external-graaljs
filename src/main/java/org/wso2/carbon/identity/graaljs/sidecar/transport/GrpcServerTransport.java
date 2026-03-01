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

        if (log.isDebugEnabled()) {
            log.debug("[gRPC-Server] Started on port: " + server.getPort());
        }

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Shutting down via shutdown hook");
            }
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
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Stopping server...");
            }
            try {
                server.shutdown().awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("[gRPC-Server] Interrupted during shutdown", e);
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Stopped");
            }
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
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] ========== evaluate() RECEIVED ==========");
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Session: " + request.getSessionId());
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Script length: " + request.getScript().length() + " chars");
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Script preview: " +
                        request.getScript().substring(0, Math.min(150, request.getScript().length())) + "...");
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Bindings count: " + request.getBindingsCount());
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Bindings keys: " + request.getBindingsMap().keySet());
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Host functions count: " + request.getHostFunctionsCount());
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Callback socket path: " + request.getCallbackSocketPath());
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Source identifier: " + request.getSourceIdentifier());
            }
            if (request.hasContextData()) {
                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] ContextData - step: " + request.getContextData().getCurrentStep() +
                            ", username: " + request.getContextData().getUsername() +
                            ", tenant: " + request.getContextData().getTenantDomain());
                }
            }

            long startTime = System.currentTimeMillis();
            try {
                // Serialize request and delegate to engine service
                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] Serializing request to bytes...");
                }
                byte[] requestBytes = request.toByteArray();
                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] Request bytes: " + requestBytes.length + " bytes");
                }

                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] >>> Delegating to JsEngineServiceImpl.handleEvaluate()...");
                }
                byte[] responseBytes = engineService.handleEvaluate(requestBytes);
                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] <<< JsEngineServiceImpl returned " + responseBytes.length + " bytes");
                }

                EvaluateResponse response = EvaluateResponse.parseFrom(responseBytes);
                long elapsed = System.currentTimeMillis() - startTime;
                long engineTime = response.getElapsedMs();
                long transportOverhead = elapsed - engineTime;

                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] ========== evaluate() RESPONSE ==========");
                }
                if (log.isDebugEnabled()) {
                    log.debug(
                            "[gRPC-Server] Time breakdown: totalElapsed={}ms, engineProcessing={}ms, transportOverhead={}ms",
                            elapsed, engineTime, transportOverhead);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] Success: " + response.getSuccess());
                }
                if (!response.getSuccess()) {
                    log.error("[gRPC-Server] Error message: " + response.getErrorMessage());
                    log.error("[gRPC-Server] Error type: " + response.getErrorType());
                }
                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] Updated bindings count: " + response.getUpdatedBindingsCount());
                }
                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] Result valueCase: " + response.getResult().getValueCase());
                }

                responseObserver.onNext(response);
                responseObserver.onCompleted();

                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] ========== evaluate() COMPLETED ==========");
                }

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
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] ========== executeCallback() RECEIVED ==========");
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Session: " + request.getSessionId());
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Function source length: " + request.getFunctionSource().length() + " chars");
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Function preview: " +
                        request.getFunctionSource().substring(0, Math.min(150, request.getFunctionSource().length()))
                        + "...");
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Arguments count: " + request.getArgumentsCount());
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Bindings count: " + request.getBindingsCount());
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Host functions count: " + request.getHostFunctionsCount());
            }
            if (log.isDebugEnabled()) {
                log.debug("[gRPC-Server] Callback socket path: " + request.getCallbackSocketPath());
            }

            long startTime = System.currentTimeMillis();
            try {
                // Serialize request and delegate to engine service
                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] Serializing request to bytes...");
                }
                byte[] requestBytes = request.toByteArray();
                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] Request bytes: " + requestBytes.length + " bytes");
                }

                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] >>> Delegating to JsEngineServiceImpl.handleExecuteCallback()...");
                }
                byte[] responseBytes = engineService.handleExecuteCallback(requestBytes);
                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] <<< JsEngineServiceImpl returned " + responseBytes.length + " bytes");
                }

                ExecuteCallbackResponse response = ExecuteCallbackResponse.parseFrom(responseBytes);
                long elapsed = System.currentTimeMillis() - startTime;
                long engineTime = response.getElapsedMs();
                long transportOverhead = elapsed - engineTime;

                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] ========== executeCallback() RESPONSE ==========");
                }
                if (log.isDebugEnabled()) {
                    log.debug(
                            "[gRPC-Server] Time breakdown: totalElapsed={}ms, engineProcessing={}ms, transportOverhead={}ms",
                            elapsed, engineTime, transportOverhead);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] Success: " + response.getSuccess());
                }
                if (!response.getSuccess()) {
                    log.error("[gRPC-Server] Error message: " + response.getErrorMessage());
                }
                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] Updated bindings count: " + response.getUpdatedBindingsCount());
                }
                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] Result valueCase: " + response.getResult().getValueCase());
                }

                responseObserver.onNext(response);
                responseObserver.onCompleted();

                if (log.isDebugEnabled()) {
                    log.debug("[gRPC-Server] ========== executeCallback() COMPLETED ==========");
                }

            } catch (Exception e) {
                log.error("[gRPC-Server] ========== executeCallback() FAILED ==========");
                log.error("[gRPC-Server] Exception type: " + e.getClass().getName());
                log.error("[gRPC-Server] Error during executeCallback", e);
                responseObserver.onError(e);
            }
        }
    }
}
