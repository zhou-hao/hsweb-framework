package org.hswebframework.web.bean;

import com.google.common.collect.ImmutableMap;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.hswebframework.ezorm.core.DefaultExtendable;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.springframework.util.ClassUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author zhouhao
 * @since 3.0
 */
public class FastBeanCopierTest {

    @Test
    public void testExtendableToExtendable() {
        ExtendableEntity source = new ExtendableEntity();
        source.setName("test");
        source.setExtension("age", 123);
        source.setExtension("color", Color.RED);

        ExtendableEntity e = FastBeanCopier.copy(source, new ExtendableEntity());

        Assert.assertEquals(source.getName(), e.getName());
        Assert.assertEquals(source.getExtension("age"), e.getExtension("age"));
        Assert.assertEquals(source.getExtension("color"), e.getExtension("color"));

    }
    @Test
    public void testToExtendable() {
        Source source = new Source();
        source.setName("test");
        source.setAge(123);
        source.setColor(Color.RED);
        ExtendableEntity e = FastBeanCopier.copy(source, new ExtendableEntity());

        Assert.assertEquals(source.getName(), e.getName());
        Assert.assertEquals(source.getAge(), e.getExtension("age"));
        Assert.assertEquals(source.getColor(), e.getExtension("color"));

        Map<String, Object> map = FastBeanCopier.copy(e, new HashMap<>());
        System.out.println(map);

        ExtendableEntity t = FastBeanCopier.copy(map, new ExtendableEntity());
        Assert.assertEquals(e.getName(), t.getName());

        System.out.println(e.extensions());
        System.out.println(t.extensions());
        Assert.assertEquals(e.extensions(), t.extensions());

    }

    @Test
    public void testFromExtendable() {
        Source source = new Source();
        ExtendableEntity e = FastBeanCopier.copy(source, new ExtendableEntity());
        e.setName("test");
        e.setExtension("age",123);
        FastBeanCopier.copy(e, source);
        Assert.assertEquals(e.getName(), source.getName());
        Assert.assertEquals(e.getExtension("age"), source.getAge());


    }
    @Test
    public void testMapToExtendable() {
        Source source = new Source();
        source.setName("test");
        source.setAge(123);
        source.setColor(Color.RED);
        Map<String, Object> map = FastBeanCopier.copy(source, new HashMap<>());
        ExtendableEntity e = FastBeanCopier.copy(map, new ExtendableEntity());
        Assert.assertEquals(source.getName(), e.getName());
        Assert.assertEquals(source.getAge(), e.getExtension("age"));
        Assert.assertEquals(source.getColor(), e.getExtension("color"));
    }


    @Getter
    @Setter
    public static class ExtendableEntity extends DefaultExtendable {

        private String name;

        private boolean boy2;
    }

    @Test
    public void test() throws InvocationTargetException, IllegalAccessException {
        Source source = new Source();
        source.setAge(100);
        source.setName("测试");
        source.setIds(new String[]{"1", "2", "3"});
        source.setAge2(2);
        source.setBoy2(true);
        source.setColor(Color.RED);
        source.setNestObject2(Collections.singletonMap("name", "mapTest"));
        NestObject nestObject = new NestObject();
        nestObject.setAge(10);
        nestObject.setPassword("1234567");
        nestObject.setName("测试2");
        source.setNestObject(nestObject);
        source.setNestObject3(nestObject);

        Target target = new Target();
        FastBeanCopier.copy(source, target);


        System.out.println(source);
        System.out.println(target);
        System.out.println(target.getNestObject() == source.getNestObject());
    }

    @Test
    public void testMapArray() {
        Map<String, Object> data = new HashMap<>();
        data.put("colors", Arrays.asList("RED"));


        Target target = new Target();
        FastBeanCopier.copy(data, target);


        System.out.println(target);
        Assert.assertNotNull(target.getColors());
        Assert.assertSame(target.getColors()[0], Color.RED);

    }

    @Test
    public void testMapList() {
        Map<String, Object> data = new HashMap<>();
        data.put("templates", new HashMap() {
            {
                put("0", Collections.singletonMap("name", "test"));
                put("1", Collections.singletonMap("name", "test"));
            }
        });

        Config config = FastBeanCopier.copy(data, new Config());

        Assert.assertNotNull(config);
        Assert.assertNotNull(config.templates);
        System.out.println(config.templates);
        Assert.assertEquals(2, config.templates.size());


    }

