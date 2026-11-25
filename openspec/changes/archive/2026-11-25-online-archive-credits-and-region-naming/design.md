# Design

## Context

参见 `proposal.md` 的背景说明。当前实现存在以下工程现状与约束：

- **标签与模型**：`MusicMetadata` 与 `TagWriterService` 已建立三类结构化创作者字段（`composer`/`composers`/`composerIds`、`lyricist`/`lyricists`/`lyricistIds`、`arranger`/`arrangers`/`arrangerIds`），分别写入标准 `FieldKey`、复数伴随标签（`COMPOSERS`/`LYRICISTS`/`ARRANGERS`）与 MBID 别名标签。`TagWriterService.writeCreatorNames` 目前在显示名为空时直接返回，不支持显式删除原文件已有的创作者标签。
- **联网搜索链路**：`OnlineIdentificationService` 支持 `native` 与 `tavily` 两种 `WebSearchClient`，其中 `TavilyWebSearchClient` 单条摘要上限为 1200 字符、最多 4 条检索词；输出受 `llm.webSearch.maxTokens` 限制。`MusicConfig.releaseCountryPriority` 已支持运行时通过 `ConfigServlet` 热更新，但当前仅被 `MusicBrainzClient` 消费。
- **待确认与归档链路**：`RecoveryService.confirmOnlineCandidateInternal` 是联网确认与缺封面延迟提交（`CoverMissingService` -> `commitDeferredOnlineMissingCover`）的统一落盘入口。单文件隔离项在 `RecoveryService.runJob` 中先复制到 `single-<jobId>` 工作目录再识别入队，导致 `ReviewItem.FileEntry.originalPath` 记录的是临时副本路径，而 `item.recoverySourcePath` 指向原始隔离文件路径，联网确认按 `recoverySourcePath` 遍历文件时无法命中 `queuedMetadataByOriginalPath`。

## Goals / Non-Goals

**Goals:**
- 在不增加联网搜索轮次的前提下，让单轮联网搜索按 `release.countryPriority` 对齐版本与艺人/创作者名称，并提取曲目级 `composers`/`lyricists`/`arrangers`。
- 建立统一、可复用的幕后人员多源合并解析逻辑，确保前后端展示与实际落盘一致，支持人工修改与显式清空。
- 打通缺封面拦截中转持久化与单文件隔离重识别的元数据衔接，保证跨重启与全流程不丢数据。

**Non-Goals:**
- 不新增作曲/作词/编曲之外的幕后角色（如制作人、混音师、录音师、`TIPL`/`TMCL`）。
- 不增加联网归档后的二次 MusicBrainz 文本检索或独立专项搜索轮次。
- 不强行意译曲目标题与专辑标题，以免破坏 `OnlineTrackMatcher` 对本地文件名与标签的匹配精度。
- 不为联网或人工编辑的创作者编造或反查 MusicBrainz ID。

## Decisions

### Decision 1: 幕后人员多源合并优先级与单一事实来源
- **选择**：在 `MetadataUtils`（或独立的 `TrackCreditsResolver` 工具方法）中实现逐字段独立的四级合并：`手动编辑 > MusicBrainz 指纹结果 > 本地文件已有标签 > 联网提取（仅 matchConfidence >= 0.6）`。
- **理由**：
  - 本地音频文件常来自自带准确词曲标签的正版音源（Mora/OTOTOY/Bandcamp），让本地已有标签优先于非结构化的 LLM 联网提取可最大限度防幻觉覆盖，同时在原标签缺失时由联网结果补齐。
  - 每个角色（作曲/作词/编曲）独立判定，允许“MB 提供作曲 + 本地保留作词 + 联网补全编曲”的组合。
  - 仅在最终采用 MusicBrainz 来源时保留 `composerIds`/`lyricistIds`/`arrangerIds`；采用联网或手动编辑时将对应 ID 置 `null`，防止人名变更后残留错误 MBID。
  - `ReviewServlet.toDetail()` 与 `RecoveryService` 复用同一判定语义，避免前端预览与后端落盘不一致。
- **替代方案**：让联网提取覆盖本地文件标签——在原标签混乱场景下更积极，但误改正确原标签的风险更高；用户在确认弹窗里仍可手动修改覆盖，因此保守默认更稳妥。

