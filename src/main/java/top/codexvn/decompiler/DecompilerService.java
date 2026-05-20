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

    public Map<String, String> readAllSources(Path sourcesJar) throws IOException {
        Map<String, String> results = new LinkedHashMap<>();
        try (FileSystem fs = FileSystems.newFileSystem(sourcesJar, (ClassLoader) null)) {
            Path root = fs.getPath("/");
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        String entryPath = p.toString();
                        String fqcn = entryPath.substring(1)
                            .replace('/', '.')
                            .replace(".java", "");
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
