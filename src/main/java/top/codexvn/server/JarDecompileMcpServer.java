package top.codexvn.server;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import top.codexvn.decompiler.DecompilerService;
import top.codexvn.resolver.JarResolver;
import top.codexvn.tool.JarGlobTool;
import top.codexvn.tool.JarGrepTool;
import top.codexvn.tool.JarReadTool;

public class JarDecompileMcpServer {

    private static final Logger log = LoggerFactory.getLogger(JarDecompileMcpServer.class);

    private final McpSyncServer server;

    public JarDecompileMcpServer() {
        JarResolver resolver = new JarResolver();
        DecompilerService decompiler = new DecompilerService();

        JarReadTool jarRead = new JarReadTool(resolver, decompiler);
        JarGlobTool jarGlob = new JarGlobTool(resolver);
        JarGrepTool jarGrep = new JarGrepTool(resolver, decompiler);

        var jsonMapper = new JacksonMcpJsonMapper(new JsonMapper());
        var transport = new StdioServerTransportProvider(jsonMapper);

        this.server = McpServer.sync(transport)
            .serverInfo("jar-decompile-mcp", "1.0.0")
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
