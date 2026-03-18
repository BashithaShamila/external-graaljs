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

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse;
import org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest;
import org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse;
import org.wso2.carbon.identity.graaljs.proto.grpc.HostCallbackServiceGrpc;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * gRPC implementation of CallbackClient.
 * Connects to the Identity Server's gRPC callback service to invoke host functions
 * and access context properties.
 */
public class GrpcCallbackClient implements CallbackClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcCallbackClient.class);

    private final String grpcTarget;
    private ManagedChannel channel;
    private HostCallbackServiceGrpc.HostCallbackServiceBlockingStub blockingStub;

    /**
     * Create a new gRPC callback client.
     *
     * @param grpcTarget The gRPC target address (host:port).
     */
    public GrpcCallbackClient(String grpcTarget) {
        this.grpcTarget = grpcTarget;
        log.info("[gRPC-Callback] Created GrpcCallbackClient for target: " + grpcTarget);
    }

    @Override
    public HostFunctionResponse invokeHostFunction(HostFunctionRequest request) throws IOException {
        log.info("[gRPC-Callback] ========== invokeHostFunction() ==========");
        log.info("[gRPC-Callback] Target: " + grpcTarget);
        log.info("[gRPC-Callback] Session: " + request.getSessionId());
        log.info("[gRPC-Callback] Function: " + request.getFunctionName());
        log.info("[gRPC-Callback] Arguments count: " + request.getArgumentsCount());
        for (int i = 0; i < request.getArgumentsCount(); i++) {
            log.info("[gRPC-Callback] Arg[" + i + "] valueCase: " + request.getArguments(i).getValueCase());
        }

        ensureConnected();
        long startTime = System.currentTimeMillis();
        try {
            log.info("[gRPC-Callback] >>> Sending invokeHostFunction to IS...");
            HostFunctionResponse response = blockingStub
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .invokeHostFunction(request);
            long elapsed = System.currentTimeMillis() - startTime;

            log.info("[gRPC-Callback] <<< Received response in " + elapsed + "ms");
            log.info("[gRPC-Callback] Success: " + response.getSuccess());
            if (!response.getSuccess()) {
                log.error("[gRPC-Callback] Error: " + response.getErrorMessage());
            }
            log.info("[gRPC-Callback] Result valueCase: " + response.getResult().getValueCase());
            log.info("[gRPC-Callback] ========== invokeHostFunction() COMPLETED ==========");
            return response;
        } catch (StatusRuntimeException e) {
            log.error("[gRPC-Callback] ========== invokeHostFunction() FAILED ==========");
            log.error("[gRPC-Callback] Status code: " + e.getStatus().getCode());
            log.error("[gRPC-Callback] Status description: " + e.getStatus().getDescription());
            log.error("[gRPC-Callback] Error invoking host function", e);
            throw new IOException("gRPC call failed: " + e.getStatus(), e);
        }
    }

    @Override
    public ContextPropertyResponse getContextProperty(ContextPropertyRequest request) throws IOException {
        log.info("[gRPC-Callback] ========== getContextProperty() ==========");
        log.info("[gRPC-Callback] Target: " + grpcTarget);
        log.info("[gRPC-Callback] Session: " + request.getSessionId());
        log.info("[gRPC-Callback] PropertyPath: " + request.getPropertyPath());
        log.info("[gRPC-Callback] ProxyType: " + request.getProxyType());

        ensureConnected();
        long startTime = System.currentTimeMillis();
        try {
            log.info("[gRPC-Callback] >>> Sending getContextProperty to IS...");
            ContextPropertyResponse response = blockingStub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getContextProperty(request);
            long elapsed = System.currentTimeMillis() - startTime;

            log.info("[gRPC-Callback] <<< Received response in " + elapsed + "ms");
            log.info("[gRPC-Callback] Success: " + response.getSuccess());
            log.info("[gRPC-Callback] IsProxy: " + response.getIsProxy());
            log.info("[gRPC-Callback] ProxyType: " + response.getProxyType());
            log.info("[gRPC-Callback] MemberKeys count: " + response.getMemberKeysCount());
            if (!response.getSuccess()) {
                log.error("[gRPC-Callback] Error: " + response.getErrorMessage());
            }
            log.info("[gRPC-Callback] ========== getContextProperty() COMPLETED ==========");
            return response;
        } catch (StatusRuntimeException e) {
            log.error("[gRPC-Callback] ========== getContextProperty() FAILED ==========");
            log.error("[gRPC-Callback] Status code: " + e.getStatus().getCode());
            log.error("[gRPC-Callback] Error getting context property", e);
            throw new IOException("gRPC call failed: " + e.getStatus(), e);
        }
    }

    @Override
    public ContextPropertySetResponse setContextProperty(ContextPropertySetRequest request) throws IOException {
        log.info("[gRPC-Callback] ========== setContextProperty() ==========");
        log.info("[gRPC-Callback] Target: " + grpcTarget);
        log.info("[gRPC-Callback] Session: " + request.getSessionId());
        log.info("[gRPC-Callback] PropertyPath: " + request.getPropertyPath());
        log.info("[gRPC-Callback] Value valueCase: " + request.getValue().getValueCase());

        ensureConnected();
        long startTime = System.currentTimeMillis();
        try {
            log.info("[gRPC-Callback] >>> Sending setContextProperty to IS...");
            ContextPropertySetResponse response = blockingStub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .setContextProperty(request);
            long elapsed = System.currentTimeMillis() - startTime;

            log.info("[gRPC-Callback] <<< Received response in " + elapsed + "ms");
            log.info("[gRPC-Callback] Success: " + response.getSuccess());
            if (!response.getSuccess()) {
                log.error("[gRPC-Callback] Error: " + response.getErrorMessage());
            }
            log.info("[gRPC-Callback] ========== setContextProperty() COMPLETED ==========");
            return response;
        } catch (StatusRuntimeException e) {
            log.error("[gRPC-Callback] ========== setContextProperty() FAILED ==========");
            log.error("[gRPC-Callback] Status code: " + e.getStatus().getCode());
            log.error("[gRPC-Callback] Error setting context property", e);
            throw new IOException("gRPC call failed: " + e.getStatus(), e);
        }
    }

    @Override
    public void connect() throws IOException {
        log.info("[gRPC-Callback] ========== connect() ==========");
        if (channel != null && !channel.isShutdown()) {
            log.info("[gRPC-Callback] Already connected to: " + grpcTarget);
            return;
        }

        log.info("[gRPC-Callback] Creating new channel to: " + grpcTarget);
        channel = ManagedChannelBuilder.forTarget(grpcTarget)
                .usePlaintext()
                .idleTimeout(60, TimeUnit.SECONDS)
                .build();

        blockingStub = HostCallbackServiceGrpc.newBlockingStub(channel);
        log.info("[gRPC-Callback] Connected successfully to: " + grpcTarget);
        log.info("[gRPC-Callback] ========== connect() COMPLETED ==========");
    }

    @Override
    public boolean isConnected() {
        boolean connected = channel != null && !channel.isShutdown();
        log.info("[gRPC-Callback] isConnected(): " + connected);
        return connected;
    }

    @Override
    public void close() throws IOException {
        log.info("[gRPC-Callback] ========== close() ==========");
        if (channel != null) {
            log.info("[gRPC-Callback] Closing connection to: " + grpcTarget);
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                log.info("[gRPC-Callback] Connection closed successfully");
            } catch (InterruptedException e) {
                log.warn("[gRPC-Callback] Interrupted during shutdown, forcing...", e);
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        } else {
            log.info("[gRPC-Callback] No channel to close");
        }
        log.info("[gRPC-Callback] ========== close() COMPLETED ==========");
    }

    /**
     * Ensure the client is connected before making calls.
     */
    private void ensureConnected() throws IOException {
        log.info("[gRPC-Callback] ensureConnected() - checking connection...");
        if (!isConnected()) {
            log.info("[gRPC-Callback] Not connected, connecting now...");
            connect();
        } else {
            log.info("[gRPC-Callback] Already connected, reusing channel");
        }
    }
}
