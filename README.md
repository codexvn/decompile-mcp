# jar-decompile-mcp

基于 SSE 协议的 MCP 服务器，为 coding agent 提供从 Maven 仓库 JAR 包中反编译和搜索 Java 源代码的能力。

## 快速开始

```bash
# 编译打包
mvn clean package -DskipTests

# 启动（默认端口 8080）
java -jar target/jar-decompile-mcp.jar

# 自定义端口 + 阿里云镜像
java -Dserver.port=9090 -Dmaven.repositories=https://maven.aliyun.com/repository/public -jar target/jar-decompile-mcp.jar
```

启动后输出可直接导入 MCP 客户端的配置：
```json
{"mcpServers":{"jar-decompile":{"url":"http://<你的IP>:8080/sse"}}}
```

## 三个工具

| 工具 | 说明 |
|---|---|
| `jar_read` | 反编译/读取 Maven JAR 中指定类的源码，cat -n 格式输出，支持分页 |
| `jar_glob` | 列出 JAR 中匹配 glob 模式的条目 |
| `jar_grep` | 在 JAR 所有反编译类中正则搜索，grep -n 格式输出 |

所有工具均支持：
- 源码包优先（`prefer_source`，默认 true）
- 强制反编译（`force_decompile`）
- 指定仓库 URL（`repository_url`）
- 强制远程下载（`force_remote`）

## Docker 部署

```bash
docker build -f docker/Dockerfile -t jar-decompile-mcp .
docker run --rm -p 8080:8080 -v ~/.m2/repository:/maven-repo jar-decompile-mcp
```

## 配置参数

| 参数 | 环境变量 | 系统属性 | 默认值 |
|---|---|---|---|
| 端口 | `SERVER_PORT` | `server.port` | `8080` |
| 绑定地址 | `SERVER_HOST` | `server.host` | `0.0.0.0` |
| 本地仓库 | `M2_REPO` | `maven.repo.local` | `~/.m2/repository` |
| 镜像仓库 | `M2_REPOSITORIES` | `maven.repositories` | Maven Central |
| 语言 | `MCP_LANG` | `mcp.lang` | `zh` |

## 技术栈

Java 21 · MCP SDK 1.1.2 · CFR 0.152 · Tomcat Embed 10.1 · Maven Resolver 1.9.22
