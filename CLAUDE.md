# jar-decompile-mcp

MCP 服务器，为 coding agent 提供从 Maven 仓库 JAR 包中反编译和搜索 Java 源代码的能力。通过 `jar_read`、`jar_glob`、`jar_grep` 三个工具，agent 可以像操作本地文件系统一样探索第三方依赖的实现。

## 技术栈

| 组件 | 库 | 版本 |
|---|---|---|
| 语言 | Java 21+ | — |
| MCP 协议 | `io.modelcontextprotocol.sdk:mcp` | 1.1.2 |
| 反编译器 | CFR (`org.benf:cfr`) | 0.152 |
| Maven 坐标解析 | `org.apache.maven.resolver:maven-resolver-*` | 1.9.22 |
| 日志 | SLF4J + Logback | 2.0.16 / 1.5.12 |
| 打包 | maven-assembly-plugin（fat JAR） | 3.7.1 |

无 Spring Boot、无 Quarkus，仅依赖官方 MCP Java SDK，通过 stdio 传输 JSON-RPC 协议通信。

## 项目结构

```
src/main/java/top/codexvn/
├── Main.java                       — 入口点，启动日志、关闭钩子、主线程阻塞
├── server/
│   └── JarDecompileMcpServer.java  — MCP 服务器组装：创建传输层、注入服务、注册工具
├── resolver/
│   ├── MavenCoordinate.java        — record: groupId, artifactId, version + 路径工具方法
│   ├── RepositoryConfig.java       — 工具类：从系统属性/环境变量加载仓库列表
│   ├── ResolutionConfig.java       — record: 工具传入的解析选项（preferSource 等）
│   ├── ResolutionResult.java       — record: 解析结果（jarPath, isSourceJar, cacheNamespace）
│   └── JarResolver.java            — JAR 解析：多仓库优先级、源码包优先、强制远程、缓存隔离
├── decompiler/
│   └── DecompilerService.java      — CFR 反编译封装 + 源码包直读 + 缓存隔离
└── tool/
    ├── JarReadTool.java            — jar_read 工具：反编译/读源码，cat -n 格式输出
    ├── JarGlobTool.java            — jar_glob 工具：列出 JAR 条目匹配 glob 模式
    └── JarGrepTool.java            — jar_grep 工具：正则搜索，grep -n 格式输出
```

## 三个 MCP 工具

三个工具均支持以下可选参数，用于控制解析行为：

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `prefer_source` | boolean | `true` | 优先使用 `-sources.jar`（含原始注释和变量名） |
| `force_decompile` | boolean | `false` | 强制 CFR 反编译，即使存在 sources JAR |
| `repository_url` | string | null | 指定远程仓库 URL，覆盖配置的仓库列表 |
| `force_remote` | boolean | `false` | 绕过本地缓存，直接下载到临时目录 |

### jar_read — 反编译指定类

映射到 coding agent 的 `Read` 工具模型。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `group_id` | string | 是 | Maven groupId，如 `com.google.guava` |
| `artifact_id` | string | 是 | Maven artifactId，如 `guava` |
| `version` | string | 是 | 版本号，如 `33.0.0-jre` |
| `class_name` | string | 是 | 全限定类名，如 `com.google.common.collect.Lists` |
| `offset` | integer | 否 | 起始行号（1-based，默认 1） |
| `limit` | integer | 否 | 最大返回行数（默认不限） |

输出：cat -n 风格，6 位右对齐行号 + 源码内容。

### jar_glob — 列出 JAR 条目

映射到 coding agent 的 `Glob` 工具模型。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `group_id` | string | 是 | Maven groupId |
| `artifact_id` | string | 是 | Maven artifactId |
| `version` | string | 是 | 版本号 |
| `pattern` | string | 是 | Glob 模式，如 `**/*Service*.class` |

注意：若使用 sources JAR（默认），条目将以 `.java` 结尾而非 `.class`。使用 `force_decompile: true` 可恢复 `.class` 后缀。

输出：按字母排序的匹配条目路径列表。

### jar_grep — 正则搜索

映射到 coding agent 的 `Grep` 工具模型。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `group_id` | string | 是 | Maven groupId |
| `artifact_id` | string | 是 | Maven artifactId |
| `version` | string | 是 | 版本号 |
| `pattern` | string | 是 | Java 正则表达式 |

