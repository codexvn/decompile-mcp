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

            // Keep the main thread alive. The transport handles MCP protocol
            // on stdin/stdout in its own threads. On SIGTERM/SIGINT, the JVM
            // runs the shutdown hook for graceful cleanup.
            // Note: join() on the current thread blocks forever but does not
            // prevent JVM shutdown — the shutdown hook runs and the JVM halts.
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
