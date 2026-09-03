package org.hswebframework.web.oauth2.server.authentication;

import lombok.Getter;
import org.hswebframework.web.oauth2.server.OAuth2Client;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Successfully authenticated OAuth2 client context passed to grant processing.
 *
 * <p>The context creates a defensive client projection without {@code clientSecret}. Attributes
 * are copied into an immutable map and {@code client_secret} is removed. Authenticators should add
 * only the minimum non-secret metadata required by the selected grant handler.</p>
 *
 * @author zhouhao
 * @since 5.0.2
 * @see ReactiveOAuth2ClientAuthenticator
 * @see org.hswebframework.web.oauth2.server.credential.ClientCredentialGrantHandler
 */
@Getter
public class OAuth2ClientAuthentication {

    public static final String DEFAULT_CLIENT_TYPE = "default";

    private final OAuth2Client client;

    private final String clientType;

    private final Map<String, Object> attributes;

    /**
     * Create a context for the legacy default client type.
     *
     * @param client authenticated source client; copied into a secret-free projection
     */
    public OAuth2ClientAuthentication(OAuth2Client client) {
        this(client, DEFAULT_CLIENT_TYPE, Collections.emptyMap());
    }

    /**
     * Create a typed authenticated client context.
     *
     * @param client authenticated source client; copied into a secret-free projection
     * @param clientType stable, non-empty type used for exact grant-handler routing
     * @param attributes optional authentication metadata; defensively copied, made immutable, and
     *                   stripped of {@code client_secret}
     */
    public OAuth2ClientAuthentication(OAuth2Client client,
                                      String clientType,
                                      Map<String, Object> attributes) {
        this.client = createClientView(Objects.requireNonNull(client, "client must not be null"));
        if (!StringUtils.hasText(clientType)) {
            throw new IllegalArgumentException("clientType must not be empty");
        }
        this.clientType = clientType;
        if (attributes == null || attributes.isEmpty()) {
            this.attributes = Collections.emptyMap();
        } else {
            Map<String, Object> safeAttributes = new LinkedHashMap<>(attributes);
            safeAttributes.remove("client_secret");
            this.attributes = Collections.unmodifiableMap(safeAttributes);
        }
    }

    private static OAuth2Client createClientView(OAuth2Client source) {
        OAuth2Client client = new OAuth2Client();
        client.setClientId(source.getClientId());
        client.setName(source.getName());
        client.setDescription(source.getDescription());
        client.setRedirectUrl(source.getRedirectUrl());
        client.setUserId(source.getUserId());
        return client;
    }
}
