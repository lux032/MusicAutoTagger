# Proposal

## Why

即使用户已经配置了 `release.countryPriority=JP`，当前 MusicBrainz 标签匹配链路仍频繁选中非日版发行（如 `US` / `XW` 版本），并将罗马音署名（如 `Yuki Kajiura` 而非 `梶浦由記`）写入 `ARTIST`、`ALBUM_ARTIST` 及归档目录路径。这会导致下游音乐服务（如 `032music-server`）和本地曲库目录在新入库专辑时继续出现同一位东亚艺人被拆分成「罗马音」和「原生文字」两条记录的问题。

已在当前 MusicBrainz 代码链路中确认的两处根因：
1. `release.countryPriority` 目前仅在 `MusicBrainzClient.selectBestRelease` Stage 2（无锁定单文件兜底猜测）中生效，而主流程的选版与候选拉取路径（`fetchAlbumDurationSequence`、`fetchAllReleaseDurationSequences`、`getReleasesForGroup`、`matchTrackInReleaseGroup`、`selectBestRelease` Stage 1 以及 `DurationSequenceService` 同组多版本选择）完全忽略地区优先级。
2. 艺术家显示名解析（`MusicBrainzClient.resolveCreditedArtistText` 与 `AlbumArtistPolicy.creditName`）始终优先采用 `credit.name`，即便 `credit.name` 只是罗马音拼写而对应的 `credit.artist.name` 是东亚原生文字，导致 `ARTIST` / `ALBUM_ARTIST` 与已经使用 `artist.name` 的 `ARTISTS` / `ALBUM_ARTISTS` 不一致。

## What Changes

- **MusicBrainz 全链路 `release.countryPriority` 优先（策略 2A）**：
  - 建立统一的 MusicBrainz 发行版本排序策略，严格按 **曲目数/时长序列匹配 > `release.countryPriority` > 音频媒体格式（`CD` > `Digital` > 其他） > 更早发行日期** 排序，继续排除视频格式；当 `release.countryPriority` 为空时保持现有格式优先逻辑不变。
  - 在全部 6 条 MusicBrainz 选版与候选拉取路径统一启用该排序规则：`fetchAlbumDurationSequence`、`fetchAllReleaseDurationSequences`（在 5 个候选截断之前排序）、`getReleasesForGroup`、`matchTrackInReleaseGroup`、`selectBestRelease` Stage 1 与 Stage 2。
  - 将发行地区（`country`）与发行日期（`date`）透传至 `AlbumDurationResult` 和 `DurationSequenceService.AlbumDurationInfo`，使 `DurationSequenceService` 在选定同一 Release Group 且时长/曲目数匹配等价时优先选择符合 `release.countryPriority` 的版本，同时不影响跨专辑的 DTW 门槛与竞争 margin 判定。
- **可配置的东亚艺术家原生展示名归一（策略 A）**：
  - 新增配置项 `tag.artist.preferNativeName`（默认 `true`，支持配置文件、`ConfigServlet` API 及 Web 设置页热更新，并在 `config.properties.example` 中说明）。
  - 开启时，解析 MusicBrainz 艺人及专辑艺人署名（`artist-credit`）时，仅当 `credit.name` 为纯拉丁字符而关联的 `credit.artist.name` 包含东亚原生文字（汉字 / 平假名 / 片假名 / 谚文）时，将展示名替换为 `credit.artist.name`；同时保留连接词（如 `feat.`、`&`）、官方拉丁艺名（如 `YOASOBI`、`LiSA`、`Aimer`）、欧美拉丁艺人、非拉丁角色名署名（如 `桜木花道`）以及精确的 MBID 顺序对齐。
  - 确保 `MusicBrainzClient`、`AlbumArtistPolicy`、`ReleaseTagBundle`、`AlbumArtistRefreshService` 与 `FolderAlbumCache` 共用同一套解析逻辑，并在运行时修改 `release.countryPriority` 或 `tag.artist.preferNativeName` 时避免命中旧配置下的时长序列缓存。
- **明确不纳入本次范围**：
  - 不新增针对已归档存量曲库的批量重贴标签或目录重命名工具；本次变更作用于后续新处理的文件以及已有的 `AlbumArtistRefreshService` 刷新流程。

## Capabilities

### New Capabilities
- `musicbrainz-release-and-artist-naming`: 规范 MusicBrainz 在时长序列匹配、Release Group 选版、录音反查等全链路下遵循 `release.countryPriority` 的版本选择行为，以及受 `tag.artist.preferNativeName` 控制的东亚艺人原生展示名解析与配置热更新缓存隔离行为。

### Modified Capabilities
<!-- 无：现有 `online-identification` 与 `online-archive-credits` 规格用于联网/LLM 兜底链路，不在本次修改范围内。 -->

## Impact

- **核心 MusicBrainz 与匹配服务**：
  - `src/main/java/com/lux032/musicautotagger/service/MusicBrainzClient.java`
  - `src/main/java/com/lux032/musicautotagger/service/AlbumArtistPolicy.java`
  - `src/main/java/com/lux032/musicautotagger/service/DurationSequenceService.java`
  - `src/main/java/com/lux032/musicautotagger/service/FolderAlbumCache.java`
  - `src/main/java/com/lux032/musicautotagger/core/ApplicationLifecycleManager.java`
  - 在 `com.lux032.musicautotagger.service` 下新增共享工具类（发行版本优先级排序器与原生艺人署名解析器）。
- **配置、Web 接口与前端界面**：
  - `src/main/java/com/lux032/musicautotagger/config/MusicConfig.java`
  - `src/main/java/com/lux032/musicautotagger/web/ConfigServlet.java`
  - `config.properties.example`
  - `src/main/resources/static/index.html`
  - `src/main/resources/messages_zh_CN.properties` 与 `src/main/resources/messages_en_US.properties`
- **测试覆盖**：
  - 新增及扩展 JUnit 单元测试，覆盖 MusicBrainz 全路径地区选版、时长匹配同组地区细化、东亚艺人原生名解析边界、配置读写/热更新与缓存隔离。
