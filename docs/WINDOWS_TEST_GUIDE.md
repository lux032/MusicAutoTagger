# Windows 11 本地测试指南

本文档将指导你在 Windows 11 环境下，从零开始配置并运行音乐文件自动标签系统。

## 1. 环境准备

在开始之前，请确保你的电脑已安装以下软件：

### 1.1 安装 Java JDK 17+
1.  下载 Eclipse Temurin JDK 17 (推荐): [下载地址](https://adoptium.net/temurin/releases/?version=17)
2.  选择 `Windows x64` 的 `.msi` 安装包。
3.  安装时务必选择 "**Set JAVA_HOME variable**"（设置 JAVA_HOME 环境变量）。
4.  验证安装：打开 CMD（命令提示符），输入 `java -version`，应显示版本 17.x.x。

### 1.2 安装 Maven
1.  下载 Maven: [下载地址](https://maven.apache.org/download.cgi)
2.  解压到任意目录（例如 `D:\Maven`）。
3.  配置环境变量：
    *   搜索 "编辑系统环境变量"。
    *   点击 "环境变量"。
    *   在 "系统变量" 中，找到 `Path`，点击 "编辑"，新建一行，填入 `D:\Maven\bin`（你的解压路径）。
4.  验证安装：打开 CMD，输入 `mvn -v`。

### 1.3 安装 Chromaprint (fpcalc)
这是音频指纹识别的关键工具。

1.  下载 Chromaprint: [下载地址](https://acoustid.org/chromaprint)
2.  下载 Windows 版本压缩包。
3.  解压后，找到 `fpcalc.exe`。
4.  **关键步骤**：将 `fpcalc.exe` 复制到本项目的根目录下（即 `D:\MusicDemo\MusicDemo`，与 `pom.xml` 同级）。
    *   或者，将其所在文件夹添加到系统 `Path` 环境变量中。

## 2. 项目配置

### 2.1 获取 AcoustID API Key
1.  访问 [AcoustID 应用注册页面](https://acoustid.org/new-application)。
2.  登录（可以使用 Google 账号）。
3.  创建一个新应用，复制生成的 **Client API Key**。

### 2.2 修改配置文件
打开项目根目录下的 `config.properties` 文件，修改以下内容：

```properties
# 1. 设置监控目录（你的下载文件夹）
# 注意：Windows 路径中的反斜杠 \ 需要写成双反斜杠 \\ 或单斜杠 /
monitor.directory=D:/Downloads/Music

# 2. 设置输出目录（处理好的音乐保存位置）
monitor.outputDirectory=D:/Music/Tagged

# 3. 填入刚才获取的 API Key
acoustid.apiKey=你的API_KEY

# 4. 修改联系邮箱（MusicBrainz 要求）
musicbrainz.userAgent=MusicDemo/1.0 ( 你的邮箱@example.com )
```

**提示**：请确保 `monitor.outputDirectory` 目录已存在，或者程序有权限创建它。

## 3. 编译与运行

### 3.1 编译项目
打开 CMD 或 PowerShell，进入项目根目录（`D:\MusicDemo\MusicDemo`），执行：

```powershell
mvn clean package
```

如果看到 `BUILD SUCCESS`，说明编译成功。

### 3.2 运行程序
继续在命令行执行：

```powershell
mvn exec:java -Dexec.mainClass="com.lux032.musicautotagger.Main"
```

程序启动后，你会看到类似以下的日志：
```
INFO: 音乐文件自动标签系统
INFO: 配置加载成功
INFO: 监控目录: D:/Downloads/Music
INFO: 输出目录: D:/Music/Tagged
INFO: 启动文件监控服务...
```

## 4. 测试流程

1.  **准备测试文件**：找一个没有标签或者标签不全的 MP3/FLAC 音乐文件（最好是知名的英文歌曲，识别率高）。
2.  **复制文件**：将该文件复制到你配置的 `monitor.directory`（例如 `D:/Downloads/Music`）。
3.  **观察控制台**：
    *   你应该会看到 "检测到新文件" 的日志。
    *   接着是 "正在进行音频指纹识别"。
    *   然后是 "识别成功: 歌手 - 歌名"。
    *   "正在获取详细元数据..."。
    *   "正在下载封面图片..."。
    *   最后显示 "✓ 文件处理成功"。
4.  **检查结果**：
    *   打开你的输出目录（`monitor.outputDirectory`）。
    *   确认文件是否已存在，且文件名已自动重命名（例如 `Artist - Title.mp3`）。
    *   使用播放器打开文件，检查是否已包含封面图片、歌手、专辑等信息。

## 5. 常见问题排查

*   **错误：fpcalc 执行失败**
    *   原因：系统找不到 `fpcalc.exe`。
    *   解决：确保 `fpcalc.exe` 在项目根目录下，或者在系统 PATH 中。

*   **错误：API 请求失败 (403/503)**
    *   原因：请求太频繁或 User-Agent 设置不正确。
    *   解决：检查 `config.properties` 中的邮箱是否正确，稍后重试。

*   **错误：无法识别文件**
    *   原因：AcoustID 数据库中没有该歌曲的指纹信息。
    *   解决：尝试另一首更大众的歌曲。
