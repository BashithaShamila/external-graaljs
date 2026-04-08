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

import java.io.Closeable;
import java.io.IOException;

/**
 * Abstraction for External server transport.
 * Allows pluggable server implementations (UDS, gRPC, etc.) without changing the core engine logic.
 */
public interface ServerTransport extends Closeable {

    /**
     * Start the server and begin accepting connections.
     *
     * @throws IOException if the server cannot be started.
     */
    void start() throws IOException;

    /**
     * Stop the server and release resources.
     *
     * @throws IOException if the server cannot be stopped cleanly.
     */
    void stop() throws IOException;

    /**
     * Check if the server is currently running.
     *
     * @return true if the server is running, false otherwise.
     */
    boolean isRunning();

    /**
     * Get a human-readable description of this transport's address.
     * For UDS: the socket path
     * For gRPC: host:port
     *
     * @return transport address string.
     */
    String getAddress();

    /**
     * Close the server (alias for stop()).
     *
     * @throws IOException if the server cannot be closed cleanly.
     */
    @Override
    default void close() throws IOException {
        stop();
    }
}
