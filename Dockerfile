# 使用多阶段构建
FROM maven:3.9-eclipse-temurin-17 AS builder

# 设置工作目录
WORKDIR /app

# 复制 pom.xml 和源代码
COPY pom.xml .
COPY src ./src

# 构建项目
RUN mvn clean package -DskipTests

# 运行阶段
FROM eclipse-temurin:17-jre

# 安装 chromaprint (fpcalc) 和 ffmpeg
RUN apt-get update && apt-get install -y libchromaprint-tools ffmpeg && rm -rf /var/lib/apt/lists/*

# 创建应用目录
WORKDIR /app

# 从构建阶段复制 JAR 文件
COPY --from=builder /app/target/MusicDemo-*.jar app.jar

# 复制配置文件模板
COPY config.properties.example config.properties

# 复制启动脚本
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# 创建数据目录（用于挂载音乐文件）
RUN mkdir -p /music

# 设置环境变量
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# 暴露 Web 监控面板端口
EXPOSE 8080

# 启动应用
ENTRYPOINT ["/app/entrypoint.sh"]
