package org.hswebframework.web.starter.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.lang.Nullable;
import org.springframework.util.MimeType;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

import java.util.Map;
import java.util.function.Function;

/**
 * Jackson encoder that delegates JSON framing to Spring and restores hsweb's
 * registered thread-local context while Jackson synchronously serializes each value.
 *
 * <p>The context bridge only decorates Reactive Streams signals. It does not subscribe,
 * buffer, or alter demand and cancellation semantics. All registered Micrometer
 * {@code ThreadLocalAccessor} values are captured once per subscription, with Reactor
 * Context values overriding same-key compatibility values from the subscribing thread.</p>
 *
 * @since 5.0
 */
public class CustomJackson2jsonEncoder extends Jackson2JsonEncoder {

    private static final ContextSnapshotFactory SNAPSHOT_FACTORY = ContextSnapshotFactory
        .builder()
        .clearMissing(true)
        .build();

    /**
     * Constructor with the application configured {@link ObjectMapper}.
     *
     * @param mapper    mapper shared with Spring WebFlux
     * @param mimeTypes optional supported mime types
     */
    protected CustomJackson2jsonEncoder(ObjectMapper mapper, MimeType... mimeTypes) {
        super(mapper, mimeTypes);
    }

    @Override
    public Flux<DataBuffer> encode(Publisher<?> inputStream,
                                   DataBufferFactory bufferFactory,
                                   ResolvableType elementType,
                                   @Nullable MimeType mimeType,
                                   @Nullable Map<String, Object> hints) {
        return Flux.deferContextual(contextView -> super.encode(
            restoreContext(inputStream, SNAPSHOT_FACTORY.captureAll(contextView)),
            bufferFactory,
            elementType,
            mimeType,
            hints));
    }

    private <T> Publisher<T> restoreContext(Publisher<T> inputStream, ContextSnapshot snapshot) {
        Function<? super Publisher<T>, ? extends Publisher<T>> lifter = Operators.liftPublisher(
            (publisher, subscriber) -> new ContextRestoringSubscriber<>(subscriber, snapshot));
        if (inputStream instanceof Mono<?>) {
            return Mono.from(inputStream).transform(lifter);
        }
        return Flux.from(inputStream).transform(lifter);
    }

    private static final class ContextRestoringSubscriber<T> implements CoreSubscriber<T> {

        private final CoreSubscriber<? super T> actual;

        private final ContextSnapshot snapshot;

        private ContextRestoringSubscriber(CoreSubscriber<? super T> actual,
                                           ContextSnapshot snapshot) {
            this.actual = actual;
            this.snapshot = snapshot;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            actual.onSubscribe(subscription);
        }

        @Override
        public void onNext(T value) {
            // The downstream onNext performs Spring's synchronous Jackson encoding.
            try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                actual.onNext(value);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            actual.onError(throwable);
        }

        @Override
        public void onComplete() {
            actual.onComplete();
        }

        @Override
        public Context currentContext() {
            return actual.currentContext();
        }
    }
}
