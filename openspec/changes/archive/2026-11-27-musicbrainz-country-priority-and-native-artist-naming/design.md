# Design

## Context

变更背景与范围见 [proposal.md](proposal.md)，外部行为契约见 [spec.md](specs/musicbrainz-release-and-artist-naming/spec.md)。

已核实的代码现状与约束：
- `MusicConfig` 已有 `releaseCountryPriority`（`release.countryPriority`）和 `collapseAlbumArtistToVariousArtists`（`tag.albumArtist.collapseToVariousArtists`），均已接入 `ConfigServlet` 与 `index.html` 热更新。
- `MusicBrainzClient` 共有 6 处 MusicBrainz 选版/候选收集入口：
  1. `fetchAlbumDurationSequence(String releaseGroupId)`（供 `QuickScanService` 调用）
  2. `fetchAllReleaseDurationSequences(String releaseGroupId)`（供 `FolderAlbumCache` 调用，受 `tryCount <= 10` / `successCount < 5` 截断限制）
  3. `getReleasesForGroup(String releaseGroupId)`（供 `ReviewResolutionService` 展开候选调用）
  4. `matchTrackInReleaseGroup(...)`（仅锁定 Release Group 时的逐曲匹配）
  5. `selectBestRelease(...)` Stage 1（命中 `preferredReleaseGroupId` 时目前盲目返回第一个版本）
  6. `selectBestRelease(...)` Stage 2（`allowAlbumGuess == true` 时虽遍历 `countryPriority`，但在同地区内未按媒体格式与日期决胜）
- `MusicBrainzClient.scoreReleaseForDuration(JsonNode)` 目前只看媒体格式（`CD=100`、`Digital=90`、其他=`50`、视频=`-100`），完全忽略地区优先级与发行日期。
- `AlbumDurationResult` 与 `DurationSequenceService.AlbumDurationInfo` 目前未携带 `releaseCountry` 与 `releaseDate`；`DurationSequenceService` 仅有无参构造 `new DurationSequenceService()`，无法读取 `release.countryPriority`。
- 艺人展示名解析分布在 `AlbumArtistPolicy.creditName(JsonNode)` 和 `MusicBrainzClient.resolveCreditedArtistText(JsonNode)` 两处，目前均优先取 `credit.name`；而 `MusicBrainzClient.artistNames(JsonNode)` 与词曲编提取已经采用 `artist.name`。
- `MusicBrainzClient.albumDurationCache` 与 `allReleaseDurationCache` 仅按 `releaseGroupId` 建键，配置热更新后最多 30 分钟内仍会命中旧配置产物。

## Goals / Non-Goals

**Goals:**
- 将 MusicBrainz 发行版排序规则（视频排除、地区优先级、音频媒体格式、更早发行日期）沉淀为单一纯工具类，在全部 6 条 `MusicBrainzClient` 选版路径和 `DurationSequenceService` 中复用。
- 将 `releaseCountry` 与 `releaseDate` 从 `AlbumDurationResult` 经 `FolderAlbumCache` 透传到 `DurationSequenceService.AlbumDurationInfo`，同时保留既有构造方法签名以兼容现有单测。
- 在 `DurationSequenceService` 中通过二阶段同组细化落实地区偏好，完全不破坏跨专辑 DTW 硬门槛与竞争 `margin` 校验。
- 将东亚原生艺人展示名解析沉淀为单一纯工具类，由 `AlbumArtistPolicy` 与 `MusicBrainzClient` 共用，并受热更新配置 `tag.artist.preferNativeName`（默认 `true`）控制。
- 为时长序列缓存引入配置指纹复合键，使运行时配置变更立即生效。

**Non-Goals:**
- 不对已归档磁盘文件执行批量重贴标签或目录重命名。
- 不修改联网/LLM 识别规格（`online-identification` / `online-archive-credits`）、AcoustID 指纹识别或封面优选策略。
- 不额外发起 MusicBrainz `/artist/{id}?inc=aliases` 联网查询（`credit.name`、`credit.artist.name`、`release.country`、`release.date` 在现有 API 响应中均已返回）。

## Decisions

### 决策 1：统一 MusicBrainz 发行版本排序工具（`MusicBrainzReleasePriority`）
在 `com.lux032.musicautotagger.service` 下新增纯工具类 `MusicBrainzReleasePriority`，提供：
- `mediaFormatScore(JsonNode release)` / `mediaFormatScore(String mediaFormat)`：保持既有分值约定（`CD=100`、`Digital=90`、其他音频=`50`、视频=`-100`）。
- `countryRank(String country, List<String> countryPriority)`：按忽略大小写匹配返回 `countryPriority` 中的 0-based 下标；配置为空或未命中时返回 `Integer.MAX_VALUE`。
- `compareReleaseMetadata(countryA, formatScoreA, dateA, countryB, formatScoreB, dateB, countryPriority)`：按严格字典序比较：
  1. `countryRank` 升序（位次越靠前越优）
  2. `formatScore` 降序（`CD` > `Digital` > 其他）
  3. 非空 `releaseDate` 升序（更早的 ISO 日期更优；非空优于空）
