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

/**
 * Centralized constants for the External side of the remote JS engine protocol.
 * These string values form the wire protocol between IS and the GraalJS External.
 * Any change here MUST be mirrored in the IS side's RemoteEngineConstants.java.
 *
 * Categories:
 * 1. Type markers   - Non-serializable object placeholders
 * 2. Reference prefixes - Lazy proxy path prefixes (__proxyref__, __hostref__)
 * 3. Proxy markers  - Serialized proxy metadata fields
 * 4. Binding keys   - Reserved binding names
 * 5. Path/transport - Path separator
 * 6. Proxy types    - Proxy type identifiers
 */
public final class ExternalConstants {

    private ExternalConstants() {
        // Utility class — no instances
    }

    // ============ Type Markers ============

    /** Placeholder for JsGraalAuthenticationContext which cannot be serialized via protobuf. */
    public static final String CONTEXT_PLACEHOLDER =
            "__JsGraalAuthenticationContext_placeholder__";

    // ============ Reference Prefixes (lazy proxy path prefixes) ============

    /** Prefix for proxy object references: "__proxyref__::<uuid>::<property>" */
    public static final String PROXY_REF_PREFIX = "__proxyref__::";

    /** Prefix for host function return references: "__hostref__::<uuid>::<property>" */
    public static final String HOST_REF_PREFIX = "__hostref__::";

    // ============ Proxy Marker Fields (serialized proxy metadata) ============

    /** Marker field indicating a serialized context proxy. */
    public static final String IS_CONTEXT_PROXY = "__isContextProxy";

    /** Field holding the proxy type (e.g., "context", "authenticateduser"). */
    public static final String PROXY_TYPE_FIELD = "__proxyType";

    /** Field holding the proxy base path for navigation. */
    public static final String BASE_PATH_FIELD = "__basePath";

    /** Marker field indicating a host function return reference. */
    public static final String IS_HOST_REF = "__isHostRef";

    /** Field holding the reference ID for proxy/host-ref objects. */
    public static final String REFERENCE_ID_FIELD = "__referenceId";

    // ============ Special Property Names ============

    /** Special property triggering member key enumeration (Object.keys()). */
    public static final String KEYS_PROPERTY = "__keys__";

    // ============ Binding Keys ============

    /** The binding key for the authentication context object. */
    public static final String CONTEXT_BINDING_KEY = "context";

    /** The binding key for the callback context in External. */
    public static final String CALLBACK_CONTEXT_KEY = "__callbackContext";

    // ============ Path and Transport ============

    /** Separator used in property paths (e.g., "steps::1::subject"). */
    public static final String PATH_SEPARATOR = "::";

    // ============ Proxy Types ============

    /** Proxy type for generic POJO objects. */
    public static final String PROXY_TYPE_POJO = "pojo";

    // ============ mTLS Configuration ============
    // mTLS is mandatory on the gRPC server. The stream carries the full
    // JsAuthenticationContext (username, tenant, userstore domain, claims,
    // session id) plus host-function payloads, so plaintext on a non-loopback
    // target is a confidentiality defect. The previous MTLS_ENABLED toggle and
    // the feature-local PEM bundle (server.pem / server-key.pem / ca.pem) have
    // been removed; the server now requires a PKCS#12 keystore + truststore and
    // refuses to start otherwise. See GrpcStreamingServerTransport#buildMtlsCredentials.
    //
    // Defaults are the bundled wso2carbon.p12 and client-truststore.p12 (same
    // material IS ships). Operator overrides via the system properties below.

    public static final String MTLS_KEYSTORE_PATH_PROP = "mtls.keystore.path";
    public static final String MTLS_KEYSTORE_PASSWORD_PROP = "mtls.keystore.password";
    public static final String MTLS_KEYSTORE_KEY_PASSWORD_PROP = "mtls.keystore.key.password";
    public static final String MTLS_TRUSTSTORE_PATH_PROP = "mtls.truststore.path";
    public static final String MTLS_TRUSTSTORE_PASSWORD_PROP = "mtls.truststore.password";

    /** Default PKCS#12 keystore (classpath resource path). Same file IS ships. */
    public static final String DEFAULT_KEYSTORE_RESOURCE = "/certs/wso2carbon.p12";

    /** Default PKCS#12 truststore (classpath resource path). Same file IS ships. */
    public static final String DEFAULT_TRUSTSTORE_RESOURCE = "/certs/client-truststore.p12";

    /** Default password matching the IS pack. */
    public static final String DEFAULT_KEYSTORE_PASSWORD = "wso2carbon";

    /** Default keystore type (matches Carbon configuration). */
    public static final String DEFAULT_KEYSTORE_TYPE = "PKCS12";
}
