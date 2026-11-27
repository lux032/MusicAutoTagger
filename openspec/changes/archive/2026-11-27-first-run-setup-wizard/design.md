# Design

## Context

参见 `proposal.md` 的背景说明。当前实现有以下直接影响本设计的工程现状与约束：

- **启动链路耦合**：`Main.main()` 在加载 `MusicConfig` 后立即依次调用 `lifecycleManager.initializeServices()`、`startWebServer()`、`startMonitoring()`。Docker 镜像通过 `Dockerfile` 预置了 `config.properties.example` 作为 `config.properties`（其中 `acoustid.apiKey=YOUR_API_KEY_HERE`、`musicbrainz.userAgent=MusicDemo/1.0 ( your-email@example.com )`），因此首次启动时 `config.isValid()` 为 `true`，文件监控在用户打开 Web 面板前就已经开始运行。
- **Web 服务与认证**：`WebServer` 基于嵌入式 Jetty，将业务 Servlet 直接绑定到 `ServletContextHandler`，并在 `/api/*` 挂载 `AuthFilter`（仅放行 `/api/i18n` 与 `/api/auth/*`）。`AuthServlet` 通过 `AdminCredentialsStore`（`data/admin.json`）返回 `needsSetup`。
- **配置与重启边界**：`ConfigServlet` 将 `monitorDirectory`、`outputDirectory`、`scanIntervalSeconds`、数据库与 `proxy.*` 列为 `RESTART_ONLY_FIELDS`，修改后只写盘不改活态 `MusicConfig`，防止已初始化的服务与内存配置脑裂。只读安全字段（`audioNormalizeFfmpegPath`、`llmAllowPrivateEndpoints`、`dbSqlitePath`、`reviewQueuePath`、`reviewStagingDirectory`、`recoveryWorkDirectory`、`recoveryTrashDirectory`）拒绝 Web 修改。
- **出站安全与代理**：`HttpClientFactory` 统一构建带代理认证的 JDK / Apache HttpClient；`OutboundUrlValidator` 提供公网 IP / DNS Pinning / Origin 校验；`LlmModelCatalog` 负责拉取模型并探测 `suggestedApiUrl`。
- **前端与视觉规范**：前端为无构建步骤的原生 HTML/CSS/JS（`src/main/resources/static/`），严格遵循 `DESIGN.md`（冷灰底 `#f2f4f8` + 白卡片 `#ffffff` + `1px` `#e3e7ee` 细线 + 单一 Signal Blue `#1d6ef0` 主操作，全量静态文案走 `data-i18n` 与 `messages_*.properties`）。

## Goals / Non-Goals

**Goals:**
- 全新首次启动时将后端分为「待初始化模式（Setup Mode）」与「运行态（Active Mode）」，在不重启 JVM、不重启 Jetty 的前提下完成从向导配置到激活核心服务与监控的无缝切换。
- 在引导流程内支持实时中英文切换、目录读写检测，以及携带未落盘代理参数的代理/AcoustID/MusicBrainz/Tavily 连通性测试与 LLM 模型拉取。
- 复用既有 `AdminCredentialsStore`、`ConfigServlet` 落盘/校验逻辑、`LlmProviderStore`、`HttpClientFactory` 与 `OutboundUrlValidator`，不引入第二套配置存储或绕开任何既有安全防线。
- 保持老用户（已有 `data/admin.json`）升级零打扰，同时支持在设置页重跑引导。
- 实现编码前先通过独立可交互静态 HTML 原型完成视觉与交互验收。

**Non-Goals:**
- 不改造 MySQL 外部建库建表流程，也不在首次引导中展开 MySQL 高级连接池、CUE 分割或置信度微调等低频高级项。
- 不放开任何现有只读安全字段（`ffmpegPath`、`llmAllowPrivateEndpoints` 等）的 Web 修改权限。
- 不允许通过代理测试接口探测用户自定义的任意 URL。
- 不引入前端打包工具链（保持原生 HTML/CSS/JS 与现有 `app.css` 复用）。

## Decisions

