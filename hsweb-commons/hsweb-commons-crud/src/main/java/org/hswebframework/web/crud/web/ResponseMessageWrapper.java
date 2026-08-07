package org.hswebframework.web.crud.web;

import lombok.Getter;
import lombok.Setter;
import org.reactivestreams.Publisher;
import org.springframework.core.MethodParameter;
import org.springframework.core.ReactiveAdapter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.EncoderHttpMessageWriter;
import org.springframework.http.codec.HttpMessageEncoder;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.lang.NonNull;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.reactive.accept.RequestedContentTypeResolver;
import org.springframework.web.reactive.result.method.annotation.ResponseBodyResultHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wraps annotated reactive controller results in {@link ResponseMessage}.
 *
 * <p>Multi-value JSON responses use a dedicated writer so the original Publisher remains
 * backpressure-aware and is encoded as an incremental array. Streaming protocols such as
 * SSE and NDJSON remain unwrapped.</p>
 */
public class ResponseMessageWrapper extends ResponseBodyResultHandler {

    private static final String IGNORE_HEADER = "X-Response-Wrapper";

    private static final MethodParameter RESPONSE_MESSAGE_TYPE =
        returnTypeOf("methodForResponseMessage");

    private static final MethodParameter STREAMING_RESPONSE_MESSAGE_TYPE =
        returnTypeOf("methodForStreamingResponseMessage");

    private final boolean streamingResponseWriterAvailable;

    @Setter
    @Getter
    private Set<String> excludes = new HashSet<>();

    public ResponseMessageWrapper(List<HttpMessageWriter<?>> writers,
                                  RequestedContentTypeResolver resolver,
                                  ReactiveAdapterRegistry registry) {
        this(configureWriters(writers), resolver, registry);
    }

    private ResponseMessageWrapper(WriterConfiguration configuration,
                                   RequestedContentTypeResolver resolver,
                                   ReactiveAdapterRegistry registry) {
        super(configuration.writers(), resolver, registry);
        this.streamingResponseWriterAvailable = configuration.streamingWriterAvailable();
        setOrder(90);
    }

    @Override
    public boolean supports(@NonNull HandlerResult result) {
        if (isExcluded(result)) {
            return false;
        }

        ReactiveAdapter adapter = getAdapter(result);
        if (adapter == null) {
            return false;
        }

        ResolvableType elementType = getElementType(result.getReturnType(), adapter);
        Class<?> elementClass = elementType.resolve();
        if (elementClass != null &&
            (ResponseMessage.class.isAssignableFrom(elementClass) ||
                ResponseEntity.class.isAssignableFrom(elementClass))) {
            return false;
        }

        RequestMapping mapping = getRequestMapping(result);
        if (mapping == null || hasStreamingProduces(mapping)) {
            return false;
        }
        return super.supports(result);
    }

    @Override
    public Mono<Void> handleResult(ServerWebExchange exchange, HandlerResult result) {
        Object body = result.getReturnValue();
        if ("Ignore".equals(exchange.getRequest().getHeaders().getFirst(IGNORE_HEADER))) {
            return writeBody(body, result.getReturnTypeSource(), exchange);
        }

        ReactiveAdapter adapter = getAdapter(result);
        if (adapter == null) {
            return writeBody(body, result.getReturnTypeSource(), exchange);
        }

        ResolvableType elementType = getElementType(result.getReturnType(), adapter);
        MediaType selectedMediaType = selectMediaType(
            exchange,
            () -> getMediaTypesFor(elementType));
        if (isStreamingMediaType(selectedMediaType)) {
            return writeBody(body, result.getReturnTypeSource(), exchange);
        }

        Publisher<?> publisher = adapter.toPublisher(body);
        if (adapter.isMultiValue()) {
            if (!streamingResponseWriterAvailable || !isJson(selectedMediaType)) {
                return writeBody(body, result.getReturnTypeSource(), exchange);
            }
            StreamingResponseMessage streaming = new StreamingResponseMessage(
                ResponseMessage.ok(),
                publisher,
                result.getReturnType(),
                elementType);
            return writeBody(
                Mono.just(streaming),
                STREAMING_RESPONSE_MESSAGE_TYPE,
                exchange);
        }

        Mono<?> wrapped = adapter.isNoValue()
            ? Mono.from(publisher).then(Mono.fromSupplier(ResponseMessage::ok))
            : Mono.from(publisher)
            .map(ResponseMessage::ok)
            .switchIfEmpty(Mono.fromSupplier(ResponseMessage::ok));
        return writeBody(wrapped, RESPONSE_MESSAGE_TYPE, exchange);
    }

