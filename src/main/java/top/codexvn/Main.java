package top.codexvn;

import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.codexvn.server.JarDecompileMcpServer;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("Starting jar-decompile-mcp server...");
        log.info("Java version: {}", System.getProperty("java.version"));

        try {
            JarDecompileMcpServer app = new JarDecompileMcpServer();
            var server = app.getServer();

            CountDownLatch shutdownLatch = new CountDownLatch(1);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down jar-decompile-mcp server");
                server.closeGracefully();
                shutdownLatch.countDown();
            }));

            log.info("Server ready, waiting for requests on stdin...");

            // 阻塞主线程等待关闭信号。使用 CountDownLatch 而非 join()，
            // 因为 join() 会导致主线程等待自身——永远无法自行退出。
            // 关闭钩子在收到 SIGTERM/SIGINT 时调用 countDown() 唤醒主线程。
            // 注意：不能使用 System.in.read() 等待 stdin EOF，因为 MCP
            // 传输层也在读取 stdin，两者抢数据会导致协议混乱。
            shutdownLatch.await();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Server interrupted, exiting");
        } catch (Exception e) {
            log.error("Fatal error during server startup", e);
            System.exit(1);
        }
    }
}
