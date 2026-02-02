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

import org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest;
import org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse;
import org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest;
import org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse;

import java.io.Closeable;
import java.io.IOException;

/**
 * Abstraction for callback client to Identity Server.
 * Enables sidecar to invoke host functions and access context properties from IS.
 */
public interface CallbackClient extends Closeable {

    /**
     * Invoke a host function on the Identity Server.
     *
     * @param request The host function request containing function name and arguments.
     * @return The host function response with result or error.
     * @throws IOException if communication fails.
     */
    HostFunctionResponse invokeHostFunction(HostFunctionRequest request) throws IOException;

    /**
     * Get a context property value from the Identity Server (for dynamic proxies).
     *
     * @param request The context property request with property path.
     * @return The context property response with value or proxy info.
     * @throws IOException if communication fails.
     */
    ContextPropertyResponse getContextProperty(ContextPropertyRequest request) throws IOException;

    /**
     * Set a context property value on the Identity Server (write-back).
     *
     * @param request The context property set request with property path and value.
     * @return The context property set response with success flag.
     * @throws IOException if communication fails.
     */
    ContextPropertySetResponse setContextProperty(ContextPropertySetRequest request) throws IOException;

    /**
     * Connect to the Identity Server callback endpoint.
     *
     * @throws IOException if connection fails.
     */
    void connect() throws IOException;

    /**
     * Check if connected to the Identity Server.
     *
     * @return true if connected, false otherwise.
     */
    boolean isConnected();

    /**
     * Close the callback client connection.
     *
     * @throws IOException if close fails.
     */
    @Override
    void close() throws IOException;
}
