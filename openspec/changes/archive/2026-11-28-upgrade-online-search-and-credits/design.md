# Design

## Context

参见 `proposal.md` 的动机与 PoC 实测背景。现有联网识别与幕后人员落盘链路的关键工程约束如下：

- **双搜索实现并存**：`OnlineIdentificationService` 持有 `NativeWebSearchClient` 与 `TavilyWebSearchClient`，运行时按 `config.getWebSearchProvider()` 动态切换，无需重启。
- **原生路径缺陷**：`NativeWebSearchClient.callOpenAi()` 请求 `/v1/responses` 时未传 `include: ["web_search_call.action.sources"]`，且 `parseOpenAiResponse()` 仅解析 `output[].content[].annotations`。实测严格 JSON 输出下 `annotations` 恒为空，且偶发携带 `?utm_source=openai` 追踪参数，导致 `OnlineIdentificationService.parseCandidate()` 按精确字符串匹配 `source_urls` 时匹配不到任何证据，有效正式候选被全部丢弃。
- **Tavily 路径局限**：`TavilyWebSearchClient` 默认受 `tavily.includeDomains`（13 个偏欧美域名）硬限制，固定 `include_raw_content = false` 且单条摘要截断 `1200` 字符，无任何网页正文抓取能力，且首轮命中专辑候选后不针对缺失幕后人员触发补漏。
- **来源可信度注册表偏窄**：`WebSearchClient.SourceReliability` 仅收录 13 个国际域名，日系正版数字音源店（`ototoy.jp`、`mora.jp`、`recochoku.jp`、`linkco.re`）、日系主流音乐媒体与歌词库一律归类为 `LOW`。
- **资源与安全约束**：生产容器（QNAP）默认内存上限 1G / `-Xmx512m`；所有出站请求必须经过 `OutboundUrlValidator` 公网校验与有界响应读取，严禁引入 SSRF 或破坏 `OnlineSearchQueueService`、`MetadataUtils`、`TagWriterService`、`AtomicAudioPublisher` 的既有保障。

## Goals / Non-Goals

**Goals:**
- 修复 `NativeWebSearchClient` 的 OpenAI `/v1/responses` 来源池采集、URL 规范化与原生检索深度配置，使支持 `/v1/responses` 的端点在不依赖 Tavily 时即可稳定产出通过来源门槛的正式候选与高召回逐曲幕后人员。
- 扩充 `WebSearchClient.SourceReliability` 的正版发行平台与日系权威来源覆盖，保持防域名仿冒的精确/真子域匹配语义。
- 为 Tavily 路径建立「确定性开放域首轮 + 网页正文提取（直连 HTTP/`jsoup` + 批量 Tavily `/extract` 降级）+ 状态压缩受控补漏微循环（默认最多 2 轮）」架构，兼容全部普通 `/v1/chat/completions` 端点而不强制依赖 `tools` 协议。
- 双引擎共用一套字段级结构化证据模型（5 态状态机）与确定性 `CreditEvidenceVerifier`（URL 真实性、原文校验、转载去重、Remix/版本防继承、Remixer≠Arranger、Instrumental 无作词护栏）。
- 在待确认界面清晰展示每个角色字段的状态徽章、来源链接、原文引文与冲突候选，同时保持向后兼容与零改动落盘协议。

**Non-Goals:**
- 不引入无界全历史对话式通用 Agent 框架或无头浏览器（Playwright/Puppeteer），避免 QNAP 1G 容器 OOM 与单专辑 150k+ Token 膨胀。
- 不新增作曲/作词/编曲之外的音频写入标签（如 `PRODUCER`/`REMIXER`/`MIXER`）；Remixer 等信息仅保留在证据 `notes` 中提示用户，不混入 `ARRANGER`。
- 不修改 `AtomicAudioPublisher`、`RecoveryRecordReconciler`、`TagWriterService` 的标签键名与原子提交/崩溃恢复流程。
- 不在 `ReviewItem` / `review-queue.json` 中持久化完整网页 HTML 或长正文（仅存短引文、URL、状态与元数据）。

## Decisions

