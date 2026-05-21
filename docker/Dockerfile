# 阶段1: 构建 — 使用 Maven + JDK 21 编译打包
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml .
# 先下载依赖，利用 Docker 缓存层加速后续构建
RUN mvn dependency:go-offline -B -q
COPY src ./src
RUN mvn package -DskipTests -B -q

# 阶段2: 运行 — 仅需 JRE，最小化镜像体积
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app
# 从构建阶段复制 fat JAR
COPY --from=builder /build/target/decompile-mcp.jar /app/decompile-mcp.jar

# Streamable HTTP 默认端口
EXPOSE 8080

# 挂载点：统一缓存目录，持久化已下载的包和反编译结果
VOLUME ["/decompile-cache"]

# 通过 DECOMPILE_CACHE_DIR 将缓存指向挂载卷
ENTRYPOINT ["java", "-Ddecompile.cache.dir=/decompile-cache", "-jar", "/app/decompile-mcp.jar"]