### Decision 1: 初始化状态存储与老用户兼容判定（`SetupStateStore`）
- **选择**：新增轻量状态文件 `data/setup-state.json`（由 `SetupStateStore` 按与 `AdminCredentialsStore` 相同的原子写 + `0600` 权限管理），包含 `completed`（boolean）、`completedAt`（ISO-8601）、`dismissedAcoustIdWarning`（boolean）。启动与状态判定遵循确定规则：
  - `setupCompleted = (setupState != null && setupState.completed) || (!setupStateExists && adminCredentialsExist)`
  - 换言之：**老用户升级**（有 `data/admin.json`、无 `data/setup-state.json`）直接判为已完成，自动平滑进入运行态；**全新安装**（无 `admin.json` 且无 `setup-state.json`）进入待初始化模式。
  - **断点续配保护**：若全新用户在第 1 步创建了 `admin.json` 后意外关闭浏览器或容器重启，因建号时写入 `setup-state.json` 为 `completed=false`，下次打开登录后仍会回到第 2 步继续完成引导，不会意外卡死在半初始化态或把老用户判定逻辑混淆。
- **替代方案**：把 `setup.completed=true` 写进 `config.properties`——容易被用户在 Docker 中覆盖挂载旧配置或手动替换模板时重置，而 `data/` 目录本就与 `admin.json`、`review-queue.json` 一起持久化，语义更一致。

### Decision 2: 待初始化模式与进程内免重启激活架构
- **选择**：在 `ApplicationLifecycleManager` 中引入两阶段启动与同步激活入口：
  1. **待初始化模式**：`Main` 检测到 `!setupStateStore.isCompleted(adminStore)` 时，不调用 `initializeServices()` 与 `startMonitoring()`，仅执行 `startWebServerInSetupMode()`。此时仅初始化轻量环境探测器（检测 `fpcalc` / `ffmpeg` 可用性）并挂载 `AuthServlet`、`I18nServlet`、`SetupServlet`、`ConfigServlet`、`LlmProviderServlet` 与带待命拦截的业务路由。
  2. **路由与服务热装配**：为了避免在 `POST /api/setup/complete` 响应过程中重启 Jetty 导致连接中断或端口竞争，`WebServer` 在启动时为全部业务路由注册轻量代理/懒绑定容器（或在 `ServletContextHandler` 中通过 `LifecycleGuardFilter` 对未激活时的业务 `/api/*` 返回 `503 {"error":"setup.required"}`，并在 `activateServices()` 完成后将 `ApplicationLifecycleManager` 刚初始化好的服务实例原子注入路由委托）。
  3. **进程内激活**：`POST /api/setup/complete` 持有互斥锁：校验并落盘 `config.properties`（直接应用到活态 `MusicConfig`，因尚无运行中服务不会脑裂）与 `llm-providers.json` -> 调用 `lifecycleManager.activateAfterSetup()`（依次执行 `initializeServices()`、绑定业务路由、启动 `FileMonitorService`） -> 成功后再将 `setup-state.json` 置为 `completed=true`。若任一步骤抛异常，不写 `completed=true` 并回滚/清理半初始化服务，前端停留在第 5 步展示具体错误供修正重试。
  4. **老用户重跑引导（已激活态）**：重跑时调用同一提交接口但走「已激活分支」：复用 `ConfigServlet` 的 `RESTART_ONLY_FIELDS` 区分逻辑，热字段实时生效，涉及目录/代理等需重启字段仅落盘并返回 `requiresRestart: true` 与 `pendingRestartFields`，前端提示一键调用既有 `/api/control/restart`。
- **替代方案**：
  - 方案 B：首次启动照常初始化所有服务但只暂停 `FileMonitorService`，引导完强制调用 `/api/control/restart` 退出 JVM——缺点是本地直接跑 `java -jar` 的用户（Windows/Linux）进程退出后不会自动重启，体验断层；且首次配置代理在重启前无法用于各服务。进程内延迟初始化彻底消除了本地与 Docker 的体验差异。

### Decision 3: 即时语言切换与 `/api/i18n` 扩展
- **选择**：
  - 扩展 `I18nServlet.doGet()` 支持可选查询参数 `?lang=zh_CN|en_US`（白名单校验），按请求语言读取对应 `messages_*.properties` 返回，不修改全局配置；这样未登录用户在第 1 步即可无副作用地实时预览并切换中英文界面。
  - 在 `POST /api/auth/setup`（或 `POST /api/setup/language` 登录后）同步将选定语言持久化到 `config.properties` 并调用 `I18nUtil.init(lang)`，确保后端日志与后续页面刷新保持所选语言。
