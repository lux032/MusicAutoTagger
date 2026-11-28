# Tasks

## 1. 依赖、配置与核心数据模型

- [x] 1.1 在 `pom.xml` 引入 `org.jsoup:jsoup` 依赖并运行 `mvn test-compile` 验证编译与依赖解析通过
- [x] 1.2 在 `MusicConfig`、`ConfigFieldSpec`、`config.properties.example` 与 `config.properties.example.zh` 中新增幕后人员补全及原生搜索深度配置项（`online.credits.enrich.enabled`、`online.credits.maxFollowUpRounds`、`online.credits.maxSearchCalls`、`online.credits.maxFetchPages`、`online.credits.allowTavilyExtract`、`online.credits.applyProbable`、`llm.webSearch.nativeContextSize`）及范围校验，补充配置读写单测并运行验证
- [x] 1.3 在 `ReviewItem.OnlineTrack` 中新增向后兼容的字段级证据数据结构（`TrackCreditDetail`、`CreditFieldResult`、`CreditEvidenceItem`、`CreditConflictCandidate`、五态 `CreditStatus`），同步更新 `ensureThreadSafeCollections()` 与 `OnlineCandidateDeepCopyTest`，运行单测验证历史 JSON 反序列化与深拷贝隔离通过
- [x] 1.4 扩展 `LlmClient.LlmResponse`、`LlmProvider` 与 `WebSearchClient.SearchResponse` 解析并透传可选 `TokenUsage`（`promptTokens` / `completionTokens` / `cachedTokens` / `reasoningTokens`）与引用 `openedPage` 标记，补充 `LlmClient`/`LlmProvider` 响应解析单测并运行验证

## 2. 来源可信度注册表与原生联网搜索引擎修复

- [x] 2.1 在 `WebSearchClient` 中实现 `canonicalizeUrl(String)`（统一 scheme/host、去 `www.`、去尾斜杠、剔除 `utm_*`/`fbclid`/`gclid`/`uo` 并排序查询参数）并扩充 `SourceReliability` 国际与东亚正版发行/数据库/媒体/歌词库分级列表，新增 `WebSearchClientReliabilityAndUrlTest` 验证精确/真子域匹配与防仿冒拦截
- [x] 2.2 升级 `NativeWebSearchClient`：OpenAI `/v1/responses` 请求加入 `include: ["web_search_call.action.sources"]` 与 `search_context_size`（带 HTTP 400 自动降级重试），在 `parseOpenAiResponse()` 中合并解析 `web_search_call.action.sources`、`open_page`/`find_in_page` 动作 URL 与 `annotations`，补充 `NativeWebSearchClientTest` 验证严格 JSON 空 `annotations`、含 `?utm_source=openai` 与 400 降级场景全部通过
- [x] 2.3 在 `OnlineIdentificationService.parse()` / `parseCandidate()` 中改用 `WebSearchClient.canonicalizeUrl()` 对齐候选 `source_urls` 与检索引用池，更新 `OnlineIdentificationServiceCreditsAndRegionTest` 验证原生空 `annotations` 但 `action.sources` 存在权威来源时正常成为正式候选、未入池捏造 URL 被拒收

## 3. 受控网页正文抓取器与确定性证据核验器

