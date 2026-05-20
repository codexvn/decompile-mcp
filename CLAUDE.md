# decompile-mcp

基于 Streamable HTTP 协议的 MCP 服务器，为 coding agent 提供从 Maven/npm/PyPI 包中读取和搜索源代码的能力。提供 9 个工具（`maven_read/glob/grep`、`npm_read/glob/grep`、`pip_read/glob/grep`），agent 可以像操作本地文件系统一样探索第三方依赖的实现。

支持多客户端并发连接，可部署在服务器上作为常驻 HTTP 服务。

## 技术栈

| 组件 | 库 | 版本 |
|---|---|---|
| 语言 | Java 21+ | — |
| MCP 协议（Streamable HTTP） | `io.modelcontextprotocol.sdk:mcp` | 1.1.2 |
| HTTP 服务器 | Tomcat Embed 10.1 | 12.0.16 |
| 反编译器 | CFR (`org.benf:cfr`) | 0.152 |
| Maven 坐标解析 | `org.apache.maven.resolver:maven-resolver-*` | 1.9.22 |
| 日志 | SLF4J + Logback | 2.0.16 / 1.5.12 |
| 打包 | maven-assembly-plugin（fat JAR） | 3.7.1 |

无 Spring Boot、无 Quarkus，嵌入 Tomcat 10.1 提供 HTTP 服务，通过 Streamable HTTP 协议与 MCP 客户端通信。

## 项目结构

```
src/main/java/top/codexvn/decompile/mcp/
├── Main.java                       — 入口点，启动 HTTP 服务器
├── server/
│   ├── SseServer.java              — Tomcat 嵌入 + Streamable HTTP Servlet 注册 + 端口配置
│   ├── DecompileMcpServer.java     — MCP 服务器组装：注入服务、注册工具
│   ├── CacheConfig.java            — 统一缓存根目录配置
│   ├── I18n.java                   — 中英文切换工具
│   ├── NetworkConfig.java          — 网卡 IP 检测 + MCP 客户端配置打印
│   └── RequestLoggingValve.java    — 请求日志 + traceId
├── maven/
│   ├── resolver/
│   │   ├── MavenCoordinate.java    — record: groupId, artifactId, version + 路径工具方法
│   │   ├── RepositoryConfig.java   — 工具类：从系统属性/环境变量加载 Maven 仓库列表
│   │   ├── ResolutionConfig.java   — record: 工具传入的解析选项（preferSource 等）
│   │   ├── ResolutionResult.java   — record: 解析结果（jarPath, isSourceJar, cacheNamespace）
│   │   └── MavenResolver.java      — Maven JAR 解析：多仓库优先级、源码包优先、强制远程、缓存隔离
│   ├── decompiler/
│   │   └── DecompilerService.java  — CFR 反编译封装 + 源码包直读 + 内存/磁盘二级缓存
│   ├── MavenReadTool.java          — maven_read 工具：反编译/读源码，cat -n 格式输出
│   ├── MavenGlobTool.java          — maven_glob 工具：列出 JAR 条目匹配 glob 模式
│   └── MavenGrepTool.java          — maven_grep 工具：正则搜索，grep -n 格式输出
├── npm/
│   ├── NpmResolver.java            — npm 包下载 + 多镜像优先级
│   ├── NpmReadTool.java            — npm_read 工具
│   ├── NpmGlobTool.java            — npm_glob 工具
│   └── NpmGrepTool.java            — npm_grep 工具
└── pip/
    ├── PipResolver.java            — PyPI 包下载 + 多镜像优先级
    ├── PipReadTool.java            — pip_read 工具
    ├── PipGlobTool.java            — pip_glob 工具
    └── PipGrepTool.java            — pip_grep 工具
```

## 九个 MCP 工具

### Maven 工具（maven_read / maven_glob / maven_grep）

