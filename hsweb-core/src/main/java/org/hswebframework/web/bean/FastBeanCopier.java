package org.hswebframework.web.bean;

import java.util.Set;
import java.util.function.Supplier;

/**
 * FastBeanCopier facade.
 */
public final class FastBeanCopier {

    @SuppressWarnings("all")
    public static final Class[] EMPTY_CLASS_ARRAY = FastBeanCopierSupport.EMPTY_CLASS_ARRAY;

    public static final FastBeanCopierSupport.DefaultConverter DEFAULT_CONVERT = FastBeanCopierSupport.DEFAULT_CONVERT;

    private FastBeanCopier() {
    }

    public static void setBeanFactory(BeanFactory beanFactory) {
        FastBeanCopierSupport.setBeanFactory(beanFactory);
        DEFAULT_CONVERT.setBeanFactory(beanFactory);
    }

    public static BeanFactory getBeanFactory() {
        return FastBeanCopierSupport.getBeanFactory();
    }

    public static void setBackend(FastBeanCopierBackend backend) {
        FastBeanCopierSupport.setBackend(backend);
    }

    public static FastBeanCopierBackend getBackend() {
        return FastBeanCopierSupport.getBackend();
    }

    @SuppressWarnings("all")
    public static Set<String> include(String... inculdeProperties) {
        return FastBeanCopierSupport.include(inculdeProperties);
    }

    public static Object getProperty(Object source, String key) {
        return FastBeanCopierSupport.getProperty(source, key);
    }

    public static <T, S> T copy(S source, T target, String... ignore) {
        return FastBeanCopierSupport.copy(source, target, ignore);
    }

    public static <T, S> T copy(S source, Supplier<T> target, String... ignore) {
        return FastBeanCopierSupport.copy(source, target, ignore);
    }

    public static <T, S> T copy(S source, Class<T> target, String... ignore) {
        return FastBeanCopierSupport.copy(source, target, ignore);
    }

    public static <T, S> T copy(S source, T target, Converter converter, String... ignore) {
        return FastBeanCopierSupport.copy(source, target, converter, ignore);
    }

    public static <T, S> T copy(S source, T target, Set<String> ignore) {
        return FastBeanCopierSupport.copy(source, target, ignore);
    }

    public static <T, S> T copy(S source, T target, Converter converter, Set<String> ignore) {
        return FastBeanCopierSupport.copy(source, target, converter, ignore);
    }

    static Class<?> getUserClass(Object object) {
        return FastBeanCopierSupport.getUserClass(object);
    }

    public static Copier getCopier(Object source, Object target, boolean autoCreate) {
        return FastBeanCopierSupport.getCopier(source, target, autoCreate);
    }

    public static Copier createCopier(Class<?> source, Class<?> target) {
        return FastBeanCopierSupport.createCopier(source, target);
    }
}
