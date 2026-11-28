# Technical Design: online-artist-creator-mbids

## Context

参见 [proposal.md](proposal.md) 中的背景与动因。当前项目现状与核心约束如下：

- **联网识别管线**：`OnlineIdentificationService.search(...)` 在完成一/二轮专辑候选搜索、逐曲匹配（`OnlineTrackMatcher`）以及幕后人员深度补全（`TrackCreditEnrichmentService` / 原生补全）后，将结果写入 `ReviewItem.onlineCandidates`。目前 `OnlineIdentificationService` 和 `RecoveryService` 均未持有 `MusicBrainzClient`。
- **MusicBrainz 客户端**：`MusicBrainzClient` 已统一封装代理、1 秒速率限制（`REQUEST_INTERVAL = 1000ms`）、`Retry-After` 与指数退避重试，并在常规识别中解析 Recording 艺人、Release 专辑艺人及 `parseCreatorCredits`（作曲/作词/编曲），但尚未暴露 `/ws/2/artist` 检索或 `/ws/2/artist/{mbid}` 单实体查询，也无跨重启持久化艺人缓存。
- **标签读写对齐约束**：`TagWriterService` 以 `"; "` 拼接方式写入 `MUSICBRAINZ_COMPOSERID`、`MUSICBRAINZ_LYRICISTID`、`MUSICBRAINZ_ARRANGERID`（与 `MusicBrainzClient.joinArtistValues` 写入 `MUSICBRAINZ_ARTISTID` / `MUSICBRAINZ_RELEASEARTISTID` 一致），并通过 `alignedCreatorMbidValue` 强制执行 `mbids.size() == names.size()` 的位置对齐；下游 032 Music Server 也依赖姓名列表与分号 MBID 的严格一一对应。
- **陈旧艺人 ID 遗留**：当前 `RecoveryService.confirmOnlineCandidateInternal` 在写入联网确定的 `ARTIST` / `ALBUM_ARTIST` 时不会设置 `clearAlbumLevelTags`，且 `TagWriterService.updateTextTags` 在 `artistId`/`albumArtistId` 为 `null` 时直接跳过，不会清除原音频文件残留的旧艺人 MBID。
- **数据库与持久化**：`DatabaseService` 在 SQLite 下启动时自动幂等建表，MySQL 下依赖 `schema.sql` 外部建表，文件模式下 `databaseService == null`；审核队列序列化为 `review-queue.json`，需对历史条目严格向后兼容。

## Goals / Non-Goals

### Goals
- 为联网识别产出的人物级实体（专辑艺人、曲目艺人、经核验的作曲/作词/编曲）提供真实、可核验的 MusicBrainz Artist MBID 解析（零模型编造）。
- 在 SQLite/MySQL/文件模式均提供低延迟的本地艺人 MBID 缓存与同名冲突感知，优先复用用户曲库中已被 MusicBrainz 识别过的实体。
- 将联网网页抓取中出现的 `musicbrainz.org/artist/<uuid>` 外链转化为零歧义的 MBID 线索（经 API 核验后采纳）。
- 对 MusicBrainz `/ws/2/artist` 同名多候选提供上下文封闭式 LLM 消歧，并在高置信不足时交由待确认弹窗人工选择，宁缺毋滥。
- 在联网确认归档与缺封面中转流程中完整保留人工 MBID 覆盖，严格按全员对齐规则写入标签，并在联网改写艺人无有效新 MBID 时显式清理陈旧艺人 ID。

### Non-Goals
- 不生成、推测或写入任何 Release ID、Release Group ID 或 Recording ID。
- 不向 MusicBrainz 创建新 Release、不上传 Cover Art Archive 封面、不提交 AcoustID 声纹（均归入后续变更 `musicbrainz-contribution-loop`）。
- 不尝试突破 MusicBrainz 1 req/s 限流，不引入无界递归或额外重型外部依赖。
- 不修改常规声纹识别/时长匹配选版逻辑或 `AtomicAudioPublisher` 原子提交协议。

## Decisions

### Decision 1: 人物级规范化索引键与 `OnlineCandidate.entityMbids` 去重建模
- **决策**：在 `ReviewItem.OnlineCandidate` 上新增按规范化人名键（`normalizedKey`）索引的 `Map<String, EntityMbidResolution> entityMbids`，而不是把 MBID 冗余拷贝到每首曲目的每个字段下。
- **规范化规则**：`normalizePersonKey(raw)` 执行 `NFKC -> trim -> 连续空白(含全角空格)折叠为单空格 -> Locale.ROOT 小写`。
- **理由**：一张 15 轨专辑往往反复出现相同的 5~10 位歌手和词曲编创作者；按唯一人名去重存储既避免 `review-queue.json` 膨胀，也让确认弹窗以“人物列表”集中展示与批量调整（改一次即同步作用于整张专辑内该人物出现的所有曲目与角色）。
- **备选方案**：在 `OnlineTrack` 的 `artists`/`composers`/`lyricists`/`arrangers` 旁各自存平行 `List<String>`。放弃原因：同一人在多轨重复出现时，用户改选同名候选需要逐轨重复点击，且 JSON 体积成倍增大。

