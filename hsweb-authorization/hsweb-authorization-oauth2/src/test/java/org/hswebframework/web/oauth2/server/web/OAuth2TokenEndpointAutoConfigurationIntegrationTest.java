package org.hswebframework.web.oauth2.server.web;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.oauth2.ErrorType;
import org.hswebframework.web.oauth2.OAuth2Exception;
import org.hswebframework.web.oauth2.server.AccessToken;
import org.hswebframework.web.oauth2.server.AccessTokenManager;
import org.hswebframework.web.oauth2.server.OAuth2Client;
import org.hswebframework.web.oauth2.server.OAuth2ClientManager;
import org.hswebframework.web.oauth2.server.OAuth2GrantService;
import org.hswebframework.web.oauth2.server.OAuth2ServerAutoConfiguration;
import org.hswebframework.web.oauth2.server.authentication.DefaultReactiveOAuth2ClientAuthenticator;
import org.hswebframework.web.oauth2.server.authentication.OAuth2ClientAuthentication;
import org.hswebframework.web.oauth2.server.authentication.OAuth2ClientAuthenticationRequest;
import org.hswebframework.web.oauth2.server.authentication.ReactiveOAuth2ClientAuthenticator;
import org.hswebframework.web.oauth2.server.code.AuthorizationCodeGranter;
import org.hswebframework.web.oauth2.server.code.AuthorizationCodeRequest;
import org.hswebframework.web.oauth2.server.code.AuthorizationCodeResponse;
import org.hswebframework.web.oauth2.server.code.AuthorizationCodeTokenRequest;
import org.hswebframework.web.oauth2.server.credential.ClientCredentialGrantHandler;
import org.hswebframework.web.oauth2.server.credential.ClientCredentialGranter;
import org.hswebframework.web.oauth2.server.credential.ClientCredentialRequest;
import org.hswebframework.web.oauth2.server.credential.CompositeClientCredentialGranter;
import org.junit.Test;
import org.springframework.boot.web.reactive.context.AnnotationConfigReactiveWebApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class OAuth2TokenEndpointAutoConfigurationIntegrationTest {

    private static final String OAUTH2_ERROR_HEADER = "X-Test-OAuth2-Error";

    @Test
    public void shouldUseGetBasicDefaultAuthenticatorAndBackOffForLegacyFacade() {
        OAuth2Client legacyClient = client("legacy-client", "legacy-secret:with:colon");
        AtomicReference<ClientCredentialRequest> grantRequest = new AtomicReference<>();
        AtomicInteger legacyCalls = new AtomicInteger();
        ClientCredentialGranter legacyGranter = request -> {
            legacyCalls.incrementAndGet();
            grantRequest.set(request);
            return Mono.just(new AccessToken("legacy-token", null, 60));
        };
        RecordingAccessTokenManager accessTokenManager = new RecordingAccessTokenManager();

        try (AnnotationConfigReactiveWebApplicationContext context = baseContext(accessTokenManager)) {
            context.registerBean(
                "oAuth2ClientManager",
                OAuth2ClientManager.class,
                () -> clientId -> "legacy-client".equals(clientId)
                    ? Mono.just(legacyClient)
                    : Mono.empty());
            context.registerBean(
                "legacyClientCredentialGranter",
                ClientCredentialGranter.class,
                () -> legacyGranter);
            context.refresh();

            assertTrue(context.getBean(ReactiveOAuth2ClientAuthenticator.class)
                              instanceof DefaultReactiveOAuth2ClientAuthenticator);
            assertSame(legacyGranter, context.getBean(ClientCredentialGranter.class));
            assertNotNull(context.getBean(OAuth2GrantService.class));
            assertNotNull(context.getBean(OAuth2AuthorizeController.class));

            webClient(context)
                .get()
                .uri(uri -> uri
                    .path("/oauth2/token")
                    .queryParam("grant_type", "client_credentials")
                    .queryParam("client_id", "ignored-form-client")
                    .queryParam("client_secret", "ignored-form-secret")
                    .build())
                .headers(headers -> headers.setBasicAuth(
                    "legacy-client",
                    "legacy-secret:with:colon",
                    StandardCharsets.UTF_8))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .consumeWith(result -> {
                    assertNotNull(result.getResponseBody());
                    String body = new String(result.getResponseBody(), StandardCharsets.UTF_8);
                    assertTrue(body.contains("legacy-token"));
                    assertFalse(body.contains("ignored-form-secret"));
                });

            assertEquals(1, legacyCalls.get());
            assertEquals("legacy-client", grantRequest.get().getClient().getClientId());
            assertNull(grantRequest.get().getClient().getClientSecret());
            assertFalse(grantRequest.get().getParameters().containsKey("client_secret"));
            assertEquals(0, accessTokenManager.createCalls.get());
        }
    }

    @Test
    public void shouldRoutePostFormToApiHandlerWithoutPassingSecretDownstream() {
        AtomicInteger authenticatorCalls = new AtomicInteger();
        AtomicInteger handlerCalls = new AtomicInteger();
        AtomicReference<OAuth2ClientAuthenticationRequest> authenticationRequest = new AtomicReference<>();
        AtomicReference<ClientCredentialRequest> grantRequest = new AtomicReference<>();
        RecordingAccessTokenManager accessTokenManager = new RecordingAccessTokenManager();

        ReactiveOAuth2ClientAuthenticator authenticator = request -> {
            authenticatorCalls.incrementAndGet();
            authenticationRequest.set(request);
            assertEquals("api-client", request.getClientId());
            assertEquals(OAuth2ClientAuthenticationRequest.CLIENT_SECRET_POST,
                         request.getAuthenticationMethod());
            assertArrayEquals("api-secret".toCharArray(), request.getCredentials());
            assertFalse(request.getParameters().containsKey("client_secret"));

            OAuth2Client source = client("api-client", "stored-secret-must-not-pass");
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("credential_id", "credential-1");
            attributes.put("client_secret", "attribute-secret-must-not-pass");
            return Mono.just(new OAuth2ClientAuthentication(source, "api", attributes));
        };
        ClientCredentialGrantHandler handler = handler("api", request -> {
            handlerCalls.incrementAndGet();
            grantRequest.set(request);
            return Mono.just(new AccessToken("api-token", null, 30));
        });

        try (AnnotationConfigReactiveWebApplicationContext context =
                 apiContext(accessTokenManager, authenticator, handler)) {
            context.refresh();

            assertSame(authenticator, context.getBean(ReactiveOAuth2ClientAuthenticator.class));
            assertTrue(context.getBean(ClientCredentialGranter.class)
                              instanceof CompositeClientCredentialGranter);
            assertNotNull(context.getBean(OAuth2GrantService.class));
            assertNotNull(context.getBean(OAuth2AuthorizeController.class));

            webClient(context)
                .post()
                .uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters
                          .fromFormData("grant_type", "client_credentials")
                          .with("client_id", "api-client")
                          .with("client_secret", "api-secret"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .consumeWith(result -> {
                    assertNotNull(result.getResponseBody());
                    String body = new String(result.getResponseBody(), StandardCharsets.UTF_8);
                    assertTrue(body.contains("api-token"));
                    assertFalse(body.contains("api-secret"));
                    assertFalse(body.contains("stored-secret-must-not-pass"));
                    assertFalse(body.contains("attribute-secret-must-not-pass"));
                });

            assertEquals(1, authenticatorCalls.get());
            assertEquals(1, handlerCalls.get());
            assertNull(authenticationRequest.get().getCredentials());
            assertFalse(grantRequest.get().getParameters().containsKey("client_secret"));
            assertNull(grantRequest.get().getClient().getClientSecret());
            assertFalse(grantRequest.get()
                                    .getClientAuthentication()
                                    .getAttributes()
                                    .containsKey("client_secret"));
            assertEquals("credential-1",
                         grantRequest.get().getClientAuthentication().getAttributes().get("credential_id"));
            assertEquals(0, accessTokenManager.createCalls.get());
        }
    }

    @Test
    public void shouldStopBeforeHandlersWhenApiSecretIsRejected() {
        AtomicInteger authenticatorCalls = new AtomicInteger();
        AtomicInteger handlerCalls = new AtomicInteger();
        RecordingAccessTokenManager accessTokenManager = new RecordingAccessTokenManager();
        ReactiveOAuth2ClientAuthenticator authenticator = request -> {
            authenticatorCalls.incrementAndGet();
            return Mono.error(new OAuth2Exception(ErrorType.ILLEGAL_CLIENT_SECRET));
        };

        try (AnnotationConfigReactiveWebApplicationContext context = apiContext(
            accessTokenManager,
            authenticator,
            handler("api", request -> {
                handlerCalls.incrementAndGet();
                return Mono.just(new AccessToken("must-not-be-issued", null, 30));
            }))) {
            context.refresh();

            requestApiToken(webClient(context), "wrong-secret")
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals(
                    OAUTH2_ERROR_HEADER,
                    ErrorType.ILLEGAL_CLIENT_SECRET.name());

            assertEquals(1, authenticatorCalls.get());
            assertEquals(0, handlerCalls.get());
            assertEquals(0, accessTokenManager.createCalls.get());
        }
    }

    @Test
    public void shouldRejectUnknownAuthenticatedTypeWithoutDefaultFallback() {
        AtomicInteger authenticatorCalls = new AtomicInteger();
        AtomicInteger apiHandlerCalls = new AtomicInteger();
        RecordingAccessTokenManager accessTokenManager = new RecordingAccessTokenManager();
        ReactiveOAuth2ClientAuthenticator authenticator = request -> {
            authenticatorCalls.incrementAndGet();
            return Mono.just(new OAuth2ClientAuthentication(
                client("api-client", "stored-secret"),
                "unknown-api-type",
                Collections.emptyMap()));
        };

        try (AnnotationConfigReactiveWebApplicationContext context = apiContext(
            accessTokenManager,
            authenticator,
            handler("api", request -> {
                apiHandlerCalls.incrementAndGet();
                return Mono.just(new AccessToken("must-not-be-issued", null, 30));
            }))) {
            context.refresh();

            requestApiToken(webClient(context), "api-secret")
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals(
                    OAUTH2_ERROR_HEADER,
                    ErrorType.UNAUTHORIZED_CLIENT.name());

            assertEquals(1, authenticatorCalls.get());
            assertEquals(0, apiHandlerCalls.get());
            assertEquals(0, accessTokenManager.createCalls.get());
        }
    }

    @Test
    public void shouldRejectMalformedBasicBeforeCustomAuthenticator() {
        AtomicInteger authenticatorCalls = new AtomicInteger();
        AtomicInteger handlerCalls = new AtomicInteger();
        RecordingAccessTokenManager accessTokenManager = new RecordingAccessTokenManager();
        ReactiveOAuth2ClientAuthenticator authenticator = request -> {
            authenticatorCalls.incrementAndGet();
            return Mono.error(new AssertionError("malformed Basic must not reach authenticator"));
        };

        try (AnnotationConfigReactiveWebApplicationContext context = apiContext(
            accessTokenManager,
            authenticator,
            handler("api", request -> {
                handlerCalls.incrementAndGet();
                return Mono.just(new AccessToken("must-not-be-issued", null, 30));
            }))) {
            context.refresh();

            webClient(context)
                .get()
                .uri(uri -> uri
                    .path("/oauth2/token")
                    .queryParam("grant_type", "client_credentials")
                    .queryParam("client_id", "valid-form-client")
                    .queryParam("client_secret", "valid-form-secret")
                    .build())
                .header(HttpHeaders.AUTHORIZATION, "Basic %%%")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals(
                    OAUTH2_ERROR_HEADER,
                    ErrorType.ILLEGAL_CLIENT_ID.name());

            assertEquals(0, authenticatorCalls.get());
            assertEquals(0, handlerCalls.get());
            assertEquals(0, accessTokenManager.createCalls.get());
        }
    }

    private AnnotationConfigReactiveWebApplicationContext baseContext(
        RecordingAccessTokenManager accessTokenManager) {
        AnnotationConfigReactiveWebApplicationContext context =
            new AnnotationConfigReactiveWebApplicationContext();
        context.registerBean(
            "accessTokenManager",
            AccessTokenManager.class,
            () -> accessTokenManager);
        context.registerBean(
            "authorizationCodeGranter",
            AuthorizationCodeGranter.class,
            StubAuthorizationCodeGranter::new);
        context.register(HttpTestConfiguration.class, OAuth2ServerAutoConfiguration.class);
        return context;
    }

    private AnnotationConfigReactiveWebApplicationContext apiContext(
        RecordingAccessTokenManager accessTokenManager,
        ReactiveOAuth2ClientAuthenticator authenticator,
        ClientCredentialGrantHandler handler) {
        AnnotationConfigReactiveWebApplicationContext context = baseContext(accessTokenManager);
        context.registerBean(
            "oAuth2ClientManager",
            OAuth2ClientManager.class,
            () -> clientId -> Mono.empty());
        context.registerBean(
            "apiClientAuthenticator",
            ReactiveOAuth2ClientAuthenticator.class,
            () -> authenticator);
        context.registerBean(
            "apiClientCredentialGrantHandler",
            ClientCredentialGrantHandler.class,
            () -> handler);
        return context;
    }

    private WebTestClient webClient(AnnotationConfigReactiveWebApplicationContext context) {
        return WebTestClient
            .bindToApplicationContext(context)
            .configureClient()
            .build();
    }

    private WebTestClient.ResponseSpec requestApiToken(WebTestClient client, String secret) {
        return client
            .post()
            .uri("/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters
                      .fromFormData("grant_type", "client_credentials")
                      .with("client_id", "api-client")
                      .with("client_secret", secret))
            .exchange();
    }

    private static OAuth2Client client(String clientId, String secret) {
        OAuth2Client client = new OAuth2Client();
        client.setClientId(clientId);
        client.setClientSecret(secret);
        client.setUserId("user-id");
        return client;
    }

    private static ClientCredentialGrantHandler handler(
        String clientType,
        java.util.function.Function<ClientCredentialRequest, Mono<AccessToken>> issuer) {
        return new ClientCredentialGrantHandler() {
            @Override
            public String getClientType() {
                return clientType;
            }

            @Override
            public Mono<AccessToken> requestToken(ClientCredentialRequest request) {
                return issuer.apply(request);
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class HttpTestConfiguration {

        @Bean
        WebExceptionHandler oAuth2TestWebExceptionHandler() {
            return new OAuth2TestWebExceptionHandler();
        }
    }

    private static final class OAuth2TestWebExceptionHandler
        implements WebExceptionHandler, Ordered {

        @Override
        public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
            if (!(error instanceof OAuth2Exception)) {
                return Mono.error(error);
            }
            OAuth2Exception oAuth2Error = (OAuth2Exception) error;
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            exchange.getResponse()
                    .getHeaders()
                    .set(OAUTH2_ERROR_HEADER, oAuth2Error.getType().name());
            return exchange.getResponse().setComplete();
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    private static final class StubAuthorizationCodeGranter implements AuthorizationCodeGranter {

        @Override
        public Mono<AuthorizationCodeResponse> requestCode(AuthorizationCodeRequest request) {
            return Mono.empty();
        }

        @Override
        public Mono<AccessToken> requestToken(AuthorizationCodeTokenRequest request) {
            return Mono.empty();
        }
    }

    private static final class RecordingAccessTokenManager implements AccessTokenManager {

        private final AtomicInteger createCalls = new AtomicInteger();

        @Override
        public Mono<Authentication> getAuthenticationByToken(String accessToken) {
            return Mono.empty();
        }

        @Override
        public Mono<AccessToken> createAccessToken(String clientId,
                                                   Authentication authentication,
                                                   boolean singleton) {
            createCalls.incrementAndGet();
            return Mono.just(new AccessToken("legacy-default-token", null, 60));
        }

        @Override
        public Mono<AccessToken> refreshAccessToken(String clientId, String refreshToken) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> removeToken(String clientId, String token) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> cancelGrant(String clientId, String userId) {
            return Mono.empty();
        }
    }
}
