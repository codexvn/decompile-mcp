package top.codexvn.decompile.mcp.server;

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
     * 打印所有可用 IP 对应的 MCP 端点和客户端配置。
     */
    public static void print(int port) {
        Set<String> ips = enumerateAddresses();

        log.info("  以下 MCP 端点可直接导入客户端:");
        for (String ip : ips) {
            String url = "http://" + ip + ":" + port + "/mcp";
            String json = "{\"mcpServers\":{\"decompile-mcp\":{\"url\":\"" + url + "\"}}}";
            log.info("    {}", json);
        }

        // 始终打印 localhost
        log.info("  本地访问: http://localhost:{}/mcp", port);
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
