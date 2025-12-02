# 音乐文件自动标签系统

一个自动监控音乐文件下载、识别音频指纹并更新元数据标签的 Java 应用程序。

## 功能特性

- 🎵 **文件监控**：实时监控指定目录的音乐文件下载
- 🔍 **音频指纹识别**：使用 Chromaprint 和 AcoustID 进行音频指纹识别
- 📝 **自动标签更新**：通过 MusicBrainz 获取准确的音乐元数据并写入文件
- 🔄 **自动重命名**：根据艺术家和标题自动重命名文件
- 🖼️ **自动封面**：自动下载并内嵌高清专辑封面
- 📂 **非破坏性处理**：处理后的文件保存到新目录，不修改源文件
- 📊 **支持多种格式**：MP3、FLAC、M4A、OGG、WAV
- 🐳 **Docker 支持**：一键部署，无需安装依赖

## 系统要求

### 本地运行
- Java 17 或更高版本
- Maven 3.6+
- Chromaprint (fpcalc) 工具

### Docker 运行（推荐）
- Docker 20.10+
- Docker Compose 1.29+

## 📦 部署方式

本项目支持多种部署方式：

- **🖥️ QNAP NAS 部署**（推荐）：使用 Container Station 图形界面管理
- **🐳 Docker 部署**：适用于任何支持 Docker 的系统
- **💻 本地运行**：直接在 Windows/macOS/Linux 上运行

### 🎯 QNAP NAS 部署（推荐）

如果你使用 QNAP NAS，请查看详细的部署指南：

📘 **[QNAP Container Station 部署指南](QNAP_DEPLOYMENT_GUIDE.md)**

该指南包含：
- ✅ 完整的图形界面操作步骤
- ✅ 配置文件模板和说明
- ✅ MySQL 数据库设置
- ✅ 常见问题解决方案
- ✅ 一键部署脚本

**快速部署（使用脚本）：**

```bash
# 1. 上传项目文件到 QNAP
# 2. SSH 连接到 QNAP
# 3. 执行部署脚本
chmod +x deploy.sh
./deploy.sh
```

## 快速开始（Docker）

使用 Docker 是最简单的运行方式，无需安装 Java 和其他依赖。

### 1. 准备配置文件

复制配置文件模板并编辑：

```bash
# 复制模板
cp config.properties.example config.properties

# 编辑配置文件
nano config.properties
```

关键配置项：
```properties
# 设置监控目录（Docker 中使用 /music）
monitor.directory=/music
# 设置输出目录（Docker 中使用 /app/tagged_music）
monitor.outputDirectory=/app/tagged_music

# 填入你的 AcoustID API Key
acoustid.apiKey=YOUR_API_KEY_HERE

# 修改联系邮箱
musicbrainz.userAgent=MusicDemo/1.0 ( your-email@example.com )

# 数据库配置（可选，也可以使用文件模式）
db.type=mysql
db.mysql.host=localhost
db.mysql.database=music_demo
db.mysql.username=root
db.mysql.password=your_password
```

### 2. 获取 AcoustID API Key

1. 访问：https://acoustid.org/new-application
2. 注册并创建一个新应用
3. 复制生成的 API Key 并填入 `config.properties`

### 3. 启动容器

```bash
# 使用 Docker Compose 启动
docker-compose up -d

# 查看日志
docker-compose logs -f

# 停止容器
docker-compose down
```

### 4. 目录映射

在 `docker-compose.yml` 中修改音乐目录映射：

```yaml
volumes:
  # 监控目录（下载目录）
  - ./downloads:/music
  # 输出目录（处理好的音乐）
  - ./music:/app/tagged_music
  # 配置文件
  - ./config.properties:/app/config.properties
```

## 本地安装步骤

### 1. 安装 Chromaprint

**Windows:**
- 下载：https://acoustid.org/chromaprint
- 将 `fpcalc.exe` 添加到系统 PATH 或放在项目目录

**macOS:**
```bash
brew install chromaprint
```

**Linux:**
```bash
sudo apt-get install libchromaprint-tools  # Ubuntu/Debian
sudo yum install chromaprint-tools          # CentOS/RHEL
```

