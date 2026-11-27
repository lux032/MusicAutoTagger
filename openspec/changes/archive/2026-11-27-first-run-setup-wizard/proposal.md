# Proposal

## Why

当前项目首次部署（尤其是 Docker / NAS 场景）存在两处明显的启动与配置断点：程序启动时会带着模板占位配置（如 `acoustid.apiKey=YOUR_API_KEY_HERE`、默认英文）立即启动文件监控，在用户尚未配置凭据、代理或审核偏好前就可能开始处理挂载目录中的音频文件；同时 Web 面板首次进入仅提供「创建管理员」单步弹窗（且禁用语言选择），建号后用户直接面对分散在 5 个设置分页中的 50 余项配置，缺少按依赖顺序引导填写目录、代理、AcoustID、MusicBrainz、LLM 供应商与 Tavily 等关键参数的能力。

## What Changes

- **首次启动待命模式与进程内免重启激活**（`Main`、`ApplicationLifecycleManager`、`WebServer`、初始化状态持久化）：
  - 全新启动（未完成初始化且不存在既有管理员凭据）时，仅启动 Web 服务与初始化所需接口，文件监控与核心处理服务保持待命，不提前扫描下载目录。
  - 用户完成初始化引导（或显式跳过剩余步骤）提交最终配置后，后端原子落盘 `config.properties`、`data/llm-providers.json` 与初始化完成标记，并在当前进程内按最终配置一次性初始化核心服务并启动文件监控，使首次配置的目录与代理无需额外重启容器或进程即可生效。
- **初始化引导、即时语言切换与连通性校验 API**（新增 `SetupServlet`，配合 `AuthServlet`、`I18nServlet`、`LlmProviderServlet`）：
  - 在未登录/首次引导阶段开放实时语言切换与环境自检摘要（`fpcalc`、`ffmpeg`、默认 SQLite 状态及默认目录可用性）。
  - 提供已认证的初始化校验接口：目录存在性/读写权限探测、HTTP 代理连通性测试、AcoustID API Key 与 MusicBrainz 连通性验证、Tavily API Key 测试，以及拉取 LLM 模型列表时透传向导暂存代理参数（所有出站请求严格遵守现有 `OutboundUrlValidator` SSRF 规则，只读安全字段继续禁止 Web 修改）。
  - 提供统一的引导完成提交接口，支持在全新启动时直接激活服务，以及老用户从设置页重跑引导时按现有 `RESTART_ONLY_FIELDS` 规则标记是否需要重启。
- **5 步初始化引导界面与配套收口**（前端向导页、`index.html`、`shell.js` 及中英文 `messages_*.properties`）：
  - 遵循 `DESIGN.md`（冷灰底、居中白卡片、`1px` 细线、单一 Signal Blue 主按钮），将原「创建管理员」合并为 5 步全屏引导：`1. 账号与语言 -> 2. 目录与网络 -> 3. 识别数据源 -> 4. AI 与联网搜索（可选） -> 5. 整理偏好与启动确认`。
  - 允许跳过第 4 步（AI/联网搜索）以及暂缓第 3 步（AcoustID Key）；当 AcoustID Key 仍为空或占位符时，在概览页顶部展示可忽略的轻提示横幅并提供快捷补全入口。
  - 在设置页顶部增加「重新运行初始化引导」按钮（已建管理员时自动从第 2 步开始）。
  - 实现前先产出可交互静态 HTML 视觉原型供验收，再进入正式前后端实现。

## Capabilities

### New Capabilities
- `first-run-setup`: 首次启动待命保护、进程内免重启初始化激活、5 步初始化引导流程（账号与语言、目录与代理、识别数据源、AI 与联网搜索、整理偏好）、配置项实时连通性校验、老用户升级兼容及未完成必填项的概览补救提示能力。

### Modified Capabilities
<!-- 现有 specs（online-identification、online-archive-credits）的需求语义均无变更 -->

## Impact

- **启动与生命周期**：`Main`、`ApplicationLifecycleManager`、`WebServer`（支持先以待初始化模式仅挂载基础认证/国际化/初始化路由，引导完成后在同进程内装配完整业务服务与路由并启动 `FileMonitorService`）。
- **配置与状态持久化**：新增轻量初始化状态记录（例如 `data/setup-state.json`，含完成标志与忽略提示状态）；复用现有 `config.properties`、`data/admin.json` 与 `data/llm-providers.json` 存储结构，不引入重复配置源。
- **Web API 与安全边界**：新增 `/api/setup/*` 路由；扩展 `AuthFilter` 白名单仅限无敏感信息的 `/api/setup/status`（与 `/api/i18n`），其余校验、拉取与保存接口均需管理员会话与 CSRF 校验；`audio.normalize.ffmpegPath`、`llm.allowPrivateEndpoints`、`db.sqlite.path`、`review.queuePath` 等只读安全项继续拒绝 Web 修改，所有测试连接接口经过 `OutboundUrlValidator` 防 SSRF 校验。
- **前端与国际化**：新增初始化引导页面/交互流程，改造 `index.html` 首次进入判断、概览页轻提示横幅及设置页重入按钮，同步补齐 `messages_zh_CN.properties` 与 `messages_en_US.properties`。
- **兼容性与非目标**：已有 `data/admin.json` 的老用户升级后默认视为已完成初始化，不强弹全屏向导；数据库仍默认 SQLite 零配置，MySQL 建库建表、CUE 分割、高级阈值微调等保留在设置页，不纳入首次引导。
