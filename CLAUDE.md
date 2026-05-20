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
│   └── JarResolver.java            — JAR 解析：先检查本地 ~/.m2，缺失则从 Maven Central 下载
├── decompiler/
│   └── DecompilerService.java      — CFR 反编译封装：全量反编译 + 单类提取 + 内存缓存
└── tool/
    ├── JarReadTool.java            — jar_read 工具：反编译单个类，cat -n 格式输出
    ├── JarGlobTool.java            — jar_glob 工具：列出 JAR 条目匹配 glob 模式
    └── JarGrepTool.java            — jar_grep 工具：正则搜索所有反编译类，grep -n 格式输出
```

## 三个 MCP 工具

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

输出：按字母排序的匹配条目路径列表。

### jar_grep — 正则搜索反编译类

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

### 3. jar_read 单类快速提取

`DecompilerService.decompileClass()` 采用两阶段策略：
1. **快速路径**：从 JAR 中提取目标 `.class` 到临时文件 → CFR 仅反编译该文件 → 删除临时文件
2. 避免每次反编译整个 JAR（如 Guava 有 620 个类，全量反编译需 ~10 秒，单类提取 <1 秒）

`jar_grep` 调用 `decompileAll()` 进行全量反编译（必须遍历所有类才能搜索）。

### 4. 内存缓存

`DecompilerService` 维护 `ConcurrentMap<Path, Map<String, FQCN>>` 缓存：
- 同一 JVM 会话中，同一 JAR 只反编译一次
- 对 `jar_grep` 后续调用同一 JAR 的 `jar_read` 可直接命中缓存
- 缓存生命周期与 JVM 进程绑定（进程退出即释放）

### 5. stderr 日志约束

**绝对禁止向 stdout 写入任何内容**。stdout 是 MCP JSON-RPC 协议的传输通道，任何非协议输出都会破坏通信。

实现方式：
- `logback.xml` 中 `<appender>` 指定 `<target>System.err</target>`
- `Main.java` 不使用 `System.out.println()`
- 噪音库（Maven Resolver、CFR、Aether）日志级别设为 WARN

### 6. 行结束符标准化

`JarReadTool.formatWithLineNumbers()` 会先执行 `source.replace("\r\n", "\n").replace('\r', '\n')` 将 Windows 风格的 `\r\n` 统一为 `\n`，格式化时使用硬编码 `\n` 而非平台相关的 `%n`。确保跨平台输出一致。

## Maven JAR 解析策略

`JarResolver.resolve()` 的三层解析逻辑：
1. 读取本地仓库路径：系统属性 `maven.repo.local` → 环境变量 `M2_REPO` → 默认 `~/.m2/repository`
2. 构建本地路径：`{repo}/{groupId.replace('.','/')}/{artifactId}/{version}/{artifactId}-{version}.jar`
3. 存在 → 直接返回；不存在 → 调用 Maven Resolver API 从 Maven Central 下载

下载后自动缓存到本地仓库，后续请求命中本地文件系统。

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
```

## MCP 客户端配置

```json
{
  "mcpServers": {
    "jar-decompile": {
      "command": "java",
      "args": ["-jar", "/path/to/jar-decompile-mcp.jar"]
    }
  }
}
```

## 验证方式

使用管道向 stdin 发送 JSON-RPC 消息，观察 stdout 返回：

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize",...}'
echo '{"jsonrpc":"2.0","method":"notifications/initialized"}'
echo '{"jsonrpc":"2.0","id":2,"method":"tools/call",...}'
```

或使用 MCP Inspector 图形化工具进行交互测试。