### Decision 2: 两表结构的本地艺人缓存（`mb_artist_cache` + `mb_artist_name_index`）与同名污染防护
- **决策**：在 SQLite（`DatabaseService.initSqliteSchema()` 自动幂等建表）与 MySQL（`schema.sql` 补充 DDL，表缺失时捕获异常降级为进程内 LRU 缓存并仅告警一次）中建立两张轻量表：
  - `mb_artist_cache(mbid PK, name, sort_name, disambiguation, country, artist_type, aliases_json, source, updated_at)`
  - `mb_artist_name_index(normalized_name, mbid, match_kind, updated_at, PRIMARY KEY(normalized_name, mbid))`
- **被动沉淀与同名防护**：
  - 常规 MusicBrainz 查询（`parseRecordingResponse`、`parseReleaseTagBundle`、`applyMatchedTrackMetadata`、`parseCreatorCredits`）拿到真实 `artist` 节点时，异步/轻量 upsert 实体主表，并将 `artist.name` 及 `credit.name` 写入 `mb_artist_name_index`。
  - Layer 1 查询 `SELECT mbid FROM mb_artist_name_index WHERE normalized_name = ?`：
    - 若仅命中 **1** 个 `mbid` 且未标记多实体冲突 -> 直接返回高置信 `LOCAL_CACHE` 命中（0 API 调用）。
    - 若命中 **>= 2** 个不同 `mbid` -> 判定本地已存在同名多实体，**禁止** Layer 1 直接拍板，透传全部本地候选进入 Layer 2 / Layer 3 做上下文消歧。
  - **关键防污染规则**：当 Layer 3 通过 `/ws/2/artist` 发现某人名存在多个精确同名实体时，即便本次靠 LLM 上下文消歧选中了其中一位，也将该人名对应的前若干精确同名实体的 `mbid` 一并写入 `mb_artist_name_index`（或拒绝写入单条唯一索引），确保下次遇到另一张专辑的同名不同艺人时不会错误走 Layer 1 短路命中！
- **备选方案**：单表 `name -> mbid` 覆盖写。放弃原因：一旦遇到日本同名作曲家/声优，后写入的艺人会覆盖前一位，导致跨专辑静默张冠李戴。

### Decision 3: 三层解析漏斗与预算/中断控制（`OnlineEntityMbidResolver`）
- **决策**：在 `OnlineIdentificationService.search(...)` 完成候选匹配与幕后人员补全后，对 `selectBestCandidate(parsed.candidates)` 执行人物级 MBID 解析（发送进度事件 `RESOLVING_MBIDS`），流程如下：
  1. **候选人名收集与优先级排序**：按「专辑艺人 > 可靠匹配曲目艺人 > 准入核验通过的作曲/作词/编曲（按出现频次降序）」收集去重人名，跳过 `Various Artists` / `Unknown Artist` / `Unknown` 等占位值，同时收集上下文（专辑名、发行日期、偏好地区 `release.countryPriority`、人物角色集合、同专辑其他合作者）。
  2. **Layer 1（本地缓存）**：零网络开销；唯一命中直接置为 `RESOLVED (LOCAL_CACHE)`。
  3. **Layer 2（已抓网页 MB 外链提取 + API 核验）**：
     - 扩展 `WebPageFetcher.FetchedPage` 保留 HTML 解析阶段从 `<a href>` 与 JSON-LD 中抽出的 `musicbrainz.org/artist/<uuid>` 链接（含锚文本），并合并会话引用池中直接出现的 `musicbrainz.org/artist/<uuid>` URL。
     - 对候选 UUID 先查 `mb_artist_cache`（按 MBID），未命中则消耗 1 次预算调用 `MusicBrainzClient.getArtistById(mbid)`；仅当该 MB 实体的官方 `name` 或 `aliases`（归一化后）与目标人名精确匹配、且无多个不同 MBID 同时匹配同一目标人名时，置为 `RESOLVED (WEB_LINK_VERIFIED)`。
  4. **Layer 3（MusicBrainz `/ws/2/artist` 检索 + 封闭式 LLM 消歧）**：
     - 在剩余预算（`online.mbid.maxLookupsPerAlbum`，默认 `15`，范围 `0..50`）内调用 `MusicBrainzClient.searchArtists(name, 8)`（Lucene 查询 `artist:"<escaped>" OR alias:"<escaped>"`）。
     - 计算归一化精确匹配集合（`name` 或 `aliases` 精确相等）：
       - **唯一精确匹配**（精确集合大小为 1，且无其他 `score >= 90` 的同名竞争者）-> 置为 `RESOLVED (MB_SEARCH_EXACT)`。
       - **多个精确匹配，或无精确匹配但存在高分近似候选（`score >= 85`）**：
         - 若 `online.mbid.llmDisambiguate.enabled` 开启且 `LlmClient.isConfigured()`，调用封闭式消歧器（复用 `LlmClient.complete` 严格 JSON：`{"choice_index":0..N,"confidence":0..1,"reason":"..."}`）。模型**只能输出候选序号 `1..N` 或 `0`（无法确定）**，绝不向模型暴露或请求输出 UUID，由代码通过序号回查真实 `mbid`。
         - 若 `choice_index` 合法且 `confidence >= 0.85` -> 置为 `RESOLVED (MB_SEARCH_LLM_DISAMBIGUATED)`，同时保留前 5 个竞争候选供确认弹窗核对或切换。
         - 否则（未启用 LLM、LLM 失败、返回 `0` 或置信度不足）-> 置为 `AMBIGUOUS`，`selectedMbid = null`，保留前 5 个候选供人工选择。
       - **无匹配候选** -> 置为 `NOT_FOUND`。
  5. **预算与中断**：每次外部网络请求前检查 `Thread.isInterrupted()` 与剩余 `maxLookupsPerAlbum` 预算；达到上限时未查询的剩余人名标记 `SKIPPED`，已解析结果完整落盘不回滚。
