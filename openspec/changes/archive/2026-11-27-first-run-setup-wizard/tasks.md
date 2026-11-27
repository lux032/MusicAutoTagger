# Tasks

## 1. 可交互 HTML 视觉原型确认

- [x] 1.1 依据 `DESIGN.md`、`app.css` 与本变更 specs 制作可直接在浏览器打开的独立静态可交互 HTML 视觉原型（覆盖 5 步初始化流程、中英文实时切换、环境自检、目录/代理/AcoustID/MusicBrainz/Tavily/拉取模型模拟反馈、跳过兜底、概览轻提示横幅与设置页重入态）；在浏览器核验桌面端与窄屏响应式布局及交互细节，待用户验收确认后再推进后端与正式前端编码

## 2. 初始化状态持久化与待命/免重启生命周期

- [x] 2.1 新增 `SetupStateStore` 持久化 `data/setup-state.json`（原子写盘 + `0600` 权限，含 `completed`、`completedAt`、`dismissedAcoustIdWarning`），实现老用户升级判定（无 `setup-state.json` 但存在 `data/admin.json` 时视为已初始化）与中断续配保护（`AuthServlet` 首次创建管理员时落盘 `completed=false`）；编写 `SetupStateStoreTest` 覆盖全新态、老用户升级态、中断续配态与原子写入并通过 `mvn test -Dtest=SetupStateStoreTest`
- [x] 2.2 改造 `Main`、`ApplicationLifecycleManager` 与 `WebServer` 支持「待初始化模式」与进程内免重启激活：未初始化启动时仅起 Web 服务与初始化必需接口、不启动 `FileMonitorService`，业务 API 经守卫返回 `503`（`setup.required`）；完成引导时在进程内按最终配置一次性初始化核心服务、装配业务路由并启动文件监控，同时保持既有 `WebServer.start(...)` 重载完全向后兼容；补充生命周期与路由守卫集成测试并通过对应 `mvn test`

## 3. 实时语言切换与初始化校验 / 提交 API

- [x] 3.1 扩展 `I18nServlet` 支持 `?lang=zh_CN|en_US` 白名单只读预览返回（不改动服务端全局状态），并在 `AuthServlet.handleSetup` 与初始化提交时持久化语言配置并刷新 `I18nUtil`；补充 `I18nServletTest` 验证双语即时切换与非法参数回退并通过 `mvn test -Dtest=I18nServletTest`
- [x] 3.2 新增 `SetupServlet`（`/api/setup/*`）并在 `AuthFilter` 中仅放行无敏感信息的 `GET /api/setup/status`：实现 `GET /api/setup/bootstrap`（环境自检、配置回填与 `YOUR_API_KEY_HERE` 占位符识别）、`POST /api/setup/check-directories`、`POST /api/setup/test-proxy`（探测内置固定 MusicBrainz 公开端点）、`POST /api/setup/test-sources`（AcoustID 鉴权码判定 + MusicBrainz 连通性）、`POST /api/setup/test-tavily`、`POST /api/setup/dismiss-acoustid-warning` 与 `POST /api/setup/complete`（全新启动走进程内激活，老用户重跑走 `RESTART_ONLY_FIELDS` 判定并严拒篡改只读字段），同时扩展 `LlmProviderServlet` / `LlmModelCatalog` 支持拉取模型时透传向导临时代理参数；编写 `SetupServletTest` 覆盖认证/CSRF 拦截、占位符识别、掩码密钥保护、只读字段拒绝、SSRF 防护与激活失败回滚并运行 `mvn test -Dtest=SetupServletTest` 通过

## 4. 正式前端向导页、概览补救与国际化

- [x] 4.1 依据第 1 步确认的视觉原型实现 `src/main/resources/static/setup.html`（复用 `app.css`、`ui.js`、`errors.js`、`api.js`），完整对接 5 步向导、实时中英文切换、Step 1 建号与刷新/重登续配（Step 2 起恢复）、目录检查、代理/数据源/Tavily 连通性校验、LLM 供应商模型拉取与 `suggestedApiUrl` 一键补全、整理偏好预设、脱敏总览及完成激活跳转；核查脚本语法与动态文本 XSS 转义无遗漏
- [x] 4.2 改造 `index.html` 与 `shell.js`：未初始化状态自动引导至 `setup.html`（已建号未完成登录后回跳 `setup.html#step=2`）、概览页新增可忽略的 AcoustID 缺失轻提示横幅（点击补全直达设置页识别 Tab 并聚焦输入框）、设置页顶部新增「重新运行初始化引导」入口（`setup.html?mode=rerun`，跳过第 1 步）；同步补齐 `messages_zh_CN.properties` 与 `messages_en_US.properties` 全部新增 i18n 键并校验中英文键集合完全一致

## 5. 全链路回归验证

- [x] 5.1 运行完整测试套件 `mvn test` 与 `openspec validate first-run-setup-wizard`，确认新增及既有单元测试、集成测试全部通过且无回归
