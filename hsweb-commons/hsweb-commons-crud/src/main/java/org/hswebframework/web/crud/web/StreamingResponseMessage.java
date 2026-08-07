package org.hswebframework.web.crud.web;

import org.reactivestreams.Publisher;
import org.springframework.core.ResolvableType;

/**
 * Internal hand-off between {@link ResponseMessageWrapper} and the JSON message writer.
 *
 * <p>The controller contract remains a regular reactive return type. This marker keeps
 * the result Publisher outside the JavaBean so Jackson never attempts to serialize or
 * subscribe to a nested reactive property.</p>
 */
final class StreamingResponseMessage {

    private final ResponseMessage<?> metadata;

    private final Publisher<?> result;

    private final ResolvableType actualType;

    private final ResolvableType elementType;

    StreamingResponseMessage(ResponseMessage<?> metadata,
                             Publisher<?> result,
                             ResolvableType actualType,
                             ResolvableType elementType) {
        this.metadata = metadata;
        this.result = result;
        this.actualType = actualType;
        this.elementType = elementType;
    }

    ResponseMessage<?> getMetadata() {
        return metadata;
    }

    Publisher<?> getResult() {
        return result;
    }

    ResolvableType getActualType() {
        return actualType;
    }

    ResolvableType getElementType() {
        return elementType;
    }
}
