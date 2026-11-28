# Tasks

## 1. 配置、持久化模型与数据库缓存底座

- [x] 1.1 在 `MusicConfig`、`ConfigFieldSpec`、`ConfigServlet`、`config.properties.example` 与 `config.properties.example.zh` 中新增支持热更新的配置项 `online.mbid.resolve.enabled`（默认 `true`）、`online.mbid.maxLookupsPerAlbum`（默认 `15`，范围 `0..50`）与 `online.mbid.llmDisambiguate.enabled`（默认 `true`），补充配置加载/保存与 HTTP 热更新单测并运行验证
- [x] 1.2 在 `DatabaseService.initSqliteSchema()` 与 `src/main/resources/schema.sql` 中新增 `mb_artist_cache` 与 `mb_artist_name_index` 表 DDL，实现 `MusicBrainzArtistCache`（支持 SQLite/MySQL 读写、表缺失或文件模式自动降级为有界内存 LRU 缓存、规范化人名归一化、以及多实体同名冲突感知），新增 `MusicBrainzArtistCacheTest` 覆盖唯一命中、同名冲突透传与无表降级并运行通过
- [x] 1.3 在 `ReviewItem` 中新增向后兼容的人物级 MBID 模型（`EntityMbidStatus`、`EntityMbidSource`、`EntityMbidCandidate`、`EntityMbidResolution`、`OnlineCandidate.entityMbids`、`MissingCover.onlineEntityMbidOverrides`）并完善 `ensureThreadSafeCollections()` 与深拷贝；在 `MusicMetadata` 中新增 `clearArtistId` / `clearAlbumArtistId` 并在 `copyWithoutHeavyFields()` 同步复制，补充序列化与历史 JSON 兼容单测并运行验证

## 2. MusicBrainz Artist 查询接口与被动缓存沉淀

- [x] 2.1 在 `MusicBrainzClient` 中新增 `searchArtists(String name, int limit)`（`GET /ws/2/artist?query=...&fmt=json`）与 `getArtistById(String mbid)`（`GET /ws/2/artist/{mbid}?fmt=json&inc=aliases+tags`）及标准 `ArtistMatch` 结果对象，复用现有 `rateLimit()`、代理与重试机制，新增 `MusicBrainzClientArtistSearchTest` 验证 Lucene 转义、别名/备注/地区/类型解析与 404/限流处理通过
- [x] 2.2 将可选 `MusicBrainzArtistCache` 注入 `MusicBrainzClient`（在 `ApplicationLifecycleManager` 统一装配并保留旧构造重载），在 `parseRecordingResponse`、`parseReleaseTagBundle`、`applyMatchedTrackMetadata` 与 `parseCreatorCredits` 中被动沉淀解析出的真实艺人及幕后人员实体，补充单测验证常规 MB 解析会安全写入缓存且缓存异常不中断主流程

## 3. 网页外链提取、封闭式消歧与三层 MBID 解析编排

- [x] 3.1 扩展 `WebPageFetcher.FetchedPage` 在 HTML 清洗前从 `<a href>` 与 JSON-LD 中提取 `musicbrainz.org/artist/<uuid>` 外链（含锚文本），保持现有构造/调用兼容，补充 `WebPageFetcherTest` 验证从 Wikipedia/VGMdb/MusicBrainz 页面提取合法 Artist UUID 并忽略非法格式
- [x] 3.2 实现封闭式同名消歧器 `LlmArtistDisambiguator`（复用 `LlmClient.complete` 严格 JSON 输出 `choice_index`/`confidence`/`reason`，绝不向模型请求或采信模型吐出的 UUID，要求 `choice_index` 合法且 `confidence >= 0.85` 才自动采纳），新增 `LlmArtistDisambiguatorTest` 验证合法选择、越界序号拦截、低置信降级与异常容错通过
- [x] 3.3 实现 `OnlineEntityMbidResolver` 三层解析漏斗（Layer 1 本地缓存 -> Layer 2 网页/引用池 MB 外链经 `getArtistById` 回查姓名/别名一致性 -> Layer 3 `/ws/2/artist` 检索唯一精确匹配与同名封闭式消歧），按“专辑艺人 > 曲目艺人 > 高频幕后人员”优先级遵守 `maxLookupsPerAlbum` 预算与 `Thread.isInterrupted()`，跳过 `Various Artists` 等占位名且确保多实体同名不污染 Layer 1 唯一索引；新增 `OnlineEntityMbidResolverTest` 覆盖全部三层路径、同名防污染与预算/中断早停并运行通过
- [x] 3.4 将 `MusicBrainzClient`、`MusicBrainzArtistCache` 与 `OnlineEntityMbidResolver` 接入 `OnlineIdentificationService` 与 `RecoveryService`（`ApplicationLifecycleManager` 装配并保留旧构造兼容），在正式候选逐曲匹配及幕后人员补全后异步触发人物级 MBID 解析并上报 `RESOLVING_MBIDS` 进度，补充 `OnlineIdentificationService` 集成单测验证热更新配置开关（关闭解析 / 关闭 LLM 消歧）与持久化结果通过

