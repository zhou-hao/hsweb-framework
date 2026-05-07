package org.hswebframework.web.bean;

import com.google.common.collect.Maps;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.beanutils.BeanUtilsBean;
import org.apache.commons.beanutils.ConvertUtilsBean;
import org.hswebframework.utils.time.DateFormatter;
import org.hswebframework.web.dict.EnumDict;
import org.hswebframework.web.utils.DynamicArrayList;
import org.springframework.util.NumberUtils;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
final class FastBeanCopierConverterSupport implements Converter {
    private static final ConvertUtilsBean CONVERT_UTILS = BeanUtilsBean.getInstance().getConvertUtils();

    private BeanFactory beanFactory;

    void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    Collection<?> newCollection(Class<?> targetClass) {
        if (targetClass == List.class || targetClass == Collection.class) {
            return new ArrayList<>();
        } else if (targetClass == ConcurrentHashMap.KeySetView.class) {
            return ConcurrentHashMap.newKeySet();
        } else if (targetClass == Set.class) {
            return new HashSet<>();
        } else if (targetClass == Queue.class) {
            return new LinkedList<>();
        } else {
            try {
                return (Collection<?>) targetClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new UnsupportedOperationException("Unsupported Collection Type:" + targetClass, e);
            }
        }
    }

    @Override
    @SuppressWarnings("all")
    @SneakyThrows
    public <T> T convert(Object source, Class<T> targetClass, Class[] genericType) {
        if (source == null) {
            return null;
        }
        ClassDescription target = ClassDescriptions.getDescription(targetClass);

        if (target.isEnumType() && source instanceof EnumDict) {
            Object val = ((EnumDict) source).getValue();
            if (targetClass.isInstance(val)) {
                return ((T) val);
            }
            return convert(val, targetClass, genericType);
        }
        if (targetClass == String.class) {
            if (source instanceof Date) {
                return (T) DateFormatter.toString(((Date) source), "yyyy-MM-dd HH:mm:ss");
            }
            return (T) String.valueOf(source);
        }
        if (targetClass == Object.class) {
            return (T) source;
        }
        if (targetClass == Date.class) {
            if (source instanceof String) {
                T parsed = (T) DateFormatter.fromString((String) source);
                if (parsed == null) {
                    return (T) converterByApache(Date.class, source);
                }
                return parsed;
            }
            if (source instanceof Number) {
                return (T) new Date(((Number) source).longValue());
            }
            if (source instanceof Date) {
                return (T) new Date(((Date) source).getTime());
            }
        }
        if (target.isCollectionType()) {
            Collection collection = newCollection(targetClass);
            Collection sourceCollection;
            if (source instanceof Collection) {
                sourceCollection = (Collection) source;
            } else if (source.getClass().isArray()) {
                sourceCollection = new DynamicArrayList(source);
            } else if (source instanceof Map) {
                sourceCollection = ((Map<?, ?>) source).values();
            } else if (source instanceof String) {
                sourceCollection = Arrays.asList(((String) source).split("[,]"));
            } else {
                sourceCollection = Arrays.asList(source);
            }
            if (genericType != null && genericType.length > 0 && genericType[0] != Object.class) {
                for (Object sourceObj : sourceCollection) {
                    collection.add(convert(sourceObj, genericType[0], null));
                }
            } else {
                collection.addAll(sourceCollection);
            }
            return (T) collection;
        }
        if (target.isEnumType()) {
            if (target.isEnumDict()) {
                String strVal = String.valueOf(source);
                Object val = null;
                for (Object anEnum : target.getEnums()) {
                    EnumDict dic = ((EnumDict) anEnum);
                    Enum e = ((Enum<?>) anEnum);
                    if (dic.eq(source) || e.name().equalsIgnoreCase(strVal)) {
                        val = anEnum;
                        break;
                    }
                }
                if (val == null) {
                    return null;
                }
                if (targetClass.isInstance(val)) {
                    return ((T) val);
                }
                return convert(val, targetClass, genericType);
            }
            String strSource = String.valueOf(source);
            for (Object e : target.getEnums()) {
                Enum t = ((Enum<?>) e);
                if (t.name().equalsIgnoreCase(strSource)
                    || Objects.equals(String.valueOf(t.ordinal()), strSource)) {
                    return (T) e;
                }
            }

            log.warn("无法将:{}转为枚举:{}",
                     source,
                     targetClass,
                     new ClassCastException(source + "=>" + targetClass));
            return null;
        }
        if (target.isArrayType()) {
            Class<?> componentType = targetClass.getComponentType();
            List<?> val = convert(source, List.class, new Class[]{componentType});
            int size = val.size();

            Object array = Array.newInstance(componentType, size);
            for (int i = 0; i < size; i++) {
                Array.set(array, i, val.get(i));
            }
            return (T) array;
        }
        if (target.isNumber()) {
            if (source instanceof String) {
                return (T) NumberUtils.parseNumber(String.valueOf(source), (Class) targetClass);
            }
            if (source instanceof Date) {
                source = ((Date) source).getTime();
            }
        }
        try {
            org.apache.commons.beanutils.Converter converter = CONVERT_UTILS.lookup(targetClass);
            if (converter != null) {
                return converter.convert(targetClass, source);
            }

            if (targetClass == Map.class) {
                if (source instanceof Map) {
                    return (T) copyMap(((Map<?, ?>) source));
                }
                if (source instanceof Collection) {
                    Map<Object, Object> map = new LinkedHashMap<>();
                    int i = 0;
                    for (Object o : ((Collection<?>) source)) {
                        if (genericType.length >= 2) {
                            map.put(convert(i++, genericType[0], FastBeanCopierSupport.EMPTY_CLASS_ARRAY),
                                    convert(o, genericType[1], FastBeanCopierSupport.EMPTY_CLASS_ARRAY));
                        } else {
                            map.put(i++, o);
                        }
                    }
                    return (T) map;
                }
                ClassDescription sourceType = ClassDescriptions.getDescription(source.getClass());
                return (T) FastBeanCopierSupport.copy(source, Maps.newHashMapWithExpectedSize(sourceType.getFieldSize()));
            }

            return FastBeanCopierSupport.copy(source, beanFactory.newInstance(targetClass), this);
        } catch (Throwable e) {
            log.warn("Copy {} to {} failed", source, targetClass, e);
            throw e;
        }
    }

    private Map<?, ?> copyMap(Map<?, ?> map) {
        if (map instanceof TreeMap) {
            return new TreeMap<>(map);
        }
        if (map instanceof LinkedHashMap) {
            return new LinkedHashMap<>(map);
        }
        if (map instanceof ConcurrentHashMap) {
            return new ConcurrentHashMap<>(map);
        }
        return new HashMap<>(map);
    }

    private Object converterByApache(Class<?> targetClass, Object source) {
        org.apache.commons.beanutils.Converter converter = CONVERT_UTILS.lookup(targetClass);
        if (converter != null) {
            return converter.convert(targetClass, source);
        }
        return null;
    }
}