- **替代方案**：让未登录接口直接改写服务器 `config.properties` 的语言——未认证写盘存在篡改风险，仅用查询参数预览 + 建号/提交时持久化更安全。

### Decision 4: 引导阶段连通性校验接口与 SSRF / 隐私防护
- **选择**：新增 `SetupServlet` 挂载在 `/api/setup/*`：
  - `GET /api/setup/status`（`AuthFilter` 放行，仅返回非敏感布尔与摘要：`setupCompleted`、`needsAdmin`、`active`、`defaultLanguage`；环境详情与默认路径在登录后通过 `GET /api/setup/bootstrap` 返回，避免未认证泄露宿主机路径与二进制探测信息）。
  - `GET /api/setup/bootstrap`（需登录）：返回环境自检（`fpcalcAvailable`、`ffmpegAvailable`、`dbType`）、当前配置回填（密钥掩码，并将 `YOUR_API_KEY_HERE` 等模板占位值显式标记为 `acoustIdApiKeyPlaceholder: true` 以便前端清空引导输入）、当前 LLM 供应商列表与 `isRerun` 标记。
  - `POST /api/setup/check-directories`（需登录 + CSRF）：校验提交的 `monitorDirectory`（绝对路径、存在、目录、可读）与 `outputDirectory`（绝对路径、存在或父目录可创建、可写），仅返回布尔与错误码，不枚举目录内容。
  - `POST /api/setup/test-proxy`（需登录 + CSRF）：接收临时代理参数（支持复用已存掩码密码仅当 host/port 未变），通过 `HttpClientFactory` 构造短超时（8s）临时客户端，向**写死的受控公网端点**（MusicBrainz API 轻量请求 `https://musicbrainz.org/ws/2/artist/5b11f4ce-a62d-471e-81fc-a69a8278c7da?fmt=json` 或 HEAD）发起探测，返回 `ok`、`latencyMs` 与错误分类（连接拒绝、代理认证失败 407、超时等）。不接受任意自定义目标 URL。
  - `POST /api/setup/test-sources`（需登录 + CSRF）：接收临时 `acoustIdApiKey`（支持掩码回退）、`userAgent` 及可选临时代理覆盖：
    - **AcoustID 探测**：向配置的 `acoustIdApiUrl`（经 `OutboundUrlValidator` 校验）发送最小参数 `client=<key>&duration=1&fingerprint=AQAA`；解析 JSON 响应，若返回 `error.code == 4`（`invalid API key`）判定密钥无效，若 `status == "ok"` 或 `error.code == 3`（`invalid fingerprint`，表明鉴权已通过）判定密钥有效且可达。
    - **MusicBrainz 探测**：带提交的 `User-Agent` 访问 `musicBrainzApiUrl`（经 `OutboundUrlValidator` 校验）并返回状态与延迟。
  - `POST /api/setup/test-tavily`（需登录 + CSRF）：接收临时 `tavilyApiKey`（支持掩码回退）与可选临时代理覆盖，对 `tavilyApiUrl`（经 `OutboundUrlValidator` 校验）发最小 `basic` + `max_results=1` 请求，区分 `401/403`（Key 无效）与 `200`（可用）。
  - **LLM 拉取模型透传临时代理**：扩展 `LlmProviderServlet` 的 `POST /api/llm/providers/models`（或在 `LlmModelCatalog.fetch` 增加可选代理配置重载），支持向导第 4 步传入第 2 步尚未落盘的临时代理，仍完全复用 `OutboundUrlValidator.validateAndResolve(..., config.isLlmAllowPrivateEndpoints(), false, true)`。
  - `POST /api/setup/dismiss-acoustid-warning`（需登录 + CSRF）：持久化 `dismissedAcoustIdWarning=true` 至 `data/setup-state.json`，供概览页轻提示横幅「忽略」按钮使用。
  - `POST /api/setup/complete`（需登录 + CSRF）：一次性校验、保存引导配置与 LLM 供应商，并按当前是否处于待初始化态执行进程内激活或返回 `requiresRestart`。
- **关键边界**：
  - 所有测试接口必须使用有界读取（`OutboundUrlValidator.readBoundedString`）、禁止自动重定向（`Redirect.NEVER`）、绝不在日志或响应中回显明文 API Key / 代理密码。
  - 代理测试时代理主机允许内网/回环地址（自托管 NAS 上 Clash/V2Ray 常见于 `127.0.0.1` 或局域网 IP），但最终请求目标固定为内置公开端点以杜绝任意内网 HTTP 探测。

