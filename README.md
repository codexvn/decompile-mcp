# decompile-mcp

基于 Streamable HTTP 协议的 MCP 服务器，为 coding agent 提供从 Maven/npm/PyPI 包中读取和搜索源代码的能力。共 9 个工具（jar/npm/pip × read/glob/grep）。

## 快速开始

```bash
# 编译打包
mvn clean package -DskipTests

# 启动（默认端口 8080）
java -jar target/decompile-mcp.jar

# 自定义端口 + 阿里云镜像
java -Dserver.port=9090 -Dmaven.repositories=https://maven.aliyun.com/repository/public -jar target/decompile-mcp.jar
```

启动后输出可直接导入 MCP 客户端的配置：
```json
{"mcpServers":{"decompile-mcp":{"url":"http://<你的IP>:8080/mcp"}}}
```

## 九个工具

| 生态 | read | glob | grep |
|---|---|---|---|
| Maven JAR | `jar_read` | `jar_glob` | `jar_grep` |
| npm | `npm_read` | `npm_glob` | `npm_grep` |
| PyPI | `pip_read` | `pip_glob` | `pip_grep` |

jar 工具支持反编译（CFR）+ 源码包优先；npm/pip 工具直接读取包内源码。
jar 工具额外支持：`prefer_source`、`force_decompile`、`repository_url`、`force_remote`。

## Docker 部署

```bash
docker build -f docker/Dockerfile -t decompile-mcp .
docker run --rm -p 8080:8080 -v ~/.m2/repository:/maven-repo decompile-mcp
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
