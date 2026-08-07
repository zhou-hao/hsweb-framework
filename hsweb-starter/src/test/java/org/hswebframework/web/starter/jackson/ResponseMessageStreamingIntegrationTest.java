package org.hswebframework.web.starter.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.context.ContextRegistry;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.crud.web.ResponseMessageWrapper;
import org.hswebframework.web.i18n.LocaleUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.accept.RequestedContentTypeResolver;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end verification through Spring WebFlux and a real Reactor Netty connection.
 */
public class ResponseMessageStreamingIntegrationTest {

    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(30);

    private static final MediaType VENDOR_JSON =
        MediaType.parseMediaType("application/vnd.hsweb+json");

    private static final String CORRELATION_CONTEXT_KEY =
        ResponseMessageStreamingIntegrationTest.class.getName() + ".correlationId";

    private static final ThreadLocal<String> CORRELATION_CONTEXT = new ThreadLocal<>();

    private static final Authentication TEST_AUTHENTICATION = new SimpleAuthentication();

    private static final Locale TEST_LOCALE = Locale.JAPANESE;

    private static AnnotationConfigApplicationContext context;

    private static DisposableServer server;

    private static HttpClient client;

    private static TestController controller;

    private static ObjectMapper mapper;

    @BeforeClass
    public static void startServer() {
        ContextRegistry
            .getInstance()
            .registerThreadLocalAccessor(CORRELATION_CONTEXT_KEY, CORRELATION_CONTEXT);
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        controller = context.getBean(TestController.class);
        mapper = context.getBean(ObjectMapper.class);

        HttpHandler handler = WebHttpHandlerBuilder
            .applicationContext(context)
            .build();
        server = HttpServer
            .create()
            .host("127.0.0.1")
            .port(0)
            .handle(new ReactorHttpHandlerAdapter(handler))
            .bindNow(VERIFY_TIMEOUT);
        client = HttpClient
            .create()
            .baseUrl("http://127.0.0.1:" + server.port())
            .responseTimeout(VERIFY_TIMEOUT);
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.disposeNow(VERIFY_TIMEOUT);
        }
        if (context != null) {
            context.close();
        }
        ContextRegistry.getInstance().removeThreadLocalAccessor(CORRELATION_CONTEXT_KEY);
        CORRELATION_CONTEXT.remove();
    }

    @Test
    public void testFirstHttpChunkArrivesBeforePublisherCompletes() throws Exception {
        ControlledStream stream = controller.openControlledStream();

        Mono<String> firstItemResponse = streamResponse(
            "/stream/controlled",
            MediaType.APPLICATION_JSON,
            false)
            .scanWith(StringBuilder::new, (builder, chunk) -> builder.append(chunk))
            .filter(builder -> builder.indexOf("\"id\":1") >= 0)
            .map(StringBuilder::toString)
            .next();

        StepVerifier
            .create(firstItemResponse)
            .then(() -> assertEquals(
                Sinks.EmitResult.OK,
                stream.emit(new TestEntity(1))))
            .assertNext(json -> {
                assertTrue(json.startsWith("{\"message\":\"success\""));
                assertTrue(json.contains("\"result\":[{\"id\":1}"));
                assertFalse(json.contains("\"status\":200"));
            })
            .expectComplete()
            .verify(VERIFY_TIMEOUT);

        assertFalse(stream.isCompleted());
        assertTrue("client cancellation was not propagated to the source",
                   stream.awaitCancellation(VERIFY_TIMEOUT));
    }

    @Test
    public void testLargeResponseIsParsedIncrementally() {
        int expected = 100_000;
        StreamingJsonProbe probe = new StreamingJsonProbe(mapper);

        Mono<Void> response = client
            .headers(headers -> headers.set(
                HttpHeaderNames.ACCEPT,
                MediaType.APPLICATION_JSON_VALUE))
            .get()
            .uri("/stream/large?count=" + expected)
            .response((httpResponse, content) -> {
                assertEquals(200, httpResponse.status().code());
                return content
                    .asByteArray()
                    .doOnNext(probe::feed)
                    .then(Mono.fromRunnable(probe::complete));
            })
            .then();

        StepVerifier
            .create(response)
            .expectComplete()
            .verify(VERIFY_TIMEOUT);

        assertEquals(expected, probe.getItemCount());
        assertEquals(200, probe.getStatus());
        assertTrue(probe.hasResultArray());
        assertTrue(probe.hasCompleteRootObject());
        assertTrue(probe.getByteCount() > expected * 8L);
    }

    @Test
    public void testVendorJsonAndIgnoreHeaderCompatibility() {
        StepVerifier
            .create(receive("/stream/finite", VENDOR_JSON, false))
            .assertNext(response -> {
                assertEquals(200, response.status());
                assertTrue(response.contentType().startsWith(VENDOR_JSON.toString()));
                JsonNode json = readTree(response.body());
                assertEquals("success", json.get("message").asText());
                assertEquals(3, json.get("result").size());
            })
            .expectComplete()
            .verify(VERIFY_TIMEOUT);

        StepVerifier
            .create(receive("/stream/finite", MediaType.APPLICATION_JSON, true))
            .assertNext(response -> {
                JsonNode json = readTree(response.body());
                assertTrue(json.isArray());
                assertEquals(3, json.size());
            })
            .expectComplete()
            .verify(VERIFY_TIMEOUT);
    }

    @Test
    public void testNdjsonAndSseRemainUnwrapped() {
        StepVerifier
            .create(receive("/stream/finite", MediaType.APPLICATION_NDJSON, false))
            .assertNext(response -> {
                assertFalse(response.body().contains("\"message\""));
                long lines = response
                    .body()
                    .lines()
                    .filter(line -> !line.isBlank())
                    .count();
                assertEquals(3, lines);
            })
            .expectComplete()
            .verify(VERIFY_TIMEOUT);

        StepVerifier
            .create(receive("/stream/finite", MediaType.TEXT_EVENT_STREAM, false))
            .assertNext(response -> {
                assertFalse(response.body().contains("\"message\""));
                assertTrue(response.body().contains("data:{\"id\":1}"));
                assertTrue(response.body().contains("data:{\"id\":3}"));
            })
            .expectComplete()
            .verify(VERIFY_TIMEOUT);
    }

    @Test
    public void testRegisteredContextsAreRestoredAcrossAsyncBoundary() {
        StepVerifier
            .create(receive("/stream/async-context", MediaType.APPLICATION_JSON, false))
            .assertNext(response -> {
                JsonNode result = readTree(response.body()).get("result").get(0);
                assertEquals("request-001", result.get("correlationId").asText());
                assertEquals(TEST_LOCALE.toLanguageTag(), result.get("locale").asText());
                assertTrue(result.get("authenticated").asBoolean());
                assertTrue(result.get("thread").asText().contains("response-context-test"));
            })
            .expectComplete()
            .verify(VERIFY_TIMEOUT);

        StepVerifier
            .create(controller.readAsyncThreadContext())
            .assertNext(state -> {
                assertNull(state.correlationId());
                assertFalse(state.authenticated());
            })
            .expectComplete()
            .verify(VERIFY_TIMEOUT);
    }

    private static Flux<String> streamResponse(String uri,
                                               MediaType accept,
                                               boolean ignoreWrapper) {
        return client
            .headers(headers -> configureHeaders(headers, accept, ignoreWrapper))
            .get()
            .uri(uri)
            .response((response, content) -> {
                assertEquals(200, response.status().code());
                return content.asString(StandardCharsets.UTF_8);
            });
    }

    private static Mono<ReceivedResponse> receive(String uri,
                                                  MediaType accept,
                                                  boolean ignoreWrapper) {
        return client
            .headers(headers -> configureHeaders(headers, accept, ignoreWrapper))
            .get()
            .uri(uri)
            .responseSingle((response, content) -> content
                .asString(StandardCharsets.UTF_8)
                .defaultIfEmpty("")
                .map(body -> new ReceivedResponse(
                    response.status().code(),
                    Objects.requireNonNullElse(
                        response.responseHeaders().get(HttpHeaderNames.CONTENT_TYPE),
                        ""),
                    body)));
    }

    private static void configureHeaders(HttpHeaders headers,
                                         MediaType accept,
                                         boolean ignoreWrapper) {
        headers.set(HttpHeaderNames.ACCEPT, accept.toString());
        if (ignoreWrapper) {
            headers.set("X-Response-Wrapper", "Ignore");
        }
    }

    private static JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException error) {
            throw new AssertionError("Invalid JSON: " + json, error);
        }
    }

    @Configuration
    @EnableWebFlux
    static class TestConfiguration implements WebFluxConfigurer {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Bean
        ObjectMapper objectMapper() {
            return objectMapper;
        }

        @Bean(destroyMethod = "dispose")
        Scheduler responseContextScheduler() {
            return Schedulers.newSingle("response-context-test");
        }

        @Bean
        TestController testController(Scheduler responseContextScheduler) {
            return new TestController(responseContextScheduler);
        }

        @Bean
        WebFilter asyncContextWebFilter() {
            return (exchange, chain) -> {
                Mono<Void> result = chain.filter(exchange);
                if (!exchange.getRequest().getPath().value().equals("/stream/async-context")) {
                    return result;
                }
                return result.contextWrite(context -> context
                    .put(CORRELATION_CONTEXT_KEY, "request-001")
                    .put(Authentication.class, TEST_AUTHENTICATION)
                    .put(Locale.class, TEST_LOCALE));
            };
        }

        @Bean
        ResponseMessageWrapper responseMessageWrapper(ServerCodecConfigurer codecConfigurer,
                                                       RequestedContentTypeResolver resolver,
                                                       ReactiveAdapterRegistry registry) {
            return new ResponseMessageWrapper(codecConfigurer.getWriters(), resolver, registry);
        }

        @Override
        public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
            configurer
                .defaultCodecs()
                .jackson2JsonEncoder(new CustomJackson2jsonEncoder(objectMapper));
        }
    }

    @RestController
    static class TestController {

        private final AtomicReference<ControlledStream> controlled = new AtomicReference<>();

        private final Scheduler responseContextScheduler;

        TestController(Scheduler responseContextScheduler) {
            this.responseContextScheduler = responseContextScheduler;
        }

        ControlledStream openControlledStream() {
            ControlledStream stream = new ControlledStream();
            controlled.set(stream);
            return stream;
        }

        @GetMapping("/stream/controlled")
        Flux<TestEntity> controlled() {
            return Objects.requireNonNull(controlled.get(), "controlled stream not initialized")
                .flux();
        }

        @GetMapping("/stream/finite")
        Flux<TestEntity> finite() {
            return Flux.just(new TestEntity(1), new TestEntity(2), new TestEntity(3));
        }

        @GetMapping("/stream/large")
        Flux<TestEntity> large(@RequestParam int count) {
            return Flux.range(0, count).map(TestEntity::new);
        }

        @GetMapping("/stream/async-context")
        Flux<AsyncContextEntity> asyncContext() {
            return Flux
                .just(new AsyncContextEntity())
                .publishOn(responseContextScheduler);
        }

        Mono<ThreadContextState> readAsyncThreadContext() {
            return Mono
                .fromCallable(() -> new ThreadContextState(
                    CORRELATION_CONTEXT.get(),
                    Authentication.current().isPresent()))
                .subscribeOn(responseContextScheduler);
        }
    }

    record TestEntity(int id) {
    }

    static class AsyncContextEntity {

        public String getCorrelationId() {
            return CORRELATION_CONTEXT.get();
        }

        public String getLocale() {
            return LocaleUtils.current().toLanguageTag();
        }

        public boolean isAuthenticated() {
            return Authentication.current().orElse(null) == TEST_AUTHENTICATION;
        }

        public String getThread() {
            return Thread.currentThread().getName();
        }
    }

    private record ReceivedResponse(int status, String contentType, String body) {
    }

    private record ThreadContextState(String correlationId, boolean authenticated) {
    }

    private static final class ControlledStream {

        private final Sinks.Many<TestEntity> sink = Sinks
            .many()
            .unicast()
            .onBackpressureBuffer();

        private final CountDownLatch cancelled = new CountDownLatch(1);

        private final AtomicBoolean completed = new AtomicBoolean();

        Flux<TestEntity> flux() {
            return sink
                .asFlux()
                .doOnCancel(cancelled::countDown)
                .doOnComplete(() -> completed.set(true));
        }

        Sinks.EmitResult emit(TestEntity entity) {
            return sink.tryEmitNext(entity);
        }

        boolean isCompleted() {
            return completed.get();
        }

        boolean awaitCancellation(Duration timeout) throws InterruptedException {
            return cancelled.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static final class StreamingJsonProbe {

        private final JsonParser parser;

        private final ByteArrayFeeder feeder;

        private int itemCount;

        private int status;

        private int objectDepth;

        private long byteCount;

        private String currentField;

        private boolean resultArray;

        private boolean completeRootObject;

        private StreamingJsonProbe(ObjectMapper mapper) {
            try {
                this.parser = mapper.getFactory().createNonBlockingByteArrayParser();
                this.feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        void feed(byte[] bytes) {
            try {
                assertTrue("parser still has unread input", feeder.needMoreInput());
                byteCount += bytes.length;
                feeder.feedInput(bytes, 0, bytes.length);
                drain();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        void complete() {
            try {
                feeder.endOfInput();
                drain();
                parser.close();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        private void drain() throws IOException {
            JsonToken token;
            while ((token = parser.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
                if (token == JsonToken.FIELD_NAME) {
                    currentField = parser.currentName();
                } else if (token == JsonToken.START_OBJECT) {
                    objectDepth++;
                } else if (token == JsonToken.END_OBJECT) {
                    objectDepth--;
                    if (objectDepth == 0) {
                        completeRootObject = true;
                    }
                } else if (token == JsonToken.START_ARRAY && "result".equals(currentField)) {
                    resultArray = true;
                } else if (token == JsonToken.VALUE_NUMBER_INT) {
                    if ("id".equals(currentField)) {
                        itemCount++;
                    } else if ("status".equals(currentField)) {
                        status = parser.getIntValue();
                    }
                }
            }
        }

        int getItemCount() {
            return itemCount;
        }

        int getStatus() {
            return status;
        }

        long getByteCount() {
            return byteCount;
        }

        boolean hasResultArray() {
            return resultArray;
        }

        boolean hasCompleteRootObject() {
            return completeRootObject;
        }
    }
}