### Decision 1: 修复原生 `/v1/responses` 来源采集与 URL 规范化
- **选择**：
  1. 在 `NativeWebSearchClient.callOpenAi()` 中：
     - `tools[0]` 支持可配置 `search_context_size`（默认 `"high"`，兼容 `"low"`/`"medium"`/`"high"`，若端点不支持该参数且返回 400 可自动回退不带该字段重试一次）；
     - 请求体显式加入 `"include": ["web_search_call.action.sources"]`。
  2. 在 `NativeWebSearchClient.parseOpenAiResponse()` 中合并采集三类真实服务端来源：
     - `output[].type == "web_search_call"` 下的 `action.sources[].url`（服务端真实检索到的来源池）；
     - `action.url`（`open_page` / `find_in_page` 实际打开的网页，标记 `openedPage = true` 供核验器区分 `verified` 与 `probable`）；
     - `output[].content[].annotations[].url_citation.url`（保留原有兜底）。
  3. 抽出共享工具方法 `WebSearchClient.canonicalizeUrl(String url)`：统一小写 scheme/host、去掉 `www.`、去除尾斜杠 `/`、剔除 `utm_*` / `fbclid` / `gclid` / `uo` 等追踪查询参数并对剩余查询参数按键排序。`OnlineIdentificationService.parseCandidate()` 与 `CreditEvidenceVerifier` 均按规范化 URL 匹配检索证据池（展示与持久化仍保留去除 `utm_source=openai` 后的可读原始 URL）。
  4. 同时记录 `usage`（`input_tokens`、`cached_tokens`、`output_tokens`、`reasoning_tokens`）到 `SearchResponse`。
- **理由**：PoC 端到端 6 专辑实测验证该修复将原生模式引用命中率从 `0%` 提升至 `98.8%`（81/82），适用字段召回率达 `87.5%`（63/72）、准确率 `100%`，且零 Tavily 费用。
- **替代方案**：放弃 `NativeWebSearchClient` 强制要求用户购买 Tavily——增加部署门槛，且实测表明原生 `/v1/responses` 在支持该协议的端点上召回与 Token 效率均优于 Tavily 方案。

### Decision 2: 扩充 `WebSearchClient.SourceReliability` 与解耦“专辑白名单 / 词曲编开放域”
- **选择**：
  - 在 `SourceReliability` 中保持精确/真子域匹配逻辑，适度扩充国际与东亚权威音乐来源：
    - `HIGH_TRUST` 新增正版数字发行平台与主流数据库：`ototoy.jp`、`mora.jp`、`recochoku.jp`、`linkco.re`、`tunecore.co.jp`、`qobuz.com`、`e-onkyo.com`、`vocadb.net`。
    - `MEDIUM_TRUST` 新增主流音乐零售、新闻通稿与歌词数据库：`tower.jp`、`hmv.co.jp`、`cdjapan.co.jp`、`oricon.co.jp`、`natalie.mu`、`prtimes.jp`、`realsound.jp`、`barks.jp`、`uta-net.com`、`utaten.com`、`lyrical-nonsense.com`、`genius.com`。
  - **解耦配置**：`tavily.includeDomains` 仅继续用于专辑级身份识别的首轮收敛（减少噪音）；`TrackCreditEnrichmentService` 的幕后人员补全查询默认传 `includeDomains = null`（全网开放域检索，允许命中任意艺人官网如 `himehina.jp`、厂牌站如 `rkmusic.jp`/`stardustrecords.jp`、YouTube 官方视频简介等）。
- **理由**：端到端 6 专辑实测表明，旧 13 域名表会把 `asobisystem.com`、`stardustrecords.jp`、`ototoy.jp`、`realsound.jp` 全判 `LOW`，导致原生搜索的正式候选通过率从 `6/6` 跌到 `4/6`，并把 Tavily 挡在所有日系一手源之外。
- **替代方案**：把全网域名都升为 `HIGH`——会破坏正式候选防假冒门槛；仅对明确的正版发行平台/主流数据库/媒体分层扩充，同时对艺人/厂牌官网仍由曲目级证据核验器按原文和来源池校验，兼顾安全与召回。

