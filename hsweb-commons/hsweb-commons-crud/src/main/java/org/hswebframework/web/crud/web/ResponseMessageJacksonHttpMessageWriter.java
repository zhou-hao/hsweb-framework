package org.hswebframework.web.crud.web;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.reactivestreams.Publisher;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.EncodingException;
import org.springframework.core.codec.Hints;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ReactiveHttpOutputMessage;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Writes one {@link ResponseMessage} whose {@code result} is an incremental JSON array.
 *
 * <p>Outer metadata is encoded with the configured Spring Jackson encoder. Array framing
 * and element encoding are delegated to the same encoder, while this writer only joins
 * the two JSON layers. It neither buffers the result Publisher nor subscribes separately.</p>
 */
final class ResponseMessageJacksonHttpMessageWriter
    implements HttpMessageWriter<StreamingResponseMessage> {

    private static final MediaType APPLICATION_PLUS_JSON =
        MediaType.parseMediaType("application/*+json");

    private static final List<MediaType> MEDIA_TYPES =
        List.of(MediaType.APPLICATION_JSON, APPLICATION_PLUS_JSON);

    private final Jackson2JsonEncoder encoder;

    private final ObjectMapper mapper;

    ResponseMessageJacksonHttpMessageWriter(Jackson2JsonEncoder encoder) {
        this.encoder = encoder;
        this.mapper = encoder.getObjectMapper();
    }

    @Override
    public List<MediaType> getWritableMediaTypes() {
        return MEDIA_TYPES;
    }

    @Override
    public boolean canWrite(ResolvableType elementType, @Nullable MediaType mediaType) {
        Class<?> resolved = elementType.resolve();
        if (resolved == null || !StreamingResponseMessage.class.isAssignableFrom(resolved)) {
            return false;
        }
        return mediaType == null || MEDIA_TYPES
            .stream()
            .anyMatch(candidate -> candidate.isCompatibleWith(mediaType));
    }

    @Override
    public Mono<Void> write(Publisher<? extends StreamingResponseMessage> inputStream,
                            ResolvableType elementType,
                            @Nullable MediaType mediaType,
                            ReactiveHttpOutputMessage outputMessage,
                            Map<String, Object> hints) {
        MediaType contentType = selectContentType(mediaType, outputMessage);
        return Mono
            .from(inputStream)
            .flatMap(message -> outputMessage.writeWith(
                encode(message, outputMessage.bufferFactory(), contentType, hints))
                .thenReturn(true))
            .defaultIfEmpty(false)
            .flatMap(written -> written ? Mono.empty() : outputMessage.setComplete());
    }

    @Override
    public Mono<Void> write(Publisher<? extends StreamingResponseMessage> inputStream,
                            ResolvableType actualType,
                            ResolvableType elementType,
                            @Nullable MediaType mediaType,
                            ServerHttpRequest request,
                            ServerHttpResponse response,
                            Map<String, Object> hints) {
        MediaType contentType = selectContentType(mediaType, response);
        return Mono
            .from(inputStream)
            .flatMap(message -> {
                Map<String, Object> allHints = Hints.merge(
                    hints,
                    encoder.getEncodeHints(
                        message.getActualType(),
                        message.getElementType(),
                        contentType,
                        request,
                        response));
                return response.writeWith(
                    encode(message, response.bufferFactory(), contentType, allHints))
                    .thenReturn(true);
            })
            .defaultIfEmpty(false)
            .flatMap(written -> written ? Mono.empty() : response.setComplete());
    }

    Flux<DataBuffer> encode(StreamingResponseMessage message,
                            DataBufferFactory bufferFactory,
                            MediaType mediaType,
                            Map<String, Object> hints) {
        Mono<EnvelopeFragments> fragments = encodeMetadata(
            message.getMetadata(),
            bufferFactory,
            mediaType,
            hints);

        return fragments
            .flatMapMany(parts -> joinEnvelope(
                encoder.encode(
                    message.getResult(),
                    bufferFactory,
                    message.getElementType(),
                    mediaType,
                    hints),
                parts,
                bufferFactory))
            .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
    }

    private Mono<EnvelopeFragments> encodeMetadata(ResponseMessage<?> metadata,
                                                   DataBufferFactory bufferFactory,
                                                   MediaType mediaType,
                                                   Map<String, Object> hints) {
        ResolvableType metadataType = ResolvableType.forInstance(metadata);
        return encoder
            .encode(Mono.just(metadata), bufferFactory, metadataType, mediaType, hints)
            .single()
            .map(buffer -> {
                byte[] bytes = new byte[buffer.readableByteCount()];
                try {
                    buffer.read(bytes);
                    return createEnvelopeFragments(
                        bytes,
                        metadata.getClass(),
                        jsonEncoding(mediaType));
                } finally {
                    DataBufferUtils.release(buffer);
                }
            });
    }

    private Flux<DataBuffer> joinEnvelope(Flux<DataBuffer> encodedResult,
                                          EnvelopeFragments parts,
                                          DataBufferFactory bufferFactory) {
        return encodedResult
            .switchOnFirst((signal, result) -> {
                if (signal.hasError()) {
                    return result;
                }
                if (!signal.hasValue()) {
                    return Flux.just(bufferFactory.wrap(parts.completeEmpty()));
                }
                return result
                    .index()
                    .map(indexed -> {
                        DataBuffer dataBuffer = indexed.getT2();
                        if (indexed.getT1() != 0) {
                            return dataBuffer;
                        }
                        // Delay the outer prefix until the first array buffer so an
                        // early source/serialization error remains uncommitted.
                        return bufferFactory.join(List.of(
                            bufferFactory.wrap(parts.prefix()),
                            dataBuffer));
                    })
                    .concatWith(Mono.fromSupplier(
                        () -> bufferFactory.wrap(parts.suffix())));
            })
            .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
    }

    private EnvelopeFragments createEnvelopeFragments(byte[] metadata,
                                                       Class<?> metadataType,
                                                       JsonEncoding encoding) {
        try {
            JsonNode json = mapper.readTree(metadata);
            if (!(json instanceof ObjectNode objectNode)) {
                throw new EncodingException("ResponseMessage metadata must encode as a JSON object");
            }

            PropertyPosition property = resolveResultProperty(metadataType, objectNode);
            ByteArrayOutputStream output = new ByteArrayOutputStream(metadata.length + 32);
            int prefixEnd = -1;
            int suffixStart = -1;

            try (JsonGenerator generator = mapper.getFactory().createGenerator(output, encoding)) {
                generator.setCodec(mapper);
                generator.writeStartObject();
                Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String name = field.getKey();
                    if (prefixEnd < 0 &&
                        (name.equals(property.resultName()) ||
                            name.equals(property.nextPropertyName()))) {
                        generator.writeFieldName(property.resultName());
                        generator.writeStartArray();
                        generator.flush();
                        prefixEnd = output.size() - encoding.bits() / Byte.SIZE;
                        generator.writeEndArray();
                        generator.flush();
                        suffixStart = output.size();
                    }
                    if (name.equals(property.resultName())) {
                        continue;
                    }
                    generator.writeFieldName(name);
                    generator.writeTree(field.getValue());
                }
                if (prefixEnd < 0) {
                    generator.writeFieldName(property.resultName());
                    generator.writeStartArray();
                    generator.flush();
                    prefixEnd = output.size() - encoding.bits() / Byte.SIZE;
                    generator.writeEndArray();
                    generator.flush();
                    suffixStart = output.size();
                }
                generator.writeEndObject();
            }

            byte[] complete = output.toByteArray();
            return new EnvelopeFragments(
                Arrays.copyOfRange(complete, 0, prefixEnd),
                Arrays.copyOfRange(complete, prefixEnd, suffixStart),
                Arrays.copyOfRange(complete, suffixStart, complete.length));
        } catch (IOException error) {
            throw new EncodingException("Failed to create streaming ResponseMessage JSON", error);
        }
    }

    private PropertyPosition resolveResultProperty(Class<?> metadataType, ObjectNode metadata) {
        BeanDescription description = mapper
            .getSerializationConfig()
            .introspect(mapper.constructType(metadataType));
        List<BeanPropertyDefinition> properties = description.findProperties();
        for (int index = 0; index < properties.size(); index++) {
            BeanPropertyDefinition property = properties.get(index);
            if (!"result".equals(property.getInternalName())) {
                continue;
            }
            for (int next = index + 1; next < properties.size(); next++) {
                String nextName = properties.get(next).getName();
                if (metadata.has(nextName)) {
                    return new PropertyPosition(property.getName(), nextName);
                }
            }
            return new PropertyPosition(property.getName(), "");
        }
        return new PropertyPosition("result", metadata.has("status") ? "status" : "");
    }

    private JsonEncoding jsonEncoding(MediaType mediaType) {
        Charset charset = mediaType.getCharset();
        if (charset == null) {
            return JsonEncoding.UTF8;
        }
        if (StandardCharsets.US_ASCII.equals(charset)) {
            return JsonEncoding.UTF8;
        }
        for (JsonEncoding encoding : JsonEncoding.values()) {
            if (encoding.getJavaName().equalsIgnoreCase(charset.name())) {
                return encoding;
            }
        }
        throw new EncodingException("Unsupported JSON charset: " + charset);
    }

    private MediaType selectContentType(@Nullable MediaType mediaType,
                                        ReactiveHttpOutputMessage outputMessage) {
        MediaType contentType = outputMessage.getHeaders().getContentType();
        if (contentType == null) {
            contentType = mediaType != null && mediaType.isConcrete()
                ? mediaType
                : MediaType.APPLICATION_JSON;
            outputMessage.getHeaders().setContentType(contentType);
        }
        return contentType;
    }

    private record PropertyPosition(String resultName, String nextPropertyName) {
    }

    private record EnvelopeFragments(byte[] prefix, byte[] emptyArray, byte[] suffix) {

        byte[] completeEmpty() {
            byte[] complete = new byte[prefix.length + emptyArray.length + suffix.length];
            System.arraycopy(prefix, 0, complete, 0, prefix.length);
            System.arraycopy(emptyArray, 0, complete, prefix.length, emptyArray.length);
            System.arraycopy(suffix, 0, complete, prefix.length + emptyArray.length, suffix.length);
            return complete;
        }
    }
}
