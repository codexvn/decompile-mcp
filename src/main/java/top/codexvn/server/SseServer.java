package top.codexvn.server;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
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

        // SSE 传输提供者——它本身就是一个 Servlet
        var transport = HttpServletSseServerTransportProvider.builder()
            .jsonMapper(new JacksonMcpJsonMapper(new JsonMapper()))
            .baseUrl("http://" + host + ":" + port)
            .messageEndpoint("/message")
            .sseEndpoint("/sse")
            .keepAliveInterval(Duration.ofSeconds(30))
            .build();

        // 基于 SSE 传输构建 MCP 服务器
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

        // 打印网卡 IP 及可直接导入的 MCP 客户端配置
        NetworkConfig.print(port);

        tomcat.start();
        log.info("SSE server started, accepting connections");
        tomcat.getServer().await();
    }

    public void stop() throws Exception {
        log.info("Stopping SSE server...");
        mcpServer.getServer().closeGracefully();
        tomcat.stop();
        log.info("SSE server stopped");
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
