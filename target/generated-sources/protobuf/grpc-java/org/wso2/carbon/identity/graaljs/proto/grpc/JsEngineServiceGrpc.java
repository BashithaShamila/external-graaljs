package org.wso2.carbon.identity.graaljs.proto.grpc;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 **
 * JsEngineService - Main service for JavaScript evaluation
 * Used by RemoteJsEngine to send evaluation and callback execution requests
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.51.1)",
    comments = "Source: js_engine_grpc.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class JsEngineServiceGrpc {

  private JsEngineServiceGrpc() {}

  public static final String SERVICE_NAME = "org.wso2.carbon.identity.graaljs.engine.proto.JsEngineService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.EvaluateRequest,
      org.wso2.carbon.identity.graaljs.proto.EvaluateResponse> getEvaluateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Evaluate",
      requestType = org.wso2.carbon.identity.graaljs.proto.EvaluateRequest.class,
      responseType = org.wso2.carbon.identity.graaljs.proto.EvaluateResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.EvaluateRequest,
      org.wso2.carbon.identity.graaljs.proto.EvaluateResponse> getEvaluateMethod() {
    io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.EvaluateRequest, org.wso2.carbon.identity.graaljs.proto.EvaluateResponse> getEvaluateMethod;
    if ((getEvaluateMethod = JsEngineServiceGrpc.getEvaluateMethod) == null) {
      synchronized (JsEngineServiceGrpc.class) {
        if ((getEvaluateMethod = JsEngineServiceGrpc.getEvaluateMethod) == null) {
          JsEngineServiceGrpc.getEvaluateMethod = getEvaluateMethod =
              io.grpc.MethodDescriptor.<org.wso2.carbon.identity.graaljs.proto.EvaluateRequest, org.wso2.carbon.identity.graaljs.proto.EvaluateResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Evaluate"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.wso2.carbon.identity.graaljs.proto.EvaluateRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.wso2.carbon.identity.graaljs.proto.EvaluateResponse.getDefaultInstance()))
              .setSchemaDescriptor(new JsEngineServiceMethodDescriptorSupplier("Evaluate"))
              .build();
        }
      }
    }
    return getEvaluateMethod;
  }

  private static volatile io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest,
      org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse> getExecuteCallbackMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ExecuteCallback",
      requestType = org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest.class,
      responseType = org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest,
      org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse> getExecuteCallbackMethod() {
    io.grpc.MethodDescriptor<org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest, org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse> getExecuteCallbackMethod;
    if ((getExecuteCallbackMethod = JsEngineServiceGrpc.getExecuteCallbackMethod) == null) {
      synchronized (JsEngineServiceGrpc.class) {
        if ((getExecuteCallbackMethod = JsEngineServiceGrpc.getExecuteCallbackMethod) == null) {
          JsEngineServiceGrpc.getExecuteCallbackMethod = getExecuteCallbackMethod =
              io.grpc.MethodDescriptor.<org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest, org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ExecuteCallback"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse.getDefaultInstance()))
              .setSchemaDescriptor(new JsEngineServiceMethodDescriptorSupplier("ExecuteCallback"))
              .build();
        }
      }
    }
    return getExecuteCallbackMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static JsEngineServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<JsEngineServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<JsEngineServiceStub>() {
        @java.lang.Override
        public JsEngineServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new JsEngineServiceStub(channel, callOptions);
        }
      };
    return JsEngineServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static JsEngineServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<JsEngineServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<JsEngineServiceBlockingStub>() {
        @java.lang.Override
        public JsEngineServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new JsEngineServiceBlockingStub(channel, callOptions);
        }
      };
    return JsEngineServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static JsEngineServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<JsEngineServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<JsEngineServiceFutureStub>() {
        @java.lang.Override
        public JsEngineServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new JsEngineServiceFutureStub(channel, callOptions);
        }
      };
    return JsEngineServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   **
   * JsEngineService - Main service for JavaScript evaluation
   * Used by RemoteJsEngine to send evaluation and callback execution requests
   * </pre>
   */
  public static abstract class JsEngineServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * Evaluate JavaScript code
     * </pre>
     */
    public void evaluate(org.wso2.carbon.identity.graaljs.proto.EvaluateRequest request,
        io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.EvaluateResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getEvaluateMethod(), responseObserver);
    }

    /**
     * <pre>
     * Execute a serialized callback function
     * </pre>
     */
    public void executeCallback(org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest request,
        io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getExecuteCallbackMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getEvaluateMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                org.wso2.carbon.identity.graaljs.proto.EvaluateRequest,
                org.wso2.carbon.identity.graaljs.proto.EvaluateResponse>(
                  this, METHODID_EVALUATE)))
          .addMethod(
            getExecuteCallbackMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest,
                org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse>(
                  this, METHODID_EXECUTE_CALLBACK)))
          .build();
    }
  }

  /**
   * <pre>
   **
   * JsEngineService - Main service for JavaScript evaluation
   * Used by RemoteJsEngine to send evaluation and callback execution requests
   * </pre>
   */
  public static final class JsEngineServiceStub extends io.grpc.stub.AbstractAsyncStub<JsEngineServiceStub> {
    private JsEngineServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected JsEngineServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new JsEngineServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Evaluate JavaScript code
     * </pre>
     */
    public void evaluate(org.wso2.carbon.identity.graaljs.proto.EvaluateRequest request,
        io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.EvaluateResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getEvaluateMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Execute a serialized callback function
     * </pre>
     */
    public void executeCallback(org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest request,
        io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getExecuteCallbackMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   **
   * JsEngineService - Main service for JavaScript evaluation
   * Used by RemoteJsEngine to send evaluation and callback execution requests
   * </pre>
   */
  public static final class JsEngineServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<JsEngineServiceBlockingStub> {
    private JsEngineServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected JsEngineServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new JsEngineServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Evaluate JavaScript code
     * </pre>
     */
    public org.wso2.carbon.identity.graaljs.proto.EvaluateResponse evaluate(org.wso2.carbon.identity.graaljs.proto.EvaluateRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getEvaluateMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Execute a serialized callback function
     * </pre>
     */
    public org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse executeCallback(org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getExecuteCallbackMethod(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   **
   * JsEngineService - Main service for JavaScript evaluation
   * Used by RemoteJsEngine to send evaluation and callback execution requests
   * </pre>
   */
  public static final class JsEngineServiceFutureStub extends io.grpc.stub.AbstractFutureStub<JsEngineServiceFutureStub> {
    private JsEngineServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected JsEngineServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new JsEngineServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Evaluate JavaScript code
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<org.wso2.carbon.identity.graaljs.proto.EvaluateResponse> evaluate(
        org.wso2.carbon.identity.graaljs.proto.EvaluateRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getEvaluateMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Execute a serialized callback function
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse> executeCallback(
        org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getExecuteCallbackMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_EVALUATE = 0;
  private static final int METHODID_EXECUTE_CALLBACK = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final JsEngineServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(JsEngineServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_EVALUATE:
          serviceImpl.evaluate((org.wso2.carbon.identity.graaljs.proto.EvaluateRequest) request,
              (io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.EvaluateResponse>) responseObserver);
          break;
        case METHODID_EXECUTE_CALLBACK:
          serviceImpl.executeCallback((org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackRequest) request,
              (io.grpc.stub.StreamObserver<org.wso2.carbon.identity.graaljs.proto.ExecuteCallbackResponse>) responseObserver);
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

  private static abstract class JsEngineServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    JsEngineServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return org.wso2.carbon.identity.graaljs.proto.grpc.JsEngineGrpcProtos.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("JsEngineService");
    }
  }

  private static final class JsEngineServiceFileDescriptorSupplier
      extends JsEngineServiceBaseDescriptorSupplier {
    JsEngineServiceFileDescriptorSupplier() {}
  }

  private static final class JsEngineServiceMethodDescriptorSupplier
      extends JsEngineServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    JsEngineServiceMethodDescriptorSupplier(String methodName) {
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
      synchronized (JsEngineServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new JsEngineServiceFileDescriptorSupplier())
              .addMethod(getEvaluateMethod())
              .addMethod(getExecuteCallbackMethod())
              .build();
        }
      }
    }
    return result;
  }
}