### Decision 2: 发行地区偏好（`release.countryPriority`）的提示词与检索词注入策略
- **选择**：每次搜索时从 `MusicConfig.getReleaseCountryPriority()` 动态读取有序地区列表，仅在非空时构造地区偏好指令注入 `OnlineIdentificationService` 的 `buildPrompt()` 与 `buildMissingTracksPrompt()`，并在 `TavilyWebSearchClient.QUERY_SYSTEM` 中增加一句遵循 `PREFERRED RELEASE REGIONS` 的指引。
- **具体本地化规则**：
  - 映射常见地区代码到书写提示（如 `JP` -> 日本語、`CN` -> 简体中文、`TW`/`HK` -> 繁體中文、`KR` -> 한국어、`US`/`GB` -> English；`XW`/`XE` 仅表示发行范围，不强制特定语种）。
  - 取列表中第一个对应具体语言/地区的条目作为主本地化目标，同时把完整优先级列表提供给模型用于版本排序。
  - 针对 `artist`、`artists`、`album_artist`、`album_artists`、`tracks[].artist`、`tracks[].artists`、`tracks[].composers`、`tracks[].lyricists`、`tracks[].arrangers`：
    1. 优先直接采用偏好地区权威页面上的官方署名；
    2. 若命中页面仅有罗马音/英文（如英文 VGMdb/Discogs 条目），允许模型转换为该地区公认名称（如 `JP` 下 `Yuki Kajiura` -> `梶浦由記`）；
    3. 保持官方拉丁艺名（如 `YOASOBI`、`LiSA`、`TeddyLoid`）与在目标地区通行拉丁署名的欧美艺人（如 `Taylor Swift`）原样不变；
    4. 对生僻且无法确定唯一汉字的人名保留来源原文，禁止猜测汉字；
    5. 强制显示名字符串与拆分数组使用相同语种和人名拼写，保证 `ArtistCreditSupport.buildCreditJson()` 顺序对齐不失效。
- **替代方案**：搜索后额外调用一次 LLM 专门翻译人名——会增加额外延迟、token 成本，且容易丢失上下文导致同音异字错误；放进单轮归纳中可结合检索原文直接对照转换。

### Decision 3: 显式清空语义与 `TagWriterService` 写入扩展
- **选择**：
  - 在 `ReviewItem.TrackCreditsOverride` 中区分三种字段状态：`null`（未人工改动，沿用自动合并）、非空列表（人工覆盖为该名单，清空旧 MBID）、空列表 `[]`（人工显式清空）。
  - 在 `MusicMetadata` 中增加 `clearComposer`、`clearLyricist`、`clearArranger` 布尔标志，与既有 `clearReleaseDate`/`clearReleaseType` 风格一致。
  - 在 `TagWriterService.updateTextTags()` 中，若 `clear*` 为 `true`，先删除 `FieldKey.*`、复数伴随标签（`COMPOSERS`/`LYRICISTS`/`ARRANGERS`）及所有 `*_MBID_KEYS` 别名。
- **理由**：`TagWriterService` 在复制原音频后做增量写标签，若仅把 `metadata.composer` 设为 `null`，文件里原本存在的 `COMPOSER` 帧不会被删除，用户将无法通过确认弹窗删掉错误的旧标签。

### Decision 4: 缺封面中转与单文件隔离重识别的持久化/路径衔接
- **缺封面中转**：在 `ReviewItem.MissingCover` 中增加 `Map<String, ReviewItem.TrackCreditsOverride> onlineTrackCredits`（以本地文件绝对路径为键，与 `OnlineTrack.matchedFilePath` 对齐）。`transitionToMissingCover` 保存它，`commitDeferredOnlineMissingCover` 读取后传入 `confirmOnlineCandidateInternal`。若 `ReviewItem` 中途落盘重启，Gson 可直接序列化/反序列化该 Map。
- **单文件隔离重识别修复**：在 `RecoveryService.confirmOnlineCandidateInternal` 构造 `queuedMetadataByOriginalPath` 时，若 `item.getRecoverySourceType() != null` 且 `originalFiles.size() == 1`、`item.getFiles().size() == 1`，当精确绝对路径未命中但文件名一致时，将唯一 `FileEntry.metadata` 关联到该隔离文件路径。
- **替代方案**：改动 `RecoveryService.runJob` 中单文件 `FileEntry.originalPath` 的写入——风险更高，因为 `cleanupStaleWorkspaces` 与 `CoverMissingService` 等多处依赖 `FileEntry.originalPath` 指向实际参与识别的路径；在消费端做受控单文件兜底影响面最小且兼容历史队列条目。

