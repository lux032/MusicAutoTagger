# Windows 环境构建 Docker 镜像指南

本指南专门为 Windows 用户编写，手把手教你如何构建 Docker 镜像并导出为 tar 文件。

## 📋 前置要求检查

### 1. 检查 Java 和 Maven

打开 **命令提示符**（CMD）或 **PowerShell**，运行：

```cmd
java -version
```

如果看到类似输出，说明 Java 已安装：
```
java version "17.0.x"
```

再检查 Maven：
```cmd
mvn -version
```

#### 如果没有安装 Maven

**方法 A：使用 IntelliJ IDEA 编译（推荐）**

1. 打开 IntelliJ IDEA
2. 打开项目 `MusicDemo`
3. 右侧边栏找到 **Maven** 面板
4. 展开 **MusicDemo** → **Lifecycle**
5. 双击 **clean**，等待完成
6. 双击 **package**，等待完成
7. 编译好的 JAR 文件在：`target/MusicDemo-1.0-SNAPSHOT.jar`

**方法 B：安装 Maven**

1. 下载：https://maven.apache.org/download.cgi
2. 解压到：`C:\Program Files\Maven`
3. 添加环境变量：
   - 变量名：`MAVEN_HOME`
   - 变量值：`C:\Program Files\Maven`
   - Path 添加：`%MAVEN_HOME%\bin`
4. 重新打开命令提示符测试

### 2. 检查 Docker Desktop

打开命令提示符，运行：

```cmd
docker --version
```

如果看到版本信息，说明 Docker 已安装并运行。

#### 如果没有安装 Docker Desktop

1. 下载：https://www.docker.com/products/docker-desktop
2. 安装并启动 Docker Desktop
3. 确保 Docker Desktop 在系统托盘运行（鲸鱼图标）

## 🚀 构建流程

### 步骤 1: 编译 Java 项目

#### 使用 IntelliJ IDEA（推荐）

1. 打开 IntelliJ IDEA
2. 打开项目 `C:\Users\lux032\Desktop\MusicDemo`
3. 右侧 **Maven** 面板 → **Lifecycle**
4. 双击 **clean** → 等待完成
5. 双击 **package** → 等待完成（约 1-2 分钟）

成功后会看到：
```
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

JAR 文件生成在：`target\MusicDemo-1.0-SNAPSHOT.jar`

#### 或使用命令行

如果你已经安装了 Maven：

```cmd
cd C:\Users\lux032\Desktop\MusicDemo
mvn clean package -DskipTests
```

### 步骤 2: 构建 Docker 镜像

打开 **命令提示符**（以管理员身份运行），运行：

```cmd
cd C:\Users\lux032\Desktop\MusicDemo

docker build -f Dockerfile.prebuilt -t music-tagger:latest .
```

这个过程约 2-3 分钟，你会看到类似输出：
```
[+] Building 120.5s (10/10) FINISHED
 => [internal] load build definition from Dockerfile.prebuilt
 => => transferring dockerfile: 1.23kB
 => [internal] load .dockerignore
 ...
 => => naming to docker.io/library/music-tagger:latest
```

### 步骤 3: 导出镜像为 tar 文件

```cmd
docker save music-tagger:latest -o music-tagger-image.tar
```

这会在当前目录生成 `music-tagger-image.tar` 文件（约 200-250 MB）。

### 步骤 4: 压缩镜像（可选但推荐）

使用 7-Zip 或 WinRAR 压缩文件，或使用 PowerShell：

```powershell
# 使用 PowerShell 压缩（Windows 10/11）
Compress-Archive -Path music-tagger-image.tar -DestinationPath music-tagger-image.tar.gz
```

压缩后约 100-150 MB。

## 📁 完整的 PowerShell 自动化脚本

保存以下内容为 `build-image.ps1`：

```powershell
# 设置错误时停止
$ErrorActionPreference = "Stop"

Write-Host "=========================================" -ForegroundColor Green
Write-Host "  Docker 镜像构建工具 (Windows)" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Green
Write-Host ""

# 检查 Docker
Write-Host "[检查] Docker 是否运行..." -ForegroundColor Yellow
$dockerRunning = docker ps 2>$null
if (-not $?) {
    Write-Host "[错误] Docker 未运行，请启动 Docker Desktop" -ForegroundColor Red
    exit 1
}
Write-Host "[成功] Docker 正在运行" -ForegroundColor Green
Write-Host ""

# 检查 JAR 文件
Write-Host "[检查] JAR 文件是否存在..." -ForegroundColor Yellow
if (-not (Test-Path "target\MusicDemo-1.0-SNAPSHOT.jar")) {
    Write-Host "[警告] 找不到 JAR 文件" -ForegroundColor Yellow
    Write-Host "[提示] 请先在 IntelliJ IDEA 中编译项目" -ForegroundColor Yellow
    Write-Host "       Maven -> Lifecycle -> clean -> package" -ForegroundColor Yellow
    Write-Host ""
    $response = Read-Host "是否已经编译完成? (y/n)"
    if ($response -ne "y") {
        exit 1
    }
}
Write-Host "[成功] JAR 文件存在" -ForegroundColor Green
Write-Host ""

# 构建镜像
Write-Host "[步骤 1/3] 构建 Docker 镜像..." -ForegroundColor Yellow
docker build -f Dockerfile.prebuilt -t music-tagger:latest .
if (-not $?) {
    Write-Host "[错误] 镜像构建失败" -ForegroundColor Red
    exit 1
}
Write-Host "[成功] 镜像构建完成" -ForegroundColor Green
Write-Host ""

# 导出镜像
Write-Host "[步骤 2/3] 导出镜像为 tar 文件..." -ForegroundColor Yellow
docker save music-tagger:latest -o music-tagger-image.tar
if (-not $?) {
    Write-Host "[错误] 镜像导出失败" -ForegroundColor Red
    exit 1
}
Write-Host "[成功] 镜像导出完成" -ForegroundColor Green
Write-Host ""

# 压缩文件
Write-Host "[步骤 3/3] 压缩 tar 文件..." -ForegroundColor Yellow
if (Test-Path "music-tagger-image.tar.gz") {
    Remove-Item "music-tagger-image.tar.gz"
}
Compress-Archive -Path music-tagger-image.tar -DestinationPath music-tagger-image.tar.gz
Write-Host "[成功] 压缩完成" -ForegroundColor Green
Write-Host ""

# 显示结果
$tarSize = (Get-Item music-tagger-image.tar).Length / 1MB
$gzSize = (Get-Item music-tagger-image.tar.gz).Length / 1MB

Write-Host "=========================================" -ForegroundColor Green
Write-Host "  构建完成！" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Green
Write-Host ""
Write-Host "生成的文件:" -ForegroundColor Cyan
Write-Host "  - music-tagger-image.tar     ({0:N2} MB)" -f $tarSize -ForegroundColor White
Write-Host "  - music-tagger-image.tar.gz  ({0:N2} MB)" -f $gzSize -ForegroundColor White
Write-Host ""
Write-Host "推荐上传: music-tagger-image.tar.gz (更小)" -ForegroundColor Yellow
Write-Host ""
Write-Host "下一步操作:" -ForegroundColor Cyan
Write-Host "  1. 将 music-tagger-image.tar.gz 上传到 QNAP" -ForegroundColor White
Write-Host "  2. 在 Container Station 中导入镜像" -ForegroundColor White
Write-Host "  3. 创建容器" -ForegroundColor White
Write-Host ""
Write-Host "详细步骤请查看: QNAP_IMAGE_IMPORT_GUIDE.md" -ForegroundColor Yellow
Write-Host ""