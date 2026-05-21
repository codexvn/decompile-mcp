package top.codexvn.decompile.mcp.npm;

import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.codexvn.decompile.mcp.server.I18n;

public class NpmGlobTool {

    private static final Logger log = LoggerFactory.getLogger(NpmGlobTool.class);
    private final NpmResolver resolver = new NpmResolver();

    public static McpSchema.Tool toolDefinition() {
        String req = I18n.zhEn("(必填) ", "(required) ");
        return McpSchema.Tool.builder()
            .name("npm_glob")
            .title(I18n.zhEn("列出 npm 包中匹配 glob 模式的条目", "List entries in an npm package matching a glob"))
            .description(I18n.zhEn("列出 npm 包中匹配 glob 模式的条目。", "List files inside an npm package matching a glob pattern."))
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(
                "package", Map.of("type", "string", "description", req + I18n.zhEn("npm 包名", "npm package name")),
                "version", Map.of("type", "string", "description", req + I18n.zhEn("版本号", "Version")),
                "pattern", Map.of("type", "string", "description", req + I18n.zhEn("Glob 模式，如 '**/*.js'", "Glob pattern, e.g. '**/*.js'"))
            ), List.of("package", "version", "pattern"), false, null, null))
            .build();
    }

    public McpSchema.CallToolResult handle(Map<String, Object> arguments) {
        try {
            String pkg = require(arguments, "package");
            String ver = require(arguments, "version");
            String pattern = require(arguments, "pattern");
            Path dir = resolver.resolve(pkg, ver);

            List<String> matches = new ArrayList<>();
            PathMatcher matcher = dir.getFileSystem().getPathMatcher("glob:" + pattern);
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> matcher.matches(p)).forEach(p -> {
                    String entry = dir.relativize(p).toString().replace('\\', '/');
                    if (!entry.isEmpty()) matches.add(entry);
                });
            }
            Collections.sort(matches);
            log.info("npm_glob {}@{}:{} -> {} entries", pkg, ver, pattern, matches.size());
            return ok(matches.isEmpty() ? "(no matches)" : String.join("\n", matches));
        } catch (Exception e) {
            log.error("npm_glob failed: {} {}", arguments, e.getMessage());
            return err(e.getMessage());
        }
    }

    private static String require(Map<String, Object> a, String k) {
        Object v = a.get(k); if (v == null) throw new IllegalArgumentException("Missing: " + k);
        return v.toString();
    }
    private static McpSchema.CallToolResult ok(String t) { return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(t))).build(); }
    private static McpSchema.CallToolResult err(String m) { return McpSchema.CallToolResult.builder().isError(true).content(List.of(new McpSchema.TextContent("Error: " + m))).build(); }
}
