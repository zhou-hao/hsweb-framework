package org.hswebframework.web.bean;

import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.hswebframework.web.dict.EnumDict;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * FastBeanCopier core support.
 *
 * @author zhouhao
 * @since 3.0
 */
public final class FastBeanCopierSupport {
    private static final Map<CacheKey, Copier> CACHE = new ConcurrentHashMap<>();

    @SuppressWarnings("all")
    public static final Class[] EMPTY_CLASS_ARRAY = new Class[0];

    private static BeanFactory BEAN_FACTORY;

    private static FastBeanCopierBackend BACKEND;

    public static final DefaultConverter DEFAULT_CONVERT;

    public static void setBeanFactory(BeanFactory beanFactory) {
        BEAN_FACTORY = beanFactory;
        DEFAULT_CONVERT.setBeanFactory(beanFactory);
    }

    public static BeanFactory getBeanFactory() {
        return BEAN_FACTORY;
    }

    public static void setBackend(FastBeanCopierBackend backend) {
        BACKEND = Objects.requireNonNull(backend, "backend");
        clearCache();
    }

    public static FastBeanCopierBackend getBackend() {
        return BACKEND;
    }

    static {
        BEAN_FACTORY = new BeanFactory() {
            @Override
            @SneakyThrows
            @SuppressWarnings("all")
            public <T> T newInstance(Class<T> beanType) {
                return beanType == Map.class ? (T) new HashMap<>() : beanType.newInstance();
            }
        };
        BACKEND = new JavassistFastBeanCopierBackend();
        DEFAULT_CONVERT = new DefaultConverter();
        DEFAULT_CONVERT.setBeanFactory(BEAN_FACTORY);
    }

    @SuppressWarnings("all")
    public static Set<String> include(String... includeProperties) {
        return new HashSet<String>(Arrays.asList(includeProperties)) {
            @Override
            public boolean contains(Object o) {
                return !super.contains(o);
            }
        };
    }

    public static Object getProperty(Object source, String key) {
        if (source instanceof Map) {
            return ((Map<?, ?>) source).get(key);
        }
        SingleValueMap<Object, Object> map = new SingleValueMap<>();
        copy(source, map, include(key));
        return map.getValue();
    }

    public static <T, S> T copy(S source, T target, String... ignore) {
        return copy(source, target, DEFAULT_CONVERT, ignore);
    }

    public static <T, S> T copy(S source, Supplier<T> target, String... ignore) {
        return copy(source, target.get(), DEFAULT_CONVERT, ignore);
    }

    @SneakyThrows
    public static <T, S> T copy(S source, Class<T> target, String... ignore) {
        return copy(source, target.getDeclaredConstructor().newInstance(), DEFAULT_CONVERT, ignore);
    }

    public static <T, S> T copy(S source, T target, Converter converter, String... ignore) {
        return copy(source,
                    target,
                    converter,
                    (ignore == null || ignore.length == 0)
                        ? Collections.emptySet()
                        : new HashSet<>(Arrays.asList(ignore)));
    }

    public static <T, S> T copy(S source, T target, Set<String> ignore) {
        return copy(source, target, DEFAULT_CONVERT, ignore);
    }

    @SuppressWarnings("all")
    public static <T, S> T copy(S source, T target, Converter converter, Set<String> ignore) {
        if (source instanceof Map && target instanceof Map) {
            if (CollectionUtils.isEmpty(ignore)) {
                ((Map) target).putAll(((Map) source));
            } else {
                ((Map) source)
                    .forEach((k, v) -> {
                        if (!ignore.contains(k)) {
                            ((Map) target).put(k, v);
                        }
                    });
            }
            return target;
        }

        getCopier(source, target, true)
            .copy(source, target, ignore, converter);
        return target;
    }

    static Class<?> getUserClass(Object object) {
        if (object instanceof Map) {
            return Map.class;
        }
        Class<?> type = ClassUtils.getUserClass(object);

        if (java.lang.reflect.Proxy.isProxyClass(type)) {
            Class<?>[] interfaces = type.getInterfaces();
            return interfaces[0];
        }

        return type;
    }

    public static Copier getCopier(Object source, Object target, boolean autoCreate) {
        Class<?> sourceType = getUserClass(source);
        Class<?> targetType = getUserClass(target);
        CacheKey key = createCacheKey(sourceType, targetType);
        if (autoCreate) {
            return CACHE.computeIfAbsent(key, k -> createCopier(k.sourceType, k.targetType));
        }
        return CACHE.get(key);
    }

    private static CacheKey createCacheKey(Class<?> source, Class<?> target) {
        return new CacheKey(source, target);
    }

    public static Copier createCopier(Class<?> source, Class<?> target) {
        return BACKEND.createCopier(source, target);
    }

    public static Object unwrapEnumDictValue(Object value, Class<?> targetType) {
        if (!(value instanceof EnumDict) || targetType.isEnum()) {
            return value;
        }
        if (Number.class.isAssignableFrom(targetType)
            || (targetType.isPrimitive() && targetType != boolean.class && targetType != char.class)) {
            Object enumValue = ((EnumDict<?>) value).getValue();
            return enumValue == null ? value : enumValue;
        }
        return value;
    }

    static void clearCache() {
        CACHE.clear();
    }

    public static final class DefaultConverter implements Converter {
        private final FastBeanCopierConverterSupport support = new FastBeanCopierConverterSupport();

        public void setBeanFactory(BeanFactory beanFactory) {
            support.setBeanFactory(beanFactory);
        }

        public Collection<?> newCollection(Class<?> targetClass) {
            return support.newCollection(targetClass);
        }

        @Override
        public <T> T convert(Object source, Class<T> targetClass, Class[] genericType) {
            return support.convert(source, targetClass, genericType);
        }
    }

    @AllArgsConstructor
    public static class CacheKey {

        private final Class<?> sourceType;

        private final Class<?> targetType;

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof CacheKey)) {
                return false;
            }
            CacheKey target = ((CacheKey) obj);
            return target.targetType == targetType && target.sourceType == sourceType;
        }

        @Override
        public int hashCode() {
            int result = this.targetType != null ? this.targetType.hashCode() : 0;
            result = 31 * result + (this.sourceType != null ? this.sourceType.hashCode() : 0);
            return result;
        }
    }
}
