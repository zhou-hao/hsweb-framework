package org.hswebframework.web.bean;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.proxy.Proxy;
import org.springframework.core.ResolvableType;

import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Record constructor copier support.
 * <p>
 * Record is immutable, so copy-to-record cannot follow the normal in-place
 * {@link Copier} contract. This support generates a small constructor copier
 * and reuses the common {@link Converter} for nested type conversion.
 *
 * @author zhouhao
 * @since 5.0.2
 */
@Slf4j
final class RecordBeanCopierSupport {
    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPERS = new HashMap<>();

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

    private RecordBeanCopierSupport() {
    }

    static RecordCopier createRecordCopier(Class<?> source, Class<?> target) {
        String method = "public Object copy(Object s, java.util.Set ignore, " +
            "org.hswebframework.web.bean.Converter converter){\n" +
            "try{\n\t" +
            createRecordCopierCode(source, target) +
            "}catch(Throwable e){\n" +
            "\tthrow new UnsupportedOperationException(e.getMessage(), e);" +
            "\n}\n" +
            "\n}";
        try {
            @SuppressWarnings("all")
            Proxy<RecordCopier> proxy = Proxy
                .create(RecordCopier.class, new Class[]{source, target})
                .addMethod(method);
            return proxy.newInstance();
        } catch (Exception e) {
            log.error("创建record copy代理对象失败:\n{}", method, e);
            throw new UnsupportedOperationException(e.getMessage(), e);
        }
    }

    private static String createRecordCopierCode(Class<?> source, Class<?> target) {
        Map<String, FastBeanCopierPropertySupport.ClassProperty> sourceProperties =
            Map.class.isAssignableFrom(source)
                ? FastBeanCopierPropertySupport.createMapProperty(FastBeanCopierPropertySupport.createRecordProperty(target))
                : FastBeanCopierPropertySupport.createProperty(source);
        String sourceTypeName = getTypeName(source);
        String targetTypeName = getTypeName(target);
        boolean sourceIsMap = Map.class.isAssignableFrom(source);
        StringBuilder code = new StringBuilder();
        code.append(sourceTypeName).append(" $$__source=(").append(sourceTypeName).append(")s;\n\t");

        RecordComponent[] components = target.getRecordComponents();
        List<String> constructorArgs = new ArrayList<>(components.length);
        for (RecordComponent component : components) {
            String name = component.getName();
            Class<?> type = component.getType();
            String typeName = getTypeName(type);
            String varName = "$$__" + name;
            code.append(typeName).append(" ").append(varName).append("=").append(defaultValueCode(type)).append(";\n\t");
            code.append("if(!ignore.contains(\"").append(name).append("\")){\n\t\t");

            FastBeanCopierPropertySupport.ClassProperty sourceProperty = sourceProperties.get(name);
            if (sourceProperty == null) {
                code.append("// source property not found, keep default value.\n\t");
                code.append("}\n\t");
                constructorArgs.add(varName);
                continue;
            }

            String valueExpression = sourceIsMap
                ? "$$__source.get(\"" + name + "\")"
                : "$$__source." + sourceProperty.getReadMethod();
            if (sourceProperty.isPrimitive()) {
                valueExpression = PRIMITIVE_WRAPPERS
                    .get(sourceProperty.getType())
                    .getName() + ".valueOf(" + valueExpression + ")";
            }
            String generic = resolveRecordGenericCode(component);
            code.append("Object $$__value=(Object)(").append(valueExpression).append(");\n\t\t");
            code.append("if($$__value!=null){\n\t\t\t");
            if (requiresRecordGenericConversion(component, type)) {
                code.append(varName).append("=").append(convertValueCode(type, generic)).append(";\n\t\t");
            } else {
                code.append("if(").append(directAssignableCode(type, "$$__value")).append("){\n\t\t\t");
                code.append(varName).append("=").append(castValueCode(type, "$$__value")).append(";\n\t\t\t");
                code.append("}else{\n\t\t\t");
                code.append(varName).append("=").append(convertValueCode(type, generic)).append(";\n\t\t\t");
                code.append("}\n\t\t");
            }
            code.append("}\n\t");
            code.append("}\n\t");
            constructorArgs.add(varName);
        }
        code.append("return new ").append(targetTypeName).append("(")
            .append(String.join(",", constructorArgs))
            .append(");\n");
        return code.toString();
    }

    private static boolean requiresRecordGenericConversion(RecordComponent component, Class<?> type) {
        return ResolvableType.forType(component.getGenericType()).hasGenerics()
            && (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type));
    }

    private static String resolveRecordGenericCode(RecordComponent component) {
        Class<?>[] genericTypes = Arrays.stream(ResolvableType.forType(component.getGenericType()).getGenerics())
            .map(ResolvableType::resolve)
            .filter(Objects::nonNull)
            .toArray(Class[]::new);
        if (genericTypes.length == 0) {
            return "org.hswebframework.web.bean.FastBeanCopierSupport.EMPTY_CLASS_ARRAY";
        }
        return "new Class[]{" + Arrays.stream(genericTypes)
            .map(type -> getTypeName(type) + ".class")
            .collect(Collectors.joining(",")) + "}";
    }

    private static String getTypeName(Class<?> type) {
        if (type.isArray()) {
            return getTypeName(type.getComponentType()) + "[]";
        }
        return type.getName();
    }

    private static String defaultValueCode(Class<?> type) {
        if (!type.isPrimitive()) {
            return "null";
        }
        if (type == boolean.class) {
            return "false";
        }
        if (type == char.class) {
            return "(char)0";
        }
        if (type == byte.class) {
            return "(byte)0";
        }
        if (type == short.class) {
            return "(short)0";
        }
        if (type == long.class) {
            return "0L";
        }
        if (type == float.class) {
            return "0F";
        }
        if (type == double.class) {
            return "0D";
        }
        return "0";
    }

    private static String castValueCode(Class<?> type, String value) {
        if (!type.isPrimitive()) {
            return "(" + getTypeName(type) + ")" + value;
        }
        Class<?> wrapper = PRIMITIVE_WRAPPERS.get(type);
        return "((" + wrapper.getName() + ")" + value + ")." + type.getName() + "Value()";
    }

    private static String convertValueCode(Class<?> type, String generic) {
        String converted = "converter.convert($$__value," + getTypeName(type) + ".class," + generic + ")";
        if (!type.isPrimitive()) {
            return "(" + getTypeName(type) + ")" + converted;
        }
        Class<?> wrapper = PRIMITIVE_WRAPPERS.get(type);
        return "((" + wrapper.getName() + ")" + converted + ")." + type.getName() + "Value()";
    }

    private static String directAssignableCode(Class<?> type, String value) {
        Class<?> directType = type.isPrimitive()
            ? PRIMITIVE_WRAPPERS.get(type)
            : type;
        return value + " instanceof " + getTypeName(directType);
    }
}