    @Getter
    @Setter
    public static class Config {
        private List<Template> templates;
    }

    @Getter
    @Setter
    public static class Template {
        private String name;

        @Override
        public String toString() {
            return "name:" + name;
        }
    }

    @Test
    @SneakyThrows
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void testRecordCopy() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assume.assumeNotNull(compiler);

        File sourceDir = new File("target/generated-test-records/src/org/hswebframework/web/bean/recordcopy");
        File classesDir = new File("target/generated-test-records/classes");
        sourceDir.mkdirs();
        classesDir.mkdirs();

        File nestedFile = writeRecordSource(sourceDir,
                                            "NestedRecord.java",
                                            "package org.hswebframework.web.bean.recordcopy;\n" +
                                                "public record NestedRecord(String name) { }\n");
        File sourceFile = writeRecordSource(sourceDir,
                                            "SourceRecord.java",
                                            "package org.hswebframework.web.bean.recordcopy;\n" +
                                                "import org.hswebframework.web.bean.Color;\n" +
                                                "public record SourceRecord(String name, int age, Color color2, NestedRecord nested) { }\n");
        File targetFile = writeRecordSource(sourceDir,
                                            "TargetRecord.java",
                                            "package org.hswebframework.web.bean.recordcopy;\n" +
                                                "import java.util.List;\n" +
                                                "import org.hswebframework.web.bean.Color;\n" +
                                                "public record TargetRecord(String name, int age, Color color2, NestedRecord nested, List<NestedRecord> nestedList) { }\n");

        int exit = compiler.run(null,
                                null,
                                null,
                                "--release",
                                "17",
                                "-classpath",
                                System.getProperty("java.class.path"),
                                "-d",
                                classesDir.getAbsolutePath(),
                                nestedFile.getAbsolutePath(),
                                sourceFile.getAbsolutePath(),
                                targetFile.getAbsolutePath());
        Assume.assumeTrue("Current JDK does not support compiling record test classes", exit == 0);

