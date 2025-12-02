# QNAP Container Station 镜像导入指南

本指南介绍如何使用预先构建好的 Docker 镜像 tar 文件在 QNAP Container Station 中部署应用。

## ✨ 这种方式的优势

- ✅ **最简单**：无需在 QNAP 上编译或构建
- ✅ **最快速**：直接导入即用，1-2 分钟完成
- ✅ **最省资源**：不占用 QNAP 的 CPU 和内存进行构建
- ✅ **跨平台**：在任何电脑上构建，在 QNAP 上运行
- ✅ **可分发**：可以将镜像文件分享给其他人使用

## 📦 完整部署流程

### 步骤 1: 在本地电脑构建镜像（Windows/Mac/Linux）

#### 方法 A：使用自动化脚本（推荐）

```bash
# 1. 进入项目目录
cd MusicDemo

# 2. 赋予执行权限（Linux/Mac）
chmod +x build-image-tar.sh

# 3. 运行构建脚本
./build-image-tar.sh

# 4. 选择构建方式
#    - 选项 1: 完整构建（首次使用或修改代码后）
#    - 选项 2: 使用已有 JAR（已经编译过）

# 5. 是否压缩（建议选 y，文件更小）
#    压缩后约 100-150 MB，未压缩约 200-250 MB
```

#### 方法 B：手动构建

```bash
# 1. 编译 Java 项目
mvn clean package -DskipTests

# 2. 构建 Docker 镜像
docker build -f Dockerfile.prebuilt -t music-tagger:latest .

# 3. 导出镜像为 tar 文件
docker save music-tagger:latest -o music-tagger-image.tar

# 4. 压缩文件（可选）
gzip music-tagger-image.tar
```

构建完成后，你会得到：
- `music-tagger-image.tar` 或
- `music-tagger-image.tar.gz`（压缩版）

### 步骤 2: 上传镜像文件到 QNAP

#### 方法 A：使用 File Station（推荐，适合新手）

1. 打开 QNAP 的 **File Station**
2. 创建目录：`/share/Public/docker-images/`
3. 将镜像文件拖拽上传到该目录
4. 等待上传完成（根据网速，约 5-15 分钟）

#### 方法 B：使用 SCP（更快）

```bash
# 如果是压缩文件
scp music-tagger-image.tar.gz admin@your-qnap-ip:/share/Public/

# 如果是未压缩文件
scp music-tagger-image.tar admin@your-qnap-ip:/share/Public/
```

#### 方法 C：使用 SFTP 客户端

使用 FileZilla、WinSCP 等 SFTP 客户端上传。

### 步骤 3: 在 QNAP Container Station 导入镜像

1. 打开 **Container Station** 应用

2. 点击左侧菜单 **"镜像"**

3. 点击右上角 **"拉取"** 按钮

4. 在弹出窗口中，选择 **"从文件导入"** 标签页

5. 点击 **"浏览"** 按钮

6. 导航到上传的镜像文件位置：
   - 如果是 `.tar.gz` 文件，Container Station 会自动识别并解压
   - 如果是 `.tar` 文件，直接导入

7. 选择文件后，点击 **"确定"**

8. 等待导入完成（约 1-2 分钟）

9. 导入成功后，在镜像列表中会看到 `music-tagger:latest`

### 步骤 4: 创建容器

#### 方法 A：使用 Container Station 图形界面

1. 在镜像列表中找到 `music-tagger:latest`

2. 点击镜像右侧的 **"+"** 按钮（创建容器）

3. **基本设置**：
   - 容器名称：`music-tagger`
   - CPU 限制：1 核心
   - 内存限制：1024 MB
   - 勾选 **"自动重启"**

4. **高级设置** → **网络**：
   - 网络模式：Bridge

5. **高级设置** → **环境变量**：
   添加以下环境变量：
   ```
   JAVA_OPTS=-Xmx512m -Xms256m
   TZ=Asia/Shanghai
   ```

6. **高级设置** → **共享文件夹**：
   添加以下卷挂载：

   | 主机路径 | 容器路径 | 权限 |
   |---------|---------|------|
   | `/share/Download/Music` | `/music` | 读写 |
   | `/share/Music` | `/app/tagged_music` | 读写 |
   | `/share/Container/music-tagger/config.properties` | `/app/config.properties` | 只读 |
   | `/share/Container/music-tagger/logs` | `/app/logs` | 读写 |
   | `/share/Container/music-tagger/cover_cache` | `/app/.cover_cache` | 读写 |

   ⚠️ **重要**：请根据你的实际路径修改！

7. 点击 **"创建"**

8. 容器会自动启动

#### 方法 B：使用 SSH 命令行

如果你已经上传了 `config.properties` 和其他配置文件到 QNAP：

