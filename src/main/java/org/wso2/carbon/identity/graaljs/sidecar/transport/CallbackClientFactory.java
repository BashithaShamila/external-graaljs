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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Factory for creating callback client implementations.
 * Automatically detects the transport type from the callback address format and
 * creates the appropriate callback client.
 * <p>
 * Address format examples:
 * - UDS: "/tmp/callback.sock" or "file:///tmp/callback.sock"
 * - gRPC: "grpc://localhost:50052" or "localhost:50052"
 * <p>
 * Usage:
 * <pre>
 * CallbackClient client = CallbackClientFactory.createClient(
 *     request.getCallbackSocketPath(),
 *     request.getSessionId()
 * );
 * client.connect();
 * </pre>
 */
public class CallbackClientFactory {

    private static final Logger log = LoggerFactory.getLogger(CallbackClientFactory.class);

    /**
     * Create a callback client based on the address format.
     * Automatically detects the transport type and creates the appropriate client.
     *
     * @param callbackAddress Address where Identity Server callback server is listening.
     * @param sessionId Session identifier.
     * @return CallbackClient implementation.
     * @throws IOException if address format is invalid or transport is not supported.
     */
    public static CallbackClient createClient(String callbackAddress, String sessionId) throws IOException {

        if (callbackAddress == null || callbackAddress.isEmpty()) {
            throw new IOException("Callback address cannot be null or empty");
        }

        // Detect transport type from address format
        String transportType = detectTransportType(callbackAddress);

        log.info("[CallbackClientFactory] Creating {} callback client for address: {}",
                transportType, callbackAddress);

        switch (transportType) {
            case "UDS":
                // UDS address: /tmp/socket.sock or file:///tmp/socket.sock
                String socketPath = callbackAddress.replace("file://", "");
                return new UdsCallbackClient(sessionId, socketPath);

            case "GRPC":
                throw new IOException("gRPC callback client is not supported in this context. " +
                        "Use bidirectional streaming transport (GrpcStreamingServerTransport) instead.");

            default:
                throw new IOException("Unknown transport type '" + transportType +
                        "' for callback address: " + callbackAddress);
        }
    }

    /**
     * Detect transport type from callback address format.
     * <p>
     * Detection logic:
     * - "grpc://" prefix or "host:port" format → GRPC
     * - Starts with "/" or "file://" → UDS
     * - Default → UDS (for backward compatibility)
     *
     * @param address Callback address.
     * @return Transport type: "UDS" or "GRPC".
     */
    private static String detectTransportType(String address) {
        if (address.startsWith("grpc://")) {
            return "GRPC";
        } else if (address.matches("^[a-zA-Z0-9.-]+:\\d+$")) {
            // Matches "host:port" format - assume gRPC
            return "GRPC";
        } else if (address.startsWith("/") || address.startsWith("file://")) {
            return "UDS";
        } else {
            // Default to UDS for backward compatibility
            log.warn("[CallbackClientFactory] Cannot detect transport type from address: {}. " +
                    "Defaulting to UDS.", address);
            return "UDS";
        }
    }

    /**
     * Private constructor - this is a utility class.
     */
    private CallbackClientFactory() {
        // Utility class - no instances
    }
}