    private boolean isExcluded(HandlerResult result) {
        if (CollectionUtils.isEmpty(excludes) || !(result.getHandler() instanceof HandlerMethod method)) {
            return false;
        }
        String typeName = method.getMethod().getDeclaringClass().getName() + "." +
            method.getMethod().getName();
        return excludes.stream().anyMatch(typeName::startsWith);
    }

    private RequestMapping getRequestMapping(HandlerResult result) {
        Method method = result.getReturnTypeSource().getMethod();
        return method == null
            ? null
            : AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
    }

    private boolean hasStreamingProduces(RequestMapping mapping) {
        for (String produce : mapping.produces()) {
            if (isStreamingMediaType(MediaType.asMediaType(MimeType.valueOf(produce)))) {
                return true;
            }
        }
        return false;
    }

    private ResolvableType getElementType(ResolvableType returnType, ReactiveAdapter adapter) {
        if (adapter.isNoValue()) {
            return ResolvableType.forClass(Void.class);
        }
        ResolvableType generic = returnType.getGeneric();
        return generic == ResolvableType.NONE
            ? ResolvableType.forClass(Object.class)
            : generic;
    }

    private List<MediaType> getMediaTypesFor(ResolvableType elementType) {
        List<MediaType> mediaTypes = new ArrayList<>();
        for (HttpMessageWriter<?> writer : getMessageWriters()) {
            if (writer.canWrite(elementType, null)) {
                mediaTypes.addAll(writer.getWritableMediaTypes(elementType));
            }
        }
        return mediaTypes;
    }

    private boolean isStreamingMediaType(MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(mediaType) ||
            mediaType.getSubtype().endsWith("+x-ndjson")) {
            return true;
        }
        for (HttpMessageWriter<?> writer : getMessageWriters()) {
            if (!(writer instanceof EncoderHttpMessageWriter<?> encoderWriter) ||
                !(encoderWriter.getEncoder() instanceof HttpMessageEncoder<?> encoder)) {
                continue;
            }
            for (MediaType streamingType : encoder.getStreamingMediaTypes()) {
                if (streamingType.isCompatibleWith(mediaType)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isJson(MediaType mediaType) {
        return mediaType != null &&
            (MediaType.APPLICATION_JSON.isCompatibleWith(mediaType) ||
                mediaType.getSubtype().endsWith("+json"));
    }

    private static WriterConfiguration configureWriters(List<HttpMessageWriter<?>> writers) {
        List<HttpMessageWriter<?>> configured = new ArrayList<>(writers);
        for (int index = 0; index < configured.size(); index++) {
            HttpMessageWriter<?> writer = configured.get(index);
            if (writer instanceof EncoderHttpMessageWriter<?> encoderWriter &&
                encoderWriter.getEncoder() instanceof Jackson2JsonEncoder encoder) {
                configured.add(index, new ResponseMessageJacksonHttpMessageWriter(encoder));
                return new WriterConfiguration(configured, true);
            }
        }
        return new WriterConfiguration(configured, false);
    }

    private static MethodParameter returnTypeOf(String methodName) {
        try {
            return new MethodParameter(
                ResponseMessageWrapper.class.getDeclaredMethod(methodName),
                -1);
        } catch (NoSuchMethodException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static Mono<ResponseMessage<?>> methodForResponseMessage() {
        return Mono.empty();
    }

    private static Mono<StreamingResponseMessage> methodForStreamingResponseMessage() {
        return Mono.empty();
    }

    private record WriterConfiguration(List<HttpMessageWriter<?>> writers,
                                       boolean streamingWriterAvailable) {
    }
}
