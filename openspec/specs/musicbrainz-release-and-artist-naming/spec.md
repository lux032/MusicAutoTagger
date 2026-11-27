# musicbrainz-release-and-artist-naming Specification

## Purpose

规范 MusicBrainz 在全链路专辑匹配与版本选择中遵循 `release.countryPriority` 的行为，以及受 `tag.artist.preferNativeName` 控制的东亚艺术家原生展示名解析与配置热更新一致性，避免新归档音轨因版本地区或罗马音署名导致艺人拆分为多条记录。

## Requirements

### Requirement: MusicBrainz 全链路选版遵循发行地区优先级
当 `release.countryPriority` 配置了一个或多个国家/地区代码（按优先级从高到低，忽略大小写）时，所有 MusicBrainz 选版与 Release Group 候选拉取路径 MUST 按以下优先级对非视频发行版排序：音频曲目数/时长匹配 > `release.countryPriority` 位次 > 音频媒体格式（`CD` > `Digital` > 其他） > 更早的非空发行日期。视频类格式（如 DVD/Blu-ray）MUST 始终排除。当 `release.countryPriority` 为空时，MUST 保持原有媒体格式与发行日期优先逻辑。

#### Scenario: 曲目匹配等价时，优先地区的数字版胜过非优先地区的 CD 版
- **WHEN** `release.countryPriority` 设为 `JP`，同一 Release Group 包含曲目数与时长完全相同的 `US` `CD` 版和 `JP` `Digital Media` 版
- **THEN** MusicBrainz 选版优先选择 `JP` `Digital Media` 版，而非 `US` `CD` 版

#### Scenario: 多版本 Release Group 在候选拉取截断前优先排序目标地区版本
- **WHEN** `release.countryPriority` 设为 `JP`，同一 Release Group 包含超过 5 个非 `JP` 的 `CD` 版本以及 1 个 `JP` 版本
- **THEN** 拉取 Release Group 时长序列时先将 `JP` 版本排到前面再应用候选数量上限，保证 `JP` 版本进入时长匹配候选集合

#### Scenario: 曲目数匹配度高于发行地区优先级
- **WHEN** `release.countryPriority` 设为 `JP`，本地目录可靠曲目数为 12 首，已锁定的 Release Group 包含 12 首曲目的 `US` 标准版和 15 首曲目的 `JP` 豪华版
- **THEN** 选版选择 12 首曲目的 `US` 版本，因为曲目数匹配优先级高于 `release.countryPriority`

#### Scenario: 优先地区的纯视频发行版仍被排除
- **WHEN** `release.countryPriority` 设为 `JP`，同一 Release Group 包含 `JP` `DVD-Video` 版和 `US` `CD` 版
- **THEN** `JP` `DVD-Video` 版被排除，系统选择 `US` `CD` 版

#### Scenario: 同一优先地区内按媒体格式与更早发行日期决出胜者
- **WHEN** `release.countryPriority` 设为 `JP`，同一 Release Group 在曲目匹配等价的前提下包含 `2020-05-01` 的 `JP` `Digital Media`、`2020-05-10` 的 `JP` `CD` 以及 `2020-04-20` 的 `JP` `CD`
- **THEN** `2020-04-20` 发行的 `JP` `CD` 排在首位

#### Scenario: 发行地区优先级为空时保持原有格式优先逻辑
- **WHEN** `release.countryPriority` 为空，同一 Release Group 包含曲目匹配等价的 `US` `CD` 版与 `JP` `Digital Media` 版
- **THEN** `CD` 版继续排在 `Digital Media` 版之前

### Requirement: 时长序列匹配在同 Release Group 内执行地区细化
在目录时长序列匹配中，当胜出的 Release Group 已通过音频时长相似度门槛与跨专辑 margin 校验，且 `release.countryPriority` 非空时，系统 MUST 在同一 Release Group 内与最佳音频匹配等价（时长相似度在容差内且曲目数接近度相同）的合格候选中，按地区优先级位次、综合目录/格式分、音频格式及更早发行日期择优。地区优先级 MUST NOT 改变不同 Release Group 之间的排序或竞争 margin 判定。

#### Scenario: 罗马音目录名仍能在同一 Release Group 内选中优先地区版本
- **WHEN** `release.countryPriority` 设为 `JP`，本地目录名为 `Yuki Kajiura - Fiction`（12 首），同一 Release Group 中 `US` 版（标题 `Fiction`）与 `JP` 版（标题 `フィクション`）时长相似度完全一致
- **THEN** 时长序列匹配最终选中该 Release Group 下的 `JP` 版本

#### Scenario: 地区优先级不得让不同专辑的劣质匹配压过更匹配的专辑
- **WHEN** `release.countryPriority` 设为 `JP`，Release Group A（`US`）的时长序列与目录高度吻合，而 Release Group B（`JP`）吻合度显著较低
- **THEN** 时长序列匹配选择 Release Group A，且 Release Group B 的 `JP` 地区不会干扰跨专辑比较或 margin 校验

#### Scenario: 同一 Release Group 内时长明显不匹配的版本不得凭地区反超
- **WHEN** `release.countryPriority` 设为 `JP`，胜出的 Release Group 包含时长精确匹配的 `US` 普通版与时长相似度明显更低的 `JP` 加曲版
- **THEN** 时长序列匹配保留 `US` 普通版