输出：`类名:行号:匹配行` 格式（与 `grep -n` 一致）。

## 关键设计决策

### 1. 选择官方 MCP SDK 而非 Spring AI

选择 `io.modelcontextprotocol.sdk:mcp`（官方 SDK）而非 Spring AI MCP Starter，原因：
- 无 Spring Boot 启动开销，单一 fat JAR 即可运行
- 更轻量，适合作为编码工具的 sidecar 进程
- 直接使用 `McpServer.sync(transport).toolCall(...).build()` API，代码简洁

代价：需手动处理服务器生命周期（`Thread.currentThread().join()` 阻塞主线程，关闭钩子处理 SIGTERM）。

### 2. 选择 CFR 作为反编译器

CFR (Class File Reader) 是 Java 反编译的事实标准：
- 对现代 Java 21+ 特性（record、sealed class、pattern matching）支持最好
- 控制流还原准确率高（~98.7% 方法体正确率）
- API 简洁：`CfrDriver.Builder().withOutputSink(...).build().analyse(paths)`

### 3. 源码包优先策略

默认优先使用 `-sources.jar`，包含原始注释和变量名，质量远优于反编译结果。解析流程：
1. 若 `prefer_source=true` 且 `force_decompile=false` → 尝试解析 `-sources.jar`
2. 找到 sources JAR → 直接读取 `.java` 文件（无需反编译）
3. 未找到 → 回退到主 JAR + CFR 反编译

`force_decompile: true` 可强制跳过此流程，始终使用反编译。

### 4. jar_read 单类快速提取

`DecompilerService.decompileClass()` 采用两阶段策略：
1. 检查缓存 → 命中则直接返回
2. 未命中 → 从 JAR 提取目标 `.class` 到临时文件 → CFR 仅反编译该文件 → 删除临时文件

避免全量反编译（如 Guava 有 620 个类，全量反编译需 ~10 秒，单类提取 <1 秒）。`jar_grep` 则必须调用 `decompileAll()` 全量反编译（搜索需要遍历所有类）。

### 5. 仓库优先级与镜像

支持通过配置加载多个仓库（如公司内网 Nexus + 阿里云镜像），按优先级顺序解析：

| 优先级 | 配置来源 | 键名 | 格式 |
|---|---|---|---|
| 1（最高） | 系统属性 | `maven.repositories` | 逗号分隔 URL |
| 2 | 环境变量 | `M2_REPOSITORIES` | 逗号分隔 URL |
| 3（回退） | 硬编码 | — | Maven Central |

也可通过工具的 `repository_url` 参数临时指定单个仓库。

### 6. 缓存隔离

同一 GAV 从不同仓库可能获取到不同内容（公司内网可能有补丁版本）。`ResolutionResult.cacheNamespace` 字段按仓库 URL 的哈希值隔离反编译缓存：
- 默认仓库列表 → `cacheNamespace = ""`，缓存键 = `jarPath`（向后兼容）
- 指定 `repository_url` → `cacheNamespace = "repo-" + url.hashCode()`，缓存键 = `jarPath | namespace`
- `force_remote` → JAR 位于临时目录（每次路径不同），天然隔离

### 7. stderr 日志约束

**绝对禁止向 stdout 写入任何内容**。stdout 是 MCP JSON-RPC 协议的传输通道，任何非协议输出都会破坏通信。

实现方式：
- `logback.xml` 中 `<appender>` 指定 `<target>System.err</target>`
- `Main.java` 不使用 `System.out.println()`
- 噪音库（Maven Resolver、CFR、Aether）日志级别设为 WARN

### 8. 行结束符标准化

`JarReadTool.formatWithLineNumbers()` 会先执行 `source.replace("\r\n", "\n").replace('\r', '\n')` 将 Windows 风格的 `\r\n` 统一为 `\n`，格式化时使用硬编码 `\n` 而非平台相关的 `%n`。确保跨平台输出一致。

## Maven JAR 解析策略

`JarResolver.resolveWithConfig()` 的完整解析流程：

