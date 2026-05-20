package top.codexvn.decompile.mcp.jar.resolver;

public record ResolutionConfig(
    boolean preferSource,
    boolean forceDecompile,
    String repositoryUrl,
    boolean forceRemote
) {
    // preferSource=true: try sources JAR first; forceDecompile=false: allow sources
    // repositoryUrl=null: use configured repos; forceRemote=false: use local cache
    public static final ResolutionConfig DEFAULT = new ResolutionConfig(true, false, null, false);
}
