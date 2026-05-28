package org.hswebframework.web.bean;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FastBeanCopierJmhRunner {

    public static void main(String[] args) throws RunnerException, IOException {
        Path moduleDir = Files.exists(Paths.get("hsweb-core", "pom.xml"))
            ? Paths.get("hsweb-core")
            : Paths.get(".");
        Path resultDir = moduleDir.resolve("target").resolve("jmh-results");
        Files.createDirectories(resultDir);
        Path resultFile = resultDir.resolve("fast-bean-copier.json");

        Options options = new OptionsBuilder()
            .include(FastBeanCopierJmhBenchmark.class.getSimpleName())
            .forks(0)
            .result(resultFile.toString())
            .resultFormat(ResultFormatType.JSON)
            .build();

        new Runner(options).run();
    }
}
