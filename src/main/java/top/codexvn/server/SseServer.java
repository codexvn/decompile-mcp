package top.codexvn.server;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import java.time.Duration;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

public class SseServer {

    private static final Logger log = LoggerFactory.getLogger(SseServer.class);

    private final int port;
    private final String host;
    private final Tomcat tomcat;
    private final JarDecompileMcpServer mcpServer;

    public SseServer() {
        this(defaultPort(), defaultHost());
    }

    public SseServer(int port, String host) {
        this.port = port;
        this.host = host;

        // Streamable HTTP 传输（MCP 2024-11-05 规范），单端点 /mcp
        var transport = HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(new JacksonMcpJsonMapper(new JsonMapper()))
            .mcpEndpoint("/mcp")
            .keepAliveInterval(Duration.ofSeconds(30))
            .build();

        // 基于 Streamable HTTP 传输构建 MCP 服务器
        this.mcpServer = new JarDecompileMcpServer(transport);

        // 组装嵌入式 Tomcat
        this.tomcat = new Tomcat();
        tomcat.setPort(port);
        tomcat.getConnector().setProperty("address", host);

        Context ctx = tomcat.addContext("", null);

        // 请求日志 + traceId Valve
        ctx.getPipeline().addValve(new RequestLoggingValve());

        Wrapper wrapper = Tomcat.addServlet(ctx, "mcp", transport);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/*", "mcp");
    }

    public void start() throws Exception {
        log.info("========================================");
        log.info("  jar-decompile-mcp v{}", JarDecompileMcpServer.VERSION);
        log.info("  绑定地址: {}:{}", host, port);
        log.info("========================================");

        NetworkConfig.print(port);

        tomcat.start();
        log.info("MCP server started (streamable HTTP), accepting connections");
        tomcat.getServer().await();
    }

    public void stop() throws Exception {
        log.info("Stopping MCP server...");
        mcpServer.getServer().closeGracefully();
        tomcat.stop();
        log.info("MCP server stopped");
    }

    private static int defaultPort() {
        String prop = System.getProperty("server.port");
        if (prop != null && !prop.isBlank()) {
            return Integer.parseInt(prop.trim());
        }
        String env = System.getenv("SERVER_PORT");
        if (env != null && !env.isBlank()) {
            return Integer.parseInt(env.trim());
        }
        return 8080;
    }

    private static String defaultHost() {
        String prop = System.getProperty("server.host");
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String env = System.getenv("SERVER_HOST");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return "0.0.0.0";
    }
}
