package org.hswebframework.web.oauth2.server.credential;

import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.authorization.ReactiveAuthenticationManager;
import org.hswebframework.web.oauth2.GrantType;
import org.hswebframework.web.oauth2.server.AccessToken;
import org.hswebframework.web.oauth2.server.AccessTokenManager;
import org.hswebframework.web.oauth2.server.OAuth2Client;
import org.hswebframework.web.oauth2.server.event.OAuth2GrantedEvent;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;

public class DefaultClientCredentialGranter implements ClientCredentialGranter {

    private final AccessTokenManager accessTokenManager;

    private final ApplicationEventPublisher eventPublisher;

    public DefaultClientCredentialGranter(AccessTokenManager accessTokenManager,
                                          ApplicationEventPublisher eventPublisher) {
        this.accessTokenManager = accessTokenManager;
        this.eventPublisher = eventPublisher;
    }

    public DefaultClientCredentialGranter(ReactiveAuthenticationManager authenticationManager,
                                          AccessTokenManager accessTokenManager,
                                          ApplicationEventPublisher eventPublisher) {
        this(accessTokenManager, eventPublisher);
    }

    @Override
    public Mono<AccessToken> requestToken(ClientCredentialRequest request) {

        OAuth2Client client = request.getClient();

        return ReactiveAuthenticationHolder
                .get(client.getUserId())
                .flatMap(auth -> accessTokenManager
                        .createAccessToken(client.getClientId(), auth, true)
                        .flatMap(token -> new OAuth2GrantedEvent(client,
                                                                 token,
                                                                 auth,
                                                                 "*",
                                                                 GrantType.client_credentials,
                                                                 request.getParameters())
                                .publish(eventPublisher)
                                .onErrorResume(err -> accessTokenManager
                                        .removeToken(client.getClientId(), token.getAccessToken())
                                        .then(Mono.error(err)))
                                .thenReturn(token))
                );
    }
}
