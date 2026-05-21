package top.codexvn.decompile.mcp.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.codexvn.decompile.mcp.maven.decompiler.DecompilerService;
import top.codexvn.decompile.mcp.maven.resolver.MavenCoordinate;
import top.codexvn.decompile.mcp.maven.resolver.MavenResolver;
import top.codexvn.decompile.mcp.maven.resolver.ResolutionConfig;
import top.codexvn.decompile.mcp.maven.resolver.ResolutionResult;
import top.codexvn.decompile.mcp.server.I18n;

public class MavenGrepTool {

    private static final Logger log = LoggerFactory.getLogger(MavenGrepTool.class);

    private final MavenResolver resolver;
    private final DecompilerService decompiler;

    public MavenGrepTool(MavenResolver resolver, DecompilerService decompiler) {
        this.resolver = resolver;
        this.decompiler = decompiler;
    }

    public static McpSchema.Tool toolDefinition() {
        String req = I18n.zhEn("(必填) ", "(required) ");
        String opt = I18n.zhEn("(可选) ", "(optional) ");

        return McpSchema.Tool.builder()
            .name("maven_grep")
            .title(I18n.zhEn("在反编译的 Maven JAR 类中搜索正则表达式",
                             "Search for a regex pattern in decompiled Maven JAR classes"))
            .description(I18n.zhEn(
                "在 Maven JAR 的所有反编译类中搜索正则表达式。返回格式：类名:行号:匹配行。",
                "Search for a regular expression across all decompiled classes in a Maven JAR. "
                    + "Results in grep -n format: ClassName:lineNumber:matchedLine."))
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
                            req + "Java 正则表达式，在反编译/源码中搜索",
                            req + "Java regex pattern to search in decompiled source")
                    ),
                    "prefer_source", Map.of(
                        "type", "boolean",
                        "description", I18n.zhEn(
                            opt + "优先搜索 sources JAR 中的 .java 文件，默认 true",
                            opt + "Prefer searching .java files in sources JAR, default: true")
                    ),
                    "force_decompile", Map.of(
                        "type", "boolean",
                        "description", I18n.zhEn(
                            opt + "强制反编译 .class 文件后搜索，即使存在 sources JAR。默认 false",
                            opt + "Force decompile .class files even if sources JAR exists. Default: false")
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
            String regex = MavenReadTool.requireString(arguments, "pattern");

            Pattern pattern;
            try {
                pattern = Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                return MavenReadTool.errorResult("Invalid regex pattern: " + e.getMessage());
            }

            ResolutionConfig config = MavenReadTool.buildConfig(arguments);
            ResolutionResult result = resolver.resolveWithConfig(coord, config);

            Map<String, String> sources;
            if (result.isSourceJar()) {
                sources = decompiler.readAllSources(result.jarPath());
            } else {
                sources = decompiler.decompileAll(
                    result.jarPath(), result.cacheNamespace());
            }

            List<String> matches = new ArrayList<>();
            for (Map.Entry<String, String> entry : sources.entrySet()) {
                String className = entry.getKey();
                String source = entry.getValue()
                    .replace("\r\n", "\n").replace('\r', '\n');
                String[] lines = source.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (pattern.matcher(lines[i]).find()) {
                        matches.add(className + ":" + (i + 1) + ":" + lines[i]);
                    }
                }
            }

            if (matches.isEmpty()) {
                log.info("maven_grep {}:/{}/ -> no matches", coord, regex);
                return MavenReadTool.successResult(
                    "(no matches found for pattern: " + regex + ")");
            }

            log.info("maven_grep {}:/{}/ -> {} matches across {} classes",
                coord, regex, matches.size(), sources.size());
            return MavenReadTool.successResult(String.join("\n", matches));

        } catch (Exception e) {
            log.error("maven_grep failed: {} {}", arguments, e.getMessage());
            return MavenReadTool.errorResult(e.getMessage());
        }
    }
}
