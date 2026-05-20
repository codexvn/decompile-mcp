package top.codexvn.decompile.mcp.maven.resolver;

public record ResolutionConfig(
    boolean preferSource,
    boolean forceDecompile,
    String repositoryUrl,
    boolean forceRemote
) {
    public static final ResolutionConfig DEFAULT = new ResolutionConfig(true, false, null, false);
}
