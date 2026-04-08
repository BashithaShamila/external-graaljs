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
import org.wso2.carbon.identity.graaljs.External.transport.ServerTransport;

import java.io.IOException;

/**
 * Main entry point for the GraalJS External server.
 * Uses gRPC bidirectional streaming transport.
 *
 * <p>Usage:</p>
 * <pre>
 * java -jar External.jar [port] [statementLimit] [threadPoolSize]
 * </pre>
 *
 * <p>Examples:</p>
 * <pre>
 * java -jar External.jar 50051 5000 10
 * </pre>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    // Default values.
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
     * Parse command-line arguments and start the gRPC transport.
     * Accepts optional "grpc" keyword as first argument for backward compatibility.
     *
     * @param args Command-line arguments: [grpc] [port] [statementLimit] [threadPoolSize].
     */
    private void parseArgsAndStart(String[] args) throws IOException {
        // Skip "grpc" keyword if present (backward compatibility).
        int offset = 0;
        if (args.length > 0 && "grpc".equalsIgnoreCase(args[0])) {
            offset = 1;
        }

        int port = args.length > offset ? Integer.parseInt(args[offset]) : DEFAULT_GRPC_PORT;
        int statementLimit = args.length > offset + 1 ? Integer.parseInt(args[offset + 1]) : DEFAULT_STATEMENT_LIMIT;
        int threadPoolSize = args.length > offset + 2 ? Integer.parseInt(args[offset + 2]) : DEFAULT_THREAD_POOL_SIZE;

        startGrpc(port, statementLimit, threadPoolSize);
    }

    /**
     * Start the External in gRPC mode.
     */
    private void startGrpc(int port, int statementLimit, int threadPoolSize) throws IOException {
        log.info("[Main] Starting External in gRPC mode");
        System.out.println("[External-STARTUP] Starting GraalJS External in gRPC mode");
        System.out.println("[External-STARTUP] Port: " + port);
        System.out.println("[External-STARTUP] Statement limit: " + statementLimit
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

        log.info("[Main] External started on: " + serverTransport.getAddress());
        System.out.println("[External-STARTUP] External listening on: " + serverTransport.getAddress());
        System.out.flush();

        // Set up shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[Main] Shutting down GraalJS External...");
            System.out.println("[External-SHUTDOWN] Shutting down GraalJS External...");
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
        log.info("[Main] GraalJS External stopped");
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
