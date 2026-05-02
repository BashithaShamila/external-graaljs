# WSO2 Identity Server — External GraalJS

Externalised GraalJS adaptive-authentication runtime for WSO2 Identity Server (IS).

This repository ships **three independently buildable Maven projects** as a single
aggregator. Together they take adaptive-authentication script execution out of the
IS JVM and onto a dedicated process that the IS calls over a mutually-authenticated
gRPC channel.

```
external-graaljs/
├── pom.xml                                                                            ← aggregator
├── server/                                                                            ← out-of-process GraalJS runtime (jar + dist)
├── org.wso2.carbon.identity.application.authentication.framework.script.remote.engine/  ← OSGi bundle the IS uses to call the runtime
└── org.wso2.carbon.identity.application.authentication.framework.script.engine.mode/    ← OSGi bundle providing the default HYBRID resolver
```

| Module | Artifact | Purpose |
|---|---|---|
| `server/` | `org.wso2.identity.graaljs:wso2is-graaljs-runtime:1.0.0-SNAPSHOT` | Standalone gRPC server hosting the GraalJS polyglot context out-of-process. Distributable as a tarball. |
| `org.wso2.carbon.identity.application.authentication.framework.script.remote.engine/` | OSGi bundle `1.0.0-SNAPSHOT` | Implements `RemoteJsGraphBuilderProvider`. Owns `JsGraalGraphEngineModeRouter`, the `ScriptEngineModeResolver` SPI, the gRPC client transport, and the protobuf wire types. Goes into IS `dropins/`. |
| `org.wso2.carbon.identity.application.authentication.framework.script.engine.mode/` | OSGi bundle `1.0.0-SNAPSHOT` | Default implementation of `ScriptEngineModeResolver` (always returns LOCAL). Operators replace it by deploying their own bundle to drive HYBRID per-request routing. Optional. |

---

## Prerequisites

- **JDK 21** (Eclipse Temurin recommended). The IS framework jar transitively pulls in
  carbon dependencies compiled against class-file version 65, so JDK 11 fails at link time.
