package top.codexvn.decompile.mcp.pip;

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

public class PipResolver {

    private static final Logger log = LoggerFactory.getLogger(PipResolver.class);
    private static final String DEFAULT_INDEX = "https://pypi.org/simple/";
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Path CACHE_DIR = CacheConfig.pipCache();

    private final List<String> indices;

    public PipResolver() {
        try { Files.createDirectories(CACHE_DIR); } catch (IOException ignored) {}
        this.indices = loadIndices();
        log.info("PipResolver initialized, cache: {}, mirrors: {}", CACHE_DIR, indices);
    }

    public Path resolve(String packageName, String version) throws PipException {
        Path cached = CACHE_DIR.resolve(packageName + "-" + version);
        if (Files.isDirectory(cached) && isNonEmpty(cached)) {
            log.debug("Using cached pip package: {}", cached);
            return cached;
        }

        // 按优先级尝试各镜像
        List<String> errors = new ArrayList<>();
        for (String index : indices) {
            try {
                String downloadUrl = findSdistUrl(index, packageName, version);
                log.info("Downloading pip package: {}=={} from {}", packageName, version, index);

                Path tgzFile = Files.createTempFile("pip-", ".tar.gz");
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(downloadUrl)).build();
                    HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() != 200) {
                        throw new PipException("download returned " + resp.statusCode());
                    }
                    try (InputStream in = resp.body()) {
                        Files.copy(in, tgzFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }

                    Path extracted = CACHE_DIR.resolve(packageName + "-" + version);
                    extractTarGz(tgzFile, extracted);
                    Files.deleteIfExists(tgzFile);

                    log.info("Extracted pip package to: {}", extracted);
                    return extracted;
                } catch (PipException e) {
                    Files.deleteIfExists(tgzFile);
                    throw e;
                } catch (Exception e) {
                    Files.deleteIfExists(tgzFile);
                    throw new PipException(e.getMessage(), e);
                }
            } catch (Exception e) {
                String msg = index + ": " + e.getMessage();
                log.debug("pip mirror failed: {}", msg);
                errors.add(msg);
            }
        }

        throw new PipException("Failed to resolve pip package " + packageName + "==" + version
            + " from all indices: " + String.join(" | ", errors));
    }

    private String findSdistUrl(String index, String packageName, String version)
        throws IOException, InterruptedException {
        String url = index + packageName + "/";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new PipException("PyPI returned " + resp.statusCode() + " for " + packageName);
        }

        Pattern linkPattern = Pattern.compile("<a[^>]*href=\"([^\"]+)\"[^>]*>(.+?)</a>",
            Pattern.CASE_INSENSITIVE);
        Matcher m = linkPattern.matcher(resp.body());
        while (m.find()) {
            String href = m.group(1);
            int frag = href.indexOf('#');
            String cleanHref = frag >= 0 ? href.substring(0, frag) : href;
            if (cleanHref.contains(version) && cleanHref.endsWith(".tar.gz")) {
                return cleanHref.startsWith("http") ? cleanHref : index + cleanHref;
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

    private static List<String> loadIndices() {
        String prop = System.getProperty("pip.mirrors");
        if (prop != null && !prop.isBlank()) {
            List<String> urls = new ArrayList<>();
            for (String url : prop.split(",")) {
                url = url.trim();
                if (!url.isEmpty()) urls.add(url);
            }
            if (!urls.isEmpty()) return List.copyOf(urls);
        }
        String env = System.getenv("PIP_MIRRORS");
        if (env != null && !env.isBlank()) {
            List<String> urls = new ArrayList<>();
            for (String url : env.split(",")) {
                url = url.trim();
                if (!url.isEmpty()) urls.add(url);
            }
            if (!urls.isEmpty()) return List.copyOf(urls);
        }
        return List.of(DEFAULT_INDEX);
    }

    public static class PipException extends RuntimeException {
        public PipException(String message) { super(message); }
        public PipException(String message, Throwable cause) { super(message, cause); }
    }
}