### Decision 3: 受控网页正文抓取器（`WebPageFetcher`）与批量 Tavily `/extract` 降级
- **选择**：
  - 新增 `WebPageFetcher`，对每个待抓取 URL 按顺序执行：
    1. **URL 归一化**：如 `uta-net.com/song/<digits>` 缺尾 `/` 时自动补 `/`，避免 301/提取失败。
    2. **SSRF 严格防护**：复用 `OutboundUrlValidator.validateAndResolve(url, false, true, true)`（禁止访问私网/回环/元数据 IP，限制仅 80/443 端口），采用与 `LlmModelCatalog` 相同的 DNS Pinning `CloseableHttpClient` 且**关闭自动重定向**；如需跟随 301/302（最多 3 跳），每跳重新调用 `OutboundUrlValidator` 校验目标地址。
    3. **有界直连 + `jsoup` 正文清洗**：单页响应体硬上限 `2 MB`、超时 `15s`；使用 `org.jsoup:jsoup` 解析 HTML 字符集（自动处理 UTF-8 / Shift_JIS / EUC-JP）、移除 `script`/`style`/`noscript`/`svg`，保留标题、meta description、结构化 `application/ld+json` 与正文文本。
    4. **空壳/反爬检测与批量降级**：遇到 HTTP 403/429/503、Cloudflare/WAF 挑战特征、正文有效字符 < `250` 字，或 YouTube 观看页返回通用空壳（无 `shortDescription`）时，将失败 URL 收集并按 **每批最多 5 个 URL 打包调用一次 `TavilyWebSearchClient.extract()`**（仅耗 1 credit，可在配置中关闭 `online.credits.allowTavilyExtract`）。
    5. **单页截断与缓存**：送入 LLM 的单页正文截断至 `8,000` 字符；完整清洗文本（上限 `60,000` 字符）仅暂存于当次任务的内存 `Map<String, FetchedPage>` 供核验器校验，任务结束立即 GC，绝不持久化到磁盘。
- **理由**：24 站点实测表明，官网 CMS、厂牌新闻、PR TIMES、Natalie、UtaTen、OTOTOY 直连 200 成功率高且零费用；而 Uta-Net / Lyrical Nonsense / Genius / YouTube / Wikipedia 在云端直连普遍触发 403/429/JS 空壳，批量打包 Tavily `/extract` 稳定救回且成本仅为逐条调用的 1/5。
- **替代方案**：全部直走 Tavily `/extract`（白白浪费大量额度）；或引入 Playwright 无头浏览器（镜像膨胀数百 MB，超出 QNAP 1G 容器承载）。

### Decision 4: Tavily 路径的「确定性首轮 + 状态压缩受控微循环」编排（`TrackCreditEnrichmentService`）
- **选择**：
  - 仅在专辑识别完成、拿到高置信度匹配候选（`OnlineTrackMatcher.MIN_CONFIDENCE = 0.6`）且该候选的曲目仍存在缺失词/曲/编（排除已有本地/MusicBrainz 完整词曲编的曲目）时触发。
  - **Stage 1（确定性专辑级首轮）**：
    - 结合 `release.countryPriority` 生成 1–2 条开放域词曲编检索词（日系自动覆盖 `作詞`/`作曲`/`編曲`/`アレンジ`/`クレジット`），调用 `TavilyWebSearchClient.searchRaw(..., includeDomains=null)`；
    - 启发式打分选前 3–4 个高价值 URL（优先艺人官网、厂牌站、官方 YouTube XFD/MV、日文歌词站、新闻稿、OTOTOY/LinkCore，降权 Apple Music/Spotify 公开页），经 `WebPageFetcher` 读取正文；
    - 单次 `LlmClient.complete()` 提取全专字段级结构化 JSON -> `CreditEvidenceVerifier` 核验。
    - **早停条件**：若所有适用字段（排除伴奏作词与 Remix 编曲）均已 `verified` 或达到配置上限，立即结束。
  - **Stage 2（状态压缩受控补漏，最多 `online.credits.maxFollowUpRounds` 轮，默认 2 轮）**：
    - 仅把**压缩状态快照**（各曲当前已确认名单 + 仍缺失字段列表 + 已查过的查询词与 URL 列表，**绝不重传前轮 8KB 网页原文**）送给 `LlmClient.complete()`，请模型产出最多 3 条精准补漏查询（允许切语种如韩语/日语、使用发现的官方别名、定向特定域名 `include_domains`、针对特定版本）；
    - 执行搜索 -> 仅抓取 2–3 个未读新 URL -> 将新抓取正文连同压缩状态送模型做增量更新 -> 再次过 `CreditEvidenceVerifier`。
  - **硬预算上限与中断**：全过程受 `online.credits.maxSearchCalls`（默认 6）、`online.credits.maxFetchPages`（默认 6）、`online.credits.maxFollowUpRounds`（默认 2）约束，每步检查 `Thread.isInterrupted()` 并通过 `SearchProgressListener` 上报进度。
