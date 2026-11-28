# Design

## Context

变更 1（`online-artist-creator-mbids`）已建立本地艺人 MBID 缓存（`MusicBrainzArtistCache`）、联网三层人物 MBID 解析（`OnlineEntityMbidResolver`）以及待确认弹窗的预览/改选/持久化底座（见 `proposal.md` - Why）。当前系统已具备：
- 联网识别候选结构 `ReviewItem.OnlineCandidate`（含 `title`、`artist`/`artists`、`albumArtist`/`albumArtists`、`releaseDate`、`edition`、`country`、`label`、`catalogNumber`、`coverUrl`、`coverSourceUrl`、`sources`、`tracks` 含 `creditDetail`、`entityMbids`）；
- 本地音频精准时长读取（`TagWriterService.readPreciseDuration`）与原子标签原地更新（`TagWriterService.updateTagsOnExistingFile` + `LibraryWriteGuard`）；
- `MusicBrainzClient`（统一 1 req/s 限流、代理、重试、`fetchReleaseTagBundle`、`getReleasesForGroup`、`searchAlbum`、`searchArtists`）与 `AudioFingerprintService`（`fpcalc` 生成时长与 Chromaprint 声纹、AcoustID `/v2/lookup`）；
- `CoverCandidateService` 与 `CoverCandidateCache`（当前将所有候选压缩至 `1200px` JPEG 后写入 `.cover_cache/.candidates/<sha256>.jpg`）；
- Jetty `WebServer` 可热装配 `SwappableServlet` 槽位与侧栏共用脚本 `shell.js`。

受 MusicBrainz 与 Cover Art Archive 官方架构约束：
1. MusicBrainz WS/2 API 不提供无头创建 Release / Work 的写入端点，官方社区标准方案为 **Release Editor Seeding**（`POST https://musicbrainz.org/release/add`，`multipart/form-data`，人工在浏览器登录态核对提交）。
2. Cover Art Archive 不提供无头图片二进制直传 API，官方标准为通过 `/release/<mbid>/add-cover-art` 网页提交（社区广泛支持 `x_seed` 查询参数配合 ECAU 脚本，同时兼容下载原图后手动拖拽）。
3. 作曲/作词/编曲在 MusicBrainz 属于 Work/Recording Relationship，不能随 `/release/add` 表单一步创建，必须在 Release 创建后前往 `/release/<mbid>/edit-relationships` 补录；且社区规范要求创作者关系绑定个人实体 MBID 并剥离末尾括号里的所属厂牌/社团名（如 `(OTOIRO)`、`(ninelive.)`）。
4. AcoustID 提供官方 `POST https://api.acoustid.org/v2/submit` 接口，需同时提供应用 Key（已有 `acoustid.apiKey`）与个人用户提交 Key（新增 `acoustid.userApiKey`）。

## Goals / Non-Goals

**Goals:**
- 构建完整、非阻塞、可跨重启恢复的 MusicBrainz 回馈工作流，兼容 SQLite、MySQL 与文件模式（零外部数据库迁移门槛）。
- 严格遵循 MusicBrainz 与 AcoustID 官方协议与社区质量红线（先查重、绝不提交残缺目录、绝不后台静默建库、附一手来源 URL、去所属括号合规指引）。
- 保留最高画质封面来源供 CAA 上传，同时不改变现有嵌入式音频封面 `1200px` JPEG 压缩策略。
- 贴回真实 Release URL/MBID 后，原位补写本地音频全套官方 MBID 与缺失发行元数据，严格保留既有词曲编标签、内嵌封面以及 `recovery-record-lineage` 监控源血缘不分裂。
- 复用既有限流、代理、SSRF 防护、`LibraryWriteGuard` 与热更新配置体系。

**Non-Goals:**
- 不实现 OAuth 登录 MusicBrainz 或后台自动提交 Release / Work / CAA 图片。
- 不改变变更 1 确定的本地音频创作者标签保留原文策略。
- 不在自动文件监控扫描阶段对未人工确认的专辑触发贡献入队（仅在人工确认联网归档完成后或用户手动触发时进入回馈流程）。

## Decisions

