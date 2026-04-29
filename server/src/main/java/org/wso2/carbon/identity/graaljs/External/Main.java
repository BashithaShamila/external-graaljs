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

package org.wso2.carbon.identity.graaljs.External;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.External.transport.GrpcStreamingServerTransport;

import java.io.IOException;

/**
 * Main entry point for the GraalJS runtime.
 * Uses gRPC bidirectional streaming transport.
 *
 * <p>Configuration precedence (highest first):</p>
 * <ol>
 *   <li>JVM system properties: {@code -Dserver.port}, {@code -Dscript.statement.limit},
 *       {@code -Dserver.thread.pool.size}.</li>
 *   <li>{@code conf/deployment.properties} under {@code -Dgraaljs.runtime.home}
 *       (or {@code -Dconf.location}).</li>
 *   <li>Positional CLI overrides: {@code [port] [statementLimit] [threadPoolSize]}.</li>
 *   <li>Built-in defaults.</li>
 * </ol>
 *
 * <p>The {@code bin/runtime.sh} / {@code bin/runtime.bat} scripts shipped with the
 * distribution provide the canonical invocation; raw {@code java -jar} usage is
 * supported for development.</p>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private GrpcStreamingServerTransport serverTransport;
    private JsEngineServiceImpl engineService;

    public static void main(String[] args) throws Exception {
        // Set up global exception handlers
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.println("[FATAL] Uncaught exception in thread " + thread.getName() + ": "
                    + throwable.getClass().getName());
            System.err.println("[FATAL] Error message: " + throwable.getMessage());
            throwable.printStackTrace(System.err);
            System.err.flush();
            log.error("[FATAL] Uncaught exception in thread " + thread.getName(), throwable);
        });

        Main main = new Main();
        main.parseArgsAndStart(args);
    }

    /**
     * Resolve runtime configuration and start the gRPC transport.
     * Positional CLI args are accepted as overrides for backward compatibility:
     * {@code [port] [statementLimit] [threadPoolSize]}. A leading {@code grpc}
     * keyword (legacy invocation) is silently consumed so older operator scripts
     * continue to work.
     *
     * @param args Command-line arguments.
     */
    private void parseArgsAndStart(String[] args) throws IOException {

        RuntimeConfig config = RuntimeConfig.load();

        // Backward compatibility: drop a leading "grpc" keyword if present.
        // Older operator scripts may still pass it; new bin/runtime.sh does not.
        int offset = 0;
        if (args.length > 0 && "grpc".equalsIgnoreCase(args[0])) {
            offset = 1;
        }

        int port = args.length > offset ? Integer.parseInt(args[offset]) : config.getPort();
        int statementLimit = args.length > offset + 1
                ? Integer.parseInt(args[offset + 1]) : config.getStatementLimit();
        int threadPoolSize = args.length > offset + 2
                ? Integer.parseInt(args[offset + 2]) : config.getThreadPoolSize();

        startGrpc(port, statementLimit, threadPoolSize);
    }

    /**
     * Start the runtime in gRPC mode.
     */
    private void startGrpc(int port, int statementLimit, int threadPoolSize) throws IOException {
        log.info("[Main] Starting GraalJS Runtime in gRPC mode");
        System.out.println("[Runtime-STARTUP] Starting WSO2 Identity GraalJS Runtime in gRPC mode");
        System.out.println("[Runtime-STARTUP] Port: " + port);
        System.out.println("[Runtime-STARTUP] Statement limit: " + statementLimit
                + ", Thread pool size: " + threadPoolSize);
        System.out.flush();

        // Create engine service
        engineService = new JsEngineServiceImpl(statementLimit);

        // Create bidirectional streaming gRPC transport
        serverTransport = new GrpcStreamingServerTransport(port, engineService);

        // Start server
        startServer();
    }

    /**
     * Start the server transport and wait.
     */
    private void startServer() throws IOException {
        // Start transport
        serverTransport.start();

        log.info("[Main] GraalJS Runtime started on: " + serverTransport.getAddress());
        System.out.println("[Runtime-STARTUP] GraalJS Runtime listening on: " + serverTransport.getAddress());
        System.out.flush();

        // Set up shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[Main] Shutting down GraalJS Runtime...");
            System.out.println("[Runtime-SHUTDOWN] Shutting down GraalJS Runtime...");
            System.out.flush();
            stop();
        }));

        // Keep main thread alive
        waitForever();
    }

    /**
     * Stop the server.
     */
    public void stop() {
        if (serverTransport != null) {
            try {
                serverTransport.stop();
            } catch (IOException e) {
                log.error("[Main] Error stopping server", e);
            }
        }
        log.info("[Main] GraalJS Runtime stopped");
    }

    /**
     * Keep the main thread alive while the server runs.
     * For UDS, the accept thread is daemon=false, so this isn't strictly needed,
     * but for gRPC it's required to keep the JVM alive.
     */
    private void waitForever() {
        try {
            while (serverTransport.isRunning()) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            log.info("[Main] Main thread interrupted");
            Thread.currentThread().interrupt();
        }
    }

}
