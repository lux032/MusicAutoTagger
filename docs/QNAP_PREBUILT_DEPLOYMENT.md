# QNAP 预编译部署指南

本指南介绍如何使用预先编译好的 JAR 文件部署到 QNAP，无需在 NAS 上编译源码。

## ✨ 优势

相比完整源码部署：
- ✅ **更快的部署速度**：无需在 QNAP 上编译 Maven 项目
- ✅ **更小的镜像体积**：只包含运行时环境和 JAR 文件
- ✅ **节省 QNAP 资源**：不需要在 NAS 上运行 Maven 构建
- ✅ **更简单的文件管理**：只需要上传少量文件

## 📦 部署步骤

### 步骤 1: 在本地电脑编译项目

在你的 Windows/Mac/Linux 电脑上：

```bash
# 进入项目目录
cd MusicDemo

# 编译项目
mvn clean package -DskipTests

# 编译成功后，JAR 文件位于：
# target/MusicDemo-1.0-SNAPSHOT.jar
```

### 步骤 2: 准备部署文件

创建一个部署文件夹，包含以下文件：

```
music-tagger-deploy/
├── MusicDemo-1.0-SNAPSHOT.jar    ← 从 target/ 复制
├── Dockerfile.prebuilt            ← 预编译版 Dockerfile
├── docker-compose.prebuilt.yml    ← 预编译版 docker-compose
├── config.properties              ← 配置文件
└── deploy-prebuilt.sh             ← 部署脚本（可选）
```

### 步骤 3: 上传到 QNAP

1. 使用 File Station 或 SFTP 上传文件到 QNAP：
   ```
   /share/Container/music-tagger/
   ```

2. 确保文件权限正确：
   ```bash
   chmod +x deploy-prebuilt.sh  # 如果使用脚本
   chmod 644 *.jar
   chmod 644 *.yml
   chmod 644 config.properties
   ```

### 步骤 4: 构建 Docker 镜像

通过 SSH 连接到 QNAP，然后：

```bash
cd /share/Container/music-tagger

# 使用预编译 Dockerfile 构建镜像
docker build -f Dockerfile.prebuilt -t music-tagger:latest .
```

构建过程约 1-2 分钟，远快于完整编译。

### 步骤 5A: 使用 Docker Compose 部署（推荐）

```bash
# 启动容器
docker-compose -f docker-compose.prebuilt.yml up -d

# 查看日志
docker-compose -f docker-compose.prebuilt.yml logs -f

# 停止容器
docker-compose -f docker-compose.prebuilt.yml down
```

### 步骤 5B: 或在 Container Station 手动创建

1. 打开 **Container Station**
2. 点击 **"容器"** → **"创建"**
3. 搜索镜像：`music-tagger:latest`
4. 按照 [QNAP_DEPLOYMENT_GUIDE.md](QNAP_DEPLOYMENT_GUIDE.md) 中的步骤配置容器

## 🔄 更新应用

当需要更新代码时：

### 方法 1: 完整更新

```bash
# 1. 在本地重新编译
mvn clean package -DskipTests

# 2. 上传新的 JAR 到 QNAP，替换旧文件

# 3. SSH 到 QNAP，重新构建镜像
cd /share/Container/music-tagger
docker-compose -f docker-compose.prebuilt.yml down
docker build -f Dockerfile.prebuilt -t music-tagger:latest .
docker-compose -f docker-compose.prebuilt.yml up -d
```

### 方法 2: 仅替换 JAR（快速）

```bash
# 1. 在本地重新编译
mvn clean package -DskipTests

# 2. 上传新的 JAR 到 QNAP

# 3. SSH 到 QNAP，重启容器
docker restart music-tagger
```

注意：方法 2 只适用于未修改 Dockerfile 的情况。

## 📝 配置文件说明

### docker-compose.prebuilt.yml

```yaml
version: '3.8'

services:
  music-tagger:
    image: music-tagger:latest  # 使用本地构建的镜像
    container_name: music-tagger
    restart: unless-stopped
    
    environment:
      - JAVA_OPTS=-Xmx512m -Xms256m
      - TZ=Asia/Shanghai
    
    volumes:
      # 根据你的实际路径修改
      - /share/Download/Music:/music
      - /share/Music:/app/tagged_music
      - ./config.properties:/app/config.properties:ro
      - ./logs:/app/logs
      - ./cover_cache:/app/.cover_cache
    
    network_mode: bridge
    
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 1G
        reservations:
          cpus: '0.5'
          memory: 512M
    
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
    
    healthcheck:
      test: ["CMD", "sh", "-c", "ps aux | grep -v grep | grep java || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s
```