### 1. 持久化与文件布局：原子 JSON 队列 + 独立原图备份目录

- **方案**：在 `reviewQueuePath` 同级目录下持久化：
  - `mb-contributions.json`（临时文件写入 + 原子重命名，参照 `ReviewQueueService` / `ReidentifyReplacementRegistry`），承载 `MbContributionItem` 列表。
  - `mb-contribution-covers/`，按 `<contributionId>.<ext>` 保存无公网 URL 封面的未压缩原图备份（仅接受合法图片魔数且 `<= ImageCompressor.MAX_INPUT_BYTES`）。
- **状态机**：`CHECKING` → `READY_TO_SEED`（Case B/C）/ `EXISTING_MATCH`（Case A）/ `LINKED`（已关联 Release 并完成本地回填，待补图/补关系/传声纹）/ `COMPLETED` / `DISMISSED` / `CHECK_FAILED`。
- **去重键**：以 `reviewItemId` 或规范化后的 `archivedOutputDir` 去重（同一次归档/同一在库专辑不产生重复卡片；重新确认覆盖归档时刷新既有未完结卡片）。
- **清理策略**：条目进入 `COMPLETED` 或 `DISMISSED` 时立即删除对应的 `mb-contribution-covers/<id>.*`；服务启动时清理超过 30 天的孤儿备份文件；所有文件路径校验必须严格位于 `mb-contribution-covers/` 规范根目录内，防路径穿越。
- **备选方案对比**：若新建 SQLite/MySQL 表，则外部 MySQL 用户需手动执行 DDL 升级且文件模式不可用；而待回馈队列规模仅数十至数百条，原子 JSON 文件与现有 `review-queue.json` 模式完全一致，零迁移成本且全模式行为一致。

### 2. MusicBrainz 存在性预检服务（`MusicBrainzExistenceChecker`）与四类分流

在 `MusicBrainzClient` 中扩展只读检索能力（复用 `rateLimit()`、代理与重试）：
- `searchReleasesByBarcode(String barcode)`：`GET /ws/2/release?query=barcode:<digits>&fmt=json&limit=10`
- `searchReleasesByCatalogNumber(String catno, String artistOrMbid)`：`GET /ws/2/release?query=catno:"..."...&fmt=json&limit=10`
- `searchReleaseGroups(String title, String artistName, String artistMbid, int limit)`：`GET /ws/2/release-group?query=...&fmt=json&limit=10`（有合法 `artistMbid` 时优先使用 `arid:<mbid>`，避免跨语种译名漏查！）
- `getFullReleaseWithTracklist(String releaseId)`：基于现有 `getFullReleaseById(releaseId)`（已含 `inc=recordings+artist-credits+labels+release-groups+media` 或补充 `url-rels`）解析结构化 `ReleaseDetail`（Release MBID、RG MBID、Title、Primary/Secondary Type、Status、Date、Country、Barcode、Labels/Catnos、Album Artist Credits、按 Medium 分组的完整 Track 列表含 `trackId`、`recordingId`、`position`/`number`、`title`、`lengthMs`、`artistCredits`）。

**四类判定逻辑（`MusicBrainzExistenceChecker`）**：
1. **Case D（`NOT_ELIGIBLE`）前置门禁**：
   - 若候选 `officialCandidate == false`，或无任何 `HIGH`/`MEDIUM` 真实来源 URL，或有效曲目表为空，或本地已归档曲目为未匹配杂乱集合（匹配率 `< 0.5` 且无可靠官方曲目表）→ 返回 `NOT_ELIGIBLE`，不自动入队（仍允许用户在历史确认记录中手动触发预检）。
2. **Step 1：精确 Release 检索（`barcode` → `catalogNumber`）**：
   - 若候选或本地音频标签存在合法条码（8–14 位数字）或非空目录号，向 MB 检索 Release。
   - 对每个高分命中 Release 拉取其曲目数与时长序列（优先复用 `getReleasesForGroup` / `getReleaseDurationSequence`，单次预检严格设限最多拉取 5 个候选 Release 以防耗尽 1 req/s 配额）。
   - 若曲目数完全一致且可比对曲目时长均在 `<= 5s` 容差内（或条码精确命中且曲目数一致）→ 返回 **Case A（`EXISTING_RELEASE`）**。