- [x] 3.1 在 `TavilyWebSearchClient` 中抽出可复用的 `searchRaw(query, depth, maxResults, includeDomains)`（支持 `includeDomains == null` 开放域检索）与批量 `extract(List<String> urls, depth)`（调用 `/extract` 接口），新增 `TavilyWebSearchClientExtractTest` 验证请求报文、结果解析与异常处理
- [x] 3.2 实现 `WebPageFetcher`：集成 `OutboundUrlValidator` + DNS Pinning + 禁自动重定向（最多 3 跳逐跳重检）+ 2MB 有界读取 + `jsoup` 字符集/正文清洗 + `uta-net.com/song/<id>` 尾斜杠归一化 + YouTube 空壳/403/429 识别与按 5 URL 批次降级调用 Tavily `/extract`，新增 `WebPageFetcherTest` 验证 SSRF/重定向拦截、编码清洗、空壳降级与超限截断
- [x] 3.3 实现 `CreditEvidenceVerifier`：按确定性规则逐曲逐字段核验 URL 来源池命中、已读正文引文与人名 NFKC 匹配、5-gram 转载通稿去重、伴奏无作词护栏、Remix/特殊版本编曲防继承护栏、Remixer/Producer/Mix 不得混淆为 `arrangers` 护栏及五态 `computedStatus` 与 `conflict` 候选计算，新增 `CreditEvidenceVerifierTest` 覆盖全部规则分支并运行通过

## 4. 双引擎幕后人员补全编排与下游准入衔接

- [x] 4.1 实现 `TrackCreditEnrichmentService`（Tavily 路径开放域首轮检索 + 规则 URL 排序读页 + 状态压缩最多 `maxFollowUpRounds` 轮补漏微循环 + 硬预算与 `Thread.interrupt` 控制 + `SearchProgressListener` 阶段上报），新增 `TrackCreditEnrichmentServiceTest` 验证首轮早停、状态压缩不重传旧网页全文、预算硬截断与线程中断保留结果
- [x] 4.2 升级 `OnlineIdentificationService` 提示词与编排衔接：原生模式在单轮内同步输出结构化字段级证据并接入 `CreditEvidenceVerifier`（可选轻量免费直连复核已引用页，全部曲目为空时支持受控第 2 轮）；Tavily 模式在正式候选逐曲匹配后按配置触发 `TrackCreditEnrichmentService` 并按 `VERIFIED` / `PROBABLE` 准入规则回填 `OnlineTrack.composers/lyricists/arrangers`，补充集成单测验证双引擎行为与深拷贝安全
- [x] 4.3 更新 `MetadataUtils.resolveOnlineTrackCredits()` 与 `RecoveryService` 确认准入逻辑：确保 `CONFLICT`、`NOT_FOUND`、`NOT_SEARCHED`（及关闭 `applyProbable` 时的 `PROBABLE`）字段不自动写入，同时保持 `MANUAL > MUSICBRAINZ > LOCAL > ONLINE` 优先级、显式清空与缺封面中转持久化不变，运行 `MetadataUtilsTest` 与 `RecoveryServiceOnlineCreditsTest` 验证通过

## 5. 待确认接口与前端界面展示

- [x] 5.1 更新 `ReviewServlet` 返回候选曲目的 `creditDetail`（状态、来源类型、来源 URL、≤200 字引文、`notes` 与冲突候选）及配置可观测字段，并在 `ReviewServletOnlineCreditsTest` 补充新旧队列兼容与字段序列化测试
- [x] 5.2 更新 `src/main/resources/static/review.html` 与 `messages_zh_CN.properties` / `messages_en_US.properties`：在候选卡片与确认弹窗中渲染五态徽章（`已核验`/`推测`/`冲突`/`未找到`）、可点击来源链接、引文预览以及 `conflict` 多候选一键填入交互，并通过单元测试与前端静态检查验证旧条目无 `creditDetail` 时正常降级展示

## 6. 全量回归与实测收尾

- [x] 6.1 运行完整 `mvn test` 确认全部新增与既有测试套件零失败，检查 `AtomicAudioPublisher`、`RecoveryRecordReconciler` 与 `TagWriterService` 回归无破坏
- [x] 6.2 在测试配置下验证原生 `/v1/responses` 与 Tavily 双路径对代表性样本（含多曲新发行与 Remix 版本）的端到端解析、证据展示与确认落盘链路正常，确认无敏感密钥泄露至日志或队列文件

## Workflow follow-up

- 实施与验收完成后，通过 `/openspec-verify-change` 核对实现与规范一致性，再通过 `/openspec-archive-change` 归档变更并同步主规范。