四个可选参数用于控制解析行为：

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `prefer_source` | boolean | `true` | 优先使用 `-sources.jar`（含原始注释和变量名） |
| `force_decompile` | boolean | `false` | 强制 CFR 反编译，即使存在 sources JAR |
| `repository_url` | string | null | 指定远程仓库 URL，覆盖配置的仓库列表 |
| `force_remote` | boolean | `false` | 绕过本地缓存，直接下载到临时目录 |

#### maven_read — 反编译指定类

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `group_id` | string | 是 | Maven groupId，如 `com.google.guava` |
| `artifact_id` | string | 是 | Maven artifactId，如 `guava` |
| `version` | string | 是 | 版本号，如 `33.0.0-jre` |
| `class_name` | string | 是 | 全限定类名，如 `com.google.common.collect.Lists` |
| `offset` | integer | 否 | 起始行号（1-based，默认 1） |
| `limit` | integer | 否 | 最大返回行数（默认不限） |

输出：cat -n 风格，6 位右对齐行号 + 源码内容。

#### maven_glob — 列出 JAR 条目

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `group_id` | string | 是 | Maven groupId |
| `artifact_id` | string | 是 | Maven artifactId |
| `version` | string | 是 | 版本号 |
| `pattern` | string | 是 | Glob 模式，如 `**/*Service*.class` |

注意：若使用 sources JAR（默认），条目将以 `.java` 结尾而非 `.class`。使用 `force_decompile: true` 可恢复 `.class` 后缀。

输出：按字母排序的匹配条目路径列表。

#### maven_grep — 正则搜索

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `group_id` | string | 是 | Maven groupId |
| `artifact_id` | string | 是 | Maven artifactId |
| `version` | string | 是 | 版本号 |
| `pattern` | string | 是 | Java 正则表达式 |

输出：`类名:行号:匹配行` 格式（与 `grep -n` 一致）。

### npm 工具（npm_read / npm_glob / npm_grep）

三个工具分别读取、列出、搜索 npm 包中的文件。参数均为 `package`、`version`、`file_path`（read）/ `pattern`（glob/grep）。

### pip 工具（pip_read / pip_glob / pip_grep）

三个工具分别读取、列出、搜索 PyPI 包（.tar.gz 源码包）中的文件。参数均为 `package`、`version`、`file_path`（read）/ `pattern`（glob/grep）。

## 关键设计决策

### 1. 选择 Tomcat Embed 10.1 嵌入而非 Spring Boot

- Tomcat Embed 10.1 仅 ~3MB，fat JAR 总大小可控
- 直接使用 `HttpServletStreamableServerTransportProvider`（官方 SDK 内置），无需适配层
- 代码量极小——`SseServer.java` 仅 ~90 行完成 HTTP 服务器组装

### 2. Streamable HTTP 协议与并发模型

```
客户端A → GET /mcp → SSE 流 → Tool Handler
客户端B → GET /mcp → SSE 流 → Tool Handler
```

- Tomcat 线程池处理 HTTP 连接和请求分发
- 每个 MCP 客户端独立 SSE session，SDK 自动管理 session 生命周期
- `DecompilerService` 缓存（Caffeine + 磁盘二级缓存）跨 session 共享，同一 JAR 只反编译一次
- 工具 handler 无状态，天然并发安全

### 3. 选择 CFR 作为反编译器

CFR (Class File Reader) 是 Java 反编译的事实标准：
- 对现代 Java 21+ 特性（record、sealed class、pattern matching）支持最好
- 控制流还原准确率高（~98.7% 方法体正确率）
- API 简洁：`CfrDriver.Builder().withOutputSink(...).build().analyse(paths)`

### 4. 源码包优先策略

默认优先使用 `-sources.jar`，包含原始注释和变量名，质量远优于反编译结果。解析流程：
1. 若 `prefer_source=true` 且 `force_decompile=false` → 尝试解析 `-sources.jar`
2. 找到 sources JAR → 直接读取 `.java` 文件（无需反编译）
3. 未找到 → 回退到主 JAR + CFR 反编译

### 5. maven_read 单类快速提取

`DecompilerService.decompileClass()` 采用两阶段策略：
1. 检查缓存 → 命中则直接返回
2. 未命中 → 从 JAR 提取目标 `.class` 到临时文件 → CFR 仅反编译该文件 → 删除临时文件