3. **Step 2：Release Group 检索（`title` + `arid:<artistMbid>` / `artist:"..."`）**：
   - 归一化比对标题（NFKC + 去版本括号 + 大小写无关）与艺人命中；对高匹配 RG 调用 `getReleasesForGroup(rgId)` 检查该 RG 下现有 Releases：
     - 若其中某 Release 曲目数一致、时长序列平均差 `<= 3s` 且最大单曲差 `<= 6s`（且若候选有明确地区/目录号则不冲突）→ 返回 **Case A（`EXISTING_RELEASE`）**。
     - 否则 RG 存在但无精确匹配发行版（例如仅有欧美版或普通版，缺当前日版/数字加曲版）→ 返回 **Case B（`MISSING_EDITION`）**，携带命中的 `existingReleaseGroupId` 与已有版本摘要列表。
4. **Step 3：无匹配** → 返回 **Case C（`MISSING_RELEASE_GROUP`）**。

> 可选数据增强：在 `OnlineIdentificationService` 的 JSON Schema 与解析中补充可选字段 `barcode`、`release_type`（`album`/`single`/`ep`/`broadcast`/`other`）与 `media_format`（`Digital Media`/`CD` 等），向后兼容历史无此字段数据；若联网未给出，自动从本地音频标签（`BARCODE`、`CATALOGNUMBER`、`LABEL`）与曲目数/路径线索保守补全。

### 3. Release Editor Seeding 构造器（`MusicBrainzSeedBuilder`）

严格按照 MusicBrainz 官方 Release Editor Seeding 规范生成扁平键值对列表 `List<SeedField(name, value)>`，由前端在用户点击按钮时动态构造 `<form method="POST" action="https://musicbrainz.org/release/add" enctype="multipart/form-data" target="_blank" accept-charset="UTF-8">` 提交：
- **为什么用前端动态 POST 表单而非 GET URL**：多曲目专辑的 Seeding 参数轻松超过 4–8 KB，GET URL 会被浏览器/代理截断；POST `multipart/form-data` 是 MusicBrainz 官方推荐方式，且直接在用户已登录的浏览器上下文打开编辑页，后端零凭据接触。
- **核心字段映射（已核对官方文档）**：
  - `name`：发行标题（专辑名，不含仅用于本地目录防撞的人造后缀，版本差异放在 `comment` 消歧备注中）。
  - `comment`：仅在存在明确版本区分（如 `Japanese digital EP`、`Limited Edition`）且非冗余描述时可选填入。
  - `release_group`：仅当 Case B 且用户保留挂入既有 RG 时传入真实 RG MBID。
  - `type`：仅当新建 RG（无 `release_group`）时传入合法主类型（`Album`/`Single`/`EP` 等）。
  - `status`：`official`；`packaging`：数字版为 `None`（仅在确定为 Digital Media 时设置，避免误猜实体包装）。
  - `language` / `script`：基于发行地区与标题字符集保守推断（如含日文假名→`jpn`/`Jpan`，纯中文→`zho`/`Hans`或`Hant`，可留空由用户在 MB 页面调整）。
  - `events.0.date.year` / `.month` / `.day` 与 `events.0.country`（ISO 3166-1 alpha-2，合法值才传，如 `JP`/`XW`）。
  - `labels.0.name`、`labels.0.catalog_number`；`barcode`（合法数字条码才传，不盲填 `none`）。
  - `artist_credit.names.i.*`：按专辑艺人拆分序列与连接符展开；已绑定合法 MBID 时传 `artist_credit.names.i.mbid` + `artist_credit.names.i.name`（发行署名原文）；未绑定时传 `artist_credit.names.i.artist.name` + `artist_credit.names.i.name`；相邻成员间传 `artist_credit.names.i.join_phrase`（从 `ArtistCreditSupport` / 原署名推导，默认 `", "` 或 `" & "`）。
  - `mediums.m.format`（如 `Digital Media` / `CD`）、`mediums.m.track.t.name`、`mediums.m.track.t.number`、`mediums.m.track.t.length`（**毫秒**整数：有本地高置信匹配文件时取 `Math.round(preciseDuration * 1000)`，否则取官方来源 `duration * 1000`）、`mediums.m.track.t.recording`（若前置指纹识别曾命中真实 Recording MBID 且与曲目一致时透传）、`mediums.m.track.t.artist_credit.names.i.*`（与专辑艺人一致时也可省略或显式填充）。
  - `urls.u.url`：仅纳入通过可信度校验（`HIGH`/`MEDIUM`）的真实一手页面 URL（最多 10 条去重，自动剔除追踪参数与本地/私网地址）；省略 `urls.u.link_type` 以让 MusicBrainz 根据域名自动匹配标准关系类型（社区最佳实践，避免写死易过期的 link_type 整数导致校验错误）。
  - `edit_note`：自动生成清晰、符合社区礼仪的英文/双语来源说明，列出所有核验用一手来源 URL、本地文件毫秒时长来源说明，并明确标明「Metadata prepared with MusicAutoTagger and verified by editor before submission」（绝不宣称全自动 AI 提交）。
