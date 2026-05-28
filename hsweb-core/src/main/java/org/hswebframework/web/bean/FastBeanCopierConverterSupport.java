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
    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPERS = new HashMap<>();
    private static final org.apache.commons.beanutils.Converter NO_CONVERTER = new org.apache.commons.beanutils.Converter() {
        @Override
        public <T> T convert(Class<T> type, Object value) {
            return null;
        }
    };
    private static final Map<Class<?>, org.apache.commons.beanutils.Converter> APACHE_CONVERTER_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, Object>> ENUM_LOOKUP_CACHE = new ConcurrentHashMap<>();

    static {
        PRIMITIVE_WRAPPERS.put(byte.class, Byte.class);
        PRIMITIVE_WRAPPERS.put(short.class, Short.class);
        PRIMITIVE_WRAPPERS.put(int.class, Integer.class);
        PRIMITIVE_WRAPPERS.put(float.class, Float.class);
        PRIMITIVE_WRAPPERS.put(double.class, Double.class);
        PRIMITIVE_WRAPPERS.put(char.class, Character.class);
        PRIMITIVE_WRAPPERS.put(boolean.class, Boolean.class);
        PRIMITIVE_WRAPPERS.put(long.class, Long.class);
    }

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

        if (isDirectScalarAssignable(targetClass, source)) {
            return (T) source;
        }

        if (target.isEnumType() && source instanceof EnumDict) {
            Object val = ((EnumDict) source).getValue();
            if (targetClass.isInstance(val)) {
                return ((T) val);
            }
            Object matched = lookupEnum(targetClass, val);
            if (matched == null) {
                matched = lookupEnum(targetClass, ((EnumDict<?>) source).getText());
            }
            if (matched != null) {
                return (T) matched;
            }
            return convert(val, targetClass, genericType);
        }
        if (targetClass == String.class || targetClass == CharSequence.class) {
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
        if (targetClass == boolean.class || targetClass == Boolean.class) {
            return (T) convertToBoolean(source);
        }
        if (targetClass == char.class || targetClass == Character.class) {
            return (T) convertToCharacter(source);
        }
        if (target.isCollectionType()) {
            Collection collection = newCollection(targetClass);
            Collection sourceCollection = asCollection(source);
            if (genericType != null && genericType.length > 0 && genericType[0] != Object.class) {
                Class<?> elementType = genericType[0];
                for (Object sourceObj : sourceCollection) {
                    if (sourceObj == null || isDirectAssignable(elementType, sourceObj)) {
                        collection.add(sourceObj);
                    } else {
                        collection.add(convert(sourceObj, elementType, null));
                    }
                }
            } else {
                collection.addAll(sourceCollection);
            }
            return (T) collection;
        }
        if (target.isEnumType()) {
            Object val = lookupEnum(targetClass, source);
            if (val != null) {
                if (targetClass.isInstance(val)) {
                    return (T) val;
                }
                return convert(val, targetClass, genericType);
            }
            log.warn("无法将:{}转为枚举:{}",
                     source,
                     targetClass,
                     new ClassCastException(source + "=>" + targetClass));
            return null;
        }
        if (target.isArrayType()) {
            return (T) convertToArray(source, targetClass.getComponentType());
        }
        if (target.isNumber()) {
            if (source instanceof Number) {
                return (T) convertNumber((Number) source, targetClass);
            }
            if (source instanceof String) {
                return (T) convertStringToNumber((String) source, targetClass);
            }
            if (source instanceof Date) {
                source = ((Date) source).getTime();
            }
        }
        if (source instanceof Map && isBeanLikeTarget(target, targetClass)) {
            return FastBeanCopierSupport.copy(source, beanFactory.newInstance(targetClass), this);
        }
        try {
            org.apache.commons.beanutils.Converter converter = getApacheConverter(targetClass);
            if (converter != null) {
                return converter.convert(targetClass, source);
            }

            if (targetClass == Map.class) {
                if (source instanceof Map) {
                    return (T) copyMap(((Map<?, ?>) source));
                }
                if (source instanceof Collection) {
                    Collection<?> sourceCollection = (Collection<?>) source;
                    Map<Object, Object> map = new LinkedHashMap<>(Math.max((int) (sourceCollection.size() / 0.75F) + 1, 16));
                    int i = 0;
                    for (Object o : sourceCollection) {
                        if (genericType != null && genericType.length >= 2) {
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

    private Collection<?> asCollection(Object source) {
        if (source instanceof Collection) {
            return (Collection<?>) source;
        }
        if (source.getClass().isArray()) {
            return new DynamicArrayList(source);
        }
        if (source instanceof Map) {
            return ((Map<?, ?>) source).values();
        }
        if (source instanceof String) {
            return Arrays.asList(((String) source).split("[,]"));
        }
        return Collections.singletonList(source);
    }

    private Object convertToArray(Object source, Class<?> componentType) {
        if (source.getClass().isArray() && source.getClass().getComponentType() == componentType) {
            int length = Array.getLength(source);
            Object array = Array.newInstance(componentType, length);
            System.arraycopy(source, 0, array, 0, length);
            return array;
        }
        Collection<?> sourceCollection = asCollection(source);
        Object array = Array.newInstance(componentType, sourceCollection.size());
        int index = 0;
        for (Object value : sourceCollection) {
            Array.set(array,
                      index++,
                      value == null || isDirectAssignable(componentType, value)
                          ? value
                          : convert(value, componentType, FastBeanCopierSupport.EMPTY_CLASS_ARRAY));
        }
        return array;
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
        org.apache.commons.beanutils.Converter converter = getApacheConverter(targetClass);
        if (converter != null) {
            return converter.convert(targetClass, source);
        }
        return null;
    }

    private org.apache.commons.beanutils.Converter getApacheConverter(Class<?> targetClass) {
        org.apache.commons.beanutils.Converter converter = APACHE_CONVERTER_CACHE.computeIfAbsent(targetClass,
                                                                                                  type -> {
                                                                                                      org.apache.commons.beanutils.Converter found = CONVERT_UTILS.lookup(type);
                                                                                                      return found == null ? NO_CONVERTER : found;
                                                                                                  });
        return converter == NO_CONVERTER ? null : converter;
    }

    private boolean isDirectScalarAssignable(Class<?> targetClass, Object source) {
        if (source instanceof Number || source instanceof Boolean || source instanceof Character || source instanceof Enum) {
            return isDirectAssignable(targetClass, source);
        }
        return false;
    }

    private Object lookupEnum(Class<?> targetClass, Object source) {
        if (source == null) {
            return null;
        }
        if (targetClass.isInstance(source)) {
            return source;
        }
        if (source instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) source;
            Object value = map.get("value");
            Object matched = lookupEnum(targetClass, value);
            return matched != null ? matched : lookupEnum(targetClass, map.get("text"));
        }
        if (source instanceof EnumDict) {
            EnumDict<?> dict = (EnumDict<?>) source;
            Object matched = lookupEnum(targetClass, dict.getValue());
            return matched != null ? matched : lookupEnum(targetClass, dict.getText());
        }
        if (source instanceof Number) {
            source = ((Number) source).intValue();
        }
        String key = normalizeEnumKey(source);
        return ENUM_LOOKUP_CACHE
            .computeIfAbsent(targetClass, this::createEnumLookup)
            .get(key);
    }

    private Map<String, Object> createEnumLookup(Class<?> targetClass) {
        ClassDescription description = ClassDescriptions.getDescription(targetClass);
        Map<String, Object> lookup = new HashMap<>();
        for (Object obj : description.getEnums()) {
            Enum<?> value = (Enum<?>) obj;
            lookup.putIfAbsent(normalizeEnumKey(value.name()), obj);
            lookup.putIfAbsent(normalizeEnumKey(value.ordinal()), obj);
            if (obj instanceof EnumDict) {
                EnumDict<?> dict = (EnumDict<?>) obj;
                lookup.putIfAbsent(normalizeEnumKey(dict.getValue()), obj);
                lookup.putIfAbsent(normalizeEnumKey(dict.getText()), obj);
            }
        }
        return lookup;
    }

    private String normalizeEnumKey(Object value) {
        return String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private boolean isDirectAssignable(Class<?> targetClass, Object source) {
        if (source == null) {
            return false;
        }
        if (targetClass.isInstance(source)) {
            return true;
        }
        if (targetClass.isPrimitive()) {
            Class<?> wrapper = PRIMITIVE_WRAPPERS.get(targetClass);
            return wrapper != null && wrapper.isInstance(source);
        }
        return false;
    }

    private boolean isBeanLikeTarget(ClassDescription target, Class<?> targetClass) {
        return targetClass != Object.class
            && targetClass != String.class
            && targetClass != CharSequence.class
            && targetClass != Date.class
            && targetClass != Boolean.class
            && targetClass != Character.class
            && targetClass != boolean.class
            && targetClass != char.class
            && !target.isEnumType()
            && !target.isArrayType()
            && !target.isCollectionType()
            && !target.isNumber()
            && !Map.class.isAssignableFrom(targetClass);
    }

    private Number convertNumber(Number source, Class<?> targetClass) {
        Class<?> wrapper = targetClass.isPrimitive() ? PRIMITIVE_WRAPPERS.get(targetClass) : targetClass;
        return NumberUtils.convertNumberToTargetClass(source, (Class<? extends Number>) wrapper);
    }

    private Number convertStringToNumber(String source, Class<?> targetClass) {
        String value = source.trim();
        Class<?> wrapper = targetClass.isPrimitive() ? PRIMITIVE_WRAPPERS.get(targetClass) : targetClass;
        try {
            if (wrapper == Integer.class) {
                return Integer.parseInt(value);
            }
            if (wrapper == Long.class) {
                return Long.parseLong(value);
            }
            if (wrapper == Short.class) {
                return Short.parseShort(value);
            }
            if (wrapper == Byte.class) {
                return Byte.parseByte(value);
            }
            if (wrapper == Double.class) {
                return Double.parseDouble(value);
            }
            if (wrapper == Float.class) {
                return Float.parseFloat(value);
            }
        } catch (NumberFormatException ignore) {
            // fallback to Spring NumberUtils for extended formats
        }
        return NumberUtils.parseNumber(value, (Class) targetClass);
    }

    private Boolean convertToBoolean(Object source) {
        if (source instanceof Boolean) {
            return (Boolean) source;
        }
        if (source instanceof Number) {
            return ((Number) source).intValue() != 0;
        }
        String value = String.valueOf(source).trim();
        if (value.isEmpty()) {
            return Boolean.FALSE;
        }
        return "true".equalsIgnoreCase(value)
            || "1".equals(value)
            || "y".equalsIgnoreCase(value)
            || "yes".equalsIgnoreCase(value)
            || "on".equalsIgnoreCase(value);
    }

    private Character convertToCharacter(Object source) {
        if (source instanceof Character) {
            return (Character) source;
        }
        if (source instanceof Number) {
            return (char) ((Number) source).intValue();
        }
        String value = String.valueOf(source);
        return value.isEmpty() ? null : value.charAt(0);
    }
}
