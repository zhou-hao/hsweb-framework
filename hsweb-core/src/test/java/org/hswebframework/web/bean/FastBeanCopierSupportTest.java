package org.hswebframework.web.bean;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.util.ClassUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FastBeanCopierSupportTest {

    @Test
    public void testSupportCopy() {
        Source source = new Source();
        source.setName("support");
        source.setAge(18);
        source.setColor(Color.RED);

        Target target = FastBeanCopierSupport.copy(source, new Target());
        Target facadeTarget = FastBeanCopier.copy(source, new Target());

        Assert.assertEquals(facadeTarget.getName(), target.getName());
        Assert.assertEquals(facadeTarget.getAge(), target.getAge());
        Assert.assertEquals(facadeTarget.getColor(), target.getColor());
        Assert.assertEquals(facadeTarget.getColor2(), target.getColor2());
    }

    @Test
    public void testSupportGetProperty() {
        Source source = new Source();
        source.setAge(20);

        Assert.assertEquals(20, FastBeanCopierSupport.getProperty(source, "age"));
    }

    @Test
    public void testFacadeDelegatesToSupport() {
        Source source = new Source();
        Target target = new Target();

        Assert.assertSame(FastBeanCopierSupport.DEFAULT_CONVERT, FastBeanCopier.DEFAULT_CONVERT);
        Assert.assertSame(FastBeanCopierSupport.getBackend(), FastBeanCopier.getBackend());
        Assert.assertTrue(FastBeanCopierSupport.getBackend() instanceof JavassistFastBeanCopierBackend);
        Assert.assertSame(
            FastBeanCopierSupport.getCopier(source, target, true),
            FastBeanCopier.getCopier(source, target, true)
        );
    }

    @Test
    public void testAdditionalBackendCompatibility() {
        FastBeanCopierBackend original = FastBeanCopierSupport.getBackend();
        try {
            for (BackendCase backendCase : additionalBackends()) {
                FastBeanCopierSupport.setBackend(backendCase.backend);
                assertBackendCompatibility(backendCase.name);
            }
        } finally {
            FastBeanCopierSupport.setBackend(original);
        }
    }

    @Test
    public void testJavassistBackendCompatibility() {
        FastBeanCopierBackend original = FastBeanCopierSupport.getBackend();
        try {
            FastBeanCopierSupport.setBackend(new JavassistFastBeanCopierBackend());
            assertBackendCompatibility("javassist");
        } finally {
            FastBeanCopierSupport.setBackend(original);
        }
    }

    @Test
    public void testAccessorDirectTransferPreservesNullSkipSemantics() {
        FastBeanCopierBackend original = FastBeanCopierSupport.getBackend();
        try {
            List<BackendCase> backends = Arrays.asList(
                new BackendCase("reflection-accessor", new ReflectionAccessorFastBeanCopierBackend()),
                new BackendCase("asm-accessor", new AsmAccessorFastBeanCopierBackend())
            );
            for (BackendCase backendCase : backends) {
                FastBeanCopierSupport.setBackend(backendCase.backend);
                Source source = new Source();
                source.setName(null);
                source.setAge(33);

                Target target = new Target();
                target.setName("keep-me");
                target.setAge(1);

                FastBeanCopierSupport.copy(source, target);

                Assert.assertEquals(backendCase.name, "keep-me", target.getName());
                Assert.assertEquals(backendCase.name, 33, target.getAge());
            }
        } finally {
            FastBeanCopierSupport.setBackend(original);
        }
    }

    @Test
    public void testCrossClassLoaderCompatibility() {
        FastBeanCopierBackend original = FastBeanCopierSupport.getBackend();
        try {
            for (BackendCase backendCase : benchmarkBackends()) {
                FastBeanCopierSupport.setBackend(backendCase.backend);
                assertCrossClassLoaderCompatibility(backendCase.name);
            }
        } finally {
            FastBeanCopierSupport.setBackend(original);
        }
    }

    @Test
    public void testBackendPerformanceComparison() {
        Source source = createBenchmarkSource();
        int warmup = 5_000;
        int iterations = 50_000;

        FastBeanCopierBackend original = FastBeanCopierSupport.getBackend();
        try {
            List<BackendCase> backends = benchmarkBackends();
            Map<String, BenchmarkResult> round1 = benchmarkBackends(backends, source, warmup, iterations);
            List<BackendCase> reversed = Arrays.asList(
                backends.get(3),
                backends.get(2),
                backends.get(1),
                backends.get(0)
            );
            Map<String, BenchmarkResult> round2 = benchmarkBackends(reversed, source, warmup, iterations);
            Map<String, BenchmarkResult> average = average(round1, round2);
            BenchmarkResult javassist = average.get("javassist");

            System.out.println("\n=== FastBeanCopier backend 性能对比 ===");
            System.out.println("预热次数: " + warmup + ", 正式迭代: " + iterations);
            System.out.println("-- round1");
            round1.values().forEach(this::printBenchmark);
            System.out.println("-- round2");
            round2.values().forEach(this::printBenchmark);
            System.out.println("-- average");
            average.values().forEach(this::printBenchmark);
            for (BenchmarkResult result : average.values()) {
                if ("javassist".equals(result.name)) {
                    continue;
                }
                System.out.println("cached ratio  (" + result.name + "/javassist): " + formatRatio(result.cachedWarmNanos, javassist.cachedWarmNanos));
                System.out.println("support ratio (" + result.name + "/javassist): " + formatRatio(result.supportWarmNanos, javassist.supportWarmNanos));
                System.out.println("cold create ratio (" + result.name + "/javassist): " + formatRatio(result.backendColdNanos, javassist.backendColdNanos));
                System.out.println("cold first copy ratio (" + result.name + "/javassist): " + formatRatio(result.supportColdNanos, javassist.supportColdNanos));
            }

            for (BenchmarkResult result : average.values()) {
                Assert.assertTrue(result.cachedWarmNanos > 0);
                Assert.assertTrue(result.supportWarmNanos > 0);
            }
        } finally {
            FastBeanCopierSupport.setBackend(original);
        }
    }

    private long runCachedCopierBenchmark(Source source, Copier copier, int iterations) {
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            copier.copy(source, new Target(), Collections.emptySet(), FastBeanCopierSupport.DEFAULT_CONVERT);
        }
        return System.nanoTime() - start;
    }

    private long runSupportBenchmark(Source source, int iterations) {
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            FastBeanCopierSupport.copy(source, new Target());
        }
        return System.nanoTime() - start;
    }

    private long runBackendColdBenchmark(Source source, FastBeanCopierBackend backend) {
        long start = System.nanoTime();
        Copier copier = backend.createCopier(Source.class, Target.class);
        copier.copy(source, new Target(), Collections.emptySet(), FastBeanCopierSupport.DEFAULT_CONVERT);
        return System.nanoTime() - start;
    }

    private long runSupportColdBenchmark(Source source) {
        FastBeanCopierSupport.clearCache();
        long start = System.nanoTime();
        FastBeanCopierSupport.copy(source, new Target());
        return System.nanoTime() - start;
    }

    private BenchmarkResult benchmarkBackend(String name,
                                             FastBeanCopierBackend backend,
                                             Source source,
                                             int warmup,
                                             int iterations) {
        FastBeanCopierSupport.setBackend(backend);
        FastBeanCopierSupport.clearCache();
        Copier cachedCopier = FastBeanCopierSupport.getCopier(source, new Target(), true);
        for (int i = 0; i < warmup; i++) {
            cachedCopier.copy(source, new Target(), Collections.emptySet(), FastBeanCopierSupport.DEFAULT_CONVERT);
            FastBeanCopierSupport.copy(source, new Target());
        }
        return new BenchmarkResult(name,
                                   runCachedCopierBenchmark(source, cachedCopier, iterations),
                                   runSupportBenchmark(source, iterations),
                                   runBackendColdBenchmark(source, backend),
                                   runSupportColdBenchmark(source));
    }

    private Map<String, BenchmarkResult> benchmarkBackends(List<BackendCase> backends,
                                                           Source source,
                                                           int warmup,
                                                           int iterations) {
        Map<String, BenchmarkResult> results = new LinkedHashMap<>();
        for (BackendCase backend : backends) {
            results.put(backend.name, benchmarkBackend(backend.name, backend.backend, source, warmup, iterations));
        }
        return results;
    }

    private Map<String, BenchmarkResult> average(Map<String, BenchmarkResult> first,
                                                 Map<String, BenchmarkResult> second) {
        Map<String, BenchmarkResult> average = new LinkedHashMap<>();
        for (Map.Entry<String, BenchmarkResult> entry : first.entrySet()) {
            average.put(entry.getKey(), BenchmarkResult.average(entry.getKey(), entry.getValue(), second.get(entry.getKey())));
        }
        return average;
    }

    private void printBenchmark(BenchmarkResult result) {
        System.out.println(String.format(Locale.ROOT,
                                         "%-18s cached=%8.2fms support=%8.2fms coldCreate=%8.3fms coldFirstCopy=%8.3fms",
                                         result.name,
                                         result.cachedWarmNanos / 1_000_000.0,
                                         result.supportWarmNanos / 1_000_000.0,
                                         result.backendColdNanos / 1_000_000.0,
                                         result.supportColdNanos / 1_000_000.0));
    }

    private String formatRatio(long value, long base) {
        return String.format(Locale.ROOT, "%.2fx", (double) value / (double) base);
    }

    private void assertBackendCompatibility(String backendName) {
        Source source = createBenchmarkSource();
        source.setIds(new String[]{"1", "2"});
        source.setArr2(new String[]{"1", "2"});
        source.setArr7(new int[]{1, 2});
        source.setColors(new Color[]{Color.BLUE, Color.RED});

        Target target = FastBeanCopier.copy(source, new Target());
        assertTargetCopied(source, target);
        Assert.assertArrayEquals(backendName, source.getIds(), target.getIds());
        Assert.assertNotSame(backendName, source.getIds(), target.getIds());
        Assert.assertArrayEquals(backendName, source.getColors(), target.getColors());
        Assert.assertNotSame(backendName, source.getColors(), target.getColors());

        Map<String, Object> map = FastBeanCopier.copy(source, new HashMap<>());
        Assert.assertEquals(backendName, source.getName(), map.get("name"));
        Assert.assertEquals(backendName, source.getAge(), map.get("age"));

        FastBeanCopierTest.ExtendableEntity extendable = FastBeanCopier.copy(source, new FastBeanCopierTest.ExtendableEntity());
        Assert.assertEquals(backendName, source.getName(), extendable.getName());
        Assert.assertEquals(backendName, source.getAge(), extendable.getExtension("age"));
        Assert.assertEquals(backendName, source.getColor(), extendable.getExtension("color"));
    }

    private void assertCrossClassLoaderCompatibility(String backendName) {
        FastBeanCopierSupport.clearCache();
        try (URLClassLoader loader = createTestClassLoader()) {
            Class<?> sourceClass = loader.loadClass(Source.class.getName());
            Class<?> targetClass = loader.loadClass(Target.class.getName());

            Assert.assertNotSame(backendName, sourceClass, Source.class);
            Assert.assertNotSame(backendName, targetClass, Target.class);

            Object source = sourceClass.getDeclaredConstructor().newInstance();
            FastBeanCopier.copy(createCrossClassLoaderSourceMap(), source);

            Map<String, Object> copiedFromSource = FastBeanCopier.copy(source, new HashMap<>());
            Assert.assertEquals(backendName, "cross-loader", copiedFromSource.get("name"));
            Assert.assertEquals(backendName, 24, copiedFromSource.get("age"));
            Assert.assertEquals(backendName, "RED", String.valueOf(copiedFromSource.get("color")));

            Object target = targetClass.getDeclaredConstructor().newInstance();
            FastBeanCopier.copy(source, target);

            Map<String, Object> copiedFromTarget = FastBeanCopier.copy(target, new HashMap<>());
            Assert.assertEquals(backendName, "cross-loader", copiedFromTarget.get("name"));
            Assert.assertEquals(backendName, 24, copiedFromTarget.get("age"));
            Assert.assertEquals(backendName, "RED", String.valueOf(copiedFromTarget.get("color2")));
            Assert.assertEquals(backendName, "BLUE", String.valueOf(copiedFromTarget.get("color3")));

            loader.close();
            Map<String, Object> copiedAfterClose = FastBeanCopier.copy(source, new HashMap<>());
            Assert.assertEquals(backendName, "cross-loader", copiedAfterClose.get("name"));
            Assert.assertEquals(backendName, 24, copiedAfterClose.get("age"));
        } catch (Exception e) {
            throw new AssertionError("Cross classloader compatibility failed for backend: " + backendName, e);
        } finally {
            FastBeanCopierSupport.clearCache();
        }
    }

    private Map<String, Object> createCrossClassLoaderSourceMap() {
        Map<String, Object> values = new HashMap<>();
        values.put("name", "cross-loader");
        values.put("age", 24);
        values.put("age2", 7);
        values.put("color", "RED");
        values.put("color2", "红色");
        values.put("color3", Color.BLUE.getValue());
        values.put("arr7", new int[]{1, 2});
        values.put("arr8", new int[]{1, 2});
        return values;
    }

    private URLClassLoader createTestClassLoader() throws IOException {
        URL classes = new File("target/test-classes").getAbsoluteFile().toURI().toURL();
        return new URLClassLoader(new URL[]{classes}, ClassUtils.getDefaultClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                try {
                    Class<?> type = loadSelfClass(name);
                    if (type != null) {
                        if (resolve) {
                            resolveClass(type);
                        }
                        return type;
                    }
                } catch (Throwable ignore) {
                    // ignore
                }
                return super.loadClass(name, resolve);
            }

            synchronized Class<?> loadSelfClass(String name) throws ClassNotFoundException {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    type = findClass(name);
                    resolveClass(type);
                }
                return type;
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
    }

    private List<BackendCase> additionalBackends() {
        return Arrays.asList(
            new BackendCase("reflect", new ReflectFastBeanCopierBackend()),
            new BackendCase("reflection-accessor", new ReflectionAccessorFastBeanCopierBackend()),
            new BackendCase("asm-accessor", new AsmAccessorFastBeanCopierBackend())
        );
    }

    private List<BackendCase> benchmarkBackends() {
        return Arrays.asList(
            new BackendCase("javassist", new JavassistFastBeanCopierBackend()),
            new BackendCase("reflect", new ReflectFastBeanCopierBackend()),
            new BackendCase("reflection-accessor", new ReflectionAccessorFastBeanCopierBackend()),
            new BackendCase("asm-accessor", new AsmAccessorFastBeanCopierBackend())
        );
    }

    private void assertTargetCopied(Source source, Target target) {
        Assert.assertEquals(source.getName(), target.getName());
        Assert.assertEquals(source.getAge(), target.getAge());
        Assert.assertEquals(source.getAge2().intValue(), target.getAge2());
        Assert.assertEquals(source.getColor().getValue().intValue(), target.getColor());
        Assert.assertEquals(source.getColor(), target.getColor2());
        Assert.assertEquals(Color.BLUE, target.getColor3());
        Assert.assertArrayEquals(new long[]{1L, 2L}, target.getArr7());
        Assert.assertEquals(Arrays.asList(1, 2), target.getArr8());
    }

    private Source createBenchmarkSource() {
        Source source = new Source();
        source.setName("benchmark");
        source.setAge(18);
        source.setAge2(20);
        source.setColor(Color.RED);
        source.setColor2("红色");
        source.setColor3(Color.BLUE.getValue());
        source.setArr7(new int[]{1, 2});
        source.setArr8(new int[]{1, 2});
        return source;
    }

    static class BenchmarkResult {
        private final String name;
        private final long cachedWarmNanos;
        private final long supportWarmNanos;
        private final long backendColdNanos;
        private final long supportColdNanos;

        BenchmarkResult(String name,
                        long cachedWarmNanos,
                        long supportWarmNanos,
                        long backendColdNanos,
                        long supportColdNanos) {
            this.name = name;
            this.cachedWarmNanos = cachedWarmNanos;
            this.supportWarmNanos = supportWarmNanos;
            this.backendColdNanos = backendColdNanos;
            this.supportColdNanos = supportColdNanos;
        }

        static BenchmarkResult average(String name, BenchmarkResult first, BenchmarkResult second) {
            return new BenchmarkResult(name,
                                       (first.cachedWarmNanos + second.cachedWarmNanos) / 2,
                                       (first.supportWarmNanos + second.supportWarmNanos) / 2,
                                       (first.backendColdNanos + second.backendColdNanos) / 2,
                                       (first.supportColdNanos + second.supportColdNanos) / 2);
        }
    }

    static class BackendCase {
        private final String name;
        private final FastBeanCopierBackend backend;

        BackendCase(String name, FastBeanCopierBackend backend) {
            this.name = name;
            this.backend = backend;
        }
    }
}