- **用户微调能力**：在待回馈工作台卡片打开 Seeding 前，允许用户调整 `title`、`comment`、`releaseGroupId`（切换挂入既有 RG 或新建 RG）、`releaseType`、`releaseDate`、`country`、`mediaFormat`、`label`、`catalogNumber`、`barcode` 与待提交 `urls` 勾选，修改保存后立即反映到 Seeding 表单中。

### 4. Cover Art Archive（CAA）高画质封面保留与双模式引导

- **原图来源捕获（最小侵入扩展 `CoverCandidateService` & `RecoveryService`）**：
  - **公网来源**：`CoverCandidateView` 已有 `sourceUrl`。当选定封面来自 `ITUNES` 时，`CoverCandidateService` 在 `CoverCandidateView` 中额外记录或由 `MusicBrainzContributionService` 从 `sourceUrl` 升格一份最高清 URL（例如将 `/1200x1200bb.jpg` 升格为 `/3000x3000bb.jpg`，并保留 `sourcePageUrl` 如 `coverSourceUrl` 或首个权威 `sourceUrl` 作为 `x_seed.origin`）；来自 `LLM_URL` / `USER_URL` 时直接保留其 `sourceUrl`（确保经过 `OutboundUrlValidator` 校验为公网 HTTP/HTTPS）。
  - **本地来源（`FOLDER` / `EMBEDDED` / `USER_UPLOAD`）**：在 `RecoveryService.confirmOnlineCandidateInternal` 确认归档落盘前（以及 `CoverMissingService` 补图落盘前），保留一份未压缩原图字节引用（优先从原目录封面文件 / 原内嵌封面 / 刚上传的原始字节读取；若已被清理则回退至候选缓存中的 `1200px` JPEG），在入队 `MbContributionItem` 时写入 `mb-contribution-covers/<id>.<ext>`。
- **双模式上传引导**：
  - 绑定 Release MBID 后，后端为卡片生成 CAA 引导对象：
    - `addCoverArtUrl`：基础为 `https://musicbrainz.org/release/<mbid>/add-cover-art`；当存在公网高画质 `publicCoverUrl` 时附加 URL 编码的社区 `x_seed` 查询参数：`x_seed.origin`、`x_seed.image.0.url`、`x_seed.image.0.types=front`、`x_seed.image.0.comment`。
    - `localDownloadUrl`：当存在本地原图备份（或可从已归档音频/封面文件提取）时，提供鉴权受保护的 `/api/mb-contributions/cover?id=<id>` 下载接口，返回 `Content-Disposition: attachment` 与正确 MIME 类型。
    - `suggestedComment`：如 `Front cover from <originDomain> (<sourceUrl>)` 或 `Embedded front cover from digital release`，支持一键复制。

### 5. 合规 Works / 词曲编关系补录参考卡（`MusicBrainzCreditSanitizer`）