- **备选方案**：在点击“确认归档”的同步 HTTP 请求中实时查 MusicBrainz。放弃原因：1 req/s 限流下 10~15 人需要 10~15 秒，会阻塞归档请求且无法让用户在提交前审阅同名消歧结果。

### Decision 4: 全员对齐落盘规则与旧艺人 MBID 显式清理
- **决策**：
  - 在 `MusicMetadata` 上新增 `clearArtistId` 与 `clearAlbumArtistId` 布尔标志（同步加入 `copyWithoutHeavyFields()`），`TagWriterService.updateTextTags` 在对应标志为 `true` 时先删除 `MUSICBRAINZ_ARTISTID` / `MUSICBRAINZ_RELEASEARTISTID`（若无新 `artistSort` / `albumArtistSort` 则同步删除对应排序名），再写入非空新值。
  - **有效实体 MBID 查找优先级**（按单个人名）：
    1. 弹窗人工提交的 `entityMbidOverrides`（合法 UUID -> 绑定；空字符串 `""` -> 显式取消绑定，阻断自动预填）；
    2. `chosen.entityMbids` 中 `status == RESOLVED` 且 `selectedMbid` 为合法非零 UUID 的条目。
  - **全有或全无（All-or-Nothing）组装**：
    - 给定某个角色的有序人名列表 `names`（`albumArtists`、`artists`、`composers`、`lyricists`、`arrangers`）：仅当 `names` 非空、每个人都查到合法非零 UUID 且解析后的 UUID 数量与 `names.size()` 严格相等时，才组装 `"; "` 拼接值（艺人）或 `List<String>`（幕后人员）；任一成员缺失或显式取消绑定，整组 MBID 置为 `null`。
  - **四类幕后人员来源的 MBID 合并规则**：
    - `CreditSource.MUSICBRAINZ`：沿用前置声纹识别得到的官方 MBID（保持最高权威，不被联网结果冲掉）。
    - `CreditSource.LOCAL`：若原文件已带合法对齐 MBID，优先保留；若原文件无 MBID，仅当存在有效绑定（人工覆盖或已核验解析）且全员对齐时补全。
    - `CreditSource.ONLINE` / `CreditSource.MANUAL`：按有效实体 MBID 查找表全员对齐组装；未全员命中时为 `null`（`TagWriterService.writeCreatorNames` 自动清除源文件旧幕后 MBID）。
  - **艺人 / 专辑艺人清理边界**：
    - 联网确认始终写入新的 `ALBUM_ARTIST`：若折叠为 `Various Artists` 或未全员命中 `albumArtistId`，置 `clearAlbumArtistId = true`。
    - 曲目达到可靠匹配阈值并由联网候选改写 `ARTIST` 时：若全员命中则写入新 `artistId`（及排序名），否则置 `clearArtistId = true`。
    - 未达可靠匹配阈值（`track == null`）保留原本地曲目艺人时：不误删源文件原有 `MUSICBRAINZ_ARTISTID`，除非用户显式覆盖。

### Decision 5: 确认接口、缺封面中转与前端弹窗交互
- **接口扩展**：`POST /api/review/online-confirm` 新增可选字段 `entityMbidOverrides`（`Map<String, String>`，键为人名或规范化人名键，值为合法 UUID 或 `""` 表示取消绑定）。服务端统一归一化键并校验 UUID 合法性（非法格式返回 HTTP `400` + `online.mbid.override.invalid`），不传时完全向后兼容。
- **缺封面持久化**：`ReviewItem.MissingCover` 新增 `onlineEntityMbidOverrides` 字段；在 `confirmOnlineCandidateInternal` 因缺封面转入 `COVER_MISSING` 时深拷贝持久化，`commitDeferredOnlineMissingCover` 恢复时原样回传，跨进程重启不丢失。
- **前端弹窗（`review.html`）**：在 `#onlineDialog` 内新增可折叠的「人物 MusicBrainz ID 绑定」区块（存在 `AMBIGUOUS` 条目时默认展开，否则默认折叠并显示汇总计数），支持点击 MB 链接外部核对、同名候选下拉点选、手填 UUID/粘贴 `musicbrainz.org/artist/<uuid>` 链接（前端自动提取 UUID）以及一键取消绑定。

## Risks / Trade-offs

- **[Risk] 同名艺人消歧错误导致下游曲库错误合并** -> **Mitigation**：Layer 1 检测多实体冲突自动降级；Layer 2 强制经 `getArtistById` 回查姓名/别名一致性；Layer 3 封闭式 LLM 仅输出候选序号且设定高置信门槛（`0.85`），低置信或冲突一律降级为 `AMBIGUOUS` 留空并交由弹窗人工点选。
- **[Risk] MusicBrainz 1 req/s 限流拖慢整张专辑联网搜索时间** -> **Mitigation**：解析在后台搜索队列异步执行（不阻塞确认弹窗）；Layer 1 本地缓存让常用艺人/创作者 0ms 命中；引入 `online.mbid.maxLookupsPerAlbum`（默认 15）硬预算，按“专辑艺人 > 曲目艺人 > 高频幕后人员”优先排序，且支持中断早停。
- **[Risk] 多人合作中仅部分成员在 MusicBrainz 建档导致整组无法写 MBID** -> **Mitigation**：严格维持全有或全无对齐原则，避免一维 `"; "` 标签错位污染下游解析器（如 032 Music Server）；在弹窗中明确提示未匹配成员，并允许用户手动补充缺失成员的 UUID 后整组生效。
- **[Risk] 既有 MySQL 部署未手动执行新表 DDL 导致缓存写入报错** -> **Mitigation**：`MusicBrainzArtistCache` 在初始化与读写时捕获表不存在等 SQL 异常，自动降级为进程内 LRU 缓存并仅记录单次告警，绝不阻断常规识别或联网归档。
- **[Risk] 用户在确认弹窗修改了专辑艺人或曲目幕后人员名称，导致预解析表缺少新输入人名** -> **Mitigation**：按修改后的最终人名重新查找 `entityMbidOverrides` 与 `entityMbids`；若新输入人名不在映射表中，则不套用旧人名的 MBID 并显式清理旧 ID，保证姓名与 MBID 绝不失配。

## Migration Plan

1. **数据库平滑升级**：SQLite 启动自动执行 `CREATE TABLE IF NOT EXISTS mb_artist_cache` 与 `mb_artist_name_index`；MySQL 同步更新 `src/main/resources/schema.sql`，并在老库未建表时安全降级为内存缓存。
2. **队列 JSON 兼容**：`ReviewItem.OnlineCandidate.entityMbids` 与 `ReviewItem.MissingCover.onlineEntityMbidOverrides` 均为可选字段，缺失时反序列化为空 Map，不影响历史待确认与缺封面条目。
3. **配置默认值与回滚**：新增配置项默认 `online.mbid.resolve.enabled=true`、`online.mbid.maxLookupsPerAlbum=15`、`online.mbid.llmDisambiguate.enabled=true`；如线上出现 MusicBrainz 网络波动或误匹配问题，可通过 Web 设置页热关闭 `online.mbid.resolve.enabled` 立即退回纯文本联网归档行为，无需重启或回滚数据库。

## Open Questions

- 后续是否需要在确认弹窗中增加“针对手动改名的新人名按需实时查一次 MB Artist”的单点按钮？（第一期通过手填 UUID/MB 链接已可满足罕见改名场景，实时单人查询按钮可作为体验优化按需追加，不影响当前数据结构与接口契约。）
