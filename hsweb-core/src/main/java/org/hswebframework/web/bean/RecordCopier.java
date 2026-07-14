package org.hswebframework.web.bean;

import java.util.Set;

/**
 * Record constructor copier.
 * <p>
 * Record is immutable and cannot reuse {@link Copier}'s in-place target mutation contract,
 * so generated implementations return a newly constructed record instance.
 *
 * @author zhouhao
 * @since 5.0.2
 */
public interface RecordCopier {

    /**
     * Copy source values into a new record instance by canonical constructor order.
     *
     * @param source source bean, record or map; generated implementations cast it to the cached source type
     * @param ignore record component names to keep at Java default values
     * @param converter value converter used when source and target component types are not directly assignable
     * @return new record instance; never mutates an existing target record
     */
    Object copy(Object source, Set<String> ignore, Converter converter);
}