- **Maven 3.6+**.
- A locally-built or installed **`org.wso2.carbon.identity.application.authentication.framework`** artifact at version `7.10.150-SNAPSHOT` (the aggregator's `<framework.version>`). Build it once from `carbon-identity-framework`:
  ```bash
  cd /path/to/carbon-identity-framework/components/authentication-framework/org.wso2.carbon.identity.application.authentication.framework
  rm -rf target
  mvn install -Dmaven.test.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dmaven.javadoc.skip=true
  ```

---

## Build

### Build everything (aggregator)

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
cd external-graaljs
mvn clean install -Dmaven.test.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dmaven.javadoc.skip=true
```

Reactor order:

```
WSO2 Identity Server - GraalJS Runtime ............. SUCCESS  (server/)
WSO2 Identity Server - External GraalJS Aggregator . SUCCESS  (pom.xml)
WSO2 Carbon - Remote Script Engine ................. SUCCESS  (script.remote.engine bundle)
WSO2 Carbon - Script Engine Mode Resolver .......... SUCCESS  (script.engine.mode bundle)
```

All three artifacts land in `~/.m2/repository/...` after `install` and in their respective `target/` folders.

### Build a single module independently

Each module has its own `pom.xml` and can be built standalone — the aggregator just declares them so a single command at the top builds everything:

```bash
# Server only
cd external-graaljs/server
mvn clean install -Dmaven.test.skip=true ...

# OSGi bundle — script.remote.engine
cd external-graaljs/org.wso2.carbon.identity.application.authentication.framework.script.remote.engine
mvn clean install -Dmaven.test.skip=true ...

# OSGi bundle — script.engine.mode
cd external-graaljs/org.wso2.carbon.identity.application.authentication.framework.script.engine.mode
mvn clean install -Dmaven.test.skip=true ...
```

The bundle modules inherit shared dependency-management, plugin versions (`maven-bundle-plugin`, `maven-compiler-plugin`, `maven-surefire-plugin`), the WSO2 Maven repos, and the `<framework.version>` property from the aggregator pom — so when you build a single bundle Maven walks up via `relativePath=../pom.xml` to resolve those.

### Build outputs

| Module | `target/` artifacts |
|---|---|
| `server/` | `wso2is-graaljs-runtime-1.0.0-SNAPSHOT.jar`, `lib/*.jar`, `wso2is-graaljs-runtime-1.0.0-SNAPSHOT.tar.gz`, `.zip` |
| `…script.remote.engine/` | `org.wso2.carbon.identity.application.authentication.framework.script.remote.engine-1.0.0-SNAPSHOT.jar` |
| `…script.engine.mode/` | `org.wso2.carbon.identity.application.authentication.framework.script.engine.mode-1.0.0-SNAPSHOT.jar` |

---

## Deploy

### 1. The OSGi bundles — into the IS pack

Drop the two bundle jars into:

```
<IS_HOME>/repository/components/dropins/
```

After dropping the jars, restart the server (or `-Dosgi.clean=true` once to clear bundle resolution caches):

```bash
cd <IS_HOME>/bin
sh wso2server.sh run -Dosgi.clean=true
```

Confirm activation in the startup log:

```
INFO {…ScriptEngineModeResolverComponent} - DefaultScriptEngineModeResolver registered as OSGi service.
INFO {…RemoteScriptEngineComponent} - DefaultRemoteJsGraphBuilderProvider registered as OSGi service.
INFO {…FrameworkServiceComponent} - RemoteJsGraphBuilderProvider set: …DefaultRemoteJsGraphBuilderProvider
INFO {…JsGraalGraphBuilderFactory} - GraalJS engine mode: REMOTE
```

**LOCAL-only deployments don't need either bundle.** The framework jar runs the in-JVM
GraalJS engine on its own; the bundles are only required for `REMOTE` and `HYBRID` modes.

### 2. The runtime server — anywhere reachable from the IS

Extract the distribution and start it on the host that should host the script-execution process:

```bash
tar -xzf server/target/wso2is-graaljs-runtime-1.0.0-SNAPSHOT.tar.gz -C /opt
cd /opt/wso2is-graaljs-runtime-1.0.0-SNAPSHOT
sh bin/runtime.sh run                     # foreground
sh bin/runtime.sh start                   # background (writes runtime.pid)
sh bin/runtime.sh stop                    # SIGTERM via runtime.pid
sh bin/runtime.sh restart
sh bin/runtime.sh debug 5005              # JDWP on port 5005
sh bin/runtime.sh version
```

Windows: `bin\runtime.bat`.

---

## Configure

### IS-side (`<IS_HOME>/repository/conf/deployment.toml`)

```toml
[authentication.adaptive.graaljs]
# "LOCAL" (in-JVM, no bundles needed), "REMOTE" (always remote), or "HYBRID" (per-request).
engine_mode      = "REMOTE"

# host:port of the runtime server's gRPC listener.
grpc_target      = "localhost:50051"

# Verbose remote-engine tracing (PERF / debug logs). Off by default.
RemoteEngineTracing = false

# Statement limit for the local engine (LOCAL / HYBRID-LOCAL paths).
ScriptStatementsLimit = 5000
```

These map to:

| TOML key | Java property | Read by |
|---|---|---|
| `engine_mode` | `AdaptiveAuth.GraalJS.EngineMode` | Framework jar (`JsGraalGraphBuilderFactory`) **and** the new bundle's router |
| `grpc_target` | `AdaptiveAuth.GraalJS.GrpcTarget` | `JsGraalGraphEngineModeRouter` (in the new bundle) |
| `RemoteEngineTracing` | `AdaptiveAuth.GraalJS.RemoteEngineTracing` | `JsGraalGraphEngineModeRouter` |
| `ScriptStatementsLimit` | `AdaptiveAuth.GraalJS.ScriptStatementsLimit` | Framework jar |

**Loud-failure semantics:** if `engine_mode = "REMOTE"` or `"HYBRID"` and the
`script.remote.engine` bundle isn't deployed, the factory throws
`IllegalStateException` per request rather than silently using local. Drop the
jar to fix.

### Runtime server (`<runtime>/conf/deployment.properties`)

| Setting | Default | Override |
|---|---|---|
| gRPC listener port | `50051` | `server.port=…` or `-Dserver.port=…` |
| Worker thread pool | `10` | `server.thread.pool.size=…` or `-Dserver.thread.pool.size=…` |
| Script statement limit | `5000` | `script.statement.limit=…` or `-Dscript.statement.limit=…` |
| JVM heap | `-Xms256m -Xmx512m` | `JVM_MEM_OPTS` env var |
| Extra JVM options | — | `JAVA_OPTS` env var |

Positional CLI args (`runtime.sh run [port] [limit] [pool]`) take precedence over both system properties and `deployment.properties`.

### mTLS

The gRPC stream carries the full `JsAuthenticationContext` (username, tenant
domain, claims, session id) and host-function payloads, so plaintext is
rejected at startup. mTLS is mandatory.

By default the runtime loads keystore + truststore from its bundled classpath
resources (`wso2carbon.p12` / `client-truststore.p12`, the same material the IS
pack ships). Override via `conf/deployment.properties` or system properties:

```
-Dmtls.keystore.path=/opt/wso2is/repository/resources/security/wso2carbon.p12
-Dmtls.keystore.password=…
-Dmtls.truststore.path=/opt/wso2is/repository/resources/security/client-truststore.p12
-Dmtls.truststore.password=…
```

---

## Engine modes — choosing one

| Mode | Behaviour | Bundles required | Runtime server required |
|---|---|---|---|
| `LOCAL` | All adaptive scripts run inside the IS JVM via GraalJS Polyglot. Original behaviour. | None | No |
| `REMOTE` | Every adaptive script call goes to the runtime over gRPC. | Both bundles | Yes |
| `HYBRID` | Per-request decision via `ScriptEngineModeResolver`. The default resolver (`script.engine.mode` bundle) returns LOCAL for everything; deploy your own resolver bundle in `dropins/` to route by tenant, SP, claim, etc. | Both bundles | Yes (for the requests routed remote) |

To replace the default HYBRID resolver, ship an OSGi bundle that implements
`org.wso2.carbon.identity.application.authentication.framework.config.model.graph.graaljs.remote.ScriptEngineModeResolver`
and registers it as a service with a higher service ranking. The framework's
`@Reference(policy=DYNAMIC)` will pick it up at runtime.

---

## Architecture

```
WSO2 Identity Server (JVM)
 ┌─────────────────────────────────────────────────────────────┐
 │ JsGraalGraphBuilderFactory                                  │   ← reads engine_mode
 │   ├─ LOCAL  → JsGraalGraphBuilder (in-JVM GraalJS)          │
 │   └─ REMOTE / HYBRID → RemoteJsGraphBuilderProvider (OSGi)  │
 └─────────────────────────────────────────────────────────────┘
                       │
                       │  (script.remote.engine bundle in dropins/)
                       ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ RemoteJsGraalGraphBuilder + RemoteJsEngine                  │
 │   gRPC client + protobuf wire types                         │
 └─────────────────────────────────────────────────────────────┘
                       │
                       │  gRPC over mTLS (bidirectional streaming)
                       ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ External GraalJS Runtime (server/)                          │
 │   ├─ JsEngineStreamingService (Evaluate, ExecuteCallback)   │
 │   └─ HostCallbackClient (callbacks to IS for executeStep,   │
 │       sendError, getSecretByName, …)                        │
 │                                                             │
 │   GraalJS Context (isolated, single-threaded per session)   │
 └─────────────────────────────────────────────────────────────┘
```

The protocol is defined in `server/proto/js_engine.proto` and `server/proto/js_engine_grpc.proto`. The same `.proto` files are committed (with matching `java_package` options) under `…script.remote.engine/src/main/proto/`, regenerated via `mvn protobuf:compile protobuf:compile-custom` if the schema changes.

---

## Day-to-day workflow

```bash
# 1. Bump or change framework code in carbon-identity-framework, rebuild framework jar.
cd /path/to/carbon-identity-framework/components/authentication-framework/org.wso2.carbon.identity.application.authentication.framework
rm -rf target && mvn install -Dmaven.test.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dmaven.javadoc.skip=true

# 2. Rebuild the OSGi bundles + runtime in one go.
cd /path/to/external-graaljs
mvn clean install -Dmaven.test.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true -Dmaven.javadoc.skip=true

# 3. Replace jars in the IS pack.
cp /path/to/carbon-identity-framework/components/authentication-framework/org.wso2.carbon.identity.application.authentication.framework/target/org.wso2.carbon.identity.application.authentication.framework-7.10.150-SNAPSHOT.jar  <IS_HOME>/repository/components/plugins/
cp org.wso2.carbon.identity.application.authentication.framework.script.remote.engine/target/*.jar  <IS_HOME>/repository/components/dropins/
cp org.wso2.carbon.identity.application.authentication.framework.script.engine.mode/target/*.jar    <IS_HOME>/repository/components/dropins/

# 4. Restart the runtime server with fresh jar.
pkill -F /opt/wso2is-graaljs-runtime-*/runtime.pid 2>/dev/null
cp server/target/wso2is-graaljs-runtime-1.0.0-SNAPSHOT.jar  /opt/wso2is-graaljs-runtime-*/lib/
sh /opt/wso2is-graaljs-runtime-*/bin/runtime.sh run

# 5. Restart IS.
sh <IS_HOME>/bin/wso2server.sh run -Dosgi.clean=true
```

Capture logs side-by-side:

```bash
sh <IS_HOME>/bin/wso2server.sh run -Dosgi.clean=true 2>&1 | tee /tmp/is.log
sh /opt/wso2is-graaljs-runtime-*/bin/runtime.sh run    2>&1 | tee /tmp/runtime.log
```

---

## Bumping the IS framework version

Single-line change in `external-graaljs/pom.xml`:

```xml
<framework.version>7.10.150-SNAPSHOT</framework.version>
```

Both bundle poms reference `${framework.version}` for their `org.wso2.carbon.identity.application.authentication.framework` dep, so changing it here propagates everywhere.

---

## Module-by-module reference

### `server/` — the runtime

Distribution layout (after `tar -xzf …tar.gz`):

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

Source layout:

```
server/
├── pom.xml
├── proto/                                gRPC service definitions
├── src/
│   ├── assembly/bin.xml                  Distribution descriptor
│   └── main/
│       ├── java/.../External/            Runtime sources
│       └── resources/certs/              Bundled default keystore + truststore
├── bin/                                  Source for the distribution launchers
├── conf/                                 Source for the distribution configuration
└── repository/                           Skeleton for runtime files (logs, security overrides)
```

### `…script.remote.engine/` — the IS-side gRPC client bundle

```
src/main/java/…/graaljs/remote/
├── DefaultRemoteJsGraphBuilderProvider.java   ← what the framework consumes
├── RemoteJsGraalGraphBuilder.java             ← per-request graph builder (gRPC delegate)
├── RemoteJsEngine.java                        ← session-scoped engine
├── JsGraalGraphEngineModeRouter.java          ← reads grpc_target / tracing / engine_mode
├── ScriptEngineModeResolver.java              ← SPI for HYBRID resolution
├── Serializer / ArgumentAdapter / ProxyTypeResolver / …  ← protobuf <-> JVM glue
├── proto/                                     ← generated protobuf classes (gitted)
├── proto/grpc/                                ← generated gRPC stubs (gitted)
├── server/                                    ← gRPC channel + connection mgmt (private package)
└── internal/
    ├── RemoteScriptEngineComponent.java       ← @Reference ScriptEngineModeResolver, registers the provider
    └── RemoteScriptEngineDataHolder.java
src/main/proto/                                ← .proto sources (regenerate stubs from here)
src/main/resources/META-INF/services/          ← gRPC NameResolverProvider / ManagedChannelProvider files (must merge across grpc-core + grpc-netty-shaded)
src/test/                                      ← unit tests for serializer / argument adapter / proxy cache / property navigator
```

Regenerating protobuf stubs (only when `.proto` files change):

```bash
cd org.wso2.carbon.identity.application.authentication.framework.script.remote.engine
mvn protobuf:compile protobuf:compile-custom
# stubs land under src/main/java/…/graaljs/remote/proto/ and proto/grpc/
```

### `…script.engine.mode/` — the default HYBRID resolver bundle

Tiny — two classes:

```
src/main/java/…/script/engine/mode/
├── DefaultScriptEngineModeResolver.java       ← always returns LOCAL (safe default)
└── internal/
    └── ScriptEngineModeResolverComponent.java ← OSGi component, registers the resolver
```

To override: write a bundle that implements
`org.wso2.carbon.identity.application.authentication.framework.config.model.graph.graaljs.remote.ScriptEngineModeResolver`
and registers it with a higher service ranking. The new bundle's
`RemoteScriptEngineComponent` will rebind to it via its DYNAMIC `@Reference`.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `IllegalStateException: Remote JavaScript execution requested but no RemoteJsGraphBuilderProvider OSGi service is registered.` | `engine_mode = REMOTE` or `HYBRID` but the `script.remote.engine` bundle isn't deployed. | Drop the bundle jar into `<IS_HOME>/repository/components/dropins/` and restart with `-Dosgi.clean=true`. |
| `BundleException: Unresolved requirement: Import-Package: org.graalvm.polyglot;version="…"` on IS startup. | Old version of the bundle pinning a stale Graal SDK version range. | Rebuild the bundle from current source. The pom uses unversioned `org.graalvm.polyglot` import + `DynamicImport-Package: *` to avoid this. |
| `io.grpc.ManagedChannelRegistry$ProviderNotFoundException: No functional channel service provider found.` | gRPC `META-INF/services/` files missing inside the bundle. | Confirm `src/main/resources/META-INF/services/` contains `io.grpc.ManagedChannelProvider`, `io.grpc.NameResolverProvider`, `io.grpc.LoadBalancerProvider`, `io.grpc.ServerProvider`. Rebuild. |
| `NoClassDefFoundError: …/internal/FrameworkServiceDataHolder` from `RemoteJsGraalGraphBuilder`. | Framework jar is hiding `…internal` as a Private-Package. | Use the framework jar from this codebase — its pom drops `Private-Package` so `internal` is exported. |
| Framework jar build error: `bad class file: … class file has wrong version 65.0, should be 55.0`. | Maven running on JDK 11 against JDK-21-compiled cached carbon jars. | `export JAVA_HOME=…/temurin-21.jdk/Contents/Home` and rebuild. |
| Framework jar build error: `The default package '.' is not permitted by the Import-Package syntax.` | Stale `target/` from an earlier build. | `rm -rf target && mvn install …` (without `clean`). |

---

## License

Apache License, Version 2.0. See [LICENSE.txt](LICENSE.txt).
