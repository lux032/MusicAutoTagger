# Proposal

## Why

在变更 1（`online-artist-creator-mbids`）打通联网识别的人物级 Artist MBID 解析后，当一张新专辑或特定地区/加曲发行版尚未被 MusicBrainz 收录时，本地音频仍只能停留在「无 Release / Release Group / Recording MBID」状态，且系统已经辛苦核验出的权威来源、曲目表、精准毫秒时长、高清封面与词曲编信息无法回馈给 MusicBrainz / AcoustID 社区。现在底座已就绪，需要补齐合规、低摩擦、人工把关的 MusicBrainz 回馈闭环，将系统从单向刮削工具升级为可自我造血的生态飞轮。

## What Changes

- **MusicBrainz 存在性预检与四类分流**：在联网候选确认归档后（或人工触发时），按条码（`barcode`）、目录号（`catalogNumber`）与「专辑名 + 艺人（含已解析 Artist MBID）」结合曲目数/时长序列向 MusicBrainz 执行只读预检，将结果清晰分为四类：
  - **Case A（MB 已有精确 Release）**：拦截重复上传，直接提供「关联已有 MB Release 并回填本地全套官方 MBID」入口；
  - **Case B（已有 Release Group 但缺当前发行版）**：生成待回馈条目并自动绑定既有 `release_group` MBID；
  - **Case C（全新专辑）**：生成待回馈条目用于新建 Release Group + Release；
  - **Case D（非官方/残缺本地目录）**：不自动创建待回馈条目，避免向公共库提交脏数据。
- **独立待回馈队列与 Web 工作台**：新增原子持久化存储 `mb-contributions.json`（与 `review-queue.json` 同目录，兼容 SQLite/MySQL/文件模式）及独立页面 `contributions.html`（接入侧栏导航与积压计数）和 `/api/mb-contributions/*` API；联网归档完成后非阻塞入队，支持重新预检、字段微调、忽略与完成。
- **MusicBrainz Release Editor Seeding（`/release/add` 预填）**：前端通过标准 `POST multipart/form-data`（新窗口提交至 `https://musicbrainz.org/release/add`）预填完整发行信息、已有 `release_group`、碟号/曲序/曲名/精准毫秒时长、已命中 Recording MBID、已绑定的 Artist MBID 与 `join_phrase`、权威来源 `urls.x.url` 及自动带一手来源 URL 的 `edit_note`；始终由用户在浏览器登录态人工核对并点击 Enter Edit，严禁后台静默提交或提交缺轨残缺目录。
- **Cover Art Archive（CAA）原图保留与双模式上传引导**：在联网归档选图与缺封面补图环节额外保留高画质封面来源（公网原图 URL 如 Apple Music/iTunes 升格 `3000x3000` 或官方原图，以及本地原图未压缩备份）；绑定 Release MBID 后提供直达 `/release/<mbid>/add-cover-art` 的双模式引导（通用原图下载 + 来源 Comment 复制，以及公网原图 URL 自动附带社区标准 `x_seed` 参数兼容 ECAU 用户脚本）。
- **合规 Works / 词曲编补录参考卡**：绑定 Release MBID 后提供直达 `/release/<mbid>/edit-relationships` 的结构化幕后人员参考卡，**在不修改本地音频标签原文的前提下**，按 MusicBrainz 社区规范自动剥离创作者末尾括号中的所属厂牌/社团后缀（如 `tepe (OTOIRO)` → 实体 `tepe`、`雨良 Amala (ninelive.)` → 实体 `雨良` + 可选人名变体 `雨良 Amala`），并清晰标注 `verified`/`probable` 与可追溯出处链接。
- **贴回 Release 链接自动回填本地全套 MBID + 可选 AcoustID 声纹提交**：用户输入新建（或 Case A 命中）的 MusicBrainz Release URL / MBID 后，后端拉取权威 Release JSON，按曲序与时长校验安全匹配本地已归档音频并原位补写官方 `MUSICBRAINZ_ALBUMID`、`MUSICBRAINZ_RELEASEGROUPID`、`MUSICBRAINZ_TRACKID`、`MUSICBRAINZ_RELEASETRACKID`、`MUSICBRAINZ_ARTISTID`、`MUSICBRAINZ_RELEASEARTISTID` 及发行元数据（保留既有词曲编与监控源血缘记录不分裂），并将艺人沉淀至 `MusicBrainzArtistCache`；若配置了 `acoustid.userApiKey`（热更新配置），支持一键计算并向 `https://api.acoustid.org/v2/submit` 提交 Chromaprint 声纹与 Recording MBID。
- **热更新配置扩展**：新增 `mb.contribution.enabled`（默认 `true`）、`mb.contribution.autoCheckAfterArchive`（默认 `true`）与 `acoustid.userApiKey`（敏感掩码、支持热更新），接入 `MusicConfig`、`ConfigFieldSpec`、`ConfigServlet`、设置页与示例配置。