- **理由**：相比无界全历史 Agent（`G3 harha` 单专暴涨至 151.6k tokens），状态压缩把单专辑 Token 稳定压在 ~20k–45k；且完全基于普通 `/v1/chat/completions` + JSON 输出实现，**不强制要求端点支持 `tools` 协议**，100% 兼容 DeepSeek、通义、Ollama 等中转。
- **替代方案**：纯确定性逐曲轮询（方案 B 实测消耗 89 credits 反而比 Agent 贵一倍，且无法自动切别名/切语种）；或全历史 OpenAI Tool Calling Agent（不兼容无 `tools` 端点，且长尾专辑 Token 爆炸）。

### Decision 5: 确定性字段级证据模型与 `CreditEvidenceVerifier`
- **选择**：
  - 在 `ReviewItem.OnlineTrack` 上新增可选字段 `TrackCreditDetail creditDetail`（与既有 `composers`/`lyricists`/`arrangers` 并存；旧数据反序列化为 `null` 时完全兼容）：
    - `versionType`（`original` / `remix` / `alternate_arrangement` / `live` / `acoustic` / `instrumental` / `other`）
    - `lyricists` / `composers` / `arrangers` 各自对应一个 `CreditFieldResult`：
      - `status`: `VERIFIED` | `PROBABLE` | `CONFLICT` | `NOT_FOUND` | `NOT_SEARCHED`
      - `names`: `List<String>`（最终通过核验的名单；`CONFLICT` / `NOT_FOUND` 时不写入默认名单）
      - `evidence`: `List<CreditEvidenceItem>`（`url`、`sourceType`、`quote`（≤ 200 字）、`versionMatch`、`fullPageVerified`、`syndicatedDuplicate`）
      - `candidates`: `List<CreditConflictCandidate>`（仅 `CONFLICT` 时存在，含各候选 `names`/`urls`/`quotes`）
      - `notes`: `String`（如 `Remix：yuigot` 说明、被拦截原因等）
  - `CreditEvidenceVerifier` 在代码层强制执行 6 项确定性校验并重算最终 `status`（不采信模型自报置信度）：
    1. **URL 真实性校验**：每条 `evidence.url` 经 `canonicalizeUrl()` 后必须命中当次会话的抓取/检索/服务端 `action.sources` 池，否则该条证据无效。
    2. **原文与人名校验（已读全文模式）**：对已抓到完整正文的页面，NFKC 归一化校验 `quote`（精确子串或分词命中率 ≥ 75%）及全部提取人名均实际出现于该页正文；原生模式下若未离线抓正文，校验 URL 命中服务端 `action.sources`/`open_page.url`（命中 `open_page.url` 且非转载可评 `VERIFIED`，仅命中检索源池评 `PROBABLE`；可选对前 1–3 个引用 URL 发起低超时免费直连复核升级）。
    3. **转载通稿去重**：对同一字段的多条证据引文计算 5-gram Jaccard 相似度（≥ 0.65 且长度 ≥ 20 字符标记 `syndicatedDuplicate = true`），多个转载页仅计 1 份独立证据。
    4. **伴奏曲无作词护栏**：若曲目标题或 `versionType` 标识为 `Instrumental` / `Off Vocal` / `Karaoke` / `伴奏` / `インスト`，强制拦截并清空 `lyricists`（降为 `NOT_FOUND`）。
    5. **Remix / 特殊版本编曲防继承护栏**：若曲目标题含 `Remix` / `Latin` / `Acoustic` / `Live` / `Version` / `Ver.` / `Arrange` / `アレンジ` / `リミックス` 等版本修饰符，`arrangers` 必须具备 `versionMatch == "exact_version"` 的有效证据；仅 `original_only` / `album_level_only` 一律拦截降为 `NOT_FOUND`；该曲目的 `lyricists`/`composers` 若仅来自 `original_only` 证据，最高评为 `PROBABLE`。
    6. **角色防混淆护栏**：若 `arrangers` 引文仅出现 `Remix` / `Remixed by` / `Remixer` / `Producer` / `Mix` 而未出现 `Arrangement` / `Arranged by` / `Arranger` / `編曲` / `アレンジ`，强制拦截降为 `NOT_FOUND` 并将原文提示保留在 `notes`。
  - **向下游回填规则**：仅 `VERIFIED` 与 `PROBABLE`（当配置 `online.credits.applyProbable=true`，默认 `true`）的 `names` 回填到既有 `OnlineTrack.composers/lyricists/arrangers`；`CONFLICT` 与 `NOT_FOUND` 回填为空列表 `[]`，避免自动污染标签，但完整保留 `creditDetail` 供前端弹窗展示与冲突点选。