- 绑定 Release MBID 后生成直达 `https://musicbrainz.org/release/<mbid>/edit-relationships` 的入口与逐曲结构化参考卡：
- **括号所属后缀清洗规则（复合并对齐变更 1 的 `OnlineEntityMbidResolver` 括号识别能力）**：
  - 仅在 MB 关系参考卡输出中执行，**绝不修改本地音频文件标签**。
  - 若创作者显示名末尾存在括号内容（半角 `(...)` 或全角 `（...）`），如 `tepe (OTOIRO)`、`雨良 Amala (ninelive.)`、`神前暁（MONACA）`：
    - 提取括号内文本作为 `affiliationNote`（例如 `OTOIRO`、`ninelive.`），建议仅放进 Edit Note 备注，不填入 MB 关系的 `credited as`；
    - 剩余纯人名部分 `cleanName`（如 `tepe`、`雨良 Amala`）：
      - 若与已绑定 MB 实体主名（`selectedName`）归一化后完全一致（如 `tepe` == `tepe`），则建议 `creditedAs = null`（直接使用规范主名）；
      - 若与主名不同但属于该艺人的真实人名/艺名变体（如 `雨良 Amala` vs 主名 `雨良`），则建议 `creditedAs = "雨良 Amala"`。
  - 若创作者无括号后缀：同主名时 `creditedAs = null`，异于主名时建议 `creditedAs = displayName`。
- **可信度与出处过滤**：
  - 仅展示最终归档采用的词/曲/编（含 `MANUAL`、`MUSICBRAINZ`、`LOCAL`、`ONLINE_VERIFIED`、`ONLINE_PROBABLE`），排除 `CONFLICT` 未决、`NOT_FOUND` 与 `NOT_SEARCHED`；
  - 对 `ONLINE_PROBABLE` 显式标记提醒标签，每首曲目附可一键复制的「Edit Note 出处模板」（列出所有相关来源 URL 与短引文）。

### 6. 贴回 Release MBID 回填本地标签与处理记录（`MusicBrainzReleaseBackfillService`）

用户提交 `POST /api/mb-contributions/link-release`（参数：`id`、`releaseUrlOrMbid`）：
1. **解析与权威拉取**：提取并校验合法非零 UUID（支持粘贴 `https://musicbrainz.org/release/<uuid>`、`https://beta.musicbrainz.org/release/<uuid>/...` 或纯 UUID），调用 `MusicBrainzClient.getFullReleaseWithTracklist(releaseId)` 拉取完整 Release；无效返回 `400`，MB 返回 404 返回 `404`。
2. **安全曲目匹配**：
   - 收集该贡献条目关联的本地已归档音频文件（优先验证仍在 `config.getOutputDirectory()` 内且真实存在）。
   - 按「碟号 + 曲号（若匹配）→ 标准化标题匹配 → 时长容差校验（`<= 10s`，且若总曲目数与官方相同要求逐首匹配无冲突）」建立严格一对一映射；若存在无法唯一安全匹配的文件或曲目数超过官方曲目数，直接返回 `400`（`mb.contribution.track.mismatch`），绝不写错 Recording ID！
3. **原子原地回填标签（保留既有成果）**：
   - 在 `LibraryWriteGuard.acquire()` 保护下逐首读取现有标签（`tagWriter.readTags(file)`），仅补写/更新官方 MB 权威字段：
     - `releaseId`（`MUSICBRAINZ_ALBUMID`）、`releaseGroupId`（`MUSICBRAINZ_RELEASEGROUPID`）、`recordingId`（`MUSICBRAINZ_TRACKID`）、`releaseTrackId`（`MUSICBRAINZ_RELEASETRACKID`）；
     - 官方 `artistId`、`albumArtistId`、`artistSort`、`albumArtistSort`（按官方 Artist Credits 完整对齐）；
     - 仅当本地原标签缺失或为未知时补全官方 `barcode`、`catalogNumber`、`recordLabel`、`releaseCountry`、`releaseStatus`、`originalYear`、`originalReleaseDate`、`releaseType`、`discTotal`、`trackTotal`；
     - **严禁覆盖或清空**本地已有的有效 `composers`/`lyricists`/`arrangers` 及其 MBID，也不覆盖本地已有内嵌封面（`coverArtData = null`），仅保留并调用 `tagWriter.updateTagsOnExistingFile(file, merged, null)`！
   - 若官方 Release 拉取到新艺人，被动写入 `MusicBrainzArtistCache` 供未来零请求复用。
