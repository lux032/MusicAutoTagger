# 使用多阶段构建
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

# 设置工作目录
WORKDIR /app

# 复制 pom.xml 和源代码
COPY pom.xml .
COPY src ./src

# 构建项目
RUN mvn clean package -DskipTests

# 运行阶段
FROM eclipse-temurin:17-jre-alpine

# 安装 chromaprint (fpcalc)
RUN apk add --no-cache chromaprint

# 创建应用目录
WORKDIR /app

# 从构建阶段复制 JAR 文件
COPY --from=builder /app/target/MusicDemo-*.jar app.jar

# 复制配置文件
COPY config.properties .

# 创建数据目录（用于挂载音乐文件）
RUN mkdir -p /music

# 设置环境变量
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# 暴露端口（如果将来需要 Web 界面）
# EXPOSE 8080

# 启动应用
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]