### 2. 获取 AcoustID API Key

1. 访问：https://acoustid.org/new-application
2. 注册并创建一个新应用
3. 复制生成的 API Key

### 3. 配置项目

编辑 `config.properties` 文件：
```properties
# 设置你的音乐下载目录
monitor.directory=C:/Users/YourUsername/Downloads/Music

# 设置处理后的文件输出目录
monitor.outputDirectory=C:/Users/YourUsername/Music/Tagged

# 填入你的 AcoustID API Key
acoustid.apiKey=YOUR_API_KEY_HERE

# 修改联系邮箱
musicbrainz.userAgent=MusicDemo/1.0 ( your-email@example.com )
```

### 4. 编译运行

```bash
# 编译项目
mvn clean package

# 运行程序
mvn exec:java -Dexec.mainClass="org.example.Main"

# 或者直接运行 JAR
java -jar target/MusicDemo-1.0-SNAPSHOT.jar
```

## 使用方法

1. **启动程序**：运行 Main 类或使用 Docker
2. **放入音乐文件**：将音乐文件下载到配置的监控目录
3. **自动处理**：程序会自动：
   - 检测新文件
   - 生成音频指纹
   - 查询 AcoustID 和 MusicBrainz
   - 更新音乐标签
   - 根据配置重命名文件
4. **查看日志**：控制台会显示处理进度和结果
5. **停止程序**：按回车键优雅退出（本地）或 `docker-compose down`（Docker）

## 项目结构

```
MusicDemo/
├── src/main/java/org/example/
│   ├── Main.java                          # 主程序入口
│   ├── config/
│   │   └── MusicConfig.java              # 配置管理
│   └── service/
│       ├── FileMonitorService.java       # 文件监控服务
│       ├── AudioFingerprintService.java  # 音频指纹识别
│       ├── MusicBrainzClient.java        # MusicBrainz API 客户端
│       ├── TagWriterService.java         # 标签写入服务
│       ├── CoverArtCache.java            # 封面缓存服务
│       ├── ImageCompressor.java          # 图片压缩服务
│       └── ProcessedFileLogger.java      # 处理记录服务
├── src/main/resources/
│   └── schema.sql                         # 数据库表结构
├── config.properties                      # 配置文件
├── config.properties.example              # 配置文件模板
├── Dockerfile                             # Docker 镜像定义
├── docker-compose.yml                     # Docker Compose 配置
├── deploy.sh                              # QNAP 一键部署脚本
├── QNAP_DEPLOYMENT_GUIDE.md              # QNAP 部署指南
├── DATABASE_SETUP.md                      # 数据库设置指南
├── .dockerignore                          # Docker 忽略文件
├── pom.xml                               # Maven 配置
└── README.md                             # 本文件
```

## 配置说明

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `monitor.directory` | 监控的下载目录 | 用户下载文件夹 |
| `monitor.outputDirectory` | 输出目录 | 用户音乐文件夹 |
| `monitor.scanInterval` | 扫描间隔（秒） | 30 |
| `acoustid.apiKey` | AcoustID API Key | 必填 |
| `file.autoRename` | 是否自动重命名 | true |
| `logging.detailed` | 详细日志 | true |

## 工作流程

1. **文件监控**：监控目录变化，检测新增的音乐文件
2. **指纹生成**：使用 fpcalc 工具生成音频指纹
3. **AcoustID 查询**：通过指纹查询 AcoustID 数据库
4. **MusicBrainz 查询**：获取详细的音乐元数据（含封面信息）
5. **封面下载**：自动从 Cover Art Archive 下载专辑封面
6. **文件处理**：将源文件复制到输出目录，并写入元数据和封面
7. **自动重命名**：根据元数据重命名输出文件

## 依赖库

- **JAudioTagger 3.0.1**：音频标签读写
- **Apache HttpClient 5.2.1**：HTTP 请求
- **Jackson 2.15.2**：JSON 解析
- **SLF4J 2.0.9**：日志框架
- **Lombok 1.18.30**：简化代码

## Docker 使用说明

### 构建镜像