- `compareReleaseJson(JsonNode a, JsonNode b, List<String> countryPriority)`：先确保视频格式（`formatScore < 0`）恒劣于音频格式，再委托 `compareReleaseMetadata`。

**理由**：消除 6 处重复打分逻辑，保证全链路严格遵循 `曲目数/时长匹配 > countryPriority > 媒体格式 > 更早发行日期`。当 `countryPriority` 为空时所有版本 `countryRank` 均为 `Integer.MAX_VALUE`，自动退化为原有的 `CD > Digital` 排序。

**被否决的备选方案**：在 `scoreReleaseForDuration` 上直接给匹配地区加固定高分（如 `+1000`）。该方式处理多级地区顺序（如 `JP,TW,HK,US`）、日期决胜以及被 `DurationSequenceService` 复用时都不如显式字典序比较器清晰可靠。

### 决策 2：全部 6 条 `MusicBrainzClient` 路径接入统一排序并透传 `country`/`date`
- **`fetchAlbumDurationSequence`**：排除纯视频版本（检查全部媒介，`DVD-Audio` 视为音频，混合 `DVD+CD` 保留音频媒介），构造「前 3 个优先地区版本 + 最多追加 3 个尚未探查的基线格式版本（按 `releaseId` 去重）」探查序列；单个版本拉取失败（空时长或 `IOException`）时继续回退后续版本（仅当全部版本均无结果且发生 `IOException` 时上抛），并将 `releaseCountry` 与 `releaseDate` 写入 `AlbumDurationResult`。
- **`fetchAllReleaseDurationSequences`**：配置 `release.countryPriority` 时，按不可变配置快照同时保留「优先地区有效版本（优先覆盖不同曲目数，最多 3 个）」与「与空地区配置等价的基线有效版本（最多 5 个）」，按 `releaseId` 去重且优先地区排前；单版本拉取异常隔离不中断基线回退，既避免目标地区被超过 5 个非目标地区 CD 截断，又保证绝不挤掉原本参与跨专辑歧义/margin 判定的基线候选。
- **`getReleasesForGroup`**：按 `compareReleaseJson` 排序并填充 `releaseCountry` / `releaseDate`，供人工审核候选列表优先展示目标地区版本。
- **`matchTrackInReleaseGroup`**：排除纯视频版本后按 `(trackCountUsable ? trackCountDiff : 0) -> compareReleaseJson` 排序，并按顺序最多探查前 3 个候选版本；首选优先地区版本命中 `RECORDING_ID` 或 `TITLE_AND_DURATION` 即接受（保留日版等区域独立 recording MBID 的命中），仅在 `NOT_FOUND` / `UNAVAILABLE` 时顺延探查次选版本以避免丢失精确命中。
- **`selectBestRelease` Stage 1（`preferredReleaseGroupId`）**：不再盲取第一项，仅在匹配该 Release Group 的非视频版本中按 `(trackCountUsable ? trackCountDiff : 0) -> compareReleaseJson` 择优；若该组仅含纯视频发行版则严格排除。
- **`selectBestRelease` Stage 2（`allowAlbumGuess`）**：在初选与曲目数降级重选阶段均排除纯视频版本；仅当获胜版本具备非空 `release-group.id` 时才构造同组同曲目数 `sameGroupReleases`，并在命中优先地区的候选中用 `compareReleaseJson` 决出地区/格式/日期最优版本（无人命中优先地区时保留原初选胜者）。
- **数据类兼容**：为 `AlbumDurationResult` 和 `DurationSequenceService.AlbumDurationInfo` 增加 `releaseCountry` 与 `releaseDate` 字段，保留既有重载构造器与 setter 保证旧测试零改动编译；更新 `FolderAlbumCache`（`analyzeSamplesWithDurationSequence` 与 `tryLockOnFirstFileWithDurationSequence`）传递这两个字段。

### 决策 3：`DurationSequenceService` 同 Release Group 二阶段地区细化
为 `DurationSequenceService` 新增重载构造 `DurationSequenceService(MusicConfig config)`，保留无参构造 `DurationSequenceService()`（内部 `config = null`）以兼容全部既有测试；在 `ApplicationLifecycleManager` 中改为传入 `config`。