### Decision 5: 前端向导结构与原型优先策略
- **选择**：
  - 将初始化引导实现为独立静态页面 `src/main/resources/static/setup.html`（复用 `app.css`、`ui.js`、`errors.js`、`api.js`），避免把已超 3000 行的 `index.html` 继续膨胀；`index.html`（及子页）启动检查 `/api/auth/status` + `/api/setup/status` 时，若 `!setupCompleted` 则直接跳转至 `setup.html`（同时保留已建号后的正常登录入口：若 `needsAdmin=false && !setupCompleted && !authenticated`，先登录再自动回到 `setup.html#step=2`）。
  - 严格遵守 `DESIGN.md`：冷灰底 `#f2f4f8` 居中单卡 `max-width: 760px`、顶部 5 步线性指示器、每步仅 1 个右下角 Signal Blue 主按钮、无渐变、无 emoji（全用 1.8px 描边 SVG）、中英双语全部走 `data-i18n`。
  - 概览页轻提示横幅仅在 `acoustIdMissing && !dismissedAcoustIdWarning` 时渲染在健康条上方，采用白底 + `1px` 细线 + 琥珀状态图标，点击「补全配置」直达 `index.html#settings` 的识别标签页并聚焦 `cfgAcoustIdApiKey`。
  - **原型先行**：在编写后端和正式 `setup.html` 前，先产出可直接在浏览器打开交互体验的独立静态 HTML 视觉原型（内置 Mock 数据与 5 步完整交互状态、中英文切换、错误/成功校验态演示），供用户验收后再按确认稿正式落地。
- **替代方案**：把 5 步向导塞进 `index.html` 的 `#loginOverlay`——会让本已 3000 行的 `index.html` 更难维护，也不利于独立 URL（如设置页重跑直接跳 `setup.html?mode=rerun`）。

## Risks / Trade-offs

- **[Risk] 首次启动待初始化模式改动 `WebServer` 路由装配，可能影响既有 Web 集成测试或直接调用 `WebServer.start(...)` 的单测** → Mitigation：保留现有 `WebServer.start(...)` 重载签名并默认以运行态装配，新增显式 Setup Mode 初始化与激活方法，保证现有全部单测无需修改即可兼容通过。
- **[Risk] 全新启动时用户建完管理员（Step 1）但未完成 Step 5 就刷新页面或重启进程** → Mitigation：建号时即写入 `data/setup-state.json`（`completed: false`），并把已填写的非敏感草稿保留在前端 `sessionStorage`；刷新后已登录会话直接恢复到 Step 2，重启后登录后同样回到 Step 2，避免与老用户升级判定混淆。
- **[Risk] `test-tavily` 会消耗 1 次基础查询额度** → Mitigation：仅在用户显式点击「测试 Tavily」按钮时触发（不自动轮询），并使用最低成本的 `search_depth: "basic"` + `max_results: 1`。
- **[Risk] 代理服务器地址通常在宿主机内网（如 `127.0.0.1:7890`），若盲目对代理主机做公网校验会导致国内/NAS 用户无法使用代理** → Mitigation：放行私网代理主机，但将代理测试目标锁死为内置 MusicBrainz 公开 URL，并对所有用户可填写的目标 API URL（LLM / AcoustID / Tavily）继续执行 `OutboundUrlValidator` 公网与协议校验。
- **[Risk] Docker 挂载目录尚未预先创建（如宿主机空映射或非 Docker 本地运行填写新路径）** → Mitigation：目录校验明确区分「监控目录不存在（阻断/警告，需存在且可读）」与「输出目录不存在但父目录可写（提示激活时自动创建）」，避免误拦合法的新建输出目录。

## Migration Plan

- **升级兼容**：老版本实例已有 `data/admin.json` 且无 `data/setup-state.json`，启动时自动判定为已初始化，直接走完整启动流程；若老用户 `config.properties` 中 `acoustid.apiKey` 仍为空或 `YOUR_API_KEY_HERE`，仅在概览页展示可忽略的轻提示横幅，不阻断服务。
- **回滚策略**：删除新增的 `data/setup-state.json` 即可回退状态文件；`config.properties`、`data/admin.json` 与 `data/llm-providers.json` 的既有格式与字段名完全不变，回退至旧版本二进制/镜像无格式兼容问题。
