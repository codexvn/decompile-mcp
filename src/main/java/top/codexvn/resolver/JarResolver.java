package top.codexvn.resolver;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JarResolver {

    private static final Logger log = LoggerFactory.getLogger(JarResolver.class);

    private static final String MAVEN_CENTRAL_URL = "https://repo1.maven.org/maven2/";

    private final Path localRepoDir;
    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final RemoteRepository central;

    public JarResolver() {
        this.localRepoDir = resolveLocalRepoPath();

        RepositorySystemSupplier supplier = new RepositorySystemSupplier();
        this.system = supplier.get();

        DefaultRepositorySystemSession s = new DefaultRepositorySystemSession();
        s.setLocalRepositoryManager(
            system.newLocalRepositoryManager(s, new LocalRepository(localRepoDir.toFile())));
        this.session = s;

        this.central = new RemoteRepository.Builder("central", "default", MAVEN_CENTRAL_URL).build();
        log.info("JarResolver initialized, local repo: {}", localRepoDir);
    }

    public Path resolve(MavenCoordinate coord) throws JarResolutionException {
        Path localJar = coord.toLocalPath(localRepoDir);

        if (Files.exists(localJar)) {
            log.debug("Found in local repo: {}", localJar);
            return localJar;
        }

        log.info("Not in local repo, downloading: {}", coord);
        return resolveRemote(coord);
    }

    private Path resolveRemote(MavenCoordinate coord) throws JarResolutionException {
        try {
            ArtifactRequest request = new ArtifactRequest();
            request.setArtifact(new DefaultArtifact(coord.toAetherCoordinate()));
            request.addRepository(central);

            ArtifactResult result = system.resolveArtifact(session, request);
            if (result.isMissing() || !result.isResolved()) {
                throw new JarResolutionException("Artifact not found in Maven Central: " + coord);
            }

            Path resolvedPath = result.getArtifact().getFile().toPath();
            log.info("Downloaded: {}", resolvedPath);
            return resolvedPath;
        } catch (org.eclipse.aether.resolution.ArtifactResolutionException e) {
            throw new JarResolutionException(
                "Failed to resolve artifact: " + coord + " - " + e.getMessage(), e);
        }
    }

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
