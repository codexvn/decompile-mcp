package top.codexvn.decompile.mcp.jar.resolver;

import java.util.ArrayList;
import java.util.List;

public final class RepositoryConfig {

    private RepositoryConfig() {}

    private static final String DEFAULT_MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    public record RepositoryEntry(String id, String url) {}

    // 加载优先级：系统属性 > 环境变量 > 默认（Maven Central）。
    // 系统属性存在时覆盖环境变量，不做合并——属性是显式指定，优先级最高。
    public static List<RepositoryEntry> load() {
        List<RepositoryEntry> repos = new ArrayList<>();

        String prop = System.getProperty("maven.repositories");
        if (prop != null && !prop.isBlank()) {
            for (String url : prop.split(",")) {
                url = url.trim();
                if (!url.isEmpty()) {
                    repos.add(new RepositoryEntry(
                        "repo-" + Integer.toHexString(url.hashCode()), url));
                }
            }
        } else {
            String env = System.getenv("M2_REPOSITORIES");
            if (env != null && !env.isBlank()) {
                for (String url : env.split(",")) {
                    url = url.trim();
                    if (!url.isEmpty()) {
                        repos.add(new RepositoryEntry(
                            "repo-" + Integer.toHexString(url.hashCode()), url));
                    }
                }
            }
        }

        if (repos.isEmpty()) {
            repos.add(new RepositoryEntry("central", DEFAULT_MAVEN_CENTRAL));
        }

        return List.copyOf(repos);
    }
}
