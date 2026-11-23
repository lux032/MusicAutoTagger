# 使用多阶段构建（JAR 为跨平台字节码，固定在构建机原生架构编译，避免 QEMU arm64 重复慢编译）
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-17 AS builder

# 设置工作目录
WORKDIR /app

# 复制 pom.xml 和源代码
COPY pom.xml .
COPY src ./src

# 构建项目
RUN mvn clean package -DskipTests

# 运行阶段
FROM eclipse-temurin:17-jre

# 安装 chromaprint (fpcalc) 和 ffmpeg（保持独立层以复用既有镜像构建缓存）
RUN apt-get update && apt-get install -y libchromaprint-tools ffmpeg && rm -rf /var/lib/apt/lists/*

# 安装 gosu（用于安全降权启动，独立单包层避免失效上方重型 ffmpeg 缓存）
RUN DEBIAN_FRONTEND=noninteractive apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends gosu \
    && rm -rf /var/lib/apt/lists/*

# 创建应用目录
WORKDIR /app

# 从构建阶段复制 JAR 文件
COPY --from=builder /app/target/MusicDemo-*.jar app.jar

# 复制配置文件模板
COPY config.properties.example config.properties

# 复制启动脚本（兼容部分运行时要求固定在根目录）
COPY entrypoint.sh /entrypoint.sh
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /entrypoint.sh /app/entrypoint.sh

# 创建数据目录（用于挂载音乐文件）
RUN mkdir -p /music

# 设置环境变量
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# 暴露 Web 监控面板端口
EXPOSE 8080

# 启动应用
ENTRYPOINT ["/entrypoint.sh"]
