package top.codexvn.decompiler;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DecompilerService {

    private static final Logger log = LoggerFactory.getLogger(DecompilerService.class);

    // 缓存无大小限制和淘汰策略。设计假设：MCP 服务器作为短生命周期 sidecar
    // 进程运行，缓存与 JVM 同生命周期（进程退出即释放）。对于 coding agent
    // 的典型使用模式（一次会话中探索若干依赖），内存占用可控。
    // 若需长期运行，可改为 LRU 缓存（如 Caffeine）。
    private final ConcurrentMap<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    // --- 向后兼容重载（无命名空间参数） ---

    public Map<String, String> decompileAll(Path jarPath) throws DecompilationException {
        return decompileAll(jarPath, "");
    }

    public String decompileClass(Path jarPath, String className) throws DecompilationException {
        return decompileClass(jarPath, className, "");
    }

    // --- 带命名空间的方法 ---

    public Map<String, String> decompileAll(Path jarPath, String cacheNamespace)
        throws DecompilationException {
        String key = cacheKey(jarPath, cacheNamespace);
        Map<String, String> cached = cache.get(key);
        if (cached != null) {
            log.debug("Cache hit for {}", key);
            return cached;
        }

        // 注意：cache.get 和 cache.put 之间无锁，并发请求同一 JAR 时可能
        // 触发两次 CFR 反编译。第二次结果覆盖第一次，无正确性问题，仅浪费
        // CPU。鉴于 MCP 工具通常串行调用，实际影响极小。

        log.info("Decompiling JAR: {}", jarPath);
        Map<String, String> results = Collections.synchronizedMap(new LinkedHashMap<>());

        try {
            CfrDriver driver = new CfrDriver.Builder()
                .withOutputSink(createSinkFactory(results))
                .build();

            driver.analyse(List.of(jarPath.toString()));

            Map<String, String> unmodifiable = Collections.unmodifiableMap(results);
            cache.put(key, unmodifiable);
            log.info("Decompiled {} classes from {}", results.size(), jarPath.getFileName());
            return unmodifiable;
        } catch (Exception e) {
            throw new DecompilationException(
                "CFR decompilation failed for " + jarPath + ": " + e.getMessage(), e);
        }
    }

    public String decompileClass(Path jarPath, String className, String cacheNamespace)
        throws DecompilationException {
        String key = cacheKey(jarPath, cacheNamespace);
        Map<String, String> cached = cache.get(key);
        if (cached != null) {
            return cached.get(className);
        }

        // 单类提取：将 .class 条目复制到临时文件，仅反编译该文件。
        // 避免全量反编译（如 Guava 约需 10 秒），单类提取 <1 秒。
        // jar_grep 需要遍历所有类，走 decompileAll 路径。
        Path tempFile = null;
        try {
            tempFile = extractClassFile(jarPath, className);
            if (tempFile == null) {
                return null;
            }

            Map<String, String> results = Collections.synchronizedMap(new LinkedHashMap<>());

            CfrDriver driver = new CfrDriver.Builder()
                .withOutputSink(createSinkFactory(results))
                .build();

            driver.analyse(List.of(tempFile.toString()));

            log.debug("Decompiled single class: {}", className);
            return results.get(className);
        } catch (Exception e) {
            throw new DecompilationException(
                "CFR decompilation failed for " + className + " in " + jarPath + ": "
                    + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    // --- 源码 JAR 直接读取（无需反编译） ---

    public String readSource(Path sourcesJar, String className) {
        String entryName = className.replace('.', '/') + ".java";
        try (FileSystem fs = FileSystems.newFileSystem(sourcesJar, (ClassLoader) null)) {
            Path sourceEntry = fs.getPath("/", entryName);
            if (!Files.exists(sourceEntry)) {
                return null;
            }
            return Files.readString(sourceEntry);
        } catch (IOException e) {
            log.warn("Failed to read source from {}: {}", sourcesJar.getFileName(), e.getMessage());
            return null;
        }
    }

    // 从源码 JAR 读取所有 .java 文件。不做缓存——源码读取极快（毫秒级），
    // 远快于 CFR 反编译（秒级），缓存收益微小，反而增加内存占用。
    public Map<String, String> readAllSources(Path sourcesJar) throws IOException {
        Map<String, String> results = new LinkedHashMap<>();
        try (FileSystem fs = FileSystems.newFileSystem(sourcesJar, (ClassLoader) null)) {
            Path root = fs.getPath("/");
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        String entryPath = p.toString();
                        // "/com/example/Foo.java" → "com.example.Foo"
                    String fqcn = entryPath.substring(1, entryPath.length() - 5)
                        .replace('/', '.');
                        try {
                            results.put(fqcn, Files.readString(p));
                        } catch (IOException ignored) {
                        }
                    });
            }
        }
        log.info("Read {} source files from {}", results.size(), sourcesJar.getFileName());
        return results;
    }

    // --- 内部工具方法 ---

    private static String cacheKey(Path jarPath, String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return jarPath.toString();
        }
        return jarPath.toString() + "|" + namespace;
    }

    private Path extractClassFile(Path jarPath, String className) throws IOException {
        String entryName = className.replace('.', '/') + ".class";

        try (FileSystem fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null)) {
            Path classEntry = fs.getPath("/", entryName);
            if (!Files.exists(classEntry)) {
                return null;
            }

            Path tempFile = Files.createTempFile("cfr-", ".class");
            Files.copy(classEntry, tempFile, StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        }
    }

    private static OutputSinkFactory createSinkFactory(Map<String, String> results) {
        return new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType,
                                                      java.util.Collection<SinkClass> available) {
                if (sinkType == SinkType.JAVA && available.contains(SinkClass.DECOMPILED)) {
                    return List.of(SinkClass.DECOMPILED, SinkClass.STRING);
                }
                return List.of(SinkClass.STRING);
            }

            @Override
            public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                if (sinkType == SinkType.JAVA && sinkClass == SinkClass.DECOMPILED) {
                    return sinkable -> {
                        SinkReturns.Decompiled d = (SinkReturns.Decompiled) sinkable;
                        String fqcn = buildFqcn(d.getPackageName(), d.getClassName());
                        results.put(fqcn, d.getJava());
                    };
                }
                return sinkable -> {};
            }
        };
    }

    private static String buildFqcn(String pkg, String clsName) {
        if (pkg == null || pkg.isBlank()) {
            return clsName;
        }
        return pkg + "." + clsName;
    }

    public static class DecompilationException extends Exception {
        public DecompilationException(String message) {
            super(message);
        }

        public DecompilationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
