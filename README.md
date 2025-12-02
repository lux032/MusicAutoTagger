# 🎵 Music Auto Tagger | 音乐文件自动整理工具

[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://www.java.com/)
[![Docker](https://img.shields.io/badge/Docker-Ready-blue.svg)](https://www.docker.com/)
[![MusicBrainz](https://img.shields.io/badge/Data-MusicBrainz-purple.svg)](https://musicbrainz.org/)
[![LrcLib](https://img.shields.io/badge/Lyrics-LrcLib-green.svg)](https://lrclib.net/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**Music Auto Tagger** 是一个基于音频指纹的自动化音乐整理工具。它专为 NAS 和服务器环境设计，能够“监听”下载目录，自动识别音乐文件，补全元数据（包括歌词），并整理归档。

> **English Description**: A fully automated music tagger based on audio fingerprinting. It monitors your download folder, identifies music files using AcoustID & MusicBrainz, fetches lyrics from LrcLib, and organizes them into a clean structure.

## ✨ 核心特性

- 🎧 **音频指纹识别**：基于 **Chromaprint (AcoustID)**，即使文件名是乱码 (`track01.mp3`) 也能精准识别。
- 📝 **权威元数据**：数据源自 **MusicBrainz**，自动补全标题、艺术家、专辑、年份、**作曲**、**作词**等信息。
- 📜 **自动同步歌词**：🆕 集成 **LrcLib**，自动下载并嵌入 **同步歌词 (.lrc)**，完美支持现代播放器。
- 🖼️ **高清封面**：自动从 Cover Art Archive 下载并内嵌高清专辑封面。
- 📁 **自动化整理**：按照 `艺术家/专辑/歌曲名` 的结构自动重命名和归档文件。
- 🤖 **无人值守**：配合 qBittorrent/Transmission 使用，下载完成后自动处理，无需人工干预。
- 💾 **双模式持久化**：
    - **文本模式 (默认)**：无需数据库，使用 CSV 文件记录已处理文件，开箱即用，适合个人用户。
    - **MySQL 模式**：支持连接外部数据库，适合海量文件和高性能并发场景。
- 🐳 **Docker 部署**：提供轻量级 Docker 镜像，支持 Synology/QNAP/Unraid 等 NAS 系统。

## 🚀 快速开始 (Docker Compose)

这是最简单的运行方式。无需安装 Java 环境。

1.  **下载配置文件模板**
    下载仓库中的 `config.properties.example` 并重命名为 `config.properties`。

2.  **申请 API Key (免费)**
    访问 [AcoustID](https://acoustid.org/new-application) 申请一个 API Key，填入配置文件：
    ```properties
    acoustid.apiKey=YOUR_API_KEY_HERE
    ```

3.  **创建 `docker-compose.yml`**
    ```yaml
    version: '3.8'
    services:
      music-tagger:
        image: ghcr.io/lux032/musicautotagger:latest # 或者 build: .
        container_name: music-tagger
        volumes:
          - /path/to/downloads:/music           # 你的下载目录
          - /path/to/music_library:/app/tagged_music # 整理后的音乐库
          - ./config.properties:/app/config.properties
        restart: unless-stopped
    ```

4.  **启动服务**
    ```bash
    docker-compose up -d
    ```

## 💻 本地运行

如果你想在本地开发或运行：

### 前置要求
- JDK 17+
- Maven 3.6+
- [Chromaprint (fpcalc)](https://acoustid.org/chromaprint) (需添加到系统 PATH)

### 编译与运行
```bash
# 1. 编译
mvn clean package

# 2. 配置
cp config.properties.example config.properties
# 编辑 config.properties 填入 API Key

# 3. 运行
java -jar target/MusicDemo-1.0-SNAPSHOT.jar
```

## 📚 文档指南

- **QNAP NAS 用户**：请参阅 [QNAP 部署指南](docs/QNAP_DEPLOYMENT_GUIDE.md)
- **数据库配置**：默认使用文件记录处理状态，如需使用 MySQL 请参阅 [数据库设置](docs/DATABASE_SETUP.md)
- **Windows 指南**：[Windows 构建与测试](docs/WINDOWS_BUILD_GUIDE.md)

## ⚙️ 配置文件说明

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `monitor.directory` | 监控的源目录 (Docker内路径) | `/music` |
| `monitor.outputDirectory` | 输出目标目录 (Docker内路径) | `/app/tagged_music` |
| `acoustid.apiKey` | **[必填]** AcoustID API 密钥 | - |
| `musicbrainz.userAgent` | 用于 API 请求的 User-Agent | `MusicTagTool/1.0` |
| `file.autoRename` | 是否自动重命名文件 | `true` |
| `cover.download` | 是否下载封面 | `true` |

### 💾 数据持久化配置 (可选)

本项目默认使用 **文本文件 (CSV)** 记录已处理的文件，无需任何额外配置即可运行。如果您希望使用 MySQL 数据库：

1.  在 `docker-compose.yml` 中添加 MySQL 服务（参考 `docs/DATABASE_SETUP.md`）。
2.  在 `config.properties` 中添加以下配置：

```properties
# 数据库类型: file (默认) 或 mysql
db.type=mysql
db.mysql.host=db
db.mysql.port=3306
db.mysql.database=music_tagger
db.mysql.username=root
db.mysql.password=example
```

## 🤝 贡献与支持

欢迎提交 Issue 或 Pull Request！

如果你觉得这个项目对你有帮助，请给个 ⭐ **Star** 支持一下！

---
**Disclaimer**: This tool relies on third-party services (MusicBrainz, AcoustID, LrcLib). Please respect their Terms of Service.