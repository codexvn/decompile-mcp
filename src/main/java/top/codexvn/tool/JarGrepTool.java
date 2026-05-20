package top.codexvn.tool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.codexvn.decompiler.DecompilerService;
import top.codexvn.resolver.JarResolver;
import top.codexvn.resolver.MavenCoordinate;
import top.codexvn.resolver.ResolutionConfig;
import top.codexvn.resolver.ResolutionResult;

public class JarGrepTool {

    private static final Logger log = LoggerFactory.getLogger(JarGrepTool.class);

    private final JarResolver resolver;
    private final DecompilerService decompiler;

    public JarGrepTool(JarResolver resolver, DecompilerService decompiler) {
        this.resolver = resolver;
        this.decompiler = decompiler;
    }

    public static McpSchema.Tool toolDefinition() {
        return McpSchema.Tool.builder()
            .name("jar_grep")
            .title("Search for a regex pattern in decompiled JAR classes")
            .description("Search for a regular expression pattern across all decompiled classes "
                + "in a Maven JAR. Each match is returned in grep -n format: "
                + "ClassName:lineNumber:matchedLine. "
                + "Use this to find usages of a method, annotation, string constant, "
                + "or any pattern across the entire JAR.")
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
                    "pattern", Map.of(
                        "type", "string",
                        "description", "Regular expression pattern to search for in decompiled source"
                    ),
                    "prefer_source", Map.of(
                        "type", "boolean",
                        "description", "Prefer searching sources JAR when available. Default: true."
                    ),
                    "force_decompile", Map.of(
                        "type", "boolean",
                        "description", "Always decompile and search .class files, "
                            + "even if sources JAR is available. Default: false."
                    ),
                    "repository_url", Map.of(
                        "type", "string",
                        "description", "Specific Maven repository URL to resolve from. "
                            + "Example: 'https://maven.aliyun.com/repository/public'"
                    ),
                    "force_remote", Map.of(
                        "type", "boolean",
                        "description", "Download directly from remote, bypass local cache. Default: false."
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
            MavenCoordinate coord = JarReadTool.extractCoordinate(arguments);
            String regex = JarReadTool.requireString(arguments, "pattern");

            Pattern pattern;
            try {
                pattern = Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                return JarReadTool.errorResult("Invalid regex pattern: " + e.getMessage());
            }

            ResolutionConfig config = JarReadTool.buildConfig(arguments);
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
                String[] lines = entry.getValue().split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (pattern.matcher(lines[i]).find()) {
                        matches.add(className + ":" + (i + 1) + ":" + lines[i]);
                    }
                }
            }

            if (matches.isEmpty()) {
                return JarReadTool.successResult(
                    "(no matches found for pattern: " + regex + ")");
            }

            return JarReadTool.successResult(String.join("\n", matches));

        } catch (IOException e) {
            log.error("jar_grep failed", e);
            return JarReadTool.errorResult(e.getMessage());
        } catch (Exception e) {
            log.error("jar_grep failed", e);
            return JarReadTool.errorResult(e.getMessage());
        }
    }
}