避免全量反编译（如 Guava 有 620 个类，全量反编译需 ~10 秒，单类提取 <1 秒）。

### 6. 多镜像优先级（统一模式）

Maven、npm、PyPI 均支持通过系统属性或环境变量指定多个镜像，按优先级顺序回退：

| 生态 | 系统属性 | 环境变量 | 默认回退 |
|---|---|---|---|
| Maven | `maven.repositories` | `M2_REPOSITORIES` | `https://repo1.maven.org/maven2/` |
| npm | `npm.mirrors` | `NPM_MIRRORS` | `https://registry.npmjs.org` |
| pip | `pip.mirrors` | `PIP_MIRRORS` | `https://pypi.org/simple/` |

所有镜像列表均为逗号分隔 URL，系统属性优先于环境变量。解析时按顺序尝试，失败则回退到下一个。

Maven 还可以通过工具的 `repository_url` 参数临时指定单个仓库。

### 7. 统一缓存目录

所有下载的包和反编译结果统一存储在 `~/.decompile-mcp/` 下：

```
~/.decompile-mcp/
├── maven-repo/     — Maven JAR 本地仓库（替代 ~/.m2/repository）
├── decompiled/     — CFR 反编译磁盘缓存
├── npm/            — npm 包解压缓存
└── pip/            — PyPI 包解压缓存
```

可通过 `decompile.cache.dir` 系统属性或 `DECOMPILE_CACHE_DIR` 环境变量自定义根目录。

### 8. 缓存隔离

同一 GAV 从不同仓库可能获取到不同内容。`ResolutionResult.cacheNamespace` 字段按仓库 URL 的哈希值隔离反编译缓存：
- 默认仓库列表 → `cacheNamespace = ""`，缓存键 = `jarPath`
- 指定 `repository_url` → `cacheNamespace = "repo-" + url.hashCode()`，缓存键 = `jarPath | namespace`
- `force_remote` → JAR 位于临时目录（每次路径不同），天然隔离

### 9. 日志与行尾符处理

- 日志全部输出到 stderr，避免污染 HTTP 响应流
- `MavenReadTool` 将 `\r\n` 统一标准化为 `\n`，确保跨平台输出一致

## Maven JAR 解析策略

```
1. 确定仓库列表
   ├─ repository_url 指定 → 单仓库，cacheNamespace = hash(url)，skipLocal = true
   └─ repository_url 为空 → 配置的仓库列表(优先级排序)，cacheNamespace = ""，skipLocal = false

2. preferSource && !forceDecompile?
   ├─ YES → 尝试解析 -sources.jar
   │         ├─ skipLocal=false → 先查本地缓存，再查远程
   │         ├─ skipLocal=true → 仅查远程
   │         ├─ 找到 → 返回 ResolutionResult(isSourceJar=true)
   │         └─ 未找到 → 继续
   └─ NO  → 继续

3. 解析主 JAR
   ├─ skipLocal=true → resolveForceRemote（临时目录）
   └─ skipLocal=false → 本地存在则直接返回，否则按优先级遍历远程仓库下载
```

## 错误处理

所有异常在工具 handler 层捕获，返回 `CallToolResult` 且 `isError=true`：
- 缺失必填参数 → `Missing required parameter: xxx`
- 无效正则 → `Invalid regex pattern: ...`
- JAR 解析失败 → `Failed to resolve artifact: ...`
- 类不存在 → `Class not found in G:A:V: xxx`
- CFR 反编译失败 → `CFR decompilation failed for ...`

完整堆栈跟踪输出到 stderr，用户可见消息保持简洁。

## 构建和运行