4. **同步更新 `processed_files`（保持监控源血缘不分裂）**：
   - 按已归档输出路径反查 `processed_files` 既有记录（优先匹配 `target_file_path == archivedFile` 的监控主记录，其次 `file_path == archivedFile`）；
   - 使用 `ProcessedFileLogger.applyRecordMigrationsAtomically` 原位更新每条记录的 `recordingId`（由 `ONLINE_SEARCH` 升级为真实官方 Recording MBID）与 `releaseGroupId`（升级为真实官方 Release Group MBID），**保持原有 `file_path`（监控源路径）、`target_file_path` 与 `processed_time` 不变**，绝不制造分裂记录！

### 7. 可选 AcoustID 声纹提交（`AudioFingerprintService.submitFingerprints`）

- **配置项**：
  - `acoustid.userApiKey`（默认空，敏感字段，支持热更新，复用 `ConfigFieldSpec` 敏感掩码机制；文档/设置页提示在 `https://acoustid.org/api-key` 登录获取个人 User API Key）。
  - 复用现有 `acoustid.apiKey` 作为 `client` 参数；新增 `acoustid.submitApiUrl`（默认 `https://api.acoustid.org/v2/submit`，便于单测 Mock）。
- **提交协议**：
  - 仅在 `acoustid.apiKey` 与 `acoustid.userApiKey` 均非空、条目已完成 Release 关联且存在至少 1 首含合法 `recordingId` 的本地在库音频时允许提交。
  - 逐首调用 `fpcalc`（复用 `AudioFingerprintService.generateFingerprint`）提取 `duration` 与 `fingerprint`，按 AcoustID 官方 `/v2/submit` 格式（`client`、`user`、`duration.0`、`fingerprint.0`、`mbid.0`…，单批最多 50 首分批提交）发起 `application/x-www-form-urlencoded`（UTF-8）POST 请求。
  - 解析 `{"status":"ok","submissions":[...]}` 响应，持久化 `acoustIdSubmittedAt`、`acoustIdSubmittedCount` 与结果摘要。

### 8. API 与前端架构

- **后端路由**：新增 `MbContributionServlet` 挂载于 `/api/mb-contributions/*`（接入 `WebServer` 可热装配 `SwappableServlet` 槽位，待初始化模式统一返回 `503 setup.required`）：
  - `GET /api/mb-contributions/stats`：各状态计数（供 `shell.js` 侧栏红/蓝徽章轮询）与开关状态 `enabled`、`acoustIdReady`。
  - `GET /api/mb-contributions/list?status=...`：按状态筛选返回卡片列表（默认展示待处理 `READY_TO_SEED` / `EXISTING_MATCH` / `LINKED` / `CHECK_FAILED`）。
  - `GET /api/mb-contributions/item?id=...`：返回完整详情，包含预检结论、可编辑字段、Seeding 表单字段数组、CAA 引导对象、合规 Works/词曲编参考卡、回填明细与声纹提交状态。
  - `POST /api/mb-contributions/create-from-review`：按 `reviewItemId`（已确认的联网归档条目）手动发起存在性预检并创建/刷新待回馈条目。
  - `POST /api/mb-contributions/recheck`：重新执行 MB 存在性预检。
  - `POST /api/mb-contributions/update-draft`：保存用户在 Seeding 前微调的发行字段（标题、备注、是否挂入既有 RG、类型、日期、国家、格式、厂牌、目录号、条码、来源 URL 勾选）。
  - `POST /api/mb-contributions/link-release`：贴回 Release URL/MBID（或 Case A 一键关联），拉取权威 Release 并执行本地全套 MBID 回填。
  - `POST /api/mb-contributions/submit-acoustid`：对已关联条目计算并批量提交 Chromaprint 声纹至 AcoustID。
  - `POST /api/mb-contributions/complete` / `POST /api/mb-contributions/dismiss`：完结或忽略条目，并清理本地原图备份。
  - `GET /api/mb-contributions/cover?id=...`：下载原图备份（校验路径在备份目录内，仅返回图片 MIME）。
