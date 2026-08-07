package org.hswebframework.web.crud.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ResponseMessageJacksonHttpMessageWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final ResponseMessageJacksonHttpMessageWriter writer =
        new ResponseMessageJacksonHttpMessageWriter(new Jackson2JsonEncoder(mapper));

    @Test
    public void testResponseMessageJsonContract() {
        StreamingResponseMessage message = message(Flux.just(
            new TestEntity("device-001"),
            new TestEntity("device-002"),
            new TestEntity("device-003")));

        encode(message)
            .as(DataBufferUtils::join)
            .map(this::readAndRelease)
            .map(this::readTree)
            .as(StepVerifier::create)
            .assertNext(json -> {
                assertEquals("success", json.get("message").asText());
                assertEquals(200, json.get("status").asInt());
                assertEquals(123L, json.get("timestamp").asLong());
                assertEquals(3, json.get("result").size());
                assertEquals("device-001", json.get("result").get(0).get("id").asText());
                assertEquals("device-003", json.get("result").get(2).get("id").asText());
            })
            .verifyComplete();
    }

    @Test
    public void testEmptyResult() {
        encode(message(Flux.empty()))
            .as(DataBufferUtils::join)
            .map(this::readAndRelease)
            .map(this::readTree)
            .as(StepVerifier::create)
            .assertNext(json -> assertTrue(json.get("result").isEmpty()))
            .verifyComplete();
    }

    @Test
    public void testResponseMessageExtensionFieldIsPreserved() {
        ExtendedResponseMessage metadata = new ExtendedResponseMessage();
        metadata.setMessage("success");
        metadata.setStatus(200);
        metadata.setTimestamp(123L);

        StreamingResponseMessage message = new StreamingResponseMessage(
            metadata,
            Flux.just(new TestEntity("device-001")),
            ResolvableType.forClassWithGenerics(Flux.class, TestEntity.class),
            ResolvableType.forClass(TestEntity.class));

        encode(message)
            .as(DataBufferUtils::join)
            .map(this::readAndRelease)
            .map(this::readTree)
            .as(StepVerifier::create)
            .assertNext(json -> assertEquals("trace-001", json.get("traceId").asText()))
            .verifyComplete();
    }

    @Test
    public void testFirstChunkArrivesBeforeSourceCompletes() {
        TestPublisher<TestEntity> source = TestPublisher.create();

        StepVerifier
            .create(encode(message(source.flux())), 0)
            .thenRequest(1)
            .then(() -> source.next(new TestEntity("device-001")))
            .assertNext(buffer -> {
                String json = readAndRelease(buffer);
                assertTrue(json.startsWith("{\"message\":\"success\",\"result\":[{"));
                assertTrue(json.contains("device-001"));
            })
            .thenRequest(2)
            .then(source::complete)
            .assertNext(buffer -> assertEquals("]", readAndRelease(buffer)))
            .assertNext(buffer -> assertTrue(readAndRelease(buffer).contains("\"status\":200")))
            .verifyComplete();
    }

    @Test
    public void testErrorBeforeFirstItemDoesNotEmitEnvelope() {
        IllegalStateException error = new IllegalStateException("source failed");

        StepVerifier
            .create(encode(message(Flux.error(error))))
            .expectErrorMatches(actual -> actual == error)
            .verify();
    }

    @Test
    public void testErrorAfterFirstItemDoesNotAppendSuccessSuffix() {
        IllegalStateException error = new IllegalStateException("source failed");

        StepVerifier
            .create(encode(message(Flux.concat(
                Flux.just(new TestEntity("device-001")),
                Flux.error(error)))))
            .assertNext(buffer -> {
                String json = readAndRelease(buffer);
                assertTrue(json.startsWith("{\"message\":\"success\",\"result\":[{"));
                assertTrue(!json.contains("\"status\":200"));
            })
            .expectErrorMatches(actual -> actual == error)
            .verify();
    }

    @Test
    public void testCancellationPropagates() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<TestEntity> source = Flux.create(sink -> {
            sink.onCancel(() -> cancelled.set(true));
            sink.next(new TestEntity("device-001"));
        });

        StepVerifier
            .create(encode(message(source)))
            .assertNext(this::readAndRelease)
            .thenCancel()
            .verify();
        assertTrue(cancelled.get());
    }

    private Flux<DataBuffer> encode(StreamingResponseMessage message) {
        return writer.encode(
            message,
            new DefaultDataBufferFactory(),
            MediaType.APPLICATION_JSON,
            Collections.emptyMap());
    }

    private StreamingResponseMessage message(Flux<TestEntity> source) {
        return new StreamingResponseMessage(
            ResponseMessage.of("success", null, 200, null, 123L),
            source,
            ResolvableType.forClassWithGenerics(Flux.class, TestEntity.class),
            ResolvableType.forClass(TestEntity.class));
    }

    private String readAndRelease(DataBuffer buffer) {
        try {
            return buffer.toString(StandardCharsets.UTF_8);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception error) {
            throw new AssertionError("Invalid JSON: " + json, error);
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

    public static class ExtendedResponseMessage extends ResponseMessage<Object> {

        public String getTraceId() {
            return "trace-001";
        }
    }
}
