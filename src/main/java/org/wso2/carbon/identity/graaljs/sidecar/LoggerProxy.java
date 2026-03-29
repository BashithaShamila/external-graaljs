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

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logger proxy for JS Log object.
 */
class LoggerProxy implements ProxyObject {

    private static final Logger log = LoggerFactory.getLogger(LoggerProxy.class);

    @Override
    public Object getMember(String key) {
        return (ProxyExecutable) args -> {
            String message = args.length > 0 ? args[0].toString() : "";
            switch (key) {
                case "info":
                    log.info("[JS] {}", message);
                    break;
                case "warn":
                    log.warn("[JS] {}", message);
                    break;
                case "error":
                    log.error("[JS] {}", message);
                    break;
                case "debug":
                    log.debug("[JS] {}", message);
                    break;
                default:
                    log.info("[JS-{}] {}", key, message);
            }
            return null;
        };
    }

    @Override
    public Object getMemberKeys() {
        return new String[] { "info", "warn", "error", "debug" };
    }

    @Override
    public boolean hasMember(String key) {
        return true;
    }

    @Override
    public void putMember(String key, Value value) {
    }
}
