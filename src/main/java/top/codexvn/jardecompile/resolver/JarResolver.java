package top.codexvn.jardecompile.resolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JarResolver {

    private static final Logger log = LoggerFactory.getLogger(JarResolver.class);

    private final Path localRepoDir;
    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;
    // 所有 forceRemote 创建的临时目录，JVM 关闭时统一清理
    private final Set<Path> tempDirs = ConcurrentHashMap.newKeySet();

    public JarResolver() {
        this.localRepoDir = resolveLocalRepoPath();

        RepositorySystemSupplier supplier = new RepositorySystemSupplier();
        this.system = supplier.get();

        DefaultRepositorySystemSession s = new DefaultRepositorySystemSession();
        s.setLocalRepositoryManager(
            system.newLocalRepositoryManager(s, new LocalRepository(localRepoDir.toFile())));
        this.session = s;

        this.repositories = RepositoryConfig.load().stream()
            .map(e -> new RemoteRepository.Builder(e.id(), "default", e.url()).build())
            .toList();
        log.info("JarResolver initialized, local: {}, repos: {}",
            localRepoDir, repositories.stream().map(RemoteRepository::getUrl).toList());

        // 注册唯一关闭钩子，清理所有临时目录
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Path dir : tempDirs) {
                cleanupDir(dir);
            }
        }));
    }

    // --- 向后兼容入口（保留供外部直接调用） ---

    public Path resolve(MavenCoordinate coord) throws JarResolutionException {
        return resolveWithConfig(coord, ResolutionConfig.DEFAULT).jarPath();
    }

    // --- 增强版主入口 ---

    public ResolutionResult resolveWithConfig(MavenCoordinate coord,
                                               ResolutionConfig config)
        throws JarResolutionException {

        List<RemoteRepository> reposToUse;
        String cacheNamespace;

        if (config.repositoryUrl() != null && !config.repositoryUrl().isBlank()) {
            String url = config.repositoryUrl().trim();
            RemoteRepository adhoc = new RemoteRepository.Builder(
                "repo-" + Integer.toHexString(url.hashCode()), "default", url).build();
            reposToUse = List.of(adhoc);
            // 用 URL 哈希生成稳定的命名空间，使得不同仓库中同 GAV 的
            // 构件拥有独立的缓存键，避免缓存污染。
            cacheNamespace = "repo-" + Integer.toHexString(url.hashCode());
        } else {
            reposToUse = this.repositories;
            // 默认仓库使用空串命名空间。空串使缓存键退化为纯 jarPath，
            // 与增强前的旧版缓存行为一致，确保已缓存数据继续生效。
            cacheNamespace = "";
        }

        // 非默认仓库必须跳过本地 ~/.m2 缓存，因为同一 GAV 从不同源
        // 可能获取到不同内容（内网补丁版、镜像陈旧等），视同强制远程。
        boolean skipLocal = config.forceRemote() || !cacheNamespace.isEmpty();

        // 若配置了优先源码包，先尝试获取 sources JAR
        if (config.preferSource() && !config.forceDecompile()) {
            Path sourcesJar = tryResolveSourcesJar(coord, reposToUse, skipLocal);
            if (sourcesJar != null) {
                log.info("Using sources JAR: {}", sourcesJar);
                return new ResolutionResult(sourcesJar, true, cacheNamespace);
            }
            log.debug("No sources JAR available for {}, falling back to main JAR", coord);
        }

        // 解析主 JAR
        Path mainJar;
        if (skipLocal) {
            mainJar = resolveForceRemote(coord, reposToUse);
        } else {
            mainJar = resolveLocalOrRemote(coord, reposToUse);
        }

        return new ResolutionResult(mainJar, false, cacheNamespace);
    }

    // --- 源码 JAR 解析 ---

    private Path tryResolveSourcesJar(MavenCoordinate coord,
                                       List<RemoteRepository> repos,
                                       boolean skipLocal) {
        if (!skipLocal) {
            Path localSources = coord.toSourceLocalPath(localRepoDir);
            if (Files.exists(localSources)) {
                return localSources;
            }
        }

        try {
            String sourceCoord = coord.toAetherSourceCoordinate();
            ArtifactRequest request = new ArtifactRequest();
            request.setArtifact(new DefaultArtifact(sourceCoord));
            repos.forEach(request::addRepository);

            // sources JAR 下载到默认本地仓库，避免临时目录泄漏。
            // skipLocal 仅跳过本地存在性检查，不影响下载目标的存储位置。
            ArtifactResult result = system.resolveArtifact(session, request);
            if (result.isResolved() && !result.isMissing()) {
                var artifactFile = result.getArtifact().getFile();
                return artifactFile != null ? artifactFile.toPath() : null;
            }
        } catch (Exception e) {
            // 源码 JAR 不可用是预期行为——多数 Maven 构件不发布 -sources.jar。
            // 静默吞异常，回退到主 JAR + CFR 反编译路径。
            log.debug("Sources JAR not found for {}: {}", coord, e.getMessage());
        }
        return null;
    }

    // --- 主 JAR 解析 ---

    private Path resolveLocalOrRemote(MavenCoordinate coord,
                                       List<RemoteRepository> repos)
        throws JarResolutionException {
        Path localPath = coord.toLocalPath(localRepoDir);
        if (Files.exists(localPath)) {
            log.debug("Found in local repo: {}", localPath);
            return localPath;
        }
        return resolveRemote(coord, repos);
    }

    private Path resolveRemote(MavenCoordinate coord,
                                List<RemoteRepository> repos)
        throws JarResolutionException {
        try {
            ArtifactRequest request = new ArtifactRequest();
            request.setArtifact(new DefaultArtifact(coord.toAetherCoordinate()));
            repos.forEach(request::addRepository);

            ArtifactResult result = system.resolveArtifact(session, request);
            if (result.isMissing() || !result.isResolved()) {
                throw new JarResolutionException("Artifact not found: " + coord);
            }

            Path resolvedPath = result.getArtifact().getFile().toPath();
            log.info("Downloaded: {}", resolvedPath);
            return resolvedPath;
        } catch (JarResolutionException e) {
            throw e;
        } catch (Exception e) {
            throw new JarResolutionException(
                "Failed to resolve artifact: " + coord + " - " + e.getMessage(), e);
        }
    }

    private Path resolveForceRemote(MavenCoordinate coord,
                                     List<RemoteRepository> repos)
        throws JarResolutionException {
        Path tempRepoDir;
        try {
            tempRepoDir = Files.createTempDirectory("mcp-remote-");
        } catch (IOException e) {
            throw new JarResolutionException("Failed to create temp directory", e);
        }

        try {
            DefaultRepositorySystemSession tempSession =
                new DefaultRepositorySystemSession();
            tempSession.setLocalRepositoryManager(
                system.newLocalRepositoryManager(tempSession,
                    new LocalRepository(tempRepoDir.toFile())));

            ArtifactRequest request = new ArtifactRequest();
            request.setArtifact(new DefaultArtifact(coord.toAetherCoordinate()));
            repos.forEach(request::addRepository);

            ArtifactResult result = system.resolveArtifact(tempSession, request);
            if (result.isMissing() || !result.isResolved()) {
                cleanupDir(tempRepoDir);
                throw new JarResolutionException("Artifact not found: " + coord);
            }

            Path resolvedPath = result.getArtifact().getFile().toPath();
            log.info("Force-downloaded: {}", resolvedPath);
            tempDirs.add(tempRepoDir);
            return resolvedPath;
        } catch (JarResolutionException e) {
            cleanupDir(tempRepoDir);
            throw e;
        } catch (Exception e) {
            cleanupDir(tempRepoDir);
            throw new JarResolutionException(
                "Failed to resolve artifact from remote: " + coord + " - " + e.getMessage(), e);
        }
    }

    // --- 清理 ---

    private static void cleanupDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (IOException ignored) {
        }
    }

    // --- 本地仓库路径 ---

    private static Path resolveLocalRepoPath() {
        String prop = System.getProperty("maven.repo.local");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv("M2_REPO");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    public static class JarResolutionException extends Exception {
        public JarResolutionException(String message) {
            super(message);
        }

        public JarResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
