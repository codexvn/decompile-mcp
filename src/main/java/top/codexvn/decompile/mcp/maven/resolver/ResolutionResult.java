package top.codexvn.decompile.mcp.maven.resolver;

import java.nio.file.Path;

public record ResolutionResult(
    Path jarPath,
    boolean isSourceJar,
    String cacheNamespace
) {}
