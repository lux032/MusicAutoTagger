# Proposal

## Why

当前联网音乐识别与逐曲幕后人员（作词 / 作曲 / 编曲）提取在真实日系新发行、VTuber、独立音乐、游戏 OST 及含 Remix/特殊版本的专辑上召回率极低：实测 6 张代表性专辑（含 `HIMEHINA - VAMPY` 等 26 首曲目、72 个适用字段）中，现有 Tavily 链路适用字段召回率仅 `20.8%`（5 张专辑为 `0%`），而原生 `/v1/responses` 搜索因严格 JSON 输出导致 `annotations` 为空与 `?utm_source=openai` 失配，使有效候选全部被来源门槛丢弃。需要同步修复原生联网搜索并重构 Tavily 路径的网页正文抓取、受控补漏与确定性证据核验机制，在不破坏现有原子归档与人工确认护栏的前提下，让双引擎在生产环境均具备高召回与高准确率。

## What Changes

- **修复并升级原生联网搜索引擎（`NativeWebSearchClient`）**：
  - 在 OpenAI `/v1/responses` 请求中启用 `include: ["web_search_call.action.sources"]` 与可配置 `search_context_size`，同步采集 `web_search_call.action.sources`、`open_page`/`find_in_page` 动作 URL 与 `annotations`，并对引用 URL 规范化剥离 `?utm_source=openai` 等追踪参数，彻底解决严格 JSON 模式下 `citations` 为空导致有效候选被误丢弃的问题。
- **扩充来源可信度域名注册表（`WebSearchClient.SourceReliability`）**：
  - 在保留既有 13 个国际域名基础上，补入主流正版数字发行平台、音乐数据库与权威日系音乐媒体/歌词库（如 `ototoy.jp`、`mora.jp`、`recochoku.jp`、`linkco.re`、`tunecore.co.jp`、`qobuz.com`、`vocadb.net`、`tower.jp`、`hmv.co.jp`、`cdjapan.co.jp`、`oricon.co.jp`、`natalie.mu`、`prtimes.jp`、`realsound.jp`、`uta-net.com`、`utaten.com`），避免非欧美正版发行与官方新闻页被一律归类为 `LOW`。
- **升级 Tavily 联网引擎为“确定性首轮 + 状态压缩受控补漏 + 网页正文提取”架构**：
  - 将专辑级身份识别与曲目级幕后人员补全解耦：专辑识别继续遵守严格来源门槛，幕后人员补全放开 13 域名硬限制，在开放域执行专辑级首轮检索与高价值网页正文抓取；若仍有缺失字段，执行最多 2 轮（可配置 0..3 轮）**状态压缩受控补漏微循环**（仅回传压缩后的缺失清单与已访问 URL，不堆叠旧网页原文，兼容全部普通 `/v1/chat/completions` 端点）。
  - 新增受控网页正文读取器（`WebPageFetcher`），复用 `OutboundUrlValidator` 公网 SSRF 防护与 DNS Pinning，优先直连 HTTP + `jsoup` 正文清洗，遭遇 403/429/YouTube 空壳/薄 JS 壳时默认批量降级调用 Tavily `/extract`（5 URL 共享 1 credit，可配置关闭）。
- **引入字段级结构化证据模型与确定性核验器（`CreditEvidenceVerifier`）**：
  - 为每首曲目的 `lyricists`、`composers`、`arrangers` 引入独立状态机（`verified`、`probable`、`conflict`、`not_found`、`not_searched`）、来源 URL、来源类型、原文证据摘录、版本匹配情况与冲突候选列表。
  - 在代码层强制执行确定性核验（不依赖模型主观置信度）：URL 必须真实存在于本会话检索/抓取池中；已抓正文页做引文与人名原文核验；多来源引文做 5-gram 转载通稿去重；Remix/特殊版本严禁继承原版编曲；`Remix`/`Remixer`/`Producer`/`Mix` 严禁混入 `arrangers`；`Instrumental`/`Off Vocal` 曲目严禁写入 `lyricists`。
- **升级待确认详情与确认弹窗展示（`ReviewServlet` & `review.html`）**：
  - 展示逐曲幕后人员的状态徽章（已核验 / 推测 / 冲突 / 未找到）、可点击来源链接与原文证据摘录；`verified` 与通过校验的 `probable` 默认参与联网预填并清晰标注徽章，`conflict` 不自动覆盖、提供多候选点选。
- **扩展 LLM 观测与可配置预算上限**：
  - 在 `LlmClient` / `LlmProvider` 中解析并记录 Token `usage`（含缓存与推理 Token）；在 `MusicConfig` 中新增幕后人员深度补全开关、最大补漏轮数、最大搜索/读页上限、Tavily `/extract` 开关与原生搜索深度配置。

## Capabilities

### New Capabilities

（无新增顶层独立 capability；本次所有行为演进均严格归属既有 `online-identification` 与 `online-archive-credits` 两大能力边界，避免能力碎片化。）

### Modified Capabilities

- `online-identification`: 放宽“仅单轮搜索”的旧约束；规范原生 `/v1/responses` 来源池采集与 URL 规范化、扩展日系/正版发行源可信度表、新增受控网页正文读取与状态压缩补漏循环，以及字段级幕后人员五态确定性证据核验与版本/角色/伴奏防错护栏。
- `online-archive-credits`: 扩展待确认详情与联网确认弹窗对字段级证据状态（`verified`/`probable`/`conflict`/`not_found`/`not_searched`）、来源链接、引文预览及冲突候选点选的交互与自动合并准入规则（`conflict` 不自动写入，既有四级合并优先级与显式清空保持不变）。

## Impact

- **新增核心类**：`WebPageFetcher`、`TrackCreditEnrichmentService`、`CreditEvidenceVerifier`（及配套单元测试）。
- **修改现有类**：`NativeWebSearchClient`、`TavilyWebSearchClient`、`WebSearchClient`、`LlmClient`、`LlmProvider`、`OnlineIdentificationService`、`ReviewItem`、`MusicConfig`、`ConfigFieldSpec`、`ReviewServlet`、前端 `review.html` 与 `messages_*.properties`。
- **依赖变更**：`pom.xml` 新增轻量无传递依赖的 `org.jsoup:jsoup` 用于稳健 HTML 正文提取与多字符集解码。
- **安全与向后兼容**：新增的所有 `ReviewItem.OnlineTrack` 证据字段均向后兼容历史 `review-queue.json`；**不修改** `AtomicAudioPublisher`、`TagWriterService` 写盘格式与故障恢复核心路径；默认并发仍为 `1`，对网页抓取实施严格 SSRF 校验、DNS Pinning、无自动重定向与 2MB 响应体有界读取。
