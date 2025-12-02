# QNAP Container Station 部署指南

本指南将帮助你通过 QNAP 的 Container Station 图形界面部署音乐自动标签系统。

## 📋 前置要求

1. ✅ QNAP NAS 已安装 **Container Station**
2. ✅ 已配置 MySQL 数据库（推荐使用 QNAP 的 MariaDB 或独立 MySQL 容器）
3. ✅ 有足够的存储空间用于音乐文件
4. ✅ 已获取 AcoustID API Key（在 https://acoustid.org/new-application 注册）

## 🚀 部署方式一：使用 Docker Compose（推荐）

### 步骤 1: 准备项目文件

1. 在 QNAP File Station 中创建项目目录：
   ```
   /share/Container/music-tagger/
   ```

2. 上传以下文件到该目录：
   - `docker-compose.yml`
   - `Dockerfile`
   - `pom.xml`
   - `src/` 文件夹（包含所有 Java 源码）
   - `config.properties.example`（重命名为 `config.properties` 并配置）

### 步骤 2: 配置文件

编辑 `config.properties`，修改以下关键配置：

```properties
# 监控目录（Docker容器内路径，无需修改）
monitor.directory=/music
# 输出目录（Docker容器内路径，无需修改）
monitor.outputDirectory=/app/tagged_music

# MusicBrainz 配置
musicbrainz.userAgent=MusicDemo/1.0 ( your-email@example.com )

# AcoustID API Key（必须配置）
acoustid.apiKey=YOUR_API_KEY_HERE

# 数据库配置（需要修改为你的 QNAP MySQL 配置）
db.type=mysql
db.mysql.host=192.168.1.100  # 修改为你的 QNAP IP
db.mysql.port=3306
db.mysql.database=music_demo
db.mysql.username=root
db.mysql.password=your_password

# 代理配置（如需要）
proxy.enabled=false
```

### 步骤 3: 创建数据库

1. 打开 Container Station
2. 如果没有 MySQL，创建一个 MariaDB 容器：
   - 搜索 "mariadb"
   - 创建容器，设置 root 密码
   - 记录容器 IP 地址

3. 连接到 MySQL，执行以下 SQL：
   ```sql
   CREATE DATABASE music_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```

4. 导入数据库结构（使用项目中的 `src/main/resources/schema.sql`）

### 步骤 4: 在 Container Station 中部署

1. 打开 **Container Station**
2. 点击左侧菜单 **"应用程序"** → **"创建"**
3. 选择 **"使用 Docker Compose 创建应用程序"**
4. 填写信息：
   - **名称**: `music-tagger`
   - **路径**: 选择 `/share/Container/music-tagger/docker-compose.yml`
5. 点击 **"创建"**

### 步骤 5: 验证部署

1. 在 Container Station 中查看容器状态
2. 点击容器名称，查看日志输出
3. 确认应用正常启动，没有错误信息

## 🔧 部署方式二：手动创建容器

如果你更喜欢通过 Container Station 图形界面手动配置：

### 步骤 1: 构建镜像

1. 通过 SSH 连接到 QNAP
2. 进入项目目录：
   ```bash
   cd /share/Container/music-tagger
   ```
3. 构建镜像：
   ```bash
   docker build -t music-tagger:latest .
   ```

### 步骤 2: 在 Container Station 创建容器

1. 打开 **Container Station**
2. 点击 **"容器"** → **"创建"**
3. 搜索刚才构建的镜像：`music-tagger`
4. 点击 **"安装"**

### 步骤 3: 容器配置

#### 基本设置
- **容器名称**: `music-tagger`
- **CPU 限制**: 1 核心
- **内存限制**: 1024 MB

#### 网络设置
- **网络模式**: Bridge
- 无需端口映射（除非将来添加 Web 界面）

#### 环境变量
添加以下环境变量：
```
JAVA_OPTS=-Xmx512m -Xms256m
TZ=Asia/Shanghai
```

#### 共享文件夹（重要！）

添加以下卷挂载：

| 主机路径 | 容器路径 | 描述 | 权限 |
|---------|---------|------|------|
| `/share/Download/Music` | `/music` | 输入：监控的音乐下载目录 | 读写 |
| `/share/Music` | `/app/tagged_music` | 输出：处理后的音乐存储目录 | 读写 |
| `/share/Container/music-tagger/config.properties` | `/app/config.properties` | 配置文件 | 只读 |
| `/share/Container/music-tagger/logs` | `/app/logs` | 日志目录（可选） | 读写 |

⚠️ **注意**：根据你的实际情况修改主机路径！

#### 自动重启
- 勾选 **"自动重启"**

### 步骤 4: 创建并启动

点击 **"创建"** 按钮，Container Station 会自动启动容器。

## 📂 目录结构说明

