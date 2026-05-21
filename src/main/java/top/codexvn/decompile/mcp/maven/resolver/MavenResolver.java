package top.codexvn.decompile.mcp.maven.resolver;

import top.codexvn.decompile.mcp.server.CacheConfig;
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

public class MavenResolver {

    private static final Logger log = LoggerFactory.getLogger(MavenResolver.class);

    private final Path localRepoDir;
    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;
    private final Set<Path> tempDirs = ConcurrentHashMap.newKeySet();

    public MavenResolver() {
        this.localRepoDir = CacheConfig.mavenRepo();

        RepositorySystemSupplier supplier = new RepositorySystemSupplier();
        this.system = supplier.get();

        DefaultRepositorySystemSession s = new DefaultRepositorySystemSession();
        s.setLocalRepositoryManager(
            system.newLocalRepositoryManager(s, new LocalRepository(localRepoDir.toFile())));
        this.session = s;

        this.repositories = RepositoryConfig.load().stream()
            .map(e -> new RemoteRepository.Builder(e.id(), "default", e.url()).build())
            .toList();
        log.info("MavenResolver initialized, cache: {}, mirrors: {}",
            localRepoDir, repositories.stream().map(RemoteRepository::getUrl).toList());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Path dir : tempDirs) {
                cleanupDir(dir);
            }
        }));
    }

    public Path resolve(MavenCoordinate coord) throws MavenResolutionException {
        return resolveWithConfig(coord, ResolutionConfig.DEFAULT).jarPath();
    }

    public ResolutionResult resolveWithConfig(MavenCoordinate coord,
                                               ResolutionConfig config)
        throws MavenResolutionException {

        List<RemoteRepository> reposToUse;
        String cacheNamespace;

        if (config.repositoryUrl() != null && !config.repositoryUrl().isBlank()) {
            String url = config.repositoryUrl().trim();
            RemoteRepository adhoc = new RemoteRepository.Builder(
                "repo-" + Integer.toHexString(url.hashCode()), "default", url).build();
            reposToUse = List.of(adhoc);
            cacheNamespace = "repo-" + Integer.toHexString(url.hashCode());
        } else {
            reposToUse = this.repositories;
            cacheNamespace = "";
        }

        boolean skipLocal = config.forceRemote() || !cacheNamespace.isEmpty();

        if (config.preferSource() && !config.forceDecompile()) {
            Path sourcesJar = tryResolveSourcesJar(coord, reposToUse, skipLocal);
            if (sourcesJar != null) {
                log.info("Using sources JAR: {}", sourcesJar);
                return new ResolutionResult(sourcesJar, true, cacheNamespace);
            }
            log.debug("No sources JAR available for {}, falling back to main JAR", coord);
        }

        Path mainJar;
        if (skipLocal) {
            mainJar = resolveForceRemote(coord, reposToUse);
        } else {
            mainJar = resolveLocalOrRemote(coord, reposToUse);
        }

        return new ResolutionResult(mainJar, false, cacheNamespace);
    }

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

            ArtifactResult result = system.resolveArtifact(session, request);
            if (result.isResolved() && !result.isMissing()) {
                var artifactFile = result.getArtifact().getFile();
                return artifactFile != null ? artifactFile.toPath() : null;
            }
        } catch (Exception e) {
            log.debug("Sources JAR not found for {}: {}", coord, e.getMessage());
        }
        return null;
    }

    private Path resolveLocalOrRemote(MavenCoordinate coord,
                                       List<RemoteRepository> repos)
        throws MavenResolutionException {
        Path localPath = coord.toLocalPath(localRepoDir);
        if (Files.exists(localPath)) {
            log.debug("Found in local repo: {}", localPath);
            return localPath;
        }
        return resolveRemote(coord, repos);
    }

    private Path resolveRemote(MavenCoordinate coord,
                                List<RemoteRepository> repos)
        throws MavenResolutionException {
        log.info("Resolving Maven artifact: {} from {}", coord,
            repos.stream().map(RemoteRepository::getUrl).toList());
        try {
            ArtifactRequest request = new ArtifactRequest();
            request.setArtifact(new DefaultArtifact(coord.toAetherCoordinate()));
            repos.forEach(request::addRepository);

            ArtifactResult result = system.resolveArtifact(session, request);
            if (result.isMissing() || !result.isResolved()) {
                throw new MavenResolutionException("Artifact not found: " + coord);
            }

            Path resolvedPath = result.getArtifact().getFile().toPath();
            log.info("Downloaded: {}", resolvedPath);
            return resolvedPath;
        } catch (MavenResolutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MavenResolutionException(
                "Failed to resolve artifact: " + coord + " - " + e.getMessage(), e);
        }
    }

    private Path resolveForceRemote(MavenCoordinate coord,
                                     List<RemoteRepository> repos)
        throws MavenResolutionException {
        log.info("Resolving Maven artifact (force remote): {} from {}",
            coord, repos.stream().map(RemoteRepository::getUrl).toList());
        Path tempRepoDir;
        try {
            tempRepoDir = Files.createTempDirectory("mcp-remote-");
        } catch (IOException e) {
            throw new MavenResolutionException("Failed to create temp directory", e);
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
                throw new MavenResolutionException("Artifact not found: " + coord);
            }

            Path resolvedPath = result.getArtifact().getFile().toPath();
            log.info("Force-downloaded: {}", resolvedPath);
            tempDirs.add(tempRepoDir);
            return resolvedPath;
        } catch (MavenResolutionException e) {
            cleanupDir(tempRepoDir);
            throw e;
        } catch (Exception e) {
            cleanupDir(tempRepoDir);
            throw new MavenResolutionException(
                "Failed to resolve artifact from remote: " + coord + " - " + e.getMessage(), e);
        }
    }

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

    public static class MavenResolutionException extends Exception {
        public MavenResolutionException(String message) {
            super(message);
        }

        public MavenResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
