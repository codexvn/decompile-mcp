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
COPY --from=builder /build/target/decompile-mcp.jar /app/decompile-mcp.jar

# Streamable HTTP 默认端口
EXPOSE 8080

# 挂载点：统一缓存目录，持久化已下载的包避免重复下载
VOLUME ["/maven-repo"]

# 容器使用非 root 用户
USER mcp

# Maven 仓库指向挂载卷；npm/pip 缓存使用默认位置（可在运行时通过 DECOMPILE_CACHE_DIR 覆盖）
ENTRYPOINT ["java", "-Dmaven.repo.local=/maven-repo", "-jar", "/app/decompile-mcp.jar"]
