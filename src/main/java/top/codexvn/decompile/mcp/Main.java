package top.codexvn.decompile.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.codexvn.decompile.mcp.server.SseServer;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("Starting jar-decompile-mcp SSE server...");
        log.info("Java version: {}", System.getProperty("java.version"));

        try {
            SseServer sseServer = new SseServer();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    sseServer.stop();
                } catch (Exception e) {
                    log.error("Error stopping SSE server", e);
                }
            }));

            sseServer.start();

        } catch (Exception e) {
            log.error("Fatal error during server startup", e);
            System.exit(1);
        }
    }
}