### Requirement: 录音反查在锁定 Release Group 时遵循地区优先级
在仅锁定 Release Group ID（未锁定具体 Release ID）的录音反查中，系统 MUST 检查该 Release Group 下全部匹配的非视频版本，并按曲目数接近度（在文件数可靠时）、`release.countryPriority`、音频格式与更早发行日期择优，而不是盲目返回响应列表中的第一项。若已提供锁定的 Release ID 且存在于录音版本列表中，该精确 Release ID MUST 保持最高优先级。

#### Scenario: 锁定 Release Group 时优先选中目标地区版本
- **WHEN** `release.countryPriority` 设为 `JP`，某录音在已锁定的 Release Group 中同时归属排在前面的 `US` 版和排在后面的 `JP` 版且曲目数相同
- **THEN** 录音反查选中 `JP` 版本

#### Scenario: 精确锁定的 Release ID 优先级高于地区配置
- **WHEN** 已锁定某 `US` 版本的 Release ID 且该版本存在于录音版本列表中，同时 `release.countryPriority` 设为 `JP`
- **THEN** 录音反查仍选择精确锁定的 `US` Release ID

### Requirement: 可配置的东亚艺术家原生展示名解析
系统 SHALL 通过配置文件、配置 HTTP API 及 Web 设置页提供支持热更新的布尔配置项 `tag.artist.preferNativeName`（默认 `true`）。开启时，解析 MusicBrainz 艺人及专辑艺人署名若 `credit.name` 为纯拉丁字符且对应 `artist.name` 含汉字、假名或谚文，MUST 改用该原生 `artist.name`，同时保留连接词、结构化艺人名单与 MBID 对齐；其余情形及设为 `false` 时 MUST 保留原署名。

#### Scenario: 东亚艺人的纯罗马音署名自动还原为原生文字
- **WHEN** `tag.artist.preferNativeName` 为 `true`，MusicBrainz 署名为 `credit.name = "Yuki Kajiura"` 且 `credit.artist.name = "梶浦由記"`
- **THEN** 解析得到的 `ARTIST`、`ALBUM_ARTIST` 及派生归档目录艺人名均为 `梶浦由記`，与 `ARTISTS` / `ALBUM_ARTISTS` 及原艺人 MBID 完全对齐

#### Scenario: 多人合作曲目保留连接词与 MBID 顺序对齐
- **WHEN** `tag.artist.preferNativeName` 为 `true`，MusicBrainz 署名数组为 `("Yuki Kajiura" -> "梶浦由記", joinphrase=" feat. ")` 与 `("Aimer" -> "Aimer", joinphrase="")`
- **THEN** 展示署名为 `梶浦由記 feat. Aimer`，结构化艺人名单为 `["梶浦由記", "Aimer"]`，且艺人 MBID 按原顺序对齐

#### Scenario: 官方拉丁艺名与欧美艺人保持不变
- **WHEN** `tag.artist.preferNativeName` 为 `true`，MusicBrainz 署名为 `("YOASOBI" -> "YOASOBI")` 或 `("Taylor Swift" -> "Taylor Swift")`
- **THEN** 解析后的展示名保持为 `YOASOBI` 与 `Taylor Swift`

#### Scenario: 非拉丁角色名署名不被覆盖
- **WHEN** `tag.artist.preferNativeName` 为 `true`，MusicBrainz 署名为 `credit.name = "桜木花道"` 且 `credit.artist.name = "草尾毅"`
- **THEN** 解析后的展示名保持为 `桜木花道`，因为 `credit.name` 并非纯拉丁字符

#### Scenario: 关闭开关后恢复原始署名
- **WHEN** `tag.artist.preferNativeName` 设为 `false`，MusicBrainz 署名为 `credit.name = "Yuki Kajiura"` 且 `credit.artist.name = "梶浦由記"`
- **THEN** 解析后的展示名保持为 `Yuki Kajiura`

### Requirement: 运行时配置热更新不得命中过期选版或署名缓存
当运行期间通过配置接口或设置页修改 `release.countryPriority`、`tag.artist.preferNativeName` 或 `tag.albumArtist.collapseToVariousArtists` 后，后续的 MusicBrainz Release Group 时长查询与元数据解析 MUST 立即按最新配置生效，不得继续返回旧配置下缓存的版本顺序或专辑艺人显示文本。

#### Scenario: 运行时修改发行地区优先级后立即生效
- **WHEN** 某 Release Group 在 `release.countryPriority` 为空时已被缓存（此前选中 `US` `CD`），随后运行时将 `release.countryPriority` 更新为 `JP`
- **THEN** 再次查询该 Release Group 时返回 `JP` 版本，而非旧缓存中的 `US` 版本

#### Scenario: 运行时修改原生艺人名开关后立即更新专辑艺人
- **WHEN** 某 Release Group 在 `tag.artist.preferNativeName` 为 `false` 时已被缓存（此前解析为 `Yuki Kajiura`），随后运行时将该开关改为 `true`
- **THEN** 再次查询该 Release Group 时解析为 `梶浦由記`，而非旧缓存中的罗马音结果
