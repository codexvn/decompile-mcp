package top.codexvn.decompile.mcp.npm;

import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.codexvn.decompile.mcp.server.I18n;

public class NpmGrepTool {

    private static final Logger log = LoggerFactory.getLogger(NpmGrepTool.class);
    private final NpmResolver resolver = new NpmResolver();

    public static McpSchema.Tool toolDefinition() {
        String req = I18n.zhEn("(必填) ", "(required) ");
        return McpSchema.Tool.builder()
            .name("npm_grep")
            .title(I18n.zhEn("在 npm 包中搜索正则表达式", "Search regex in an npm package"))
            .description(I18n.zhEn("在 npm 包的所有文件中搜索正则表达式。返回格式：文件路径:行号:匹配行。",
                "Search a regex across all files in an npm package. Output: path:line:match."))
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(
                "package", Map.of("type", "string", "description", req + I18n.zhEn("npm 包名", "npm package name")),
                "version", Map.of("type", "string", "description", req + I18n.zhEn("版本号", "Version")),
                "pattern", Map.of("type", "string", "description", req + I18n.zhEn("正则表达式", "Regex pattern"))
            ), List.of("package", "version", "pattern"), false, null, null))
            .build();
    }

    public McpSchema.CallToolResult handle(Map<String, Object> arguments) {
        try {
            String pkg = require(arguments, "package");
            String ver = require(arguments, "version");
            String regex = require(arguments, "pattern");

            Pattern pattern;
            try { pattern = Pattern.compile(regex); }
            catch (PatternSyntaxException e) { return err("Invalid regex: " + e.getMessage()); }

            Path dir = resolver.resolve(pkg, ver);
            List<String> matches = new ArrayList<>();

            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        String[] lines = Files.readString(file).split("\n", -1);
                        for (int i = 0; i < lines.length; i++) {
                            if (pattern.matcher(lines[i]).find()) {
                                matches.add(dir.relativize(file) + ":" + (i + 1) + ":" + lines[i]);
                            }
                        }
                    } catch (Exception ignored) {}
                });
            }
            log.info("npm_grep {}@{}:/{}/ -> {} matches", pkg, ver, regex, matches.size());
            return ok(matches.isEmpty() ? "(no matches)" : String.join("\n", matches));
        } catch (Exception e) {
            log.error("npm_grep failed: {} {}", arguments, e.getMessage());
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
