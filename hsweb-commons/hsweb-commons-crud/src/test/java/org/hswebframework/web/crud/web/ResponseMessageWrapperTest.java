package org.hswebframework.web.crud.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.reactive.accept.RequestedContentTypeResolverBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResponseMessageWrapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final ResponseMessageWrapper wrapper = createWrapper();

    @Test
    public void testFluxIsWrappedAsStreamingResultArray() throws Exception {
        HandlerResult result = handlerResult(
            "flux",
            Flux.just(new TestEntity("device-001"), new TestEntity("device-002")));
        MockServerWebExchange exchange = exchange(MediaType.APPLICATION_JSON);

        assertTrue(wrapper.supports(result));
        wrapper
            .handleResult(exchange, result)
            .then(Mono.defer(exchange.getResponse()::getBodyAsString))
            .map(this::readTree)
            .as(StepVerifier::create)
            .assertNext(json -> {
                if (!json.has("message") || !json.has("result")) {
                    throw new AssertionError("Actual JSON: " + json);
                }
                assertEquals("success", json.get("message").asText());
                assertEquals(2, json.get("result").size());
                assertEquals("device-001", json.get("result").get(0).get("id").asText());
            })
            .verifyComplete();
    }

    @Test
    public void testEmptyFluxHasEmptyResultArray() throws Exception {
        HandlerResult result = handlerResult("flux", Flux.empty());
        MockServerWebExchange exchange = exchange(MediaType.APPLICATION_JSON);

        wrapper
            .handleResult(exchange, result)
            .then(Mono.defer(exchange.getResponse()::getBodyAsString))
            .map(this::readTree)
            .as(StepVerifier::create)
            .assertNext(json -> {
                if (!json.has("result")) {
                    throw new AssertionError("Actual JSON: " + json);
                }
                assertTrue(json.get("result").isEmpty());
            })
            .verifyComplete();
    }

    @Test
    public void testMonoKeepsSingleResultContract() throws Exception {
        HandlerResult result = handlerResult("mono", Mono.just(new TestEntity("device-001")));
        MockServerWebExchange exchange = exchange(MediaType.APPLICATION_JSON);

        wrapper
            .handleResult(exchange, result)
            .then(Mono.defer(exchange.getResponse()::getBodyAsString))
            .map(this::readTree)
            .as(StepVerifier::create)
            .assertNext(json -> assertEquals(
                "device-001",
                json.get("result").get("id").asText()))
            .verifyComplete();
    }

    @Test
    public void testNdjsonIsNotWrapped() throws Exception {
        HandlerResult result = handlerResult(
            "flux",
            Flux.just(new TestEntity("device-001"), new TestEntity("device-002")));
        MockServerWebExchange exchange = exchange(MediaType.APPLICATION_NDJSON);

        wrapper
            .handleResult(exchange, result)
            .then(Mono.defer(exchange.getResponse()::getBodyAsString))
            .as(StepVerifier::create)
            .assertNext(json -> {
                assertFalse(json.contains("\"message\""));
                assertTrue(json.contains("device-001"));
                assertTrue(json.contains("device-002"));
            })
            .verifyComplete();
    }

    @Test
    public void testSseAcceptIsNotWrapped() throws Exception {
        HandlerResult result = handlerResult(
            "flux",
            Flux.just(new TestEntity("device-001"), new TestEntity("device-002")));
        MockServerWebExchange exchange = exchange(MediaType.TEXT_EVENT_STREAM);

        assertTrue(wrapper.supports(result));
        wrapper
            .handleResult(exchange, result)
            .then(Mono.defer(exchange.getResponse()::getBodyAsString))
            .as(StepVerifier::create)
            .assertNext(body -> {
                assertFalse(body.contains("\"message\""));
                assertTrue(body.contains("data:"));
                assertTrue(body.contains("device-001"));
            })
            .verifyComplete();
    }

    @Test
    public void testStreamingProducesIsNotSupported() throws Exception {
        HandlerResult result = handlerResult(
            "sse",
            Flux.just(new TestEntity("device-001")));

        assertFalse(wrapper.supports(result));
    }

    @Test
    public void testExcludesStillBypassWrapper() throws Exception {
        wrapper.setExcludes(Set.of(TestController.class.getName() + ".flux"));

        assertFalse(wrapper.supports(handlerResult(
            "flux",
            Flux.just(new TestEntity("device-001")))));
    }

    @Test
    public void testCancellationPropagatesFromWrapper() throws Exception {
        TestPublisher<TestEntity> source = TestPublisher.create();
        HandlerResult result = handlerResult("flux", source.flux());

        StepVerifier
            .create(wrapper.handleResult(exchange(MediaType.APPLICATION_JSON), result))
            .then(() -> source.assertSubscribers(1))
            .then(() -> source.next(new TestEntity("device-001")))
            .thenCancel()
            .verify();

        source.assertCancelled();
    }

    @Test
    public void testIgnoreHeaderBypassesWrapper() throws Exception {
        HandlerResult result = handlerResult(
            "flux",
            Flux.just(new TestEntity("device-001"), new TestEntity("device-002")));
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest
                .get("/test")
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Response-Wrapper", "Ignore"));

        wrapper
            .handleResult(exchange, result)
            .then(Mono.defer(exchange.getResponse()::getBodyAsString))
            .map(this::readTree)
            .as(StepVerifier::create)
            .assertNext(json -> {
                assertTrue(json.isArray());
                assertEquals(2, json.size());
            })
            .verifyComplete();
    }

    @Test
    public void testExplicitResponseMessageIsNotSupported() throws Exception {
        HandlerResult result = handlerResult(
            "response",
            Mono.just(ResponseMessage.ok(new TestEntity("device-001"))));

        assertFalse(wrapper.supports(result));
    }

    private ResponseMessageWrapper createWrapper() {
        RequestedContentTypeResolverBuilder resolver = new RequestedContentTypeResolverBuilder();
        resolver.headerResolver();
        ResponseMessageWrapper result = new ResponseMessageWrapper(
            ServerCodecConfigurer.create().getWriters(),
            resolver.build(),
            ReactiveAdapterRegistry.getSharedInstance());
        assertTrue(result.getMessageWriters().stream()
            .anyMatch(ResponseMessageJacksonHttpMessageWriter.class::isInstance));
        return result;
    }

    private HandlerResult handlerResult(String methodName, Object value) throws Exception {
        Method method = TestController.class.getDeclaredMethod(methodName);
        HandlerMethod handler = new HandlerMethod(new TestController(), method);
        return new HandlerResult(handler, value, new MethodParameter(method, -1));
    }

    private MockServerWebExchange exchange(MediaType accept) {
        return MockServerWebExchange.from(
            MockServerHttpRequest.get("/test").accept(accept));
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception error) {
            throw new AssertionError("Invalid JSON: " + json, error);
        }
    }

    @Controller
    @ResponseBody
    public static class TestController {

        @RequestMapping("/flux")
        public Flux<TestEntity> flux() {
            return Flux.empty();
        }

        @RequestMapping("/mono")
        public Mono<TestEntity> mono() {
            return Mono.empty();
        }

        @RequestMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public Flux<TestEntity> sse() {
            return Flux.empty();
        }

        @RequestMapping("/response")
        public Mono<ResponseMessage<TestEntity>> response() {
            return Mono.empty();
        }
    }

    public static class TestEntity {

        private final String id;

        public TestEntity(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }
}