## Capabilities

### New Capabilities
- `musicbrainz-contribution`: 规范 MusicBrainz 缺失专辑/版本的存在性预检分流、待回馈队列生命周期、合规 Release Editor Seeding 预填、CAA 高画质封面上传引导、去所属括号的 Works 幕后人员补录参考、贴回 Release MBID 回填本地标签与处理记录，以及可选 AcoustID 声纹提交的完整可观测行为。

### Modified Capabilities
<!-- 无既有规范需求变更：本地联网识别与归档仍严格遵循 online-identification 与 online-archive-credits；官方 Release/Recording MBID 仅在用户显式关联真实 MusicBrainz Release 后由本新增能力拉取权威实体回填。 -->

## Impact

- **配置与存储**：[MusicConfig.java](file:///home/ubuntu/ds-workspace/MusicAutoTagger/src/main/java/com/lux032/musicautotagger/config/MusicConfig.java)、[ConfigFieldSpec.java](file:///home/ubuntu/ds-workspace/MusicAutoTagger/src/main/java/com/lux032/musicautotagger/config/ConfigFieldSpec.java)、[ConfigServlet.java](file:///home/ubuntu/ds-workspace/MusicAutoTagger/src/main/java/com/lux032/musicautotagger/web/ConfigServlet.java)、`config.properties.example*`；数据目录新增 `mb-contributions.json` 与 `mb-contribution-covers/`（无需改动 MySQL/SQLite 表结构）。
- **后端核心服务**：扩展 [MusicBrainzClient.java](file:///home/ubuntu/ds-workspace/MusicAutoTagger/src/main/java/com/lux032/musicautotagger/service/MusicBrainzClient.java)（条码/目录号/艺人+标题 Release 与 Release Group 检索、完整 Release 含曲目/时长解析）、[AudioFingerprintService.java](file:///home/ubuntu/ds-workspace/MusicAutoTagger/src/main/java/com/lux032/musicautotagger/service/AudioFingerprintService.java)（AcoustID `/v2/submit` 批量提交）、[CoverCandidateService.java](file:///home/ubuntu/ds-workspace/MusicAutoTagger/src/main/java/com/lux032/musicautotagger/service/CoverCandidateService.java)（高画质原图 URL / 未压缩字节保留接口）、[RecoveryService.java](file:///home/ubuntu/ds-workspace/MusicAutoTagger/src/main/java/com/lux032/musicautotagger/service/RecoveryService.java)（联网归档完成与缺封面补图完成后的非阻塞入队钩子）及 [ApplicationLifecycleManager.java](file:///home/ubuntu/ds-workspace/MusicAutoTagger/src/main/java/com/lux032/musicautotagger/core/ApplicationLifecycleManager.java)；新增贡献模型、存在性预检、队列服务、Seeding/CAA/Works 生成器、本地标签回填服务与 `MbContributionServlet`。
- **Web 路由与前端 UI**：[WebServer.java](file:///home/ubuntu/ds-workspace/MusicAutoTagger/src/main/java/com/lux032/musicautotagger/web/WebServer.java) 注册可热装配的 `/api/mb-contributions/*` 路由；新增 `src/main/resources/static/contributions.html`，并在 `index.html`、`review.html`、`covers.html`、`recovery.html`、`records.html`、`shell.js`、`errors.js` 与中英文 i18n 资源中接入侧栏入口、计数徽章与配置项。
- **非目标（明确排除）**：
  - 不在后端存储用户 MusicBrainz 账号密码或使用无头自动化脚本绕过人工审核向 MusicBrainz 静默建库；
  - 不通过非官方接口直接向 Cover Art Archive / Internet Archive 静默上传图片二进制；
  - 不修改变更 1 已确定的本地音频标签原文保留策略（去所属括号仅作用于 MB 社区提交参考卡，不改写本地 FLAC/ID3/MP4 创作者姓名）。
