package top.codexvn.jardecompile.tool;

import java.util.List;
import java.util.Map;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.codexvn.jardecompile.decompiler.DecompilerService;
import top.codexvn.jardecompile.resolver.JarResolver;
import top.codexvn.jardecompile.resolver.MavenCoordinate;
import top.codexvn.jardecompile.resolver.ResolutionConfig;
import top.codexvn.jardecompile.resolver.ResolutionResult;
import top.codexvn.jardecompile.server.I18n;

public class JarReadTool {

    private static final Logger log = LoggerFactory.getLogger(JarReadTool.class);

    private final JarResolver resolver;
    private final DecompilerService decompiler;

    public JarReadTool(JarResolver resolver, DecompilerService decompiler) {
        this.resolver = resolver;
        this.decompiler = decompiler;
    }

    public static McpSchema.Tool toolDefinition() {
        String req = I18n.zhEn("(必填) ", "(required) ");
        String opt = I18n.zhEn("(可选) ", "(optional) ");

        return McpSchema.Tool.builder()
            .name("jar_read")
            .title(I18n.zhEn("读取并反编译 Maven JAR 中的类",
                             "Read and decompile a class from a Maven JAR"))
            .description(I18n.zhEn(
                "反编译 Maven JAR 中的指定类并返回 Java 源码。输出格式为 cat -n 风格带行号。",
                "Decompile a specific class from a Maven JAR and return its Java source. "
                    + "Output is formatted with line numbers in cat -n style."))
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
                    "class_name", Map.of(
                        "type", "string",
                        "description", I18n.zhEn(
                            req + "全限定类名，如 'com.google.common.collect.Lists'",
                            req + "Fully qualified class name, e.g. 'com.google.common.collect.Lists'")
                    ),
                    "offset", Map.of(
                        "type", "integer",
                        "description", I18n.zhEn(
                            opt + "起始行号，1-based，默认 1",
                            opt + "Line number to start reading from, 1-based, default: 1")
                    ),
                    "limit", Map.of(
                        "type", "integer",
                        "description", I18n.zhEn(
                            opt + "最大返回行数，默认不限制",
                            opt + "Maximum number of lines to return, default: unlimited")
                    ),
                    "prefer_source", Map.of(
                        "type", "boolean",
                        "description", I18n.zhEn(
                            opt + "优先从 -sources.jar 读取原始源码（含注释和原始变量名），不存在时回退到反编译。默认 true",
                            opt + "Prefer reading from -sources.jar (original source with comments). "
                                + "Falls back to decompilation if unavailable. Default: true")
                    ),
                    "force_decompile", Map.of(
                        "type", "boolean",
                        "description", I18n.zhEn(
                            opt + "强制 CFR 反编译，即使存在 sources JAR。默认 false",
                            opt + "Force CFR decompilation even if sources JAR exists. Default: false")
                    ),
                    "repository_url", Map.of(
                        "type", "string",
                        "description", I18n.zhEn(
                            opt + "指定 Maven 仓库 URL，覆盖已配置的仓库列表，如 'https://maven.aliyun.com/repository/public'",
                            opt + "Specific Maven repository URL, overrides configured repo list. "
                                + "Example: 'https://maven.aliyun.com/repository/public'")
                    ),
                    "force_remote", Map.of(
                        "type", "boolean",
                        "description", I18n.zhEn(
                            opt + "直接从远程下载到临时目录，完全绕过本地 Maven 缓存。默认 false",
                            opt + "Download directly from remote to temp dir, bypassing local cache. Default: false")
                    )
                ),
                List.of("group_id", "artifact_id", "version", "class_name"),
                false,
                null,
                null
            ))
            .build();
    }

    public McpSchema.CallToolResult handle(Map<String, Object> arguments) {
        try {
            MavenCoordinate coord = extractCoordinate(arguments);
            String className = requireString(arguments, "class_name");
            int offset = getIntOrDefault(arguments, "offset", 1);
            Integer limit = arguments.containsKey("limit")
                ? getIntOrDefault(arguments, "limit", Integer.MAX_VALUE) : null;

            if (offset < 1) {
                return errorResult("offset must be >= 1, got: " + offset);
            }
            if (limit != null && limit <= 0) {
                return errorResult("limit must be > 0, got: " + limit);
            }

            ResolutionConfig config = buildConfig(arguments);
            ResolutionResult result = resolver.resolveWithConfig(coord, config);

            String source;
            if (result.isSourceJar()) {
                source = decompiler.readSource(result.jarPath(), className);
                if (source == null) {
                    return errorResult("Class not found in " + coord + ": " + className);
                }
            } else {
                source = decompiler.decompileClass(
                    result.jarPath(), className, result.cacheNamespace());
                if (source == null) {
                    return errorResult("Class not found in " + coord + ": " + className);
                }
            }

            String formatted = formatWithLineNumbers(source, offset, limit);
            return successResult(formatted);

        } catch (Exception e) {
            log.error("jar_read failed", e);
            return errorResult(e.getMessage());
        }
    }

    // --- 共享工具方法（所有工具类共用） ---

    static MavenCoordinate extractCoordinate(Map<String, Object> args) {
        String groupId = requireString(args, "group_id");
        String artifactId = requireString(args, "artifact_id");
        String version = requireString(args, "version");
        return new MavenCoordinate(groupId, artifactId, version);
    }

    static String requireString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return val.toString();
    }

    static int getIntOrDefault(Map<String, Object> args, String key, int defaultValue) {
        Object val = args.get(key);
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": " + val);
        }
    }

    static boolean getBooleanOrDefault(Map<String, Object> args,
                                        String key, boolean defaultVal) {
        Object val = args.get(key);
        if (val == null) {
            return defaultVal;
        }
        if (val instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(val.toString());
    }

    static String getStringOrNull(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
    }

    static ResolutionConfig buildConfig(Map<String, Object> args) {
        return new ResolutionConfig(
            getBooleanOrDefault(args, "prefer_source", true),
            getBooleanOrDefault(args, "force_decompile", false),
            getStringOrNull(args, "repository_url"),
            getBooleanOrDefault(args, "force_remote", false)
        );
    }

    static McpSchema.CallToolResult successResult(String text) {
        return McpSchema.CallToolResult.builder()
            .content(List.of(new McpSchema.TextContent(text)))
            .build();
    }

    static McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
            .isError(true)
            .content(List.of(new McpSchema.TextContent("Error: " + message)))
            .build();
    }

    private String formatWithLineNumbers(String source, int startLine, Integer maxLines) {
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder();

        int endLine = maxLines != null
            ? Math.min(lines.length, startLine - 1 + maxLines)
            : lines.length;

        for (int i = startLine - 1; i < endLine; i++) {
            sb.append(String.format("%6d  %s\n", i + 1, lines[i]));
        }

        return sb.toString();
    }
}
