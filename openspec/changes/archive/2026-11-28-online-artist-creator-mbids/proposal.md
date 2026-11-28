# Proposal

## Why

当前联网音乐识别虽然已经能够提取结构化歌手、专辑艺人及经过确定性证据核验的逐曲作曲、作词与编曲名单，但在规范、数据模型与归档落盘链路上均未解析或写入这些人物在 MusicBrainz 中的真实 Artist MBID（仅当曲目在更早的声纹识别阶段命中 MusicBrainz 或本地原文件原本携带有效 ID 时才被动保留）。然而，“新专辑或特定发行版尚未被 MusicBrainz 收录”并不代表演唱者与词曲编创作者在 MusicBrainz 中没有档案——绝大多数歌手与幕后创作者早已拥有稳定的 Artist MBID。补齐这些经 MusicBrainz 真实实体核验的人物级 MBID，一方面可让下游音乐服务器（如 032 Music Server、Navidrome、Plex）将联网归档曲目准确聚合到已有艺人及创作者主页下，另一方面也为后续「向 MusicBrainz 回馈缺失专辑（Release Editor Seeding）」提供现成的 Artist MBID 底座。

## What Changes

- **新增 MusicBrainz 艺人本地缓存与被动沉淀机制（`MusicBrainzArtistCache`）**：
  - 在 SQLite（启动自动幂等建表）与 MySQL（同步补充 `schema.sql` DDL，老库未建表时安全降级跳过）中新增 `mb_artist_cache` 表，文件模式下回退为进程内有界 LRU 缓存。
  - 每当常规 MusicBrainz 识别路径（`MusicBrainzClient`）解析出 Recording 艺人、Release 专辑艺人或曲目级作曲/作词/编曲实体时，被动沉淀其 `mbid`、官方主名、排序名（`sort-name`）、已知别名、`disambiguation` 备注、地区与实体类型，实现库内已有艺人 0ms 命中且与既有曲库 100% 实体一致。
- **扩展 `MusicBrainzClient` 支持 Artist 检索与单实体核验**：
  - 新增 `searchArtists(String queryName, int limit)` 与 `getArtistById(String mbid)`，统一复用现有 1 req/s 速率限制、代理与重试机制，用于检索同名候选及核验外链提取到的 Artist UUID。
- **在联网识别阶段新增三层人物 MBID 预解析与消歧编排（`OnlineEntityMbidResolver`）**：
  - 在联网搜索完成候选匹配与幕后人员补全后，按配置开关与每张专辑的请求预算上限，异步对候选涉及的去重人名（专辑艺人、曲目艺人以及准入核验通过的作曲、作词、编曲）执行三层解析：
    1. **Layer 1（本地缓存命中）**：归一化人名/别名精确匹配且本地无同名多实体冲突时直接高置信采纳；
    2. **Layer 2（联网页面 MB 外链提取 + API 回查）**：从本轮已抓取网页（如 MusicBrainz、Wikipedia、Wikidata、VGMdb、VocaDB 等）中提取 `musicbrainz.org/artist/<uuid>` 外链，经 `getArtistById` 核验 UUID 真实存在且主名/别名与目标人名一致后直接采纳；
    3. **Layer 3（MusicBrainz `/ws/2/artist` 检索 + 封闭式 LLM 上下文消歧）**：唯一精确主名/官方别名命中且无同名竞争者时自动高置信绑定；遇到同名多候选时，将真实候选列表（含 `disambiguation`、地区、类型、别名、标签）交由封闭式 LLM 选择器仅从真实候选中择一或返回 `NONE`；无法高置信消歧时标记为 `ambiguous` 并保留候选列表交由人工选择，严禁 LLM 凭记忆生成或猜测 UUID。
  - 新增可热更新配置项：`online.mbid.resolve.enabled`、`online.mbid.maxLookupsPerAlbum`、`online.mbid.llmDisambiguate.enabled`。
