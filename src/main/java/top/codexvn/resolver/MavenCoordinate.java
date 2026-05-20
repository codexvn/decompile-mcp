package top.codexvn.resolver;

import java.nio.file.Path;

public record MavenCoordinate(String groupId, String artifactId, String version) {

    public MavenCoordinate {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be blank");
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
    }

    public String toRepoPath() {
        return groupId.replace('.', '/') + "/" + artifactId + "/" + version;
    }

    public String toJarFilename() {
        return artifactId + "-" + version + ".jar";
    }

    public Path toLocalPath(Path localRepoDir) {
        return localRepoDir.resolve(toRepoPath()).resolve(toJarFilename());
    }

    public String toAetherCoordinate() {
        return groupId + ":" + artifactId + ":jar:" + version;
    }

    public String toSourceJarFilename() {
        return artifactId + "-" + version + "-sources.jar";
    }

    public Path toSourceLocalPath(Path localRepoDir) {
        return localRepoDir.resolve(toRepoPath()).resolve(toSourceJarFilename());
    }

    public String toAetherSourceCoordinate() {
        return groupId + ":" + artifactId + ":jar:sources:" + version;
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
