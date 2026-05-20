package top.codexvn.server;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动时枚举网卡 IP，打印可直接导入 MCP 客户端的 JSON 配置。
 */
public final class NetworkConfig {

    private static final Logger log = LoggerFactory.getLogger(NetworkConfig.class);

    private NetworkConfig() {}

    /**
     * 打印所有可用 IP 对应的 SSE 端点和 MCP 客户端配置。
     */
    public static void print(int port) {
        Set<String> ips = enumerateAddresses();

        for (String ip : ips) {
            String url = "http://" + ip + ":" + port + "/sse";
            log.info("  SSE 端点: {}", url);
        }

        if (!ips.isEmpty()) {
            String defaultUrl = "http://" + ips.iterator().next() + ":" + port + "/sse";
            String json = "{\"mcpServers\":{\"jar-decompile\":{\"url\":\"" + defaultUrl + "\"}}}";
            log.info("  可直接导入的 MCP 客户端配置: {}", json);
        }

        // 始终打印 localhost
        log.info("  本地访问: http://localhost:{}/sse", port);
    }

    /**
     * 枚举所有运行中的网卡的 IPv4 地址，去重，优先外网地址。
     */
    static Set<String> enumerateAddresses() {
        Set<String> result = new LinkedHashSet<>();
        try {
            NetworkInterface.networkInterfaces()
                .filter(ni -> {
                    try { return ni.isUp(); } catch (Exception e) { return false; }
                })
                .flatMap(ni -> ni.inetAddresses())
                .filter(addr -> addr instanceof Inet4Address)
                .filter(addr -> !addr.isLoopbackAddress())
                .map(InetAddress::getHostAddress)
                .forEach(result::add);
        } catch (Exception e) {
            log.warn("枚举网卡 IP 失败: {}", e.getMessage());
        }
        return result;
    }
}
