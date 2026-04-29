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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Bootstrap configuration for the GraalJS runtime.
 * <p>
 * Resolution order for each setting (highest precedence first):
 * <ol>
 *     <li>JVM system property (e.g. {@code -Dserver.port=50051})</li>
 *     <li>{@code conf/deployment.properties} under {@code -Dgraaljs.runtime.home}
 *         (or {@code -Dconf.location} if set explicitly)</li>
 *     <li>Built-in defaults</li>
 * </ol>
 * <p>
 * This class is bootstrap-only — it is consumed by {@link Main} during startup
 * to populate the gRPC transport and engine service. It does not influence
 * request/script execution behaviour.
 */
final class RuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfig.class);

    static final String SYS_PROP_RUNTIME_HOME = "graaljs.runtime.home";
    static final String SYS_PROP_CONF_LOCATION = "conf.location";

    static final String DEPLOYMENT_PROPERTIES_FILE = "deployment.properties";
    static final String CONF_DIRECTORY = "conf";

    static final String PROP_SERVER_PORT = "server.port";
    static final String PROP_STATEMENT_LIMIT = "script.statement.limit";
    static final String PROP_THREAD_POOL_SIZE = "server.thread.pool.size";

    static final int DEFAULT_SERVER_PORT = 50051;
    static final int DEFAULT_STATEMENT_LIMIT = 5000;
    static final int DEFAULT_THREAD_POOL_SIZE = 10;

    private final int port;
    private final int statementLimit;
    private final int threadPoolSize;
    private final Path source;

    private RuntimeConfig(int port, int statementLimit, int threadPoolSize, Path source) {
        this.port = port;
        this.statementLimit = statementLimit;
        this.threadPoolSize = threadPoolSize;
        this.source = source;
    }

    int getPort() {
        return port;
    }

    int getStatementLimit() {
        return statementLimit;
    }

    int getThreadPoolSize() {
        return threadPoolSize;
    }

    /**
     * Source path of the loaded {@code deployment.properties} file, or {@code null}
     * if defaults / system properties were used exclusively.
     */
    Path getSource() {
        return source;
    }

    /**
     * Resolve the runtime configuration using the standard precedence chain.
     */
    static RuntimeConfig load() {

        Properties fileProperties = new Properties();
        Path resolvedSource = null;

        Path deploymentProperties = resolveDeploymentPropertiesPath();
        if (deploymentProperties != null && Files.isRegularFile(deploymentProperties)) {
            try (InputStream in = Files.newInputStream(deploymentProperties)) {
                fileProperties.load(in);
                resolvedSource = deploymentProperties;
                log.info("Loaded runtime configuration from {}", deploymentProperties);
            } catch (IOException e) {
                log.warn("Unable to read deployment.properties at {} — falling back to defaults: {}",
                        deploymentProperties, e.getMessage());
            }
        } else if (deploymentProperties != null) {
            log.debug("deployment.properties not found at {} — using defaults / system properties only.",
                    deploymentProperties);
        }

        int port = readInt(fileProperties, PROP_SERVER_PORT, DEFAULT_SERVER_PORT);
        int statementLimit = readInt(fileProperties, PROP_STATEMENT_LIMIT, DEFAULT_STATEMENT_LIMIT);
        int threadPoolSize = readInt(fileProperties, PROP_THREAD_POOL_SIZE, DEFAULT_THREAD_POOL_SIZE);

        return new RuntimeConfig(port, statementLimit, threadPoolSize, resolvedSource);
    }

    /**
     * Locate {@code deployment.properties} using {@code -Dconf.location} first
     * (matches Carbon convention), then {@code -Dgraaljs.runtime.home/conf}.
     * Returns {@code null} when neither system property is set — typical for
     * IDE / unit-test invocations where defaults are sufficient.
     */
    private static Path resolveDeploymentPropertiesPath() {

        String confLocation = System.getProperty(SYS_PROP_CONF_LOCATION);
        if (confLocation != null && !confLocation.isEmpty()) {
            return Paths.get(confLocation, DEPLOYMENT_PROPERTIES_FILE);
        }
        String runtimeHome = System.getProperty(SYS_PROP_RUNTIME_HOME);
        if (runtimeHome != null && !runtimeHome.isEmpty()) {
            return Paths.get(runtimeHome, CONF_DIRECTORY, DEPLOYMENT_PROPERTIES_FILE);
        }
        return null;
    }

    private static int readInt(Properties fileProperties, String key, int fallback) {

        String sysValue = System.getProperty(key);
        if (sysValue != null && !sysValue.isEmpty()) {
            try {
                return Integer.parseInt(sysValue.trim());
            } catch (NumberFormatException e) {
                log.warn("Ignoring non-numeric system property -D{}={} — using next source.", key, sysValue);
            }
        }
        String fileValue = fileProperties.getProperty(key);
        if (fileValue != null && !fileValue.trim().isEmpty()) {
            try {
                return Integer.parseInt(fileValue.trim());
            } catch (NumberFormatException e) {
                log.warn("Ignoring non-numeric deployment.properties value {}={} — using default {}.",
                        key, fileValue, fallback);
            }
        }
        return fallback;
    }
}
