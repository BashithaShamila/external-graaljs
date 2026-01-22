# GraalJS Sidecar

GraalJS sidecar for remote script evaluation via Unix Domain Sockets + Protocol Buffers.

## Prerequisites

- Java 11+
- Maven 3.6+

## Build

```bash
# Generate protobuf classes and build
mvn clean compile

# Package fat JAR
mvn package -DskipTests
```

## Run

```bash
# Using Maven
mvn exec:java -Dexec.mainClass=org.wso2.carbon.identity.graaljs.sidecar.Main \
    -Dexec.args="/var/run/graaljs.sock 5000"

# Using JAR (after package)
java -jar target/graaljs-sidecar-1.0.0-SNAPSHOT.jar /var/run/graaljs.sock 5000
```

**Arguments:**
1. `socketPath` - UDS socket path (default: `/var/run/graaljs.sock`)
2. `statementLimit` - JS statement limit (default: `5000`)

## Architecture

```
Identity Server (JVM)
    |
    | gRPC over UDS
    v
GraalJS Sidecar
    |
    |-- JsEngineService (Evaluate, ExecuteCallback)
    |-- HostCallbackClient (calls back to IS for executeStep, etc.)
    v
GraalJS Context (isolated, blocking)
```

## Protocol

See `proto/js_engine.proto` for the full gRPC service definition.

### Services

- **JsEngineService** - Sidecar provides this
  - `Evaluate` - Evaluate a script
  - `ExecuteCallback` - Execute a callback function
  - `CreateSession` / `CloseSession` - Session management

- **HostCallbackService** - IS provides this
  - `InvokeHostFunction` - Called by sidecar when JS invokes `executeStep`, `sendError`, etc.
