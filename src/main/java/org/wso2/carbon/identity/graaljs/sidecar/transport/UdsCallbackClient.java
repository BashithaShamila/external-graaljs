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
import org.wso2.carbon.identity.graaljs.proto.SerializedValue;
import org.wso2.carbon.identity.graaljs.sidecar.HostCallbackClient;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * UDS wrapper implementation of CallbackClient interface.
 * Adapts the CallbackClient interface to the existing HostCallbackClient implementation.
 */
public class UdsCallbackClient implements CallbackClient {

    private final HostCallbackClient delegate;

    /**
     * Create a new UDS callback client.
     *
     * @param sessionId        Session identifier.
     * @param callbackAddress  UDS socket path for callbacks.
     */
    public UdsCallbackClient(String sessionId, String callbackAddress) {
        this.delegate = new HostCallbackClient(sessionId, callbackAddress);
    }

    @Override
    public HostFunctionResponse invokeHostFunction(HostFunctionRequest request) throws IOException {
        // Extract function name and arguments from request
        String functionName = request.getFunctionName();

        // Deserialize arguments
        List<Object> args = new ArrayList<>();
        for (SerializedValue sv : request.getArgumentsList()) {
            Object arg = deserializeValue(sv);
            args.add(arg);
        }

        // Call delegate
        Object result = delegate.invokeHostFunction(functionName, args.toArray());

        // Build response
        return HostFunctionResponse.newBuilder()
                .setSuccess(true)
                .setResult(serializeValue(result))
                .build();
    }

    @Override
    public ContextPropertyResponse getContextProperty(ContextPropertyRequest request) throws IOException {
        return delegate.getContextProperty(request.getPropertyPath(), request.getProxyType());
    }

    @Override
    public ContextPropertySetResponse setContextProperty(ContextPropertySetRequest request) throws IOException {
        return delegate.setContextProperty(request.getPropertyPath(), "", request.getValue());
    }

    @Override
    public void connect() throws IOException {
        delegate.connect();
    }

    @Override
    public boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    /**
     * Deserialize a protobuf value to Java object.
     * Simplified version - proper implementation in JsEngineServiceImpl.
     */
    private Object deserializeValue(SerializedValue sv) {
        switch (sv.getValueCase()) {
            case STRING_VALUE:
                return sv.getStringValue();
            case INT_VALUE:
                return sv.getIntValue();
            case DOUBLE_VALUE:
                return sv.getDoubleValue();
            case BOOL_VALUE:
                return sv.getBoolValue();
            case NULL_VALUE:
                return null;
            default:
                return null;
        }
    }

    /**
     * Serialize a Java object to protobuf value.
     * Simplified version - proper implementation in JsEngineServiceImpl.
     */
    private SerializedValue serializeValue(Object value) {
        SerializedValue.Builder builder = SerializedValue.newBuilder();
        if (value == null) {
            builder.setNullValue(ByteString.EMPTY);
        } else if (value instanceof String) {
            builder.setStringValue((String) value);
        } else if (value instanceof Integer) {
            builder.setIntValue((Integer) value);
        } else if (value instanceof Double) {
            builder.setDoubleValue((Double) value);
        } else if (value instanceof Boolean) {
            builder.setBoolValue((Boolean) value);
        } else {
            builder.setNullValue(ByteString.EMPTY);
        }
        return builder.build();
    }
}
