package org.hswebframework.web.starter.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hswebframework.web.dict.EnumDict;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.AuthenticationHolder;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.i18n.LocaleUtils;
import org.hswebframework.web.i18n.MessageSourceInitializer;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicBoolean;

public class CustomJackson2jsonEncoderTest {


    @Before
    public void init(){
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setDefaultEncoding("utf-8");
        messageSource.setBasenames("i18n.messages");
        MessageSourceInitializer.init(messageSource);
    }

    @Test
    public void testI18n() {

        doTest(new TestEntity(TestEnum.e1),Locale.forLanguageTag("en-US"),s->s.contains("Option1"));
        doTest(new TestEntity(TestEnum.e1),Locale.forLanguageTag("zh-CN"),s->s.contains("选项1"));

    }

    @Test
    public void testJsonFluxStreamsAsArray() {
        CustomJackson2jsonEncoder encoder = new CustomJackson2jsonEncoder(new ObjectMapper());
        TestPublisher<TestEntity> source = TestPublisher.create();

        Flux<String> encoded = encoder
            .encode(source.flux(),
                    new DefaultDataBufferFactory(),
                    ResolvableType.forType(TestEntity.class),
                    MediaType.APPLICATION_JSON,
                    Collections.emptyMap())
            .map(this::readAndRelease);

        StepVerifier
            .create(encoded, 0)
            .thenRequest(1)
            .then(() -> source.next(new TestEntity(TestEnum.e1)))
            .expectNextMatches(json -> json.startsWith("[{") && json.contains("\"e1\""))
            .thenRequest(1)
            .then(() -> source.next(new TestEntity(TestEnum.e2)))
            .expectNextMatches(json -> json.startsWith(",{") && json.contains("\"e2\""))
            .thenRequest(1)
            .then(source::complete)
            .expectNext("]")
            .verifyComplete();
    }

    @Test
    public void testEmptyJsonFlux() {
        CustomJackson2jsonEncoder encoder = new CustomJackson2jsonEncoder(new ObjectMapper());

        encoder
            .encode(Flux.empty(),
                    new DefaultDataBufferFactory(),
                    ResolvableType.forType(TestEntity.class),
                    MediaType.APPLICATION_JSON,
                    Collections.emptyMap())
            .as(DataBufferUtils::join)
            .map(this::readAndRelease)
            .as(StepVerifier::create)
            .expectNext("[]")
            .verifyComplete();
    }

    @Test
    public void testAuthenticationAvailableDuringEncoding() {
        CustomJackson2jsonEncoder encoder = new CustomJackson2jsonEncoder(new ObjectMapper());
        Authentication authentication = new SimpleAuthentication();

        AuthenticationHolder.executeWith(authentication, () -> {
            encoder
                .encode(Mono.just(new AuthenticationAwareEntity()),
                        new DefaultDataBufferFactory(),
                        ResolvableType.forType(AuthenticationAwareEntity.class),
                        MediaType.APPLICATION_JSON,
                        Collections.emptyMap())
                .as(DataBufferUtils::join)
                .map(this::readAndRelease)
                .as(StepVerifier::create)
                .expectNextMatches(json -> json.contains("\"authenticated\":true"))
                .verifyComplete();
            org.junit.Assert.assertSame(authentication, Authentication.current().orElse(null));
            return null;
        });
    }

    @Test
    public void testReactorContextOverridesSameKeyThreadLocal() {
        CustomJackson2jsonEncoder encoder = new CustomJackson2jsonEncoder(new ObjectMapper());
        Authentication threadLocalAuthentication = new SimpleAuthentication();
        Authentication reactorAuthentication = new SimpleAuthentication();

        AuthenticationHolder.executeWith(threadLocalAuthentication, () -> {
            encoder
                .encode(Mono.just(new ExpectedAuthenticationEntity(reactorAuthentication)),
                        new DefaultDataBufferFactory(),
                        ResolvableType.forType(ExpectedAuthenticationEntity.class),
                        MediaType.APPLICATION_JSON,
                        Collections.emptyMap())
                .as(DataBufferUtils::join)
                .map(this::readAndRelease)
                .contextWrite(context -> context.put(Authentication.class, reactorAuthentication))
                .as(StepVerifier::create)
                .expectNextMatches(json -> json.contains("\"reactorContextAuthentication\":true"))
                .verifyComplete();
            org.junit.Assert.assertSame(
                threadLocalAuthentication,
                Authentication.current().orElse(null));
            return null;
        });
    }

    @Test
    public void testCancellationPropagatesToSource() {
        CustomJackson2jsonEncoder encoder = new CustomJackson2jsonEncoder(new ObjectMapper());
        AtomicBoolean cancelled = new AtomicBoolean();

        Flux<DataBuffer> encoded = encoder.encode(
            Flux.create(sink -> {
                sink.onCancel(() -> cancelled.set(true));
                sink.next(new TestEntity(TestEnum.e1));
            }),
            new DefaultDataBufferFactory(),
            ResolvableType.forType(TestEntity.class),
            MediaType.APPLICATION_JSON,
            Collections.emptyMap());

        StepVerifier
            .create(encoded)
            .assertNext(DataBufferUtils::release)
            .thenCancel()
            .verify();
        org.junit.Assert.assertTrue(cancelled.get());
    }

    @Test
    public void testFirstErrorIsNotConvertedToJson() {
        CustomJackson2jsonEncoder encoder = new CustomJackson2jsonEncoder(new ObjectMapper());
        IllegalStateException error = new IllegalStateException("source failed");

        encoder
            .encode(Flux.error(error),
                    new DefaultDataBufferFactory(),
                    ResolvableType.forType(TestEntity.class),
                    MediaType.APPLICATION_JSON,
                    Collections.emptyMap())
            .as(StepVerifier::create)
            .expectErrorMatches(actual -> actual == error)
            .verify();
    }

    public void doTest(TestEntity entity, Locale locale, Predicate<String> verify){

        CustomJackson2jsonEncoder encoder = new CustomJackson2jsonEncoder(new ObjectMapper());

        encoder.encode(Mono.just(entity),
                       new DefaultDataBufferFactory(),
                       ResolvableType.forType(TestEntity.class),
                       MediaType.APPLICATION_JSON,
                       Collections.emptyMap())
               .as(DataBufferUtils::join)
               .map(buf -> buf.toString(StandardCharsets.UTF_8))
               .contextWrite(LocaleUtils.useLocale(locale))
               .as(StepVerifier::create)
               .expectNextMatches(verify)
               .verifyComplete();
    }

    private String readAndRelease(DataBuffer buffer) {
        try {
            return buffer.toString(StandardCharsets.UTF_8);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TestEntity {

        private TestEnum testEnum;
    }

    public static class AuthenticationAwareEntity {

        public boolean isAuthenticated() {
            return Authentication.current().isPresent();
        }
    }

    @AllArgsConstructor
    public static class ExpectedAuthenticationEntity {

        private final Authentication expected;

        public boolean isReactorContextAuthentication() {
            return Authentication.current().orElse(null) == expected;
        }
    }


    @Getter
    @AllArgsConstructor
    public enum TestEnum implements EnumDict<String> {
        e1("enum.e1"),
        e2("enum.e2");

        private final String text;

        @Override
        public String getValue() {
            return name();
        }

    }
}