### Dockerfile.prebuilt

已包含在项目中，主要特点：
- 使用轻量级 JRE 17 镜像
- 预装 chromaprint (fpcalc)
- 只复制编译好的 JAR 文件
- 镜像体积小，构建快

## 🛠️ 自动化部署脚本

创建 `deploy-prebuilt.sh`：

```bash
#!/bin/bash

# 自动化部署脚本
# 用法：./deploy-prebuilt.sh

set -e

echo "========================================="
echo "  Music Tagger 预编译部署脚本"
echo "========================================="
echo ""

# 检查 JAR 文件
if [ ! -f "MusicDemo-1.0-SNAPSHOT.jar" ]; then
    echo "错误: 找不到 MusicDemo-1.0-SNAPSHOT.jar"
    echo "请先在本地运行 mvn clean package，然后上传 JAR 文件"
    exit 1
fi

# 检查配置文件
if [ ! -f "config.properties" ]; then
    echo "错误: 找不到 config.properties"
    echo "请复制 config.properties.example 并配置"
    exit 1
fi

# 停止旧容器
echo "停止旧容器..."
docker-compose -f docker-compose.prebuilt.yml down || true

# 构建镜像
echo "构建 Docker 镜像..."
docker build -f Dockerfile.prebuilt -t music-tagger:latest .

# 启动容器
echo "启动容器..."
docker-compose -f docker-compose.prebuilt.yml up -d

echo ""
echo "========================================="
echo "  部署完成！"
echo "========================================="
echo ""
echo "查看日志: docker-compose -f docker-compose.prebuilt.yml logs -f"
echo "停止服务: docker-compose -f docker-compose.prebuilt.yml down"
echo ""
```

## 📊 文件大小对比

| 部署方式 | 需要上传的文件 | 大小 |
|---------|--------------|------|
| 完整源码部署 | 整个项目源码 | ~2-5 MB |
| 预编译部署 | JAR + 配置文件 | ~20-30 MB |

虽然 JAR 文件更大，但：
- 上传一次后更新更快
- 不需要在 QNAP 上下载 Maven 依赖（可能数百 MB）
- 构建速度快 10-20 倍

## ⚡ 性能对比

| 操作 | 完整源码部署 | 预编译部署 |
|-----|------------|-----------|
| 首次构建时间 | 5-10 分钟 | 1-2 分钟 |
| 镜像大小 | ~400 MB | ~250 MB |
| 更新时间 | 5-10 分钟 | 1-2 分钟 |
| QNAP CPU 使用 | 高 | 低 |
| QNAP 内存使用 | 高 | 低 |

## 🔍 常见问题

### Q: 如何验证 JAR 文件正确？

在本地测试：
```bash
java -jar target/MusicDemo-1.0-SNAPSHOT.jar
```

### Q: 可以在不同电脑编译吗？

可以，只要：
- 使用相同的 JDK 版本（建议 Java 17）
- Maven 依赖完整下载
- 编译参数一致

### Q: JAR 文件在 QNAP 上无法运行？

检查：
1. 确保编译时使用 Java 17
2. 确保 JAR 文件完整（没有损坏）
3. 查看容器日志：`docker logs music-tagger`

### Q: 需要修改代码后如何更新？

```bash
# 1. 修改代码
# 2. 重新编译
mvn clean package -DskipTests
# 3. 上传新 JAR
# 4. 重建镜像
docker build -f Dockerfile.prebuilt -t music-tagger:latest .
# 5. 重启容器
docker restart music-tagger
```

## 📚 相关文档

- [QNAP_DEPLOYMENT_GUIDE.md](QNAP_DEPLOYMENT_GUIDE.md) - 完整部署指南
- [README.md](README.md) - 项目总览
- [DATABASE_SETUP.md](DATABASE_SETUP.md) - 数据库配置

## 💡 推荐方案

**开发阶段**：使用完整源码部署，方便调试和修改

**生产环境**：使用预编译部署，更快、更稳定、更省资源

---

**这种部署方式特别适合 QNAP NAS 等资源有限的设备！**