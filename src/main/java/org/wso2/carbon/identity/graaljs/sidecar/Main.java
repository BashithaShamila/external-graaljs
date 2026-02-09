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

package org.wso2.carbon.identity.graaljs.sidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.graaljs.sidecar.transport.GrpcStreamingServerTransport;
import org.wso2.carbon.identity.graaljs.sidecar.transport.ServerTransport;
import org.wso2.carbon.identity.graaljs.sidecar.transport.UdsServerTransport;

import java.io.IOException;

/**
 * Main entry point for the GraalJS sidecar server.
 * Supports both UDS and gRPC transports via command-line arguments.
 *
 * <p>Usage:</p>
 * <pre>
 * UDS mode:  java -jar sidecar.jar uds [socketPath] [statementLimit] [threadPoolSize]
 * gRPC mode: java -jar sidecar.jar grpc [port] [statementLimit] [threadPoolSize]
 * </pre>
 *
 * <p>Examples:</p>
 * <pre>
 * java -jar sidecar.jar uds /tmp/graaljs.sock 5000 10
 * java -jar sidecar.jar grpc 50051 5000 10
 * </pre>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    // Default values
    private static final String DEFAULT_TRANSPORT = "uds";
    private static final String DEFAULT_SOCKET_PATH = "/tmp/graaljs-sidecar.sock";
    private static final int DEFAULT_GRPC_PORT = 50051;
    private static final int DEFAULT_STATEMENT_LIMIT = 5000;
    private static final int DEFAULT_THREAD_POOL_SIZE = 10;

    private ServerTransport serverTransport;
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
     * Parse command-line arguments and start the appropriate transport.
     *
     * @param args Command-line arguments.
     */
    private void parseArgsAndStart(String[] args) throws IOException {
        String transport = DEFAULT_TRANSPORT;
        int statementLimit = DEFAULT_STATEMENT_LIMIT;
        int threadPoolSize = DEFAULT_THREAD_POOL_SIZE;

        // Parse transport type (first argument)
        if (args.length > 0) {
            transport = args[0].toLowerCase();
        }

        if ("uds".equals(transport)) {
            // UDS mode: uds [socketPath] [statementLimit] [threadPoolSize]
            String socketPath = args.length > 1 ? args[1] : DEFAULT_SOCKET_PATH;
            statementLimit = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_STATEMENT_LIMIT;
            threadPoolSize = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_THREAD_POOL_SIZE;

            startUds(socketPath, statementLimit, threadPoolSize);

        } else if ("grpc".equals(transport)) {
            // gRPC mode: grpc [port] [statementLimit] [threadPoolSize]
            int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_GRPC_PORT;
            statementLimit = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_STATEMENT_LIMIT;
            threadPoolSize = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_THREAD_POOL_SIZE;

            startGrpc(port, statementLimit, threadPoolSize);

        } else {
            printUsageAndExit();
        }
    }

    /**
     * Start the sidecar in UDS mode.
     */
    private void startUds(String socketPath, int statementLimit, int threadPoolSize) throws IOException {
        log.info("[Main] Starting sidecar in UDS mode");
        System.out.println("[SIDECAR-STARTUP] Starting GraalJS Sidecar in UDS mode");
        System.out.println("[SIDECAR-STARTUP] Socket path: " + socketPath);
        System.out.println("[SIDECAR-STARTUP] Statement limit: " + statementLimit
                + ", Thread pool size: " + threadPoolSize);
        System.out.flush();

        // Create engine service
        engineService = new JsEngineServiceImpl(statementLimit);

        // Create UDS transport
        serverTransport = new UdsServerTransport(socketPath, engineService, threadPoolSize);

        // Start server
        startServer();
    }

    /**
     * Start the sidecar in gRPC mode.
     */
    private void startGrpc(int port, int statementLimit, int threadPoolSize) throws IOException {
        log.info("[Main] Starting sidecar in gRPC mode");
        System.out.println("[SIDECAR-STARTUP] Starting GraalJS Sidecar in gRPC mode");
        System.out.println("[SIDECAR-STARTUP] Port: " + port);
        System.out.println("[SIDECAR-STARTUP] Statement limit: " + statementLimit
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

        log.info("[Main] Sidecar started on: " + serverTransport.getAddress());
        System.out.println("[SIDECAR-STARTUP] Sidecar listening on: " + serverTransport.getAddress());
        System.out.flush();

        // Set up shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[Main] Shutting down GraalJS Sidecar...");
            System.out.println("[SIDECAR-SHUTDOWN] Shutting down GraalJS Sidecar...");
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
        log.info("[Main] GraalJS Sidecar stopped");
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

    /**
     * Print usage information and exit.
     */
    private static void printUsageAndExit() {
        System.err.println("Usage:");
        System.err.println("  UDS mode:  java -jar sidecar.jar uds [socketPath] [statementLimit] [threadPoolSize]");
        System.err.println("  gRPC mode: java -jar sidecar.jar grpc [port] [statementLimit] [threadPoolSize]");
        System.err.println();
        System.err.println("Defaults:");
        System.err.println("  Socket path:      " + DEFAULT_SOCKET_PATH);
        System.err.println("  gRPC port:        " + DEFAULT_GRPC_PORT);
        System.err.println("  Statement limit:  " + DEFAULT_STATEMENT_LIMIT);
        System.err.println("  Thread pool size: " + DEFAULT_THREAD_POOL_SIZE);
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  java -jar sidecar.jar uds");
        System.err.println("  java -jar sidecar.jar uds /tmp/custom.sock");
        System.err.println("  java -jar sidecar.jar uds /tmp/custom.sock 10000 20");
        System.err.println("  java -jar sidecar.jar grpc");
        System.err.println("  java -jar sidecar.jar grpc 50052");
        System.err.println("  java -jar sidecar.jar grpc 50052 10000 20");
        System.exit(1);
    }
}