```bash
# 编译并打包 fat JAR
mvn clean package -DskipTests

# 默认端口 8080 启动
java -jar target/decompile-mcp.jar

# 自定义端口
java -Dserver.port=9090 -jar target/decompile-mcp.jar

# 仅本地监听
java -Dserver.host=127.0.0.1 -jar target/decompile-mcp.jar

# 自定义缓存目录
java -Ddecompile.cache.dir=/data/decompile-cache -jar target/decompile-mcp.jar

# 指定 Maven 镜像仓库
java -Dmaven.repositories=https://maven.aliyun.com/repository/public -jar target/decompile-mcp.jar

# 通过环境变量配置 Maven 多个镜像（逗号分隔，按优先级）
export M2_REPOSITORIES=https://nexus.company.com/repository/maven-public,https://maven.aliyun.com/repository/public
java -jar target/decompile-mcp.jar

# 指定 npm 镜像
java -Dnpm.mirrors=https://registry.npmmirror.com -jar target/decompile-mcp.jar
# 或通过环境变量
export NPM_MIRRORS=https://registry.npmmirror.com,https://registry.yarnpkg.com

# 指定 PyPI 镜像
java -Dpip.mirrors=https://pypi.tuna.tsinghua.edu.cn/simple/ -jar target/decompile-mcp.jar
# 或通过环境变量
export PIP_MIRRORS=https://mirrors.aliyun.com/pypi/simple/,https://pypi.tuna.tsinghua.edu.cn/simple/
```

### Streamable HTTP 端点

| 端点 | 方法 | 用途 |
|---|---|---|
| `/mcp` | GET/POST | MCP Streamable HTTP 端点（同时处理 SSE 和消息） |

### 配置参数

| 参数 | 环境变量 | 系统属性 | 默认值 |
|---|---|---|---|
| 端口 | `SERVER_PORT` | `server.port` | `8080` |
| 绑定地址 | `SERVER_HOST` | `server.host` | `0.0.0.0` |
| 缓存根目录 | `DECOMPILE_CACHE_DIR` | `decompile.cache.dir` | `~/.decompile-mcp` |
| Maven 镜像 | `M2_REPOSITORIES` | `maven.repositories` | Maven Central |
| npm 镜像 | `NPM_MIRRORS` | `npm.mirrors` | `https://registry.npmjs.org` |
| PyPI 镜像 | `PIP_MIRRORS` | `pip.mirrors` | `https://pypi.org/simple/` |

## Docker 部署

### 镜像构建

```bash
docker build -f docker/Dockerfile -t decompile-mcp .
```

多阶段构建：`maven:3.9-eclipse-temurin-21-alpine`（编译）→ `eclipse-temurin:21-jre-alpine`（运行），最终镜像约 120MB。

### 数据卷

| 容器路径 | 用途 | 建议挂载 |
|---|---|---|
| `/decompile-cache` | 统一缓存目录（Maven/npm/pip + 反编译结果） | 持久化卷 |

### docker-compose

```bash
docker-compose -f docker/docker-compose.yml up
```

### 手动运行

```bash
# 基本运行
docker run --rm -p 8080:8080 \
  -v ~/.decompile-mcp:/decompile-cache \
  decompile-mcp

# 自定义端口 + Maven 镜像
docker run --rm -p 9090:8080 \
  -v ~/.decompile-mcp:/decompile-cache \
  -e M2_REPOSITORIES=https://maven.aliyun.com/repository/public \
  decompile-mcp

# 指定 npm 和 PyPI 镜像
docker run --rm -p 8080:8080 \
  -v ~/.decompile-mcp:/decompile-cache \
  -e NPM_MIRRORS=https://registry.npmmirror.com \
  -e PIP_MIRRORS=https://pypi.tuna.tsinghua.edu.cn/simple/ \
  decompile-mcp
```

## MCP 客户端配置

```json
{
  "mcpServers": {
    "decompile-mcp": {
      "url": "http://your-server:8080/mcp"
    }
  }
}
```

若需要配置镜像仓库，可在启动服务端时通过环境变量传入。

## 验证方式

使用 curl 测试端点连通性：

```bash
# 启动服务器
java -jar target/decompile-mcp.jar &

# 连接 MCP 端点，应收到 session 事件
curl -N http://localhost:8080/mcp
```

或使用 MCP Inspector 图形化工具进行完整交互测试。
