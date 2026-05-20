package top.codexvn.decompile.mcp.server;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.codexvn.decompile.mcp.maven.decompiler.DecompilerService;
import top.codexvn.decompile.mcp.maven.MavenGlobTool;
import top.codexvn.decompile.mcp.maven.MavenGrepTool;
import top.codexvn.decompile.mcp.maven.MavenReadTool;
import top.codexvn.decompile.mcp.maven.resolver.MavenResolver;
import top.codexvn.decompile.mcp.npm.NpmGlobTool;
import top.codexvn.decompile.mcp.npm.NpmGrepTool;
import top.codexvn.decompile.mcp.npm.NpmReadTool;
import top.codexvn.decompile.mcp.pip.PipGlobTool;
import top.codexvn.decompile.mcp.pip.PipGrepTool;
import top.codexvn.decompile.mcp.pip.PipReadTool;

public class DecompileMcpServer {

    private static final Logger log = LoggerFactory.getLogger(DecompileMcpServer.class);

    public static final String VERSION = "1.0.0";

    private final McpSyncServer server;

    public DecompileMcpServer(McpStreamableServerTransportProvider transport) {
        MavenResolver resolver = new MavenResolver();
        DecompilerService decompiler = new DecompilerService();

        MavenReadTool mavenRead = new MavenReadTool(resolver, decompiler);
        MavenGlobTool mavenGlob = new MavenGlobTool(resolver);
        MavenGrepTool mavenGrep = new MavenGrepTool(resolver, decompiler);

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
            MavenReadTool.toolDefinition(), (ex, req) -> mavenRead.handle(req.arguments())));
        server.addTool(new McpServerFeatures.SyncToolSpecification(
            MavenGlobTool.toolDefinition(), (ex, req) -> mavenGlob.handle(req.arguments())));
        server.addTool(new McpServerFeatures.SyncToolSpecification(
            MavenGrepTool.toolDefinition(), (ex, req) -> mavenGrep.handle(req.arguments())));

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

        log.info("Registered 9 tools: maven/npm/pip × read/glob/grep");
    }

    public McpSyncServer getServer() {
        return server;
    }
}
