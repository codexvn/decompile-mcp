package top.codexvn.tool;

import java.util.List;
import java.util.Map;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.codexvn.decompiler.DecompilerService;
import top.codexvn.resolver.JarResolver;
import top.codexvn.resolver.MavenCoordinate;
import top.codexvn.resolver.ResolutionConfig;
import top.codexvn.resolver.ResolutionResult;

public class JarReadTool {

    private static final Logger log = LoggerFactory.getLogger(JarReadTool.class);

    private final JarResolver resolver;
    private final DecompilerService decompiler;

    public JarReadTool(JarResolver resolver, DecompilerService decompiler) {
        this.resolver = resolver;
        this.decompiler = decompiler;
    }

    public static McpSchema.Tool toolDefinition() {
        return McpSchema.Tool.builder()
            .name("jar_read")
            .title("Read and decompile a class from a Maven JAR")
            .description("Decompile a specific class from a Maven JAR and return its Java source. "
                + "Output is formatted with line numbers in cat -n style. "
                + "Use this to inspect the decompiled source of a class from any Maven artifact.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "group_id", Map.of(
                        "type", "string",
                        "description", "Maven group ID, e.g. 'com.google.guava'"
                    ),
                    "artifact_id", Map.of(
                        "type", "string",
                        "description", "Maven artifact ID, e.g. 'guava'"
                    ),
                    "version", Map.of(
                        "type", "string",
                        "description", "Version string, e.g. '33.0.0-jre'"
                    ),
                    "class_name", Map.of(
                        "type", "string",
                        "description", "Fully qualified class name, e.g. 'com.google.common.collect.Lists'"
                    ),
                    "offset", Map.of(
                        "type", "integer",
                        "description", "Line number to start reading from (1-based, default: 1)"
                    ),
                    "limit", Map.of(
                        "type", "integer",
                        "description", "Maximum number of lines to return (default: unlimited)"
                    ),
                    "prefer_source", Map.of(
                        "type", "boolean",
                        "description", "Prefer reading from -sources.jar when available. "
                            + "Sources JARs contain original .java files with comments. Default: true."
                    ),
                    "force_decompile", Map.of(
                        "type", "boolean",
                        "description", "Always decompile from .class files, "
                            + "even if a sources JAR is available. Default: false."
                    ),
                    "repository_url", Map.of(
                        "type", "string",
                        "description", "Specific Maven repository URL to resolve from. "
                            + "Overrides configured repository priority list. "
                            + "Example: 'https://maven.aliyun.com/repository/public'"
                    ),
                    "force_remote", Map.of(
                        "type", "boolean",
                        "description", "Download directly from remote to a temp location, "
                            + "completely bypassing the local Maven cache. Default: false."
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

            ResolutionConfig config = buildConfig(arguments);
            ResolutionResult result = resolver.resolveWithConfig(coord, config);

            String source;
            if (result.isSourceJar()) {
                source = decompiler.readSource(result.jarPath(), className);
                if (source == null) {
                    return errorResult("Class not found in sources JAR: " + className);
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
