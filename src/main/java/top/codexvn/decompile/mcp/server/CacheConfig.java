package top.codexvn.decompile.mcp.server;

import java.nio.file.Path;

/**
 * 统一缓存根目录。可通过 decompile.cache.dir 系统属性或 DECOMPILE_CACHE_DIR 环境变量控制。
 */
public final class CacheConfig {

    private CacheConfig() {}

    private static final Path DEFAULT = Path.of(System.getProperty("user.home"), ".decompile-mcp");

    public static Path root() {
        String prop = System.getProperty("decompile.cache.dir");
        if (prop != null && !prop.isBlank()) return Path.of(prop.trim());
        String env = System.getenv("DECOMPILE_CACHE_DIR");
        if (env != null && !env.isBlank()) return Path.of(env.trim());
        return DEFAULT;
    }

    public static Path mavenRepo()    { return root().resolve("maven-repo"); }
    public static Path decompiled()   { return root().resolve("decompiled"); }
    public static Path npmCache()     { return root().resolve("npm"); }
    public static Path pipCache()     { return root().resolve("pip"); }
}