```bash
# SSH 连接到 QNAP
ssh admin@your-qnap-ip

# 进入配置目录
cd /share/Container/music-tagger

# 创建必要的目录
mkdir -p logs cover_cache

# 使用 docker run 创建容器
docker run -d \
  --name music-tagger \
  --restart unless-stopped \
  -e JAVA_OPTS="-Xmx512m -Xms256m" \
  -e TZ="Asia/Shanghai" \
  -v /share/Download/Music:/music \
  -v /share/Music:/app/tagged_music \
  -v /share/Container/music-tagger/config.properties:/app/config.properties:ro \
  -v /share/Container/music-tagger/logs:/app/logs \
  -v /share/Container/music-tagger/cover_cache:/app/.cover_cache \
  music-tagger:latest
```

### 步骤 5: 验证部署

1. 在 Container Station 的 **"容器"** 列表中查看容器状态
   - 应该显示为 **"运行中"**（绿色）

2. 点击容器名称，查看 **"日志"** 标签页
   - 确认应用正常启动
   - 没有错误信息

3. 测试功能：
   - 将一个音乐文件放到监控目录
   - 等待处理（默认 30 秒扫描一次）
   - 检查输出目录是否有处理后的文件

## 📝 配置文件准备

在创建容器之前，需要先准备好配置文件：

1. 在 QNAP 创建目录：
   ```bash
   mkdir -p /share/Container/music-tagger
   ```

2. 上传或创建 `config.properties`：
   - 可以从 `config.properties.example` 复制
   - 或直接在 File Station 中创建文件

3. 必须配置的项目：
   ```properties
   # 监控目录（容器内路径）
   monitor.directory=/music
   monitor.outputDirectory=/app/tagged_music
   
   # AcoustID API Key（必须！）
   acoustid.apiKey=YOUR_API_KEY_HERE
   
   # MusicBrainz User Agent
   musicbrainz.userAgent=MusicDemo/1.0 ( your-email@example.com )
   
   # 数据库配置
   db.type=mysql
   db.mysql.host=192.168.x.x  # 你的 QNAP IP
   db.mysql.database=music_demo
   db.mysql.username=root
   db.mysql.password=your_password
   ```

## 🔄 更新镜像

当需要更新应用版本时：

1. 在本地重新构建镜像：
   ```bash
   ./build-image-tar.sh
   ```

2. 上传新的镜像文件到 QNAP

3. 在 Container Station 中：
   - 停止并删除旧容器（数据不会丢失）
   - 删除旧镜像
   - 导入新镜像
   - 重新创建容器

## 📊 文件大小参考

| 文件 | 未压缩 | 压缩后 (gzip) |
|-----|-------|--------------|
| 镜像 tar | ~250 MB | ~100-150 MB |
| 上传时间 (100Mbps) | ~3-4 分钟 | ~1-2 分钟 |
| 导入时间 | ~1 分钟 | ~1-2 分钟 |

## 🔍 常见问题

### Q: 压缩文件需要先解压吗？

不需要，Container Station 支持直接导入 `.tar.gz` 文件。

### Q: 可以在 Windows 上构建镜像吗？

可以，但需要安装：
- Docker Desktop for Windows
- Maven（或使用 IDE 如 IntelliJ IDEA 内置的 Maven）

### Q: 导入镜像失败怎么办？

检查：
1. 文件是否完整（对比文件大小）
2. QNAP 存储空间是否足够（至少 500MB）
3. Container Station 是否正常运行
4. 尝试重启 Container Station

### Q: 镜像可以在不同的 QNAP 上使用吗？

可以！这就是这种部署方式的优势。你可以：
- 在一台 QNAP 上构建，在多台 QNAP 上使用
- 将镜像文件分享给朋友
- 作为备份保存

### Q: 需要每次都上传整个镜像吗？

首次部署需要上传完整镜像。后续更新时：
- 如果只是配置变更：修改配置文件，重启容器即可
- 如果是代码更新：需要重新构建和上传镜像

### Q: Windows 没有 bash，怎么运行 .sh 脚本？

可以使用：
1. **Git Bash**（推荐）：安装 Git for Windows 自带
2. **WSL**（Windows Subsystem for Linux）
3. **手动执行命令**：参考脚本内容，逐条运行

或者使用手动构建方法（方法 B）。

## 💡 最佳实践

1. **首次部署**：
   - 使用压缩镜像（节省上传时间）
   - 先测试配置文件是否正确

2. **定期更新**：
   - 保存镜像文件作为备份
   - 更新前先导出容器配置

3. **多台 QNAP**：
   - 构建一次，处处使用
   - 使用版本号标记镜像（如 `music-tagger:v1.0`）

4. **存储管理**：
   - 定期清理旧镜像
   - 使用单独的数据卷存储日志和缓存

## 📚 相关文档

- [build-image-tar.sh](build-image-tar.sh) - 自动化构建脚本
- [QNAP_DEPLOYMENT_GUIDE.md](QNAP_DEPLOYMENT_GUIDE.md) - 完整部署指南
- [config.properties.example](config.properties.example) - 配置文件模板
- [README.md](README.md) - 项目总览

---

**这是最简单、最快速的 QNAP 部署方式！** 🎉