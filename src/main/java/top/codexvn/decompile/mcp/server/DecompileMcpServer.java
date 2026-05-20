package top.codexvn.decompile.mcp.server;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.codexvn.decompile.mcp.jar.decompiler.DecompilerService;
import top.codexvn.decompile.mcp.npm.NpmGlobTool;
import top.codexvn.decompile.mcp.npm.NpmGrepTool;
import top.codexvn.decompile.mcp.npm.NpmReadTool;
import top.codexvn.decompile.mcp.pip.PipGlobTool;
import top.codexvn.decompile.mcp.pip.PipGrepTool;
import top.codexvn.decompile.mcp.pip.PipReadTool;
import top.codexvn.decompile.mcp.jar.resolver.JarResolver;
import top.codexvn.decompile.mcp.jar.JarGlobTool;
import top.codexvn.decompile.mcp.jar.JarGrepTool;
import top.codexvn.decompile.mcp.jar.JarReadTool;

public class DecompileMcpServer {

    private static final Logger log = LoggerFactory.getLogger(DecompileMcpServer.class);

    public static final String VERSION = "1.0.0";

    private final McpSyncServer server;

    public DecompileMcpServer(McpStreamableServerTransportProvider transport) {
        JarResolver resolver = new JarResolver();
        DecompilerService decompiler = new DecompilerService();

        JarReadTool jarRead = new JarReadTool(resolver, decompiler);
        JarGlobTool jarGlob = new JarGlobTool(resolver);
        JarGrepTool jarGrep = new JarGrepTool(resolver, decompiler);

        NpmReadTool npmRead = new NpmReadTool();
        NpmGlobTool npmGlob = new NpmGlobTool();
        NpmGrepTool npmGrep = new NpmGrepTool();

        PipReadTool pipRead = new PipReadTool();
        PipGlobTool pipGlob = new PipGlobTool();
        PipGrepTool pipGrep = new PipGrepTool();

        this.server = McpServer.sync(transport)
            .serverInfo("decompile-mcp", VERSION)
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .build();

        server.addTool(new McpServerFeatures.SyncToolSpecification(
            JarReadTool.toolDefinition(), (ex, req) -> jarRead.handle(req.arguments())));
        server.addTool(new McpServerFeatures.SyncToolSpecification(
            JarGlobTool.toolDefinition(), (ex, req) -> jarGlob.handle(req.arguments())));
        server.addTool(new McpServerFeatures.SyncToolSpecification(
            JarGrepTool.toolDefinition(), (ex, req) -> jarGrep.handle(req.arguments())));

        server.addTool(new McpServerFeatures.SyncToolSpecification(
            NpmReadTool.toolDefinition(), (ex, req) -> npmRead.handle(req.arguments())));
        server.addTool(new McpServerFeatures.SyncToolSpecification(
            NpmGlobTool.toolDefinition(), (ex, req) -> npmGlob.handle(req.arguments())));
        server.addTool(new McpServerFeatures.SyncToolSpecification(
            NpmGrepTool.toolDefinition(), (ex, req) -> npmGrep.handle(req.arguments())));

        server.addTool(new McpServerFeatures.SyncToolSpecification(
            PipReadTool.toolDefinition(), (ex, req) -> pipRead.handle(req.arguments())));
        server.addTool(new McpServerFeatures.SyncToolSpecification(
            PipGlobTool.toolDefinition(), (ex, req) -> pipGlob.handle(req.arguments())));
        server.addTool(new McpServerFeatures.SyncToolSpecification(
            PipGrepTool.toolDefinition(), (ex, req) -> pipGrep.handle(req.arguments())));

        log.info("Registered 9 tools: jar/npm/pip × read/glob/grep");
    }

    public McpSyncServer getServer() {
        return server;
    }
}