## 4. 归档落盘全员对齐写入、旧 ID 清理与缺封面持久化

- [x] 4.1 扩展 `TagWriterService.updateTextTags()` 支持 `clearArtistId` 与 `clearAlbumArtistId`（删除旧 `MUSICBRAINZ_ARTISTID` / `MUSICBRAINZ_RELEASEARTISTID` 及无新值时对应排序名），补充 `TagWriterServiceTest` 在 FLAC、Vorbis、ID3v2.3/v2.4 与 MP4 容器上验证清理与写入行为一致通过
- [x] 4.2 扩展 `MetadataUtils` 与 `RecoveryService.confirmOnlineCandidateInternal()` / `commitDeferredOnlineMissingCover()` 接收可选 `entityMbidOverrides`：实现按单人有效绑定（`人工覆盖 > RESOLVED 解析`）执行全有或全无（All-or-Nothing）位置对齐组装，保留既有幕后人员四级优先级，在联网改写艺人无有效对齐 MBID 或折叠为 `Various Artists` 时置 `clearArtistId`/`clearAlbumArtistId`，在未达阈值保留本地艺人曲目上不误删旧 ID，并在转入「缺少封面」队列时持久化 `onlineEntityMbidOverrides`；新增/更新 `MetadataUtilsTest` 与 `RecoveryServiceOnlineCreditsTest` 验证全员命中、部分命中丢弃、旧 ID 清理与缺封面重启恢复通过

## 5. 待确认 API 与前端弹窗交互

- [x] 5.1 更新 `ReviewServlet`：在 `GET /api/review/item` 透传候选的 `entityMbids` 与人物级 MBID 配置可见状态；在 `POST /api/review/online-confirm` 解析并严格校验可选 `entityMbidOverrides`（合法非零 UUID 规范化小写，`""` 表示取消绑定，非法值返回 HTTP `400` `online.mbid.override.invalid`），补充 `ReviewServletOnlineCreditsTest` 验证合法覆盖、取消绑定、非法 UUID 拦截与历史兼容通过
- [x] 5.2 更新 `src/main/resources/static/review.html`、`messages_zh_CN.properties` 与 `messages_en_US.properties`：在候选详情与 `#onlineDialog` 确认弹窗中渲染可折叠的「人物 MusicBrainz ID 绑定」区块（存在 `AMBIGUOUS` 时默认展开），展示来源徽章、可点击 `musicbrainz.org/artist/<mbid>` 链接与 `disambiguation` 备注，提供同名候选点选、粘贴 UUID 或 MB 艺人链接自动提取以及一键取消绑定，并检查无 `entityMbids` 的历史条目正常降级渲染

## 6. 全量回归与收尾检查

- [x] 6.1 运行完整 `mvn test` 确认所有新增与既有测试套件零失败，重点核验 `TagWriterServiceTest`、`RecoveryServiceOnlineCreditsTest`、`OnlineIdentificationServiceCreditsAndRegionTest` 及既有 MusicBrainz 选版测试无回归
- [x] 6.2 验证新增日志、异常与队列序列化不泄露敏感信息，且联网归档产物绝不写入虚构或未核验的 Release / Release Group / Recording ID

## Workflow follow-up

- 实施与验收完成后，通过 `/openspec-verify-change` 核对实现与规范一致性，再通过 `/openspec-archive-change` 归档变更并同步主规范。
- 变更 1 归档后，按既定两期蓝图启动变更 2（`musicbrainz-contribution-loop`）规划与实施。