在 `selectBestMatch` 与 `selectBestMatchWithFolderName` 中采用二阶段选择：
1. **Stage 1（跨专辑选定与安全门槛，逻辑保持不变）**：
   - 计算 DTW `durationSimilarity`（以及 `selectBestMatchWithFolderName` 中的 `combinedScore`）。
   - 执行 `durationSimilarity >= MIN_DURATION_SIMILARITY` 硬门槛。
   - 选出最高分候选 `bestCandidate`，并执行跨 Release Group 的 `bestRivalScore` 与 `margin >= MIN_BEST_MATCH_MARGIN` 校验。
2. **Stage 2（同 Release Group 地区细化）**：
   - 仅当 Stage 1 通过、`bestCandidate.getReleaseGroupId()` 非空且 `config.getReleaseCountryPriority()` 非空时触发。
   - 收集与 `bestCandidate` 同属一个 `releaseGroupId` 的全部合格候选，计算该组内最佳 `durationSimilarity`（`groupBestDtw`），并在满足 `durationSimilarity >= groupBestDtw - SAME_GROUP_DTW_TIE_EPSILON`（`0.01`）的候选中计算最小曲目数差 `groupBestTrackDiff = min |candidate.effectiveTrackCount() - folderDurations.size()|`。
   - 在同时满足 `durationSimilarity >= groupBestDtw - 0.01` 且曲目数差等于 `groupBestTrackDiff` 的同组候选中，按以下顺序择优：
     1. `countryRank(candidate.getReleaseCountry(), countryPriority)` 升序
     2. `combinedScore` 降序（同地区内保留目录名及显式 `[SACD]`/`[CD]` 格式提示）
     3. `mediaFormatScore(candidate.getMediaFormat())` 降序（`CD > Digital > 其他`）
     4. 非空 `releaseDate` 升序
   - 用最终选定版本自身的 `durationSimilarity` 和 `combinedScore` 返回 `AlbumMatchResult`。

**理由**：
- 严格满足 `曲目数/时长匹配 > countryPriority > 媒体格式 > 更早发行日期`。
- 彻底解决「下载文件夹是罗马音（如 `Yuki Kajiura - Fiction`），`US` 版标题恰好也叫 `Fiction` 而 `JP` 版叫 `フィクション`」时，原有 `nameSimilarity` 加分导致错选 `US` 版的问题。
- 完全不改动跨专辑 `combinedScore` 与 `bestRivalScore`，避免地区配置破坏跨专辑歧义拦截门槛；配置为空时跳过 Stage 2，完全兼容现有测试（含 `[SACD]` vs `[CD]`）。

**被否决的备选方案**：在 Stage 1 直接向 `combinedScore` 注入地区加分。因为罗马音目录名匹配英文专辑名最多能拿到 `+0.40`（`0.30 + 0.10`），加分过小压不过目录名，加分过大则会污染跨专辑 `rivalScore`，导致错误的 `JP` 专辑压过正确的 `US` 专辑。

### 决策 4：共享东亚原生艺人署名解析器（`ArtistCreditNameResolver`）
在 `com.lux032.musicautotagger.service` 下新增纯工具类 `ArtistCreditNameResolver`：
- `isPureLatinName(String text)`：非空、至少包含一个 `Character.UnicodeScript.LATIN` 且满足 `Character.isLetter(cp)` 的拉丁字母（排除孤立罗马数字如 `Ⅳ`），且所有 `Character.isLetter(cp)` 字符均属于 `LATIN`（允许空格、数字、标点、连字符及 `ō`/`é` 等拉丁变音符号；若含任意汉字/假名/谚文/西里尔/希腊/阿拉伯等非拉丁字母则返回 `false`）。
- `containsEastAsianScript(String text)`：至少包含一个 `HAN`、`HIRAGANA`、`KATAKANA` 或 `HANGUL` 脚本字符。
- `resolveCreditName(JsonNode credit, boolean preferNativeName)`：
  - 提取去空白后的 `creditedName = credit.path("name")` 与 `artistName = credit.path("artist").path("name")`。
  - `creditedName` 为空时退回 `artistName`（若亦为空返回 `null`）。
  - 若 `preferNativeName && !artistName.isEmpty() && isPureLatinName(creditedName) && containsEastAsianScript(artistName)`，返回 `artistName`。
  - 否则返回 `creditedName`。
- `resolveCreditedArtistText(JsonNode credits, boolean preferNativeName)`：逐项调用 `resolveCreditName` 并按原规则拼接 `joinphrase`（保留末尾 `joinphrase`）。

