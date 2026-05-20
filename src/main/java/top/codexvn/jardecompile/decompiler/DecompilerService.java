package top.codexvn.jardecompile.decompiler;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DecompilerService {

    private static final Logger log = LoggerFactory.getLogger(DecompilerService.class);

    // 磁盘缓存根目录：~/.m2/repository/.decompiled-cache/
    private static final Path DISK_CACHE_ROOT = Path.of(
        System.getProperty("user.home"), ".m2", "repository", ".decompiled-cache");

    // 内存缓存：最多 50 个 JAR，30 分钟无访问自动淘汰
    private final Cache<String, Map<String, String>> memoryCache = Caffeine.newBuilder()
        .maximumSize(50)
        .expireAfterAccess(Duration.ofMinutes(30))
        .build();

    // --- 向后兼容重载 ---

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

        // 1. 内存缓存命中 → 直接返回
        Map<String, String> memHit = memoryCache.getIfPresent(key);
        if (memHit != null) {
            return memHit;
        }

        // 2. 磁盘缓存命中 → 加载到内存，返回
        Map<String, String> diskResult = loadDiskCache(jarPath, cacheNamespace);
        if (diskResult != null) {
            memoryCache.put(key, diskResult);
            return diskResult;
        }

        // 3. 缓存未命中 → 执行反编译，同时写磁盘
        try {
            return memoryCache.get(key, k -> {
                try {
                    Map<String, String> results = doDecompileAll(jarPath);
                    saveDiskCache(jarPath, cacheNamespace, results);
                    return results;
                } catch (DecompilationException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof DecompilationException de) {
                throw de;
            }
            throw e;
        }
    }

    public String decompileClass(Path jarPath, String className, String cacheNamespace)
        throws DecompilationException {
        String key = cacheKey(jarPath, cacheNamespace);

        // 先查内存和磁盘缓存
        Map<String, String> cached = memoryCache.getIfPresent(key);
        if (cached != null) {
            return cached.get(className);
        }

        Map<String, String> diskResult = loadDiskCache(jarPath, cacheNamespace);
        if (diskResult != null) {
            memoryCache.put(key, diskResult);
            return diskResult.get(className);
        }

        // 单类提取快速路径：仅反编译目标类，不写磁盘缓存（只有 decompileAll 写）
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

            String source = results.get(className);
            if (source != null) {
                // 将单类结果放入内存缓存，避免后续重复反编译
                Map<String, String> partial = new LinkedHashMap<>();
                partial.put(className, source);
                memoryCache.put(key, Collections.unmodifiableMap(partial));
            }
            log.debug("Decompiled single class: {}", className);
            return source;
        } catch (Exception e) {
            throw new DecompilationException(
                "CFR decompilation failed for " + className + " in " + jarPath + ": "
                    + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    // --- 反编译引擎 ---

    private Map<String, String> doDecompileAll(Path jarPath) throws DecompilationException {
        log.info("Decompiling JAR: {}", jarPath);
        Map<String, String> results = Collections.synchronizedMap(new LinkedHashMap<>());

        try {
            CfrDriver driver = new CfrDriver.Builder()
                .withOutputSink(createSinkFactory(results))
                .build();
            driver.analyse(List.of(jarPath.toString()));
        } catch (Exception e) {
            throw new DecompilationException(
                "CFR decompilation failed for " + jarPath + ": " + e.getMessage(), e);
        }

        log.info("Decompiled {} classes from {}", results.size(), jarPath.getFileName());
        return Collections.unmodifiableMap(results);
    }

    // --- 磁盘缓存 ---

    /**
     * 磁盘缓存布局：
     * ~/.m2/repository/.decompiled-cache/{sha256前16位}/
     *   lastModified    — JAR 的修改时间戳，用于校验有效性
     *   {fqcn}.java     — 反编译后的源码文件
     */
    private Map<String, String> loadDiskCache(Path jarPath, String namespace) {
        Path cacheDir = diskCacheDir(jarPath, namespace);
        Path metaFile = cacheDir.resolve("lastModified");

        try {
            if (!Files.exists(metaFile)) {
                return null;
            }

            // 校验：JAR 修改时间与缓存时的修改时间一致
            long jarMod = Files.getLastModifiedTime(jarPath).toMillis();
            long cachedMod = Long.parseLong(Files.readString(metaFile).trim());
            if (jarMod != cachedMod) {
                log.debug("Disk cache stale for {}", jarPath);
                deleteRecursive(cacheDir);
                return null;
            }

            // 从磁盘加载所有 .java 文件
            Map<String, String> results = new LinkedHashMap<>();
            try (Stream<Path> files = Files.list(cacheDir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        String fqcn = p.getFileName().toString();
                        fqcn = fqcn.substring(0, fqcn.length() - 5); // 去 .java 后缀
                        try {
                            results.put(fqcn, Files.readString(p));
                        } catch (IOException ignored) {}
                    });
            }

            log.info("Loaded {} classes from disk cache: {}", results.size(), cacheDir);
            return Collections.unmodifiableMap(results);
        } catch (Exception e) {
            log.warn("Failed to load disk cache: {}", e.getMessage());
            return null;
        }
    }

    private void saveDiskCache(Path jarPath, String namespace, Map<String, String> results) {
        Path cacheDir = diskCacheDir(jarPath, namespace);
        try {
            Files.createDirectories(cacheDir);

            long jarMod = Files.getLastModifiedTime(jarPath).toMillis();
            Files.writeString(cacheDir.resolve("lastModified"), String.valueOf(jarMod));

            for (Map.Entry<String, String> entry : results.entrySet()) {
                Path file = cacheDir.resolve(entry.getKey() + ".java");
                Files.writeString(file, entry.getValue());
            }

            log.info("Saved {} classes to disk cache: {}", results.size(), cacheDir);
        } catch (Exception e) {
            log.warn("Failed to save disk cache: {}", e.getMessage());
        }
    }

    private Path diskCacheDir(Path jarPath, String namespace) {
        String raw = jarPath.toAbsolutePath().toString() + "|" + namespace;
        // 使用 SHA-256 前 16 位十六进制作为目录名，避免 32 位 hashCode 碰撞
        String hash = sha256Prefix(raw, 16);
        return DISK_CACHE_ROOT.resolve(hash);
    }

    private static String sha256Prefix(String input, int hexChars) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, hexChars);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JVM 必须支持的算法，不会发生
            throw new RuntimeException(e);
        }
    }

    private static void deleteRecursive(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException ignored) {}
    }

    // --- 源码 JAR 直接读取 ---

    public String readSource(Path sourcesJar, String className) {
        String entryName = className.replace('.', '/') + ".java";
        try (FileSystem fs = FileSystems.newFileSystem(sourcesJar, (ClassLoader) null)) {
            Path sourceEntry = fs.getPath("/", entryName);
            if (!Files.exists(sourceEntry)) return null;
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
                        String fqcn = entryPath.substring(1, entryPath.length() - 5)
                            .replace('/', '.');
                        try { results.put(fqcn, Files.readString(p)); } catch (IOException ignored) {}
                    });
            }
        }
        log.info("Read {} source files from {}", results.size(), sourcesJar.getFileName());
        return results;
    }

    // --- 内部工具 ---

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
            if (!Files.exists(classEntry)) return null;
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
        if (pkg == null || pkg.isBlank()) return clsName;
        return pkg + "." + clsName;
    }

    public static class DecompilationException extends Exception {
        public DecompilationException(String message) { super(message); }
        public DecompilationException(String message, Throwable cause) { super(message, cause); }
    }
}
