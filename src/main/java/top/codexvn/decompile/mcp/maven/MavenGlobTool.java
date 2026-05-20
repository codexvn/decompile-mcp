package top.codexvn.decompile.mcp.maven;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.codexvn.decompile.mcp.maven.resolver.MavenCoordinate;
import top.codexvn.decompile.mcp.maven.resolver.MavenResolver;
import top.codexvn.decompile.mcp.maven.resolver.ResolutionConfig;
import top.codexvn.decompile.mcp.maven.resolver.ResolutionResult;
import top.codexvn.decompile.mcp.server.I18n;

public class MavenGlobTool {

    private static final Logger log = LoggerFactory.getLogger(MavenGlobTool.class);

    private final MavenResolver resolver;

    public MavenGlobTool(MavenResolver resolver) {
        this.resolver = resolver;
    }

    public static McpSchema.Tool toolDefinition() {
        String req = I18n.zhEn("(必填) ", "(required) ");
        String opt = I18n.zhEn("(可选) ", "(optional) ");

        return McpSchema.Tool.builder()
            .name("maven_glob")
            .title(I18n.zhEn("列出 Maven JAR 中匹配 glob 模式的条目",
                             "List entries in a Maven JAR matching a glob pattern"))
            .description(I18n.zhEn(
                "列出 Maven JAR 中匹配 glob 模式的所有条目，按字母排序返回。用于发现类文件、资源等。",
                "List entries inside a Maven JAR matching a glob pattern. "
                    + "Returns a sorted list of matching entry paths."))
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "group_id", Map.of(
                        "type", "string",
                        "description", I18n.zhEn(
                            req + "Maven group ID，如 'com.google.guava'",
                            req + "Maven group ID, e.g. 'com.google.guava'")
                    ),
                    "artifact_id", Map.of(
                        "type", "string",
                        "description", I18n.zhEn(
                            req + "Maven artifact ID，如 'guava'",
                            req + "Maven artifact ID, e.g. 'guava'")
                    ),
                    "version", Map.of(
                        "type", "string",
                        "description", I18n.zhEn(
                            req + "版本号，如 '33.0.0-jre'",
                            req + "Version string, e.g. '33.0.0-jre'")
                    ),
                    "pattern", Map.of(
                        "type", "string",
                        "description", I18n.zhEn(
                            req + "Glob 模式，如 '**/*Service*.class'",
                            req + "Glob pattern, e.g. '**/*Service*.class'")
                    ),
                    "prefer_source", Map.of(
                        "type", "boolean",
                        "description", I18n.zhEn(
                            opt + "优先列出 -sources.jar 中的条目（.java），默认 true",
                            opt + "Prefer listing from -sources.jar (.java entries), default: true")
                    ),
                    "force_decompile", Map.of(
                        "type", "boolean",
                        "description", I18n.zhEn(
                            opt + "强制使用主 JAR（.class 条目），即使存在 sources JAR。默认 false",
                            opt + "Force main JAR (.class entries) even if sources available. Default: false")
                    ),
                    "repository_url", Map.of(
                        "type", "string",
                        "description", I18n.zhEn(
                            opt + "指定 Maven 仓库 URL，覆盖已配置的仓库列表",
                            opt + "Specific Maven repository URL, overrides configured repo list")
                    ),
                    "force_remote", Map.of(
                        "type", "boolean",
                        "description", I18n.zhEn(
                            opt + "直接从远程下载到临时目录，绕过本地缓存。默认 false",
                            opt + "Download from remote to temp dir, bypassing local cache. Default: false")
                    )
                ),
                List.of("group_id", "artifact_id", "version", "pattern"),
                false,
                null,
                null
            ))
            .build();
    }

    public McpSchema.CallToolResult handle(Map<String, Object> arguments) {
        try {
            MavenCoordinate coord = MavenReadTool.extractCoordinate(arguments);
            String pattern = MavenReadTool.requireString(arguments, "pattern");

            ResolutionConfig config = MavenReadTool.buildConfig(arguments);
            ResolutionResult result = resolver.resolveWithConfig(coord, config);

            List<String> matches = globJar(result.jarPath(), pattern);
            Collections.sort(matches);

            if (matches.isEmpty()) {
                return MavenReadTool.successResult("(no entries matched pattern: " + pattern + ")");
            }

            return MavenReadTool.successResult(String.join("\n", matches));

        } catch (Exception e) {
            log.error("maven_glob failed", e);
            return MavenReadTool.errorResult(e.getMessage());
        }
    }

    private List<String> globJar(Path jarPath, String globPattern) throws java.io.IOException {
        List<String> result = new ArrayList<>();

        try (FileSystem fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null)) {
            PathMatcher matcher = fs.getPathMatcher("glob:" + globPattern);
            Path root = fs.getPath("/");

            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> matcher.matches(p)).forEach(p -> {
                    String entry = p.toString();
                    if (entry.startsWith("/")) {
                        entry = entry.substring(1);
                    }
                    result.add(entry);
                });
            }
        }

        return result;
    }
}
