package org.wso2.carbon.identity.graaljs.proto.grpc;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 **
 * HostCallbackService - Callback service for host function invocations
 * Used by External to call back to IS for host functions (executeStep, sendError, etc.)
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.51.1)",
    comments = "Source: js_engine_grpc.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class HostCallbackServiceGrpc {

  private HostCallbackServiceGrpc() {}

  public static final String SERVICE_NAME = "org.wso2.carbon.identity.graaljs.engine.proto.HostCallbackService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest,
      org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse> getInvokeHostFunctionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "InvokeHostFunction",
      requestType = org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest.class,
      responseType = org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest,
      org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse> getInvokeHostFunctionMethod() {
    io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest, org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse> getInvokeHostFunctionMethod;
    if ((getInvokeHostFunctionMethod = HostCallbackServiceGrpc.getInvokeHostFunctionMethod) == null) {
      synchronized (HostCallbackServiceGrpc.class) {
        if ((getInvokeHostFunctionMethod = HostCallbackServiceGrpc.getInvokeHostFunctionMethod) == null) {
          HostCallbackServiceGrpc.getInvokeHostFunctionMethod = getInvokeHostFunctionMethod =
              io.grpc.MethodDescriptor.<org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest, org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "InvokeHostFunction"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new HostCallbackServiceMethodDescriptorSupplier("InvokeHostFunction"))
              .build();
        }
      }
    }
    return getInvokeHostFunctionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest,
      org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse> getGetContextPropertyMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetContextProperty",
      requestType = org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest.class,
      responseType = org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest,
      org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse> getGetContextPropertyMethod() {
    io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest, org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse> getGetContextPropertyMethod;
    if ((getGetContextPropertyMethod = HostCallbackServiceGrpc.getGetContextPropertyMethod) == null) {
      synchronized (HostCallbackServiceGrpc.class) {
        if ((getGetContextPropertyMethod = HostCallbackServiceGrpc.getGetContextPropertyMethod) == null) {
          HostCallbackServiceGrpc.getGetContextPropertyMethod = getGetContextPropertyMethod =
              io.grpc.MethodDescriptor.<org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest, org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetContextProperty"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse.getDefaultInstance()))
              .setSchemaDescriptor(new HostCallbackServiceMethodDescriptorSupplier("GetContextProperty"))
              .build();
        }
      }
    }
    return getGetContextPropertyMethod;
  }

  private static volatile io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest,
      org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse> getSetContextPropertyMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SetContextProperty",
      requestType = org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest.class,
      responseType = org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest,
      org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse> getSetContextPropertyMethod() {
    io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest, org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse> getSetContextPropertyMethod;
    if ((getSetContextPropertyMethod = HostCallbackServiceGrpc.getSetContextPropertyMethod) == null) {
      synchronized (HostCallbackServiceGrpc.class) {
        if ((getSetContextPropertyMethod = HostCallbackServiceGrpc.getSetContextPropertyMethod) == null) {
          HostCallbackServiceGrpc.getSetContextPropertyMethod = getSetContextPropertyMethod =
              io.grpc.MethodDescriptor.<org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest, org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SetContextProperty"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse.getDefaultInstance()))
              .setSchemaDescriptor(new HostCallbackServiceMethodDescriptorSupplier("SetContextProperty"))
              .build();
        }
      }
    }
    return getSetContextPropertyMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static HostCallbackServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<HostCallbackServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<HostCallbackServiceStub>() {
        @java.lang.Override
        public HostCallbackServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new HostCallbackServiceStub(channel, callOptions);
        }
      };
    return HostCallbackServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static HostCallbackServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<HostCallbackServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<HostCallbackServiceBlockingStub>() {
        @java.lang.Override
        public HostCallbackServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new HostCallbackServiceBlockingStub(channel, callOptions);
        }
      };
    return HostCallbackServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static HostCallbackServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<HostCallbackServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<HostCallbackServiceFutureStub>() {
        @java.lang.Override
        public HostCallbackServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new HostCallbackServiceFutureStub(channel, callOptions);
        }
      };
    return HostCallbackServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   **
   * HostCallbackService - Callback service for host function invocations
   * Used by External to call back to IS for host functions (executeStep, sendError, etc.)
   * </pre>
   */
  public static abstract class HostCallbackServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * Invoke a host function from JavaScript
     * </pre>
     */
    public void invokeHostFunction(org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest request,
        io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getInvokeHostFunctionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Get a context property value (for dynamic context proxy)
     * </pre>
     */
    public void getContextProperty(org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest request,
        io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetContextPropertyMethod(), responseObserver);
    }

    /**
     * <pre>
     * Set a context property value (write-back from External)
     * </pre>
     */
    public void setContextProperty(org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest request,
        io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSetContextPropertyMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getInvokeHostFunctionMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest,
                org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse>(
                  this, METHODID_INVOKE_HOST_FUNCTION)))
          .addMethod(
            getGetContextPropertyMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest,
                org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse>(
                  this, METHODID_GET_CONTEXT_PROPERTY)))
          .addMethod(
            getSetContextPropertyMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest,
                org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse>(
                  this, METHODID_SET_CONTEXT_PROPERTY)))
          .build();
    }
  }

  /**
   * <pre>
   **
   * HostCallbackService - Callback service for host function invocations
   * Used by External to call back to IS for host functions (executeStep, sendError, etc.)
   * </pre>
   */
  public static final class HostCallbackServiceStub extends io.grpc.stub.AbstractAsyncStub<HostCallbackServiceStub> {
    private HostCallbackServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected HostCallbackServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new HostCallbackServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Invoke a host function from JavaScript
     * </pre>
     */
    public void invokeHostFunction(org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest request,
        io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getInvokeHostFunctionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Get a context property value (for dynamic context proxy)
     * </pre>
     */
    public void getContextProperty(org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest request,
        io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetContextPropertyMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Set a context property value (write-back from External)
     * </pre>
     */
    public void setContextProperty(org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest request,
        io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSetContextPropertyMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   **
   * HostCallbackService - Callback service for host function invocations
   * Used by External to call back to IS for host functions (executeStep, sendError, etc.)
   * </pre>
   */
  public static final class HostCallbackServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<HostCallbackServiceBlockingStub> {
    private HostCallbackServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected HostCallbackServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new HostCallbackServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Invoke a host function from JavaScript
     * </pre>
     */
    public org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse invokeHostFunction(org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getInvokeHostFunctionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Get a context property value (for dynamic context proxy)
     * </pre>
     */
    public org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse getContextProperty(org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetContextPropertyMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Set a context property value (write-back from External)
     * </pre>
     */
    public org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse setContextProperty(org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSetContextPropertyMethod(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   **
   * HostCallbackService - Callback service for host function invocations
   * Used by External to call back to IS for host functions (executeStep, sendError, etc.)
   * </pre>
   */
  public static final class HostCallbackServiceFutureStub extends io.grpc.stub.AbstractFutureStub<HostCallbackServiceFutureStub> {
    private HostCallbackServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected HostCallbackServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new HostCallbackServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Invoke a host function from JavaScript
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse> invokeHostFunction(
        org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getInvokeHostFunctionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Get a context property value (for dynamic context proxy)
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse> getContextProperty(
        org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetContextPropertyMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Set a context property value (write-back from External)
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse> setContextProperty(
        org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSetContextPropertyMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_INVOKE_HOST_FUNCTION = 0;
  private static final int METHODID_GET_CONTEXT_PROPERTY = 1;
  private static final int METHODID_SET_CONTEXT_PROPERTY = 2;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final HostCallbackServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(HostCallbackServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_INVOKE_HOST_FUNCTION:
          serviceImpl.invokeHostFunction((org.wso2.carbon.identity.graaljs.proto.HostFunctionRequest) request,
              (io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.HostFunctionResponse>) responseObserver);
          break;
        case METHODID_GET_CONTEXT_PROPERTY:
          serviceImpl.getContextProperty((org.wso2.carbon.identity.graaljs.proto.ContextPropertyRequest) request,
              (io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.ContextPropertyResponse>) responseObserver);
          break;
        case METHODID_SET_CONTEXT_PROPERTY:
          serviceImpl.setContextProperty((org.wso2.carbon.identity.graaljs.proto.ContextPropertySetRequest) request,
              (io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.ContextPropertySetResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class HostCallbackServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    HostCallbackServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return org.wso2.carbon.identity.graaljs.proto.grpc.JsEngineGrpcProtos.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("HostCallbackService");
    }
  }

  private static final class HostCallbackServiceFileDescriptorSupplier
      extends HostCallbackServiceBaseDescriptorSupplier {
    HostCallbackServiceFileDescriptorSupplier() {}
  }

  private static final class HostCallbackServiceMethodDescriptorSupplier
      extends HostCallbackServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    HostCallbackServiceMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (HostCallbackServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new HostCallbackServiceFileDescriptorSupplier())
              .addMethod(getInvokeHostFunctionMethod())
              .addMethod(getGetContextPropertyMethod())
              .addMethod(getSetContextPropertyMethod())
              .build();
        }
      }
    }
    return result;
  }
}