        try (URLClassLoader loader = new URLClassLoader(new URL[]{classesDir.toURI().toURL()},
                                                        ClassUtils.getDefaultClassLoader())) {
            Class<?> nestedClass = loader.loadClass("org.hswebframework.web.bean.recordcopy.NestedRecord");
            Class<?> sourceClass = loader.loadClass("org.hswebframework.web.bean.recordcopy.SourceRecord");
            Class<?> targetClass = loader.loadClass("org.hswebframework.web.bean.recordcopy.TargetRecord");

            Map<String, Object> values = new LinkedHashMap<>();
            values.put("name", "record-target");
            values.put("age", "18");
            values.put("color2", "RED");
            values.put("nested", Collections.singletonMap("name", "nested-map"));
            values.put("nestedList", Collections.singletonList(Collections.singletonMap("name", "nested-list")));

            Object target = FastBeanCopier.copy(values, (Class) targetClass);
            Assert.assertEquals("record-target", invokeAccessor(target, "name"));
            Assert.assertEquals(18, invokeAccessor(target, "age"));
            Assert.assertEquals(Color.RED, invokeAccessor(target, "color2"));
            Assert.assertEquals("nested-map", invokeAccessor(invokeAccessor(target, "nested"), "name"));
            List<?> nestedList = (List<?>) invokeAccessor(target, "nestedList");
            Assert.assertEquals("nested-list", invokeAccessor(nestedList.get(0), "name"));

            Object ignored = FastBeanCopier.copy(values, (Class) targetClass, "age");
            Assert.assertEquals(0, invokeAccessor(ignored, "age"));

            Object nested = nestedClass.getDeclaredConstructor(String.class).newInstance("nested-source");
            Object source = sourceClass
                .getDeclaredConstructor(String.class, int.class, Color.class, nestedClass)
                .newInstance("record-source", 20, Color.BLUE, nested);

            Target beanTarget = FastBeanCopier.copy(source, new Target());
            Assert.assertEquals("record-source", beanTarget.getName());
            Assert.assertEquals(20, beanTarget.getAge());
            Assert.assertEquals(Color.BLUE, beanTarget.getColor2());

            Map<String, Object> copiedMap = FastBeanCopier.copy(source, new HashMap<>());
            Assert.assertEquals("record-source", copiedMap.get("name"));
            Assert.assertEquals(20, copiedMap.get("age"));

            Object emptyTarget = targetClass
                .getDeclaredConstructor(String.class, int.class, Color.class, nestedClass, List.class)
                .newInstance("old", 1, Color.BLUE, nested, Collections.emptyList());
            Object rebuilt = FastBeanCopier.copy(values, emptyTarget);
            Assert.assertNotSame(emptyTarget, rebuilt);
            Assert.assertEquals("record-target", invokeAccessor(rebuilt, "name"));
        }
    }

    private File writeRecordSource(File sourceDir, String fileName, String source) throws IOException {
        File file = new File(sourceDir, fileName);
        Files.write(file.toPath(), source.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private Object invokeAccessor(Object target, String name) throws Exception {
        return target.getClass().getMethod(name).invoke(target);
    }

    @Test
    public void testCopyMap() {


        Source source = new Source();
        source.setAge(100);
        source.setName("测试");
//        source.setIds(new String[]{"1", "2", "3"});
        NestObject nestObject = new NestObject();
        nestObject.setAge(10);
        nestObject.setName("测试2");
//        source.setNestObject(nestObject);


        Map<String, Object> target = new HashMap<>();


        System.out.println(FastBeanCopier.copy(source, target, FastBeanCopier.include("age")));

        System.out.println(target);
        System.out.println(FastBeanCopier.copy(target, new Target()));
    }

    @Test
    @SneakyThrows
    public void testCrossClassLoader() {
        URL clazz = new File("target/test-classes").getAbsoluteFile().toURI().toURL();

        System.out.println(clazz);
        URLClassLoader loader = new URLClassLoader(new URL[]{
            clazz
        }, ClassUtils.getDefaultClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                try {
                    Class<?> clazz = loadSelfClass(name);
                    if (null != clazz) {
                        if (resolve) {
                            resolveClass(clazz);
                        }
                        return clazz;
                    }
                } catch (Throwable ignore) {

                }
                return super.loadClass(name, resolve);
            }

            @SneakyThrows
            public synchronized Class<?> loadSelfClass(String name) {
                Class<?> clazz = super.findLoadedClass(name);
                if (clazz == null) {
                    clazz = super.findClass(name);
                    resolveClass(clazz);
                }
                return clazz;
            }

            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return findResources(name);
            }

            @Override
            public URL getResource(String name) {
                return findResource(name);
            }
        };
        Class<?> sourceClass = loader.loadClass(Source.class.getName());
        Assert.assertNotSame(sourceClass, Source.class);

        Object source = sourceClass.newInstance();
        FastBeanCopier.copy(Collections.singletonMap("name", "测试"), source);

        Map<String, Object> map = FastBeanCopier.copy(source, new HashMap<>());
        System.out.println(map);

        loader.close();
        map = FastBeanCopier.copy(source, new HashMap<>());

        System.out.println(map);

    }


    @Test
    public void testProxy() {
        AtomicReference<Object> reference = new AtomicReference<>();

        ProxyTest test = (ProxyTest) Proxy.newProxyInstance(ClassUtils.getDefaultClassLoader(),
                                                            new Class[]{ProxyTest.class}, (proxy, method, args) -> {
                if (method.getName().equals("getName")) {
                    return "test";
                }

                if (method.getName().equals("setName")) {
                    reference.set(args[0]);
                    return null;
                }

                return null;
            });

        Target source = new Target();

        FastBeanCopier.copy(test, source);
        Assert.assertEquals(source.getName(), test.getName());


        source.setName("test2");
        FastBeanCopier.copy(source, test);

        Assert.assertEquals(reference.get(), source.getName());
    }

    @Test
    public void testGetProperty() {

        Assert.assertEquals(1, FastBeanCopier.getProperty(ImmutableMap.of("a", 1, "b", 2), "a"));

    }


    public interface ProxyTest {
        String getName();

        void setName(String name);
    }

}