package org.hswebframework.web.oauth2.server.authentication;

import reactor.core.publisher.Mono;

/**
 * Authenticates an OAuth2 client during token endpoint processing.
 *
 * <p>The token endpoint invokes this strategy after checking that the client identifier and
 * credentials are present. The supplied request contains a defensive credential copy and its
 * parameters do not contain {@code client_secret}. Implementations may query external storage
 * asynchronously and may verify hashed credentials.</p>
 *
 * <p>An implementation must emit exactly one non-null {@link OAuth2ClientAuthentication} or fail
 * with an authentication error. It must not use {@link Mono#empty()} to request another strategy:
 * empty completion is an authentication failure and never enables fallback. Errors propagate to
 * the token endpoint unchanged. Implementations must not retain, log, or otherwise expose the
 * request credentials.</p>
 *
 * @author zhouhao
 * @since 5.0.2
 * @see DefaultReactiveOAuth2ClientAuthenticator
 * @see OAuth2ClientAuthentication
 */
public interface ReactiveOAuth2ClientAuthenticator {

    /**
     * Authenticate one token endpoint request.
     *
     * @param request non-null, single-attempt authentication request whose basic identifier and
     *                credential presence have been checked and whose parameters are sanitized;
     *                implementations must not retain or mutate its security-sensitive state
     * @return a publisher that emits exactly one authenticated client context; empty completion is
     *         not a fallback signal and must be treated as authentication failure, while errors are
     *         propagated to the token endpoint
     */
    Mono<OAuth2ClientAuthentication> authenticate(OAuth2ClientAuthenticationRequest request);

}
