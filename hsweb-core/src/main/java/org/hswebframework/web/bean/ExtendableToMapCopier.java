package org.hswebframework.web.bean;

import lombok.AllArgsConstructor;
import org.hswebframework.ezorm.core.Extendable;

import java.util.Map;
import java.util.Set;

@AllArgsConstructor
class ExtendableToMapCopier implements Copier {

    private final Copier copier;

    @Override
    public void copy(Object source, Object target, Set<String> ignore, Converter converter) {
        ExtendableUtils.copyToMap((Extendable) source, ignore, (Map<String, Object>) target);
        copier.copy(source, target, ignore, converter);
        //移除map中的extensions
        ((Map<?, ?>) target).remove("extensions");
    }


}