### Decision 6: 原生路径提示词与两轮兼容策略
- **选择**：
  - 将增强后的曲目级证据提取提示词、版本/角色/伴奏规则与 `source_urls` 引导直接整合进 `OnlineIdentificationService`；
  - 对原生引擎（`NativeWebSearchClient`），由于 `/v1/responses` 单次请求内服务端已自带多轮 `search` + `open_page`，首轮即可同步完成专辑识别与幕后人员抓取，默认**不再额外叠加 `TrackCreditEnrichmentService` 的 Tavily 循环**；仅在首轮仍未产生正式候选或 `needs_second_round=true` 时触发原有第 2 轮（或在全部曲目幕后人员均为空且启用补漏时，执行最多 1 次定向幕后人员原生检索）。
- **理由**：实测原生模式单次调用已实现 87.5% 适用字段召回、100% 准确率与 34.6k 平均 Token，无需冗余二次搜索。

## Risks / Trade-offs

- **[Risk] 开放域网页抓取带来 SSRF / 重定向跳内网 / 大页面 OOM 风险** → Mitigation：强制经由 `OutboundUrlValidator`（`allowPrivateEndpoints=false`, `restrictStandardPorts=true`）+ DNS Pinning + 禁用自动重定向（手动逐跳重检，最多 3 跳）+ 2MB 有界流 + 15s 超时；默认并发保持 `1`。
- **[Risk] 网页正文内藏间接 Prompt Injection 诱导模型捏造人名** → Mitigation：正文隔离在显式围栏内，LLM 仅做只读结构化抽取无写权限；所有提取结果必须过 `CreditEvidenceVerifier` 原文与来源池核验，且最终入库仍需人工确认弹窗。
- **[Risk] 部分第三方 `/v1/responses` 兼容网关不认识 `search_context_size` 或 `include` 参数返回 HTTP 400** → Mitigation：`NativeWebSearchClient` 在携带扩展参数收到 400 时，按“去掉 `search_context_size` → 去掉 `include`”做受控降级重试，并记录降级日志，确保不破坏既有可用端点。
- **[Risk] 原生模式下服务端不返回被打开网页正文，极少数情况下模型可能简写 `quote`** → Mitigation：首先要求 URL 必须真实存在于服务端 `action.sources`/`open_page.url`；同时提供可选轻量免费直连复核对直连 200 的引用页做正文引文校验，对 403 页保留来源池校验并由弹窗明确标注来源链接供人工核查。
- **[Risk] `review-queue.json` 体积增大** → Mitigation：单条 `quote` 强制截断 ≤ 200 字符，每字段最多保留 3 条核心证据，不存任何 HTML/全文。
- **[Risk] QNAP 1G 容器下 `jsoup` 与多页正文内存占用** → Mitigation：单页解析完立即抽取纯文本并丢弃 `Document` DOM，限制每轮最多缓存 8 页（总计 < 500KB 文本），单任务结束后随局部变量释放。

## Migration Plan

- **完全向后兼容，无需数据库或队列数据迁移**：
  - 历史 `review-queue.json` 缺少 `OnlineTrack.creditDetail` 时反序列化为 `null`，前后端自动回退到既有 `composers`/`lyricists`/`arrangers` 展示与合并逻辑。
  - 现有 `config.properties` 升级后自动应用新增配置的安全默认值（`online.credits.enrich.enabled=true`、`maxFollowUpRounds=2`、`maxSearchCalls=6`、`maxFetchPages=6`、`allowTavilyExtract=true`、`applyProbable=true`、`llm.webSearch.nativeContextSize=high`），无需人工修改即可生效。
- **灰度与回滚**：用户可通过配置将 `online.credits.enrich.enabled=false` 一键退回仅专辑识别模式；音频文件写入的标签键名完全未变，代码回退零残留。