```bash
# 构建 Docker 镜像
docker build -t music-tagger .

# 或使用 Docker Compose
docker-compose build
```

### 运行容器

```bash
# 使用 Docker 运行
docker run -d \
  --name music-tagger \
  -v /path/to/downloads:/music \
  -v /path/to/output:/app/tagged_music \
  -v $(pwd)/config.properties:/app/config.properties \
  music-tagger

# 使用 Docker Compose（推荐）
docker-compose up -d
```

### 查看日志

```bash
# Docker
docker logs -f music-tagger

# Docker Compose
docker-compose logs -f
```

### 环境变量

可以通过环境变量配置 JVM 参数：

```bash
docker run -d \
  -e JAVA_OPTS="-Xmx1g -Xms512m" \
  -v /path/to/music:/music \
  music-tagger
```

### 与 qBittorrent 集成

如果使用 Docker 运行 qBittorrent，可以共享下载目录：

```yaml
version: '3.8'

services:
  qbittorrent:
    image: linuxserver/qbittorrent
    volumes:
      - ./downloads:/downloads
    ports:
      - "8080:8080"
  
  music-tagger:
    build: .
    volumes:
      - ./downloads/music:/music
      - ./music_library:/app/tagged_music
      - ./config.properties:/app/config.properties
    depends_on:
      - qbittorrent
```

### 资源限制

在 `docker-compose.yml` 中已配置资源限制：
- CPU: 0.5-1.0 核心
- 内存: 512MB-1GB

可根据需要调整。

## 注意事项

1. **API 速率限制**：
   - MusicBrainz：每秒最多 1 个请求
   - AcoustID：请查看官方限制

2. **文件权限**：确保程序有读写监控目录的权限

3. **备份文件**：备份文件以 `.backup_时间戳` 后缀保存

4. **支持格式**：目前支持 MP3、FLAC、M4A、OGG、WAV

## 故障排除

### fpcalc 未找到（本地运行）
- 确认 Chromaprint 已安装
- 检查 `fpcalc` 是否在 PATH 中
- Windows 用户可将 `fpcalc.exe` 放在项目根目录

### API Key 错误
- 确认在 AcoustID 正确注册
- 检查 `config.properties` 中的 API Key 是否正确

### 无法识别音乐
- 检查音频文件是否完整
- 某些文件可能在 AcoustID 数据库中没有记录
- 尝试手动添加标签或使用其他音乐识别服务

### Docker 故障排除

**容器无法启动**
```bash
# 查看容器状态
docker ps -a

# 查看详细日志
docker logs music-tagger
```

**fpcalc 不可用**
容器已预装 chromaprint，如果仍有问题：
```bash
# 进入容器检查
docker exec -it music-tagger sh
fpcalc -version
```

**文件权限问题**
确保挂载的目录有正确的权限：
```bash
# 在宿主机上
chmod -R 755 /path/to/music
```

## 📚 相关文档

- 📘 [QNAP 部署指南](QNAP_DEPLOYMENT_GUIDE.md) - QNAP Container Station 详细部署步骤
- 📗 [数据库设置指南](DATABASE_SETUP.md) - MySQL 数据库配置说明
- 📙 [Windows 测试指南](WINDOWS_TEST_GUIDE.md) - Windows 环境测试说明

## 🔧 高级功能

### 数据库支持

项目支持两种数据持久化方式：

1. **文件模式**（默认）：简单易用，适合个人使用
2. **MySQL 模式**（推荐）：性能更好，支持并发，适合生产环境

详细配置请参考 [DATABASE_SETUP.md](DATABASE_SETUP.md)

### 封面缓存

自动缓存已下载的封面图片，避免重复下载：
- 自动压缩大尺寸封面
- 优化存储空间
- 提升处理速度

### 已处理文件记录

智能记录已处理的文件，避免重复处理：
- 支持文件模式和数据库模式
- 自动去重
- 失败重试机制

## 许可证

本项目仅供学习和个人使用。

## 联系方式

如有问题或建议，请通过配置文件中的邮箱联系。

---

**⭐ 如果这个项目对你有帮助，请给个 Star！**