- **扩展待确认数据模型、接口与确认弹窗交互（`ReviewItem`、`ReviewServlet`、`review.html`）**：
  - 在 `ReviewItem.OnlineCandidate` 中持久化人物级 MBID 解析表（`entityMbids`，按规范化人名索引，记录状态 `resolved`/`ambiguous`/`not_found`、绑定 MBID、匹配来源、排序名、`disambiguation` 与同名候选列表），保持向后兼容历史 `review-queue.json`。
  - 在待确认详情与联网确认弹窗中展示即将写入的艺人/幕后人员 MBID 徽章与可点击 MusicBrainz 链接；遇到 `ambiguous` 同名条目时列出候选（含 `disambiguation`、地区、类型）供人工点选，同时支持手动输入合法 UUID 或显式取消绑定（留空）。
  - 在 `POST /api/review/online-confirm` 中支持可选的 `entityMbidOverrides` 参数，并在拦截转入「缺少封面」队列时随 `ReviewItem.MissingCover` 持久化，重启后仍完整生效。
- **在联网归档落盘阶段写入对齐的人物 MBID 并清理旧艺人 ID（`RecoveryService`、`MetadataUtils`、`TagWriterService`）**：
  - 写入 `MUSICBRAINZ_ARTISTID`、`MUSICBRAINZ_RELEASEARTISTID`、`MUSICBRAINZ_COMPOSERID`、`MUSICBRAINZ_LYRICISTID`、`MUSICBRAINZ_ARRANGERID` 时严格遵守“**全有或全无（All-or-Nothing）**”顺序对齐规则：单人命中或多人列表全部成员均拥有经核验/人工选定的合法 UUID 且无冲突时才写入，任一成员缺失则整组留空，杜绝位置错位。
  - 保留现有幕后人员四级合并优先级（`手动编辑 > MusicBrainz 指纹结果 > 本地文件已有标签 > 联网解析结果`），并补齐 `TagWriterService` 在联网改写 `ARTIST` / `ALBUM_ARTIST` 且无新有效 MBID 时对源文件残留旧 `MUSICBRAINZ_ARTISTID` / `MUSICBRAINZ_RELEASEARTISTID`（及对应排序名）的显式清理能力。
  - 仍严格禁止联网识别生成或写入任何 Release ID、Release Group ID 或 Recording ID。

## Capabilities

### New Capabilities

（无新增顶层独立 capability；本次所有规范变更均自然归属既有 `online-identification` 与 `online-archive-credits` 两大能力边界。）

### Modified Capabilities

- `online-identification`: 将原先“不生成或捏造任何 MusicBrainz ID”细化为禁止模型虚构任何 MBID 且不生成专辑/录音级 MBID，新增联网识别对人物级实体（专辑艺人、曲目艺人、经核验的作曲/作词/编曲）执行「本地缓存 -> 网页 MB 外链提取核验 -> MusicBrainz Artist API 检索与封闭式消歧」的三层解析行为与预算控制要求。
- `online-archive-credits`: 扩展联网归档与确认弹窗对人物级 MBID 的展示、同名候选点选、手填与取消绑定交互，规范 `MUSICBRAINZ_ARTISTID` / `MUSICBRAINZ_RELEASEARTISTID` 与幕后人员 MBID 的全员严格对齐写入、缺封面中转持久化，以及联网改写艺人无有效新 ID 时对旧艺人 MBID 的显式清理。

## Impact

- **新增核心类**：`MusicBrainzArtistCache`、`OnlineEntityMbidResolver`（含封闭式 LLM 消歧辅助组件及配套单元测试）。
- **修改现有类**：
  - 核心服务与模型：`MusicBrainzClient`、`DatabaseService`、`WebPageFetcher`、`OnlineIdentificationService`、`TrackCreditEnrichmentService`、`ReviewItem`、`MusicMetadata`、`MetadataUtils`、`TagWriterService`、`RecoveryService`、`ApplicationLifecycleManager`。
  - 配置与 Web 层：`MusicConfig`、`ConfigFieldSpec`、`ConfigServlet`、`ReviewServlet`、`src/main/resources/schema.sql`、`src/main/resources/static/review.html`、`src/main/resources/messages_zh_CN.properties`、`src/main/resources/messages_en_US.properties`。
- **兼容性与边界**：
  - `ReviewItem` / `MissingCover` 新增字段全部可空，完全兼容历史 `review-queue.json` 与不传 `entityMbidOverrides` 的旧调用方。
  - **明确非目标（Out of Scope）**：本次不包含向 MusicBrainz 回馈新专辑（Release Editor Seeding）、Cover Art Archive 封面上传与 AcoustID 声纹提交（由后续独立变更 `musicbrainz-contribution-loop` 承接）；不修改 `AtomicAudioPublisher` 原子发布链路与既有 MusicBrainz 自动选版逻辑。
