package org.hswebframework.web.oauth2.server.authentication;

import org.hswebframework.web.oauth2.ErrorType;
import org.hswebframework.web.oauth2.OAuth2Exception;
import org.hswebframework.web.oauth2.server.OAuth2Client;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class DefaultReactiveOAuth2ClientAuthenticatorTest {

    @Test
    public void shouldAuthenticateLegacyClient() {
        OAuth2Client client = client("test", "secret");
        DefaultReactiveOAuth2ClientAuthenticator authenticator =
            new DefaultReactiveOAuth2ClientAuthenticator(id -> Mono.just(client));

        authenticator
            .authenticate(request("test", "secret"))
            .as(StepVerifier::create)
            .assertNext(authentication -> {
                assertNotSame(client, authentication.getClient());
                assertEquals(client.getClientId(), authentication.getClient().getClientId());
                assertNull(authentication.getClient().getClientSecret());
                assertEquals("secret", client.getClientSecret());
                assertEquals(OAuth2Client.DEFAULT_CLIENT_TYPE,
                             authentication.getClientType());
                assertEquals(OAuth2Client.DEFAULT_CLIENT_TYPE,
                             authentication.getClient().getClientType());
                assertTrue(authentication.getAttributes().isEmpty());
            })
            .verifyComplete();
    }

    @Test
    public void shouldPropagateTrustedClientTypeToSanitizedAuthenticationContext() {
        OAuth2Client client = client("test", "secret");
        client.setClientType("api");
        DefaultReactiveOAuth2ClientAuthenticator authenticator =
            new DefaultReactiveOAuth2ClientAuthenticator(id -> Mono.just(client));
        Map<String, String> untrustedParameters = new HashMap<>();
        untrustedParameters.put("client_type", "untrusted");
        untrustedParameters.put("clientType", "untrusted");
        OAuth2ClientAuthenticationRequest request = new OAuth2ClientAuthenticationRequest(
            "test",
            OAuth2ClientAuthenticationRequest.CLIENT_SECRET_POST,
            "secret".toCharArray(),
            "client_credentials",
            untrustedParameters);

        authenticator
            .authenticate(request)
            .as(StepVerifier::create)
            .assertNext(authentication -> {
                assertEquals("api", authentication.getClientType());
                assertEquals("api", authentication.getClient().getClientType());
                assertNotSame(client, authentication.getClient());
                assertNull(authentication.getClient().getClientSecret());
                assertEquals("secret", client.getClientSecret());
            })
            .verifyComplete();
    }

    @Test
    public void shouldRejectUnknownClientWithLegacyError() {
        DefaultReactiveOAuth2ClientAuthenticator authenticator =
            new DefaultReactiveOAuth2ClientAuthenticator(id -> Mono.empty());

        authenticator
            .authenticate(request("missing", "secret"))
            .as(StepVerifier::create)
            .expectErrorMatches(error -> error instanceof OAuth2Exception
                && ((OAuth2Exception) error).getType() == ErrorType.ILLEGAL_CLIENT_ID)
            .verify();
    }

    @Test
    public void shouldRejectInvalidSecretWithLegacyError() {
        OAuth2Client client = client("test", "secret");
        DefaultReactiveOAuth2ClientAuthenticator authenticator =
            new DefaultReactiveOAuth2ClientAuthenticator(id -> Mono.just(client));

        authenticator
            .authenticate(request("test", "wrong"))
            .as(StepVerifier::create)
            .expectErrorMatches(error -> error instanceof OAuth2Exception
                && ((OAuth2Exception) error).getType() == ErrorType.ILLEGAL_CLIENT_SECRET)
            .verify();
    }

    @Test
    public void shouldRejectMissingSecretWithLegacyError() {
        OAuth2Client client = client("test", "secret");
        DefaultReactiveOAuth2ClientAuthenticator authenticator =
            new DefaultReactiveOAuth2ClientAuthenticator(id -> Mono.just(client));
        OAuth2ClientAuthenticationRequest request = new OAuth2ClientAuthenticationRequest(
            "test",
            OAuth2ClientAuthenticationRequest.CLIENT_SECRET_POST,
            null,
            "client_credentials",
            Collections.emptyMap());

        authenticator
            .authenticate(request)
            .as(StepVerifier::create)
            .expectErrorMatches(error -> error instanceof OAuth2Exception
                && ((OAuth2Exception) error).getType() == ErrorType.ILLEGAL_CLIENT_SECRET)
            .verify();
    }

    @Test
    public void shouldClearCredentialCopyAfterLegacyValidation() {
        OAuth2Client client = client("test", "secret");
        AtomicReference<char[]> credentialCopy = new AtomicReference<>();
        OAuth2ClientAuthenticationRequest request = new OAuth2ClientAuthenticationRequest(
            "test",
            OAuth2ClientAuthenticationRequest.CLIENT_SECRET_POST,
            "secret".toCharArray(),
            "client_credentials",
            Collections.emptyMap()) {
            @Override
            public synchronized char[] getCredentials() {
                char[] credentials = super.getCredentials();
                credentialCopy.set(credentials);
                return credentials;
            }
        };

        new DefaultReactiveOAuth2ClientAuthenticator(id -> Mono.just(client))
            .authenticate(request)
            .as(StepVerifier::create)
            .expectNextCount(1)
            .verifyComplete();

        assertArrayEquals(new char["secret".length()], credentialCopy.get());
        assertArrayEquals("secret".toCharArray(), request.getCredentials());
    }

    @Test
    public void shouldProtectCredentialsAndAuthenticationContext() {
        char[] credentials = "secret".toCharArray();
        Map<String, String> parameters = new HashMap<>();
        parameters.put("client_secret", "secret");
        parameters.put("scope", "read");

        OAuth2ClientAuthenticationRequest request = new OAuth2ClientAuthenticationRequest(
            "test",
            OAuth2ClientAuthenticationRequest.CLIENT_SECRET_POST,
            credentials,
            "client_credentials",
            parameters);

        credentials[0] = 'X';
        parameters.put("scope", "write");
        char[] exposed = request.getCredentials();
        exposed[0] = 'Y';

        assertArrayEquals("secret".toCharArray(), request.getCredentials());
        assertEquals("read", request.getParameters().get("scope"));
        assertFalse(request.getParameters().containsKey("client_secret"));
        assertFalse(request.toString().contains("secret"));
        try {
            request.getParameters().put("scope", "write");
            fail("parameters must be immutable");
        } catch (UnsupportedOperationException ignore) {
            // expected
        }
        request.clearCredentials();
        assertNull(request.getCredentials());

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("credentialId", "credential-1");
        attributes.put("client_secret", "secret");
        OAuth2Client client = client("test", "secret");
        client.setName("client name");
        client.setDescription("client description");
        client.setRedirectUrl("https://example.com/callback");
        client.setUserId("user-id");
        OAuth2ClientAuthentication authentication =
            new OAuth2ClientAuthentication(client, "api", attributes);
        attributes.put("credentialId", "credential-2");

        assertNotSame(client, authentication.getClient());
        assertEquals("test", authentication.getClient().getClientId());
        assertEquals("client name", authentication.getClient().getName());
        assertEquals("client description", authentication.getClient().getDescription());
        assertEquals("https://example.com/callback", authentication.getClient().getRedirectUrl());
        assertEquals("user-id", authentication.getClient().getUserId());
        assertNull(authentication.getClient().getClientSecret());
        assertEquals("secret", client.getClientSecret());
        assertEquals("api", authentication.getClientType());
        assertEquals("api", authentication.getClient().getClientType());
        assertEquals("credential-1", authentication.getAttributes().get("credentialId"));
        assertFalse(authentication.getAttributes().containsKey("client_secret"));
        try {
            authentication.getAttributes().put("credentialId", "credential-2");
            fail("attributes must be immutable");
        } catch (UnsupportedOperationException ignore) {
            // expected
        }

        assertInvalidClientType(client, null);
        assertInvalidClientType(client, " ");
    }

    private OAuth2ClientAuthenticationRequest request(String clientId, String secret) {
        return new OAuth2ClientAuthenticationRequest(
            clientId,
            OAuth2ClientAuthenticationRequest.CLIENT_SECRET_POST,
            secret.toCharArray(),
            "client_credentials",
            Collections.singletonMap("client_secret", secret));
    }

    private OAuth2Client client(String clientId, String secret) {
        OAuth2Client client = new OAuth2Client();
        client.setClientId(clientId);
        client.setClientSecret(secret);
        return client;
    }

    private void assertInvalidClientType(OAuth2Client client, String clientType) {
        try {
            new OAuth2ClientAuthentication(client, clientType, Collections.emptyMap());
            fail("clientType must be rejected");
        } catch (IllegalArgumentException ignore) {
            // expected
        }
    }
}
