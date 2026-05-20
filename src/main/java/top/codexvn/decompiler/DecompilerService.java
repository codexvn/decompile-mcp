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
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DecompilerService {

    private static final Logger log = LoggerFactory.getLogger(DecompilerService.class);

    private final ConcurrentMap<Path, Map<String, String>> cache = new ConcurrentHashMap<>();

    public Map<String, String> decompileAll(Path jarPath) throws DecompilationException {
        Map<String, String> cached = cache.get(jarPath);
        if (cached != null) {
            log.debug("Cache hit for {}", jarPath);
            return cached;
        }

        log.info("Decompiling JAR: {}", jarPath);
        Map<String, String> results = Collections.synchronizedMap(new LinkedHashMap<>());

        OutputSinkFactory sinkFactory = createSinkFactory(results);

        try {
            CfrDriver driver = new CfrDriver.Builder()
                .withOutputSink(sinkFactory)
                .build();

            driver.analyse(List.of(jarPath.toString()));

            Map<String, String> unmodifiable = Collections.unmodifiableMap(results);
            cache.put(jarPath, unmodifiable);
            log.info("Decompiled {} classes from {}", results.size(), jarPath.getFileName());
            return unmodifiable;
        } catch (Exception e) {
            throw new DecompilationException(
                "CFR decompilation failed for " + jarPath + ": " + e.getMessage(), e);
        }
    }

    public String decompileClass(Path jarPath, String className) throws DecompilationException {
        // Check cache first
        Map<String, String> cached = cache.get(jarPath);
        if (cached != null) {
            return cached.get(className);
        }

        // Fast path: extract single class and decompile just that file
        Path tempFile = null;
        try {
            tempFile = extractClassFile(jarPath, className);
            if (tempFile == null) {
                return null;
            }

            Map<String, String> results = Collections.synchronizedMap(new LinkedHashMap<>());
            OutputSinkFactory sinkFactory = createSinkFactory(results);

            CfrDriver driver = new CfrDriver.Builder()
                .withOutputSink(sinkFactory)
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

    /**
     * Extracts a single .class file from a JAR to a temporary file.
     */
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
