package top.codexvn.decompile.mcp.npm;

import top.codexvn.decompile.mcp.server.CacheConfig;
import java.io.BufferedInputStream;
import top.codexvn.decompile.mcp.server.CacheConfig;
import java.io.IOException;
import top.codexvn.decompile.mcp.server.CacheConfig;
import java.io.InputStream;
import top.codexvn.decompile.mcp.server.CacheConfig;
import java.net.URI;
import top.codexvn.decompile.mcp.server.CacheConfig;
import java.net.http.HttpClient;
import top.codexvn.decompile.mcp.server.CacheConfig;
import java.net.http.HttpRequest;
import top.codexvn.decompile.mcp.server.CacheConfig;
import java.net.http.HttpResponse;
import top.codexvn.decompile.mcp.server.CacheConfig;
import java.nio.file.Files;
import top.codexvn.decompile.mcp.server.CacheConfig;
import java.nio.file.Path;
import top.codexvn.decompile.mcp.server.CacheConfig;
import java.util.regex.Matcher;
import top.codexvn.decompile.mcp.server.CacheConfig;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NpmResolver {

    private static final Logger log = LoggerFactory.getLogger(NpmResolver.class);
    private static final String REGISTRY = "https://registry.npmjs.org";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Path CACHE_DIR = CacheConfig.npmCache();

    public NpmResolver() {
        try { Files.createDirectories(CACHE_DIR); } catch (IOException ignored) {}
    }

    public Path resolve(String packageName, String version) throws NpmException {
        Path cached = CACHE_DIR.resolve(packageName + "-" + version);
        if (Files.isDirectory(cached) && isNonEmpty(cached)) {
            log.debug("Using cached npm package: {}", cached);
            return cached;
        }

        try {
            String tarballUrl = getTarballUrl(packageName, version);
            log.info("Downloading npm package: {}@{}", packageName, version);

            Path tgzFile = Files.createTempFile("npm-", ".tgz");
            HttpRequest req = HttpRequest.newBuilder(URI.create(tarballUrl)).build();
            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                Files.copy(in, tgzFile);
            }

            Path extracted = CACHE_DIR.resolve(packageName + "-" + version);
            extractTgz(tgzFile, extracted);
            Files.deleteIfExists(tgzFile);

            log.info("Extracted npm package to: {}", extracted);
            return extracted;
        } catch (Exception e) {
            throw new NpmException("Failed to resolve npm package " + packageName + "@" + version + ": " + e.getMessage(), e);
        }
    }

    private String getTarballUrl(String packageName, String version) throws IOException, InterruptedException {
        String url = REGISTRY + "/" + packageName + "/" + version;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new NpmException("npm registry returned " + resp.statusCode() + " for " + packageName + "@" + version);
        }

        // 解析 dist.tarball 字段
        Pattern p = Pattern.compile("\"tarball\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(resp.body());
        if (m.find()) {
            return m.group(1);
        }
        throw new NpmException("tarball not found in npm registry response for " + packageName + "@" + version);
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
                if (!outFile.startsWith(targetDir)) continue; // 防 zip slip
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

    public static class NpmException extends RuntimeException {
        public NpmException(String message) { super(message); }
        public NpmException(String message, Throwable cause) { super(message, cause); }
    }
}
