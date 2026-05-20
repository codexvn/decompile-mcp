package top.codexvn.tool;

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
import top.codexvn.resolver.JarResolver;
import top.codexvn.resolver.MavenCoordinate;

public class JarGlobTool {

    private static final Logger log = LoggerFactory.getLogger(JarGlobTool.class);

    private final JarResolver resolver;

    public JarGlobTool(JarResolver resolver) {
        this.resolver = resolver;
    }

    public static McpSchema.Tool toolDefinition() {
        return McpSchema.Tool.builder()
            .name("jar_glob")
            .title("List entries in a Maven JAR matching a glob pattern")
            .description("List entries inside a Maven JAR matching a glob pattern. "
                + "Returns a sorted list of matching entry paths. "
                + "Use this to discover class files, resources, or any JAR content.")
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
                        "description", "Glob pattern to match against JAR entry paths, e.g. '**/*Service*.class'"
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
            String pattern = JarReadTool.requireString(arguments, "pattern");

            Path jarPath = resolver.resolve(coord);
            List<String> matches = globJar(jarPath, pattern);
            Collections.sort(matches);

            if (matches.isEmpty()) {
                return JarReadTool.successResult("(no entries matched pattern: " + pattern + ")");
            }

            return JarReadTool.successResult(String.join("\n", matches));

        } catch (Exception e) {
            log.error("jar_glob failed", e);
            return JarReadTool.errorResult(e.getMessage());
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
