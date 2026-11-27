# Tasks

## 1. 配置项、Web 设置页与国际化（`tag.artist.preferNativeName`）

- [x] 1.1 在 `MusicConfig` 中新增 `preferNativeArtistName`（对应 `tag.artist.preferNativeName`，默认 `true`，涵盖字段初始化、`setDefaultValues`、`loadFromProperties`、`saveToProperties`）并在 `config.properties.example` 中补充说明；新增单元测试覆盖默认 `true`、显式 `false` 加载与保存往返，执行 `mvn test -Dtest=MusicConfigPreferNativeArtistNameTest` 验证通过。
- [x] 1.2 将 `preferNativeArtistName` 接入 `ConfigServlet`（`PROPERTY_KEY_MAP`、参数校验、GET 返回与热更新生效）、`src/main/resources/static/index.html` 以及 `messages_zh_CN.properties` / `messages_en_US.properties`；验证 API 读写与中英文 i18n 键一致性（`mvn test -Dtest=P1Task3FrontendI18nOpsRegressionTest,MusicConfigPreferNativeArtistNameTest`）。

## 2. 东亚艺术家原生展示名解析

- [x] 2.1 实现 `ArtistCreditNameResolver`（`isPureLatinName`、`containsEastAsianScript`、`resolveCreditName`、`resolveCreditedArtistText`）并编写单元测试，覆盖罗马音转汉字/假名/谚文、带拉丁变音符号拼写、多人合作连接词、官方拉丁艺名（`YOASOBI`/`LiSA`/`Aimer`）、欧美艺人、非拉丁角色名（`桜木花道`）及 `preferNativeName=false` 场景；执行 `mvn test -Dtest=ArtistCreditNameResolverTest` 验证通过。
- [x] 2.2 将 `ArtistCreditNameResolver` 集成至 `AlbumArtistPolicy` 与 `MusicBrainzClient`（`resolveCreditedArtistText`、`applyRecordingArtistCredit`、`parseReleaseTagBundle`、`searchAlbums`、`parseRecordingResponse`、`applyMatchedTrackMetadata`、`searchRecordings`），保持 `ARTIST`、`ALBUM_ARTIST`、`ARTISTS`、`ALBUM_ARTISTS`、`encodeArtistCredit` 及 `AlbumArtistRefreshService` 一致；补充 `AlbumArtistPolicyTest` 与解析测试，执行 `mvn test -Dtest=AlbumArtistPolicyTest,AlbumArtistRefreshServiceTest,MusicBrainzClientCreatorCreditsTest` 验证通过。

## 3. MusicBrainz 全链路发行地区优先级

- [x] 3.1 实现 `MusicBrainzReleasePriority`（`mediaFormatScore`、`countryRank`、`compareReleaseMetadata`、`compareReleaseJson`），落实 `countryPriority > 媒体格式（CD > Digital > 其他） > 更早非空发行日期`、视频格式排除以及空配置向后兼容；执行 `mvn test -Dtest=MusicBrainzReleasePriorityTest` 验证通过。
- [x] 3.2 更新 `MusicBrainzClient` 全部 6 条选版路径（`fetchAlbumDurationSequence`、`fetchAllReleaseDurationSequences` 在 `tryCount`/`successCount` 截断前排序、`getReleasesForGroup`、`matchTrackInReleaseGroup`、`selectBestRelease` Stage 1 与 Stage 2），为 `AlbumDurationResult` 增加向后兼容的 `releaseCountry` / `releaseDate` 字段，并按有效配置（`releaseCountryPriority`、`preferNativeArtistName`、`collapseAlbumArtistToVariousArtists`）隔离 `albumDurationCache` 与 `allReleaseDurationCache` 缓存键；执行 `mvn test -Dtest=MusicBrainzClientCountryPriorityTest,MusicBrainzClientDurationParsingTest` 验证通过。

## 4. 时长序列匹配同组地区细化

- [x] 4.1 为 `DurationSequenceService.AlbumDurationInfo` 增加 `releaseCountry` / `releaseDate` 字段（保留原有构造方法），为 `DurationSequenceService` 新增 `DurationSequenceService(MusicConfig config)` 重载并保留无参构造，在 `ApplicationLifecycleManager` 中传入 `config`，并更新 `FolderAlbumCache`（`analyzeSamplesWithDurationSequence` 与 `tryLockOnFirstFileWithDurationSequence`）透传地区与日期；执行 `mvn test -Dtest=FolderAlbumCacheMissingTracksTest,MissingTracksArchiveChainTest` 验证通过。
- [x] 4.2 在 `DurationSequenceService.selectBestMatch` 与 `selectBestMatchWithFolderName` 中实现 Stage 2 同 Release Group 地区细化（保留跨专辑 DTW 硬门槛、`bestRivalScore` 与 `MIN_BEST_MATCH_MARGIN`，并约束 `SAME_GROUP_DTW_TIE_EPSILON = 0.01` 与最优曲目数接近度）；新增单测覆盖罗马音目录名选中 `JP` 版、曲目数/时长不匹配不反超、跨专辑零干扰以及空配置向后兼容，执行 `mvn test -Dtest=DurationSequenceServiceCountryPriorityTest,P1Task1CorePipelineRegressionTest` 验证通过。

## 5. 全量集成回归验证

- [x] 5.1 在 `MusicAutoTagger` 运行全量测试套件（`mvn test`），确认核心流水线、审核候选展开、标签写入、配置接口/前端 i18n 与专辑艺人刷新等所有测试全部通过、无回归。

## Workflow follow-up

- 编码完成后提交并推送变更，供实机验证。
- 实机验证确认无误后同步主规格并归档 `musicbrainz-country-priority-and-native-artist-naming`。