### Decision 5: 前端交互与 API 兼容性
- **API**：
  - `GET /api/review/item`：在 `files[]` 中新增 `composers`、`lyricists`、`arrangers`（列表，兼容缺失）以及 `creditsFromMusicBrainz`（布尔值，基于该条目入队元数据是否带有真实 MusicBrainz `recordingId`，即非空且非 `ONLINE_SEARCH`/`LOCAL_TAGS` 哨兵），同时可由后端直接返回或供前端精确区分 `[MusicBrainz]` 与 `[本地]`。为彻底避免歧义，前端合并展示直接采用与后端一致的四级规则；更稳妥的做法是在 `files[]` 中分别带上 `queuedCredits` 与 `localCredits` 或直接由后端给出每文件初始基础来源（`MB` / `LOCAL`）。
  - `POST /api/review/online-confirm`：新增可选字段 `trackCredits`（`Map<String, {composers?: string[], lyricists?: string[], arrangers?: string[]}>`）。未传该字段或某文件未在 map 中时，完全保持既有行为并走自动合并；若某文件字段值为 `[]` 视为显式清空，为 `null`/缺失视为未改动。
- **UI**：
  - 在 `review.html` 的 `renderOnline()` 候选卡片中，于匹配行下方仅当存在词/曲/编时渲染紧凑一行 `曲: ... · 词: ... · 编: ...`。
  - 在 `onlineDialog` 中复用 `localDialog` 现有的 `<details class="local-tracks">` 折叠模式，新增「逐曲幕后人员（作曲 / 作词 / 编曲）」折叠面板，摘要行显示有幕后人员的曲目数（如 `6/8 首`）。展开后每首显示曲名、三项输入（分号/换行分隔，复用 `ArtistCreditSupport.parseSemicolonOrLineList` 语义，避免逗号误拆带逗号人名）及来源徽章；仅对用户实际修改过的曲目字段构造 `trackCredits` 提交。

## Risks / Trade-offs

- **[Risk] Tavily 摘要截断（1200 字符）导致长专辑后半段漏抓幕后人员** → Mitigation：允许空字段省略以节省输出 token；在 `TavilyWebSearchClient` 检索词中顺带引入地区/词曲编线索，提高高信息密度页面（VGMdb/Discogs/MusicBrainz/Wikipedia）命中率；允许用户在确认弹窗里手动补录。
- **[Risk] 模型对日系罗马音人名转换时猜错同音异字汉字，或把演唱者误填为词曲作者** → Mitigation：提示词中设置双层护栏（禁止由歌手推断；冷门不确定汉字保留原文），本地已有标签优先于联网提取，且确认弹窗直观标出 `[联网]` 来源供人工一眼核对与一键清空/修改。
- **[Risk] 确认弹窗展示本地标签时若条目仅保存了 `FileEntry.metadata`，重启或入队后源文件变化** → Mitigation：联网确认入口本身已有 `evidenceHash` 校验（文件变动直接报 `online.evidence.stale` 要求重搜），同时 `ReviewServlet.toDetail()` 对 `files[]` 补充来源标识时与 `RecoveryService` 读取规则保持一致（`Path A` 入队时 `FileEntry.metadata` 已通过 `mergeMetadata` 合并过 MB 与源标签，通过 `recordingId` 是否为有效 MB UUID 可可靠区分是否存在 MB 来源，或读源文件与队列快照分别标注）。
- **[Risk] `onlineDialog` 字段增多导致窄屏或大曲目数专辑弹窗高度溢出** → Mitigation：为 `onlineDialog` 套用 `local-dialog` 同款 `max-height` 内部滚动容器，逐曲幕后人员区域默认折叠。

## Migration Plan

- 纯增量向后兼容变更，无需数据迁移：
  - 旧 `review-queue.json` 缺少 `OnlineTrack.composers/lyricists/arrangers` 或 `MissingCover.onlineTrackCredits` 时，Gson 反序列化后安全回退为空集合/`null`。
  - 未传 `trackCredits` 的旧版前端或外部调用方请求 `/api/review/online-confirm` 正常兼容。
- 回滚策略：直接回退代码即可；已写入音频的标准 `COMPOSER`/`LYRICIST`/`ARRANGER` 及 `COMPOSERS`/`LYRICISTS`/`ARRANGERS` 标签完全符合项目既有格式，不引入新专有标签键。
