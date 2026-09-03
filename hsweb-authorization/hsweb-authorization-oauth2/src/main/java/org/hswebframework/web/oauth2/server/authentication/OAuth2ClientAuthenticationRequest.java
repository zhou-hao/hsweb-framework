package org.hswebframework.web.oauth2.server.authentication;

import lombok.Getter;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-attempt input for OAuth2 client authentication at the token endpoint.
 *
 * <p>Credentials are defensively copied on input and access. Parameters are copied into an
 * immutable map with {@code client_secret} removed. The controller clears the internal credential
 * copy when authentication terminates; authenticators must not retain the request or any returned
 * credential copy beyond the asynchronous authentication operation.</p>
 *
 * @author zhouhao
 * @since 5.0.2
 * @see ReactiveOAuth2ClientAuthenticator
 */
@Getter
public class OAuth2ClientAuthenticationRequest {

    public static final String CLIENT_SECRET_BASIC = "client_secret_basic";

    public static final String CLIENT_SECRET_POST = "client_secret_post";

    private final String clientId;

    private final String authenticationMethod;

    private char[] credentials;

    private final String grantType;

    private final Map<String, String> parameters;

    /**
     * Create the context for one client authentication attempt.
     *
     * @param clientId client identifier extracted by the token endpoint
     * @param authenticationMethod authentication mechanism used to obtain the credentials
     * @param credentials credentials to copy for this attempt, or {@code null}
     * @param grantType requested OAuth2 grant type
     * @param parameters request parameters; copied and stripped of {@code client_secret}
     */
    public OAuth2ClientAuthenticationRequest(String clientId,
                                             String authenticationMethod,
                                             char[] credentials,
                                             String grantType,
                                             Map<String, String> parameters) {
        this.clientId = clientId;
        this.authenticationMethod = authenticationMethod;
        this.credentials = credentials == null ? null : credentials.clone();
        this.grantType = grantType;
        this.parameters = safeParameters(parameters);
    }

    /**
     * Obtain a defensive copy of the credentials for immediate verification.
     *
     * <p>The caller owns the returned array and should erase it after use. The request's internal
     * credential state remains available until {@link #clearCredentials()} is invoked.</p>
     *
     * @return credential copy, or {@code null} after cleanup or when no credentials were supplied
     */
    public synchronized char[] getCredentials() {
        return credentials == null ? null : credentials.clone();
    }

    /**
     * Erase and release the credentials held by this request.
     *
     * <p>This lifecycle operation is idempotent and is normally invoked by the token endpoint when
     * authentication completes, fails, or is cancelled.</p>
     */
    public synchronized void clearCredentials() {
        if (credentials != null) {
            Arrays.fill(credentials, '\0');
            credentials = null;
        }
    }

    private static Map<String, String> safeParameters(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> safe = new LinkedHashMap<>(parameters);
        safe.remove("client_secret");
        return Collections.unmodifiableMap(safe);
    }
}
