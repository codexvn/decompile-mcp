package top.codexvn.decompile.mcp.pip;

import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.codexvn.decompile.mcp.server.I18n;

public class PipReadTool {

    private static final Logger log = LoggerFactory.getLogger(PipReadTool.class);
    private final PipResolver resolver = new PipResolver();

    public static McpSchema.Tool toolDefinition() {
        String req = I18n.zhEn("(必填) ", "(required) ");

        return McpSchema.Tool.builder()
            .name("pip_read")
            .title(I18n.zhEn("读取 PyPI 包中的文件", "Read a file from a PyPI package"))
            .description(I18n.zhEn(
                "下载并解压 PyPI 包（.tar.gz），读取指定文件的源码。输出 cat -n 风格带行号。",
                "Download and extract a PyPI package (.tar.gz), read the source of a specified file. "
                    + "Output in cat -n format with line numbers."))
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "package", Map.of("type", "string", "description",
                        req + I18n.zhEn("PyPI 包名，如 'requests'", req + "PyPI package name, e.g. 'requests'")),
                    "version", Map.of("type", "string", "description",
                        req + I18n.zhEn("版本号，如 '2.31.0'", req + "Version, e.g. '2.31.0'")),
                    "file_path", Map.of("type", "string", "description",
                        req + I18n.zhEn("包内文件路径，如 'requests/api.py'", req + "File path inside package, e.g. 'requests/api.py'"))
                ),
                List.of("package", "version", "file_path"),
                false, null, null
            ))
            .build();
    }

    public McpSchema.CallToolResult handle(Map<String, Object> arguments) {
        try {
            String pkg = require(arguments, "package");
            String ver = require(arguments, "version");
            String filePath = require(arguments, "file_path");

            Path extracted = resolver.resolve(pkg, ver);
            Path file = extracted.resolve(filePath).normalize();

            if (!file.startsWith(extracted) || !Files.isRegularFile(file)) {
                return err("File not found in " + pkg + "==" + ver + ": " + filePath);
            }

            String source = Files.readString(file);
            int lineCount = (int) source.lines().count();
            log.info("pip_read {}=={}:{} -> {} lines", pkg, ver, filePath, lineCount);
            return ok(formatWithLineNumbers(source));

        } catch (Exception e) {
            log.error("pip_read failed: {} {}", arguments, e.getMessage());
            return err(e.getMessage());
        }
    }

    private static String require(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) throw new IllegalArgumentException("Missing: " + key);
        return v.toString();
    }

    private static String formatWithLineNumbers(String source) {
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%6d  %s\n", i + 1, lines[i]));
        }
        return sb.toString();
    }

    private static McpSchema.CallToolResult ok(String text) {
        return McpSchema.CallToolResult.builder()
            .content(List.of(new McpSchema.TextContent(text))).build();
    }

    private static McpSchema.CallToolResult err(String msg) {
        return McpSchema.CallToolResult.builder().isError(true)
            .content(List.of(new McpSchema.TextContent("Error: " + msg))).build();
    }
}
