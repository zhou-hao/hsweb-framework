package org.hswebframework.web.oauth2.server.authentication;

import lombok.AllArgsConstructor;
import org.hswebframework.web.oauth2.ErrorType;
import org.hswebframework.web.oauth2.OAuth2Exception;
import org.hswebframework.web.oauth2.server.OAuth2ClientManager;
import reactor.core.publisher.Mono;

import java.util.Arrays;

/**
 * Default client authenticator backed by the legacy {@link OAuth2ClientManager}.
 *
 * <p>It loads the configured client, validates its client secret, and produces the
 * {@link OAuth2ClientAuthentication#DEFAULT_CLIENT_TYPE default} authentication context. It does
 * not select a grant handler or issue tokens. Missing clients and invalid credentials fail the
 * current request without attempting another authenticator, and the temporary credential copy is
 * erased after validation.</p>
 *
 * @author zhouhao
 * @since 5.0.2
 * @see ReactiveOAuth2ClientAuthenticator
 * @see OAuth2ClientAuthentication
 */
@AllArgsConstructor
public class DefaultReactiveOAuth2ClientAuthenticator implements ReactiveOAuth2ClientAuthenticator {

    private final OAuth2ClientManager clientManager;

    @Override
    public Mono<OAuth2ClientAuthentication> authenticate(OAuth2ClientAuthenticationRequest request) {
        return clientManager
            .getClient(request.getClientId())
            .switchIfEmpty(Mono.error(() -> new OAuth2Exception(ErrorType.ILLEGAL_CLIENT_ID)))
            .map(client -> {
                char[] credentials = request.getCredentials();
                try {
                    client.validateSecret(credentials == null ? null : new String(credentials));
                    return new OAuth2ClientAuthentication(client);
                } finally {
                    if (credentials != null) {
                        Arrays.fill(credentials, '\0');
                    }
                }
            });
    }
}