集成方式：
- `AlbumArtistPolicy.creditName(JsonNode credit)` 与重载 `resolveFromCredits(credits, collapse, preferNative)` 委托给 `ArtistCreditNameResolver`；默认读取 `config == null || config.isPreferNativeArtistName()`（无配置对象时与默认值 `true` 对齐），自然覆盖 `MusicBrainzClient`、`ReleaseTagBundle`、`AlbumArtistRefreshService` 及 `FolderAlbumCache`。
- `MusicBrainzClient` 新增 `static resolveCreditedArtistText(JsonNode credits, boolean preferNativeName)`，保留单参 `static resolveCreditedArtistText(JsonNode credits)` 默认按 `true` 委托以兼容旧测试；将 `applyRecordingArtistCredit` 改为实例方法动态读取 `config.isPreferNativeArtistName()`，统一覆盖 `searchAlbums`、`parseRecordingResponse`、`applyMatchedTrackMetadata` 和 `searchRecordings`。
- `artistNames(credits)` 与 `joinArtistValues(credits, "id")` 保持提取 `artist.name` 与 `artist.id`，使 `ARTIST`、`ALBUM_ARTIST`、`ARTISTS`、`ALBUM_ARTISTS`、`ARTIST_CREDIT`、`ALBUM_ARTIST_CREDIT` 及归档目录名全部对齐。

**被否决的备选方案**：
- 开启后无条件全部改用 `artist.name`：会误覆盖合法的非拉丁角色名署名（如 `桜木花道` → `草尾毅`）或日文别名。
- 额外调用 MusicBrainz `/artist/{mbid}?inc=aliases` 查日文别名：MusicBrainz 东亚艺人主条目 `artist.name` 本身即为原生文字（仅 `YOASOBI`/`LiSA`/`Aimer` 等官方拉丁艺名为拉丁，且这类艺名本就应保留），且避免让受 1 req/s 限流的 MusicBrainz 请求翻倍。

### 决策 5：配置项、Web UI 与热更新缓存隔离
- **`MusicConfig`**：新增 `private volatile boolean preferNativeArtistName = true;`，`setDefaultValues()`、`loadFromProperties`（缺省按 `true` 加载）与 `saveToProperties` 同步读写 `tag.artist.preferNativeName`。
- **`ConfigServlet`、`config.properties.example`、Web UI 与 i18n**：在 `PROPERTY_KEY_MAP` 注册 `preferNativeArtistName -> tag.artist.preferNativeName`，补齐校验/读写/热更新，并在 `index.html` 中 `cfgCollapseAlbumArtistToVariousArtists` 旁新增复选框，同步更新 `messages_zh_CN.properties` 与 `messages_en_US.properties`。
- **时长缓存隔离与并发快照（`MusicBrainzClient`）**：`MusicConfig.releaseCountryPriority` 改为 `volatile` 不可变副本；每次时长查询入口先采集一次不可变快照 `EffectiveLookupConfig(countryPriority, preferNativeArtistName, collapseToVariousArtists)`，并用同一份快照同时生成缓存键、执行选版排序与解析整组专辑艺人，消除热更新并发竞态；`fullReleaseCache` 存的是未加工的 MusicBrainz 原始 `JsonNode`，继续按 `releaseId` 缓存即可。

## Risks / Trade-offs

- **[风险] 某 Release Group 下的 `JP` 版本缺少曲目时长数据（`durations.isEmpty()`），而 `US` 版本时长完整** → **缓解**：`fetchAlbumDurationSequence` 与 `fetchAllReleaseDurationSequences` 本身就会跳过空时长版本并继续尝试排序后的下一个版本，因此将 `JP` 排在前面时会自动安全回退到 `US` 版本。
- **[风险] `DurationSequenceService` 的 `SAME_GROUP_DTW_TIE_EPSILON = 0.01` 在极端情况下可能让多 1 首加曲的 `JP` 版进入候选** → **缓解**：Stage 2 额外要求曲目数差必须等于组内最优 `groupBestTrackDiff`，确保加曲版绝不会替代曲目数完全一致的标准版。
- **[风险] 已有部署升级后（`config.properties` 未显式写 `tag.artist.preferNativeName`）会默认对新文件输出东亚原生艺人名** → **缓解**：符合预期默认体验；如个别用户希望保留罗马音，可在配置文件或 Web 设置页将其设为 `false` 立即热更新生效。
- **[风险] 存量已归档目录中的罗马音文件夹不会自动与新归档的原生名文件夹合并** → **缓解**：本工具不改动历史存量目录，下游 `032music-server` 已完成基于 MBID 的艺人规范名与自动合并能力兜底。

## Migration Plan

1. 无需数据库迁移或历史文件格式升级。
2. 存量 `config.properties` 未配置 `tag.artist.preferNativeName` 时自动按默认值 `true` 生效。
3. 回滚零成本：运行时将 `tag.artist.preferNativeName` 设为 `false` 即可恢复原署名逻辑，清空 `release.countryPriority` 即可恢复纯媒体格式排序。
