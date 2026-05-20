# 阶段1: 构建 — 使用 Maven + JDK 21 编译打包
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build
COPY pom.xml .
# 先下载依赖，利用 Docker 缓存层加速后续构建
RUN mvn dependency:go-offline -B -q
COPY src ./src
RUN mvn package -DskipTests -B -q

# 阶段2: 运行 — 仅需 JRE，最小化镜像体积
FROM eclipse-temurin:21-jre-alpine AS runtime

# 创建非 root 用户运行服务
RUN addgroup -S mcp && adduser -S mcp -G mcp

WORKDIR /app
# 从构建阶段复制 fat JAR
COPY --from=builder /build/target/jar-decompile-mcp.jar /app/jar-decompile-mcp.jar

# 挂载点：Maven 本地仓库，持久化已下载的 JAR 避免重复下载
VOLUME ["/maven-repo"]

# 容器使用非 root 用户
USER mcp

# 日志输出到 stderr，stdout 保留给 MCP JSON-RPC 协议
ENTRYPOINT ["java", "-Dmaven.repo.local=/maven-repo", "-jar", "/app/jar-decompile-mcp.jar"]