```
1. 确定仓库列表
   ├─ repository_url 指定 → 单仓库，cacheNamespace = hash(url)，skipLocal = true
   └─ repository_url 为空 → 配置的仓库列表(优先级排序)，cacheNamespace = ""，skipLocal = false

2. preferSource && !forceDecompile?
   ├─ YES → 尝试解析 -sources.jar
   │         ├─ skipLocal=false → 先查本地 ~/.m2，再查远程
   │         ├─ skipLocal=true → 仅查远程（临时目录）
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

# 运行（产物：target/jar-decompile-mcp.jar）
java -jar target/jar-decompile-mcp.jar

# 指定自定义 Maven 本地仓库
java -Dmaven.repo.local=/path/to/.m2/repository -jar target/jar-decompile-mcp.jar

# 指定镜像仓库（最高优先级）
java -Dmaven.repositories=https://maven.aliyun.com/repository/public -jar target/jar-decompile-mcp.jar

# 通过环境变量配置仓库
export M2_REPOSITORIES=https://nexus.company.com/repository/maven-public,https://maven.aliyun.com/repository/public
java -jar target/jar-decompile-mcp.jar
```

## Docker 部署

### 镜像构建

```bash
docker build -f docker/Dockerfile -t jar-decompile-mcp .
```

采用多阶段构建，最终镜像约 120MB：
- 构建阶段：`maven:3.9-eclipse-temurin-21-alpine`（编译打包）
- 运行阶段：`eclipse-temurin:21-jre-alpine`（仅 JRE，~80MB）

### 数据卷

| 容器路径 | 用途 | 建议挂载 |
|---|---|---|
| `/maven-repo` | Maven 本地仓库 | `~/.m2/repository` |

挂载本地仓库可避免每次重启容器重新下载依赖。

### docker-compose 运行

```bash
cd jar-decompile-mcp
docker-compose -f docker/docker-compose.yml up
```

### 手动运行

```bash
# 基本运行
docker run --rm -i \
  -v ~/.m2/repository:/maven-repo \
  jar-decompile-mcp

# 配置镜像仓库
docker run --rm -i \
  -v ~/.m2/repository:/maven-repo \
  -e M2_REPOSITORIES=https://maven.aliyun.com/repository/public \
  jar-decompile-mcp
```

### MCP 客户端配置（Docker 模式）

```json
{
  "mcpServers": {
    "jar-decompile": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "-v", "/home/user/.m2/repository:/maven-repo",
        "jar-decompile-mcp"
      ]
    }
  }
}
```

### 常规 Java 运行

```bash
# 编译并打包 fat JAR
mvn clean package -DskipTests

# 运行（产物：target/jar-decompile-mcp.jar）
java -jar target/jar-decompile-mcp.jar

# 指定自定义 Maven 本地仓库
java -Dmaven.repo.local=/path/to/.m2/repository -jar target/jar-decompile-mcp.jar

# 指定镜像仓库（最高优先级）
java -Dmaven.repositories=https://maven.aliyun.com/repository/public -jar target/jar-decompile-mcp.jar

# 通过环境变量配置仓库
export M2_REPOSITORIES=https://nexus.company.com/repository/maven-public,https://maven.aliyun.com/repository/public
java -jar target/jar-decompile-mcp.jar
```

## MCP 客户端配置（Java 模式）

```json
{
  "mcpServers": {
    "jar-decompile": {
      "command": "java",
      "args": ["-jar", "/path/to/jar-decompile-mcp.jar"],
      "env": {
        "M2_REPOSITORIES": "https://nexus.company.com/repository/maven-public,https://maven.aliyun.com/repository/public"
      }
    }
  }
}
```

## 验证方式

使用管道向 stdin 发送 JSON-RPC 消息，观察 stdout 返回：

```bash
# 初始化 + 工具调用
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","clientInfo":{"name":"test","version":"1.0"},"capabilities":{}}}'
echo '{"jsonrpc":"2.0","method":"notifications/initialized"}'
echo '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"jar_read","arguments":{"group_id":"com.google.guava","artifact_id":"guava","version":"33.0.0-jre","class_name":"com.google.common.collect.Lists","limit":15}}}'
```

或使用 MCP Inspector 图形化工具进行交互测试。
