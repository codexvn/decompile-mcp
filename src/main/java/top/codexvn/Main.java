package top.codexvn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.codexvn.server.JarDecompileMcpServer;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("Starting jar-decompile-mcp server...");
        log.info("Java version: {}", System.getProperty("java.version"));

        String repo = System.getProperty("maven.repo.local",
            System.getenv().getOrDefault("M2_REPO",
                System.getProperty("user.home") + "/.m2/repository"));
        log.info("Maven local repo: {}", repo);

        try {
            JarDecompileMcpServer app = new JarDecompileMcpServer();
            var server = app.getServer();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down jar-decompile-mcp server");
                server.closeGracefully();
            }));

            log.info("Server ready, waiting for requests on stdin...");

            // 阻塞主线程保持 JVM 存活。MCP 协议传输在后台线程中处理
            // stdin/stdout 的读写。收到 SIGTERM/SIGINT 时，JVM 会先执行
            // 关闭钩子（调用 server.closeGracefully()）再退出。
            // join() 阻塞当前线程但不会阻止 JVM 关闭——关闭钩子仍然会执行。
            Thread.currentThread().join();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Server interrupted, exiting");
        } catch (Exception e) {
            log.error("Fatal error during server startup", e);
            System.exit(1);
        }
    }
}
