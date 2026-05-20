package top.codexvn.server;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.codexvn.decompiler.DecompilerService;
import top.codexvn.resolver.JarResolver;
import top.codexvn.tool.JarGlobTool;
import top.codexvn.tool.JarGrepTool;
import top.codexvn.tool.JarReadTool;

public class JarDecompileMcpServer {

    private static final Logger log = LoggerFactory.getLogger(JarDecompileMcpServer.class);

    public static final String VERSION = "1.0.0";

    private final McpSyncServer server;
    private final JarResolver resolver;
    private final DecompilerService decompiler;

    public JarDecompileMcpServer(McpServerTransportProvider transport) {
        this.resolver = new JarResolver();
        this.decompiler = new DecompilerService();

        JarReadTool jarRead = new JarReadTool(resolver, decompiler);
        JarGlobTool jarGlob = new JarGlobTool(resolver);
        JarGrepTool jarGrep = new JarGrepTool(resolver, decompiler);

        this.server = McpServer.sync(transport)
            .serverInfo("jar-decompile-mcp", VERSION)
            .capabilities(McpSchema.ServerCapabilities.builder()
                .tools(true)
                .build())
            .toolCall(JarReadTool.toolDefinition(),
                (exchange, request) -> jarRead.handle(request.arguments()))
            .toolCall(JarGlobTool.toolDefinition(),
                (exchange, request) -> jarGlob.handle(request.arguments()))
            .toolCall(JarGrepTool.toolDefinition(),
                (exchange, request) -> jarGrep.handle(request.arguments()))
            .build();

        log.info("Registered 3 tools: jar_read, jar_glob, jar_grep");
    }

    public McpSyncServer getServer() {
        return server;
    }
}
