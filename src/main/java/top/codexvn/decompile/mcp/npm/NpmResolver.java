package top.codexvn.decompile.mcp.npm;

import top.codexvn.decompile.mcp.server.CacheConfig;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NpmResolver {

    private static final Logger log = LoggerFactory.getLogger(NpmResolver.class);
    private static final String DEFAULT_REGISTRY = "https://registry.npmjs.org";
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Path CACHE_DIR = CacheConfig.npmCache();

    private final List<String> registries;

    public NpmResolver() {
        try { Files.createDirectories(CACHE_DIR); } catch (IOException ignored) {}
        this.registries = loadRegistries();
        log.info("NpmResolver initialized, cache: {}, mirrors: {}", CACHE_DIR, registries);
    }

    public Path resolve(String packageName, String version) throws NpmException {
        Path cached = CACHE_DIR.resolve(packageName + "-" + version);
        if (Files.isDirectory(cached) && isNonEmpty(cached)) {
            log.debug("Using cached npm package: {}", cached);
            return cached;
        }

        // 按优先级尝试各镜像
        List<String> errors = new ArrayList<>();
        for (String registry : registries) {
            try {
                String tarballUrl = getTarballUrl(registry, packageName, version);
                log.info("Downloading npm package: {}@{} -> {}", packageName, version, tarballUrl);

                Path tgzFile = Files.createTempFile("npm-", ".tgz");
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(tarballUrl)).build();
                    HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() != 200) {
                        throw new NpmException("tarball download returned " + resp.statusCode());
                    }
                    try (InputStream in = resp.body()) {
                        Files.copy(in, tgzFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }

                    Path extracted = CACHE_DIR.resolve(packageName + "-" + version);
                    extractTgz(tgzFile, extracted);
                    Files.deleteIfExists(tgzFile);

                    log.info("Extracted npm package to: {}", extracted);
                    return extracted;
                } catch (NpmException e) {
                    Files.deleteIfExists(tgzFile);
                    throw e;
                } catch (Exception e) {
                    Files.deleteIfExists(tgzFile);
                    throw new NpmException(e.getMessage(), e);
                }
            } catch (Exception e) {
                String msg = registry + ": " + e.getMessage();
                log.debug("npm mirror failed: {}", msg);
                errors.add(msg);
            }
        }

        throw new NpmException("Failed to resolve npm package " + packageName + "@" + version
            + " from all registries: " + String.join(" | ", errors));
    }

    private String getTarballUrl(String registry, String packageName, String version)
        throws IOException, InterruptedException {
        String url = registry + "/" + packageName + "/" + version;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new NpmException("npm registry returned " + resp.statusCode()
                + " for " + packageName + "@" + version);
        }

        Pattern p = Pattern.compile("\"tarball\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(resp.body());
        if (m.find()) {
            return m.group(1);
        }
        throw new NpmException("tarball not found in npm registry response for "
            + packageName + "@" + version);
    }

    private void extractTgz(Path tgzFile, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (InputStream fi = Files.newInputStream(tgzFile);
             BufferedInputStream bi = new BufferedInputStream(fi);
             GzipCompressorInputStream gz = new GzipCompressorInputStream(bi);
             TarArchiveInputStream tar = new TarArchiveInputStream(gz)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path outFile = targetDir.resolve(entry.getName()).normalize();
                if (!outFile.startsWith(targetDir)) continue;
                if (entry.isDirectory()) {
                    Files.createDirectories(outFile);
                } else {
                    Files.createDirectories(outFile.getParent());
                    Files.copy(tar, outFile);
                }
            }
        }
    }

    private static boolean isNonEmpty(Path dir) {
        try (var s = Files.list(dir)) { return s.findAny().isPresent(); }
        catch (IOException e) { return false; }
    }

    private static List<String> loadRegistries() {
        String prop = System.getProperty("npm.mirrors");
        if (prop != null && !prop.isBlank()) {
            List<String> urls = new ArrayList<>();
            for (String url : prop.split(",")) {
                url = url.trim();
                if (!url.isEmpty()) urls.add(url);
            }
            if (!urls.isEmpty()) return List.copyOf(urls);
        }
        String env = System.getenv("NPM_MIRRORS");
        if (env != null && !env.isBlank()) {
            List<String> urls = new ArrayList<>();
            for (String url : env.split(",")) {
                url = url.trim();
                if (!url.isEmpty()) urls.add(url);
            }
            if (!urls.isEmpty()) return List.copyOf(urls);
        }
        return List.of(DEFAULT_REGISTRY);
    }

    public static class NpmException extends RuntimeException {
        public NpmException(String message) { super(message); }
        public NpmException(String message, Throwable cause) { super(message, cause); }
    }
}