- **前端界面**：
  - 新增 `src/main/resources/static/contributions.html`（沿用 `app.css` + `shell.js` + `api.js` + `i18n.js` 统一样式与暗/亮主题），提供清晰的 3 步引导卡片视图：
    - **Step 1**：预检分流横幅（Case A 一键关联已有 Release；Case B 切换挂入既有 RG / 新建 RG；Case C 新建专辑）+ 可折叠发行字段微调表单 + 「前往 MusicBrainz 预填提交（新窗口）」按钮（动态构建 POST `multipart/form-data` 表单）。
    - **Step 2**：贴回新建的 MusicBrainz Release 链接输入框 + 「校验并回填本地官方 MBID」按钮（展示逐曲匹配与回填摘要）。
    - **Step 3**（关联后解锁）：三栏收尾工具 —— **CAA 高画质封面上传**（直达 `add-cover-art` + `x_seed` / 原图下载 / 复制 Comment）、**Works 词曲编合规参考卡**（直达 `edit-relationships` + 去所属括号干净人名/MBID 链接 + 一键复制出处 Edit Note）、**AcoustID 声纹提交**（一键提交或配置引导）+ 「标记已完成」按钮。
  - 在 `index.html`、`review.html`、`covers.html`、`recovery.html`、`records.html` 侧栏新增「回馈 MB」入口与积压徽章；在 `index.html` 设置页新增 `mb.contribution.enabled`、`mb.contribution.autoCheckAfterArchive` 与 `acoustid.userApiKey` 配置项；在 `review.html` 已确认联网条目详情中补充「检查并回馈 MusicBrainz」快捷按钮。

## Risks / Trade-offs

- **[Risk] MusicBrainz 1 req/s 限流在批量联网确认时导致预检排队较慢** → Mitigation：存在性预检完全在归档完成后的独立单线程低优先级异步执行器中运行，不阻塞归档响应或主监控扫描；单次预检严格限制最多查 1 次条码/目录号、1 次 RG 搜索与最多 5 个 Release 展开，并在进程关闭时优雅中断。
- **[Risk] 预检误把不同版本当成 Case A（漏报 Case B）或误把已有版本当成 Case C（重复提交）** → Mitigation：Case A 要求条码/目录号精确命中或曲目数+时长序列严格匹配；无论 Case B 还是 Case C，提交始终经由 MusicBrainz 官方 Release Editor 浏览器页面（MB 网页端本身还会展示相似 RG/Release 二次提醒），做到双重防重。
- **[Risk] 贴回错误的 Release URL 导致本地音频写入错误的 Recording MBID** → Mitigation：回填前强制校验曲目数与时长容差，无法安全一一匹配时直接拒绝修改任何本地文件，并向用户展示本地与远端曲目明细差异。
- **[Risk] `mb-contribution-covers/` 本地未压缩原图长期堆积占用磁盘** → Mitigation：仅在无公网原图 URL 时落盘本地备份；条目完成/忽略立即删除，启动时自动清理超过 30 天且不再被活跃条目引用的孤儿备份；文件大小沿用 `ImageCompressor.MAX_INPUT_BYTES` 上限。
- **[Risk] `acoustid.userApiKey` 泄露** → Mitigation：归类为敏感配置项，`/api/config` 仅返回掩码且日志中禁止输出；更新接口支持掩码保值与显式清空。

## Migration Plan

- **零破坏升级**：新增 `mb-contributions.json` 与 `mb-contribution-covers/` 均按需自动创建，不修改 SQLite/MySQL 表结构，无需手动执行 SQL 迁移。
- **配置默认兼容**：`mb.contribution.enabled` 与 `mb.contribution.autoCheckAfterArchive` 默认 `true`，`acoustid.userApiKey` 默认空（未配置时仅声纹提交按钮提示配置，其余功能全部可用）。
- **回滚策略**：关闭 `mb.contribution.enabled=false` 或回退镜像即可完全停用回馈队列；已回填进本地音频文件的标准官方 MusicBrainz 标签与 `processed_files` 中的真实 Recording/RG MBID 均与既有版本完全兼容。