```
QNAP NAS
├── /share/Download/Music/        ← qBittorrent 下载目录（输入）
├── /share/Music/                 ← 处理后的音乐库（输出）
└── /share/Container/
    └── music-tagger/
        ├── docker-compose.yml    ← Docker Compose 配置
        ├── Dockerfile            ← Docker 镜像构建文件
        ├── pom.xml               ← Maven 配置
        ├── config.properties     ← 应用配置文件
        ├── src/                  ← Java 源码
        └── logs/                 ← 日志文件（可选）
```

## 🔍 常见问题

### 1. 容器启动失败

**检查项：**
- 查看容器日志，确认错误信息
- 检查 MySQL 连接配置是否正确
- 确认 AcoustID API Key 已正确配置
- 确认挂载的目录路径正确且有读写权限

**查看日志：**
```bash
# SSH 连接到 QNAP
docker logs music-tagger
```

或在 Container Station 界面点击容器 → 日志

### 2. 无法连接 MySQL

**解决方案：**
- 确认 MySQL 容器正在运行
- 使用容器内部 IP 地址（在 Container Station 查看）
- 或使用 QNAP 的主机名：`192.168.x.x`
- 检查防火墙设置

**测试 MySQL 连接：**
```bash
# 在 QNAP SSH 中测试
docker exec -it music-tagger sh
nc -zv 192.168.1.100 3306
```

### 3. 文件权限问题

**症状：** 容器无法读取音乐文件或无法写入输出目录

**解决方案：**
```bash
# 在 QNAP SSH 中修改权限
chmod -R 755 /share/Download/Music
chmod -R 755 /share/Music
```

### 4. 内存不足

**症状：** 容器频繁重启，日志显示 OutOfMemoryError

**解决方案：**
1. 在 Container Station 中增加容器内存限制（推荐 1GB）
2. 或修改 JAVA_OPTS 环境变量：
   ```
   JAVA_OPTS=-Xmx768m -Xms256m
   ```

### 5. 代理设置

如果需要通过代理访问 MusicBrainz API：

在 `config.properties` 中设置：
```properties
proxy.enabled=true
proxy.host=127.0.0.1
proxy.port=7890
```

## 🔄 更新应用

### 使用 Docker Compose

1. SSH 连接到 QNAP
2. 进入项目目录：
   ```bash
   cd /share/Container/music-tagger
   ```
3. 拉取最新代码并重新构建：
   ```bash
   docker-compose down
   docker-compose build --no-cache
   docker-compose up -d
   ```

### 手动更新

1. 在 Container Station 停止容器
2. 删除旧容器（保留镜像）
3. 更新代码文件
4. 重新构建镜像：
   ```bash
   docker build -t music-tagger:latest .
   ```
5. 使用新镜像创建容器

## 📊 监控运行状态

### Container Station 界面

1. 打开 Container Station
2. 点击容器名称查看：
   - CPU 使用率
   - 内存使用率
   - 网络流量
   - 实时日志

### 命令行监控

```bash
# 查看容器状态
docker ps | grep music-tagger

# 查看实时日志
docker logs -f music-tagger

# 查看资源使用
docker stats music-tagger
```

## 📝 配置示例

### 完整的 config.properties 示例

```properties
# 监控目录（容器内路径）
monitor.directory=/music
monitor.outputDirectory=/app/tagged_music
monitor.scanInterval=30

# MusicBrainz API
musicbrainz.apiUrl=https://musicbrainz.org/ws/2
musicbrainz.coverArtApiUrl=https://coverartarchive.org
musicbrainz.userAgent=MusicDemo/1.0 ( your-email@example.com )

# AcoustID API
acoustid.apiKey=YOUR_ACOUSTID_API_KEY
acoustid.apiUrl=https://api.acoustid.org/v2/lookup

# 文件处理
file.autoRename=true
file.createBackup=false

# 日志
logging.detailed=true
logging.processedFileLogPath=/app/logs/processed_files.log

# 封面缓存
cache.coverArtDirectory=/app/.cover_cache

# 数据库（MySQL）
db.type=mysql
db.mysql.host=192.168.1.100
db.mysql.port=3306
db.mysql.database=music_demo
db.mysql.username=root
db.mysql.password=your_password
db.mysql.pool.maxPoolSize=10
db.mysql.pool.minIdle=2
db.mysql.pool.connectionTimeout=30000

# 代理（可选）
proxy.enabled=false
proxy.host=127.0.0.1
proxy.port=7890
```

## 🎯 使用建议

1. **定期备份配置文件**：
   - `config.properties`
   - `docker-compose.yml`
   
2. **监控日志文件大小**：
   - 定期清理或归档日志
   - 在 docker-compose.yml 中已配置日志轮转

3. **性能优化**：
   - 根据音乐文件大小调整扫描间隔
   - 合理设置 JVM 内存参数
   - 使用 SSD 存储提升处理速度

4. **安全建议**：
   - 不要在 git 仓库中提交包含密码的 config.properties
   - 定期更新数据库密码
   - 限制容器的资源使用

## 📞 技术支持

如遇到问题：
1. 检查容器日志
2. 查看 MySQL 连接状态
3. 确认文件权限设置
4. 参考本文档的常见问题部分

---

**祝你部署顺利！🎉**