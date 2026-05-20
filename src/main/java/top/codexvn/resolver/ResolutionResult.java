package top.codexvn.resolver;

import java.nio.file.Path;

/**
 * @param jarPath       resolved JAR file path
 * @param isSourceJar   true if the path points to a -sources.jar
 * @param cacheNamespace  empty for default repos; hash-based key for custom repos,
 *                        used to segregate decompiler caches when the same GAV
 *                        may have different content from different repositories
 */
public record ResolutionResult(
    Path jarPath,
    boolean isSourceJar,
    String cacheNamespace
) {}
