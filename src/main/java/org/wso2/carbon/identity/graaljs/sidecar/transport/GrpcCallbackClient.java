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
    }

    @Override
    public HostFunctionResponse invokeHostFunction(HostFunctionRequest request) throws IOException {
        ensureConnected();
        try {
            return blockingStub
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .invokeHostFunction(request);
        } catch (StatusRuntimeException e) {
            log.error("[gRPC-Callback] Error invoking host function", e);
            throw new IOException("gRPC call failed: " + e.getStatus(), e);
        }
    }

    @Override
    public ContextPropertyResponse getContextProperty(ContextPropertyRequest request) throws IOException {
        ensureConnected();
        try {
            return blockingStub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getContextProperty(request);
        } catch (StatusRuntimeException e) {
            log.error("[gRPC-Callback] Error getting context property", e);
            throw new IOException("gRPC call failed: " + e.getStatus(), e);
        }
    }

    @Override
    public ContextPropertySetResponse setContextProperty(ContextPropertySetRequest request) throws IOException {
        ensureConnected();
        try {
            return blockingStub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .setContextProperty(request);
        } catch (StatusRuntimeException e) {
            log.error("[gRPC-Callback] Error setting context property", e);
            throw new IOException("gRPC call failed: " + e.getStatus(), e);
        }
    }

    @Override
    public void connect() throws IOException {
        if (channel != null && !channel.isShutdown()) {
            log.debug("[gRPC-Callback] Already connected");
            return;
        }

        log.info("[gRPC-Callback] Connecting to: " + grpcTarget);
        channel = ManagedChannelBuilder.forTarget(grpcTarget)
                .usePlaintext()
                .idleTimeout(60, TimeUnit.SECONDS)
                .build();

        blockingStub = HostCallbackServiceGrpc.newBlockingStub(channel);
        log.info("[gRPC-Callback] Connected to: " + grpcTarget);
    }

    @Override
    public boolean isConnected() {
        return channel != null && !channel.isShutdown();
    }

    @Override
    public void close() throws IOException {
        if (channel != null) {
            log.info("[gRPC-Callback] Closing connection");
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("[gRPC-Callback] Interrupted during shutdown", e);
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Ensure the client is connected before making calls.
     */
    private void ensureConnected() throws IOException {
        if (!isConnected()) {
            connect();
        }
    }
}
