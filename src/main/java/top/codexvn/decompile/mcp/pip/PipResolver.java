package top.codexvn.decompile.mcp.pip;

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

public class PipResolver {

    private static final Logger log = LoggerFactory.getLogger(PipResolver.class);
    private static final String SIMPLE_API = "https://pypi.org/simple/";
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Path CACHE_DIR = CacheConfig.pipCache();

    public PipResolver() {
        try { Files.createDirectories(CACHE_DIR); } catch (IOException ignored) {}
    }

    public Path resolve(String packageName, String version) throws PipException {
        Path cached = CACHE_DIR.resolve(packageName + "-" + version);
        if (Files.isDirectory(cached) && isNonEmpty(cached)) {
            log.debug("Using cached pip package: {}", cached);
            return cached;
        }

        try {
            String downloadUrl = findSdistUrl(packageName, version);
            log.info("Downloading pip package: {}=={}", packageName, version);

            Path tgzFile = Files.createTempFile("pip-", ".tar.gz");
            HttpRequest req = HttpRequest.newBuilder(URI.create(downloadUrl)).build();
            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                Files.copy(in, tgzFile);
            }

            Path extracted = CACHE_DIR.resolve(packageName + "-" + version);
            extractTarGz(tgzFile, extracted);
            Files.deleteIfExists(tgzFile);

            log.info("Extracted pip package to: {}", extracted);
            return extracted;
        } catch (Exception e) {
            throw new PipException("Failed to resolve pip package " + packageName + "==" + version + ": " + e.getMessage(), e);
        }
    }

    private String findSdistUrl(String packageName, String version) throws IOException, InterruptedException {
        // PyPI simple API: GET /simple/{package}/ returns HTML with links
        String url = SIMPLE_API + packageName + "/";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new PipException("PyPI returned " + resp.statusCode() + " for " + packageName);
        }

        // 查找 .tar.gz 链接（优先），跳过 .whl
        Pattern linkPattern = Pattern.compile("<a[^>]*href=\"([^\"]+)\"[^>]*>(.+?)</a>", Pattern.CASE_INSENSITIVE);
        Matcher m = linkPattern.matcher(resp.body());
        while (m.find()) {
            String href = m.group(1);
            String text = m.group(2).trim();
            // 匹配版本且为 sdist 格式 (.tar.gz)
            if (href.contains(version) && href.endsWith(".tar.gz")) {
                return href.startsWith("http") ? href : "https://pypi.org" + href;
            }
        }

        throw new PipException("No .tar.gz found for " + packageName + "==" + version);
    }

    private void extractTarGz(Path tgzFile, Path targetDir) throws IOException {
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

    public static class PipException extends RuntimeException {
        public PipException(String message) { super(message); }
        public PipException(String message, Throwable cause) { super(message, cause); }
    }
}
