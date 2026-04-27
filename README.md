# WSO2 Identity Server — GraalJS Runtime

Externalised GraalJS script-execution runtime for WSO2 Identity Server adaptive
authentication. The runtime hosts the GraalJS polyglot context out-of-process
and serves the IS over a mutually-authenticated gRPC channel.

## Prerequisites

- JDK 11–21
- Maven 3.6+

## Build

```bash
mvn clean install
```

This produces:

- `target/wso2is-graaljs-runtime-1.0.0-SNAPSHOT.jar` — runtime jar
- `target/lib/*.jar` — runtime dependencies
- `target/wso2is-graaljs-runtime-1.0.0-SNAPSHOT.tar.gz` — distribution
- `target/wso2is-graaljs-runtime-1.0.0-SNAPSHOT.zip` — distribution

The protobuf and gRPC stubs are generated during the `generate-sources` phase,
so a single `mvn clean install` is sufficient.

## Distribution layout

```
wso2is-graaljs-runtime-1.0.0-SNAPSHOT/
├── bin/
│   ├── runtime.sh           Linux / macOS launcher (start | stop | restart | run | debug | version)
│   ├── runtime.bat          Windows launcher
│   └── version.txt
├── conf/
│   ├── deployment.properties     Tunable settings (port, statement limit, thread pool, mTLS overrides)
│   ├── log4j2.properties         Reserved for log4j2 (placeholder)
│   └── simplelogger.properties   slf4j-simple configuration
├── lib/                          Runtime jar + dependencies
├── repository/
│   ├── logs/                     Heap dumps and runtime logs
│   └── resources/security/       Operator-supplied keystore / truststore overrides
├── LICENSE.txt
├── NOTICE
└── README.md
```

## Run

Extract the distribution archive and use the launcher:

```bash
tar -xzf wso2is-graaljs-runtime-1.0.0-SNAPSHOT.tar.gz
cd wso2is-graaljs-runtime-1.0.0-SNAPSHOT
sh bin/runtime.sh run             # foreground
sh bin/runtime.sh start           # background (writes runtime.pid)
sh bin/runtime.sh stop            # SIGTERM via runtime.pid
sh bin/runtime.sh restart
sh bin/runtime.sh debug 5005      # JDWP on port 5005
sh bin/runtime.sh version
```

Windows: substitute `bin\runtime.bat`.

## Configuration

| Setting | Default | Override |
|---|---|---|
| gRPC listener port | `50051` | `server.port` in `deployment.properties` or `-Dserver.port` |
| Script statement limit | `5000` | `script.statement.limit` or `-Dscript.statement.limit` |
| Worker thread pool size | `10` | `server.thread.pool.size` or `-Dserver.thread.pool.size` |
| JVM heap | `-Xms256m -Xmx512m` | `JVM_MEM_OPTS` env var |
| Extra JVM options | — | `JAVA_OPTS` env var |

Positional CLI args (`runtime.sh run [port] [limit] [pool]`) take precedence
over both system properties and `deployment.properties`.

## mTLS

mTLS is mandatory: the gRPC stream carries the full `JsAuthenticationContext`
(username, tenant domain, claims, session id) plus host-function payloads,
so plaintext is rejected at startup.

By default the runtime loads keystore + truststore from its bundled classpath
resources (the same `wso2carbon.p12` / `client-truststore.p12` the IS pack
ships). Override the locations by un-commenting the `mtls.*` block in
`conf/deployment.properties`, or via system properties:

```
-Dmtls.keystore.path=/opt/wso2is/repository/resources/security/wso2carbon.p12
-Dmtls.keystore.password=...
-Dmtls.truststore.path=/opt/wso2is/repository/resources/security/client-truststore.p12
-Dmtls.truststore.password=...
```

## Architecture

```
Identity Server (JVM)
    │
    │ gRPC over mTLS (bidirectional streaming)
    ▼
GraalJS Runtime
    │
    ├── JsEngineStreamingService (Evaluate, ExecuteCallback)
    └── HostCallbackClient (callbacks to IS for executeStep, sendError, ...)
    │
    ▼
GraalJS Context (isolated, single-threaded per session)
```

The protocol is defined in `proto/js_engine.proto` and `proto/js_engine_grpc.proto`.

## Project structure

```
external-graaljs/
├── pom.xml
├── proto/                 gRPC service definitions
├── src/
│   ├── assembly/bin.xml   Distribution descriptor
│   └── main/
│       ├── java/.../External/   Runtime sources
│       └── resources/certs/     Bundled default keystore + truststore
├── bin/                   Source for the distribution launchers
├── conf/                  Source for the distribution configuration
└── repository/            Skeleton for runtime files (logs, security overrides)
```
