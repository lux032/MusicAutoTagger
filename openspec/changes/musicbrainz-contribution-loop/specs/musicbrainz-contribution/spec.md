# Spec Delta: musicbrainz-contribution

## Purpose

规范 MusicBrainz 缺失专辑与版本的存在性预检分流、待回馈队列管理、合规 Release Editor Seeding 预填、Cover Art Archive 高画质封面上传引导、去所属括号的 Works 幕后人员补录参考、关联真实 Release 回填本地全套官方 MBID 及可选 AcoustID 声纹提交的可观测行为。

## ADDED Requirements

### Requirement: MusicBrainz 存在性预检与四类分流
系统 SHALL 支持对联网识别确认归档的专辑执行只读 MusicBrainz 存在性预检，按条码、目录号与「专辑名 + 艺人（优先使用已解析 Artist MBID）」结合曲目数和时长序列将目标专辑划分为四类结论：`EXISTING_RELEASE`（Case A）、`MISSING_EDITION`（Case B）、`MISSING_RELEASE_GROUP`（Case C）或 `NOT_ELIGIBLE`（Case D）。

#### Scenario: 条码或目录号精确命中已有 Release 时归为 Case A 并拦截重复上传
- **WHEN** 预检携带合法的 `barcode` 或 `catalogNumber` 向 MusicBrainz 检索到已有官方 Release，且该 Release 的曲目数与本地/候选曲目数一致、已匹配曲目时长差均在容差范围内
- **THEN** 系统将预检结论判定为 `EXISTING_RELEASE`（Case A），记录命中的 Release MBID、Release Group MBID 与外部链接
- **AND** 系统禁用「新建 Release（Seeding）」主操作，转而提示用户直接关联该已有 Release 并回填本地全套官方 MBID

#### Scenario: 标题与艺人命中已有 Release Group 且缺当前版本时归为 Case B
- **WHEN** 预检在 MusicBrainz 中检索到同名同艺人的已有 Release Group，但该 Release Group 下不存在与当前曲目数、时长序列、地区或版本信息精确匹配的 Release
- **THEN** 系统将预检结论判定为 `MISSING_EDITION`（Case B），记录候选既有 Release Group MBID 与摘要信息
- **AND** 允许用户以挂入该既有 Release Group 的方式发起 Release Editor Seeding，并允许在确认不对时切换为新建 Release Group

#### Scenario: MusicBrainz 未检索到对应专辑或版本时归为 Case C
- **WHEN** 按条码、目录号与「专辑名 + 艺人」依次向 MusicBrainz 检索均未发现匹配的 Release 或 Release Group
- **THEN** 系统将预检结论判定为 `MISSING_RELEASE_GROUP`（Case C），生成待回馈条目用于新建 Release Group 与 Release

#### Scenario: 非官方候选或本地覆盖不完整时归为 Case D 不自动建议上传
- **WHEN** 联网候选未标记为官方发行（`officialCandidate=false`）、无可靠一手来源 URL，或本地已归档曲目未完整覆盖候选曲目表且无法确认官方发行信息
- **THEN** 系统将预检结论判定为 `NOT_ELIGIBLE`（Case D），不自动在待回馈队列中创建待上传卡片

### Requirement: 待回馈 MusicBrainz 队列持久化与非阻塞生命周期管理
系统 SHALL 将待回馈条目原子持久化到数据目录并支持跨重启保留；联网归档成功后 MUST 以非阻塞方式触发预检与入队，且待回馈工作台 SHALL 支持列表筛选、状态统计、重新预检、字段微调、手动发起与忽略关闭。

#### Scenario: 联网确认归档完成后异步预检并持久化待回馈卡片
- **WHEN** `mb.contribution.enabled` 与 `mb.contribution.autoCheckAfterArchive` 均为 `true`，用户完成一次联网候选确认归档
- **THEN** 归档请求立即返回成功而不等待外部存在性预检完成
- **AND** 后台任务执行 MusicBrainz 存在性预检，在命中 Case A、Case B 或 Case C 时生成去重后的待回馈条目并原子落盘，服务重启后状态完整保留

#### Scenario: 缺封面拦截在补图或确认无封面完成后同样触发预检
- **WHEN** 联网确认因缺少封面转入「缺少封面」队列，随后用户在「缺少封面」页面补全封面或确认无封面完成最终落盘
- **THEN** 系统在最终落盘成功后同样非阻塞触发 MusicBrainz 存在性预检与入队（携带最终选定的高清封面线索）

#### Scenario: 待回馈工作台支持重新预检、忽略与手动发起
- **WHEN** 用户在待回馈工作台对条目点击「重新预检」或「忽略」，或在已确认的联网归档记录上手动发起「检查并回馈 MusicBrainz」
- **THEN** 「重新预检」立即按最新 MusicBrainz 数据刷新 Case 判定与已有 RG/Release 候选；「忽略」将条目置为 `DISMISSED` 并释放其暂存原图备份；手动发起可在自动入队关闭或此前被跳过时按需创建或刷新待回馈条目

### Requirement: 合规构造 MusicBrainz Release Editor Seeding 预填表单
当用户对 Case B 或 Case C 条目发起 Release 提交时，系统 SHALL 构造符合 MusicBrainz 官方 Release Editor Seeding 规范（`POST https://musicbrainz.org/release/add`，`multipart/form-data`）的表单字段载荷，并始终交由用户在浏览器登录态人工审核提交，绝不存储用户 MusicBrainz 凭据或后台静默建库。

#### Scenario: Case B 与 Case C 生成标准 Seeding 字段与一手来源 Edit Note
- **WHEN** 用户请求某个待回馈条目的 Release Seeding 载荷并在浏览器中打开 MusicBrainz Release Editor
- **THEN** 载荷包含合法的 `name`、`status`（默认 `official`）、`type`（如 `album`/`single`/`ep`）、发行事件（`events.0.date.year/month/day`、`events.0.country`）、厂牌与目录号（`labels.0.name`、`labels.0.catalog_number`）、条码（`barcode`）、媒介格式（`mediums.x.format`）、完整曲目（`mediums.x.track.y.name/number/length` 与已有 `recording`）、外部权威链接（`urls.z.url`）及自动生成且列出一手可核验来源 URL 与人工核对声明的 `edit_note`
- **AND** 当条目为 Case B 且用户保留关联既有 Release Group 时，载荷携带 `release_group=<rgMbid>`；当用户选择新建或为 Case C 时省略 `release_group`

#### Scenario: 多人艺人署名按已核验 MBID 与连接符正确展开
- **WHEN** 专辑艺人或曲目艺人包含单人或多位合作艺人，且部分或全部成员已绑定经核验的真实 MusicBrainz Artist MBID
- **THEN** Seeding 载荷按顺位生成 `artist_credit.names.i.mbid`（仅对已绑定的合法 UUID）、`artist_credit.names.i.name`（发行署名原文）、未绑定 MBID 时的 `artist_credit.names.i.artist.name` 搜索预填以及 `artist_credit.names.i.join_phrase`
- **AND** 绝不输出任何虚构或非法格式的 UUID

#### Scenario: 本地缺轨时不把残缺曲目表当完整专辑预填
- **WHEN** 联网核验出的官方完整曲目表共有 10 首，但本地已归档目录仅包含其中 6 首音频文件
- **THEN** Seeding 载荷按 10 首完整官方曲目表生成所有 `mediums.x.track.y` 条目，仅对高置信匹配到本地文件的 6 首使用本地精准毫秒时长（`length`）补强，其余 4 首沿用官方来源时长或留空

### Requirement: Cover Art Archive 高画质原图保留与双模式上传引导
系统 SHALL 在联网归档选图与缺封面补图时为待回馈条目保留最高画质封面来源（公网原图 URL 或未压缩本地原图备份），并在绑定 Release MBID 后提供面向 `https://musicbrainz.org/release/<mbid>/add-cover-art` 的通用拖拽与 `x_seed` 双模式上传引导。

#### Scenario: 公网原图封面保留高清 URL 并生成兼容 x_seed 的上传链接
- **WHEN** 选定的封面来自公网来源（如 Apple Music/iTunes 封面可升级至高分辨率原图 URL 或权威站点原图 URL）且条目已绑定合法 Release MBID
- **THEN** 系统提供直达 `https://musicbrainz.org/release/<mbid>/add-cover-art` 且附带 `x_seed.origin`、`x_seed.image.0.url`、`x_seed.image.0.types=front` 与 `x_seed.image.0.comment` 参数的链接（兼容 ECAU 等社区脚本），同时提供建议 Comment 文本一键复制与原图下载

#### Scenario: 本地或上传封面保留未压缩原图备份并在终态自动清理
- **WHEN** 选定的封面来自本地目录图片、内嵌封面或用户本地上传（无公网原图 URL）
- **THEN** 系统在归档压缩 `1200px` 嵌图的同时额外保留一份未压缩原图备份供用户在待回馈工作台安全下载上传至 CAA
- **AND** 当条目被标记为 `COMPLETED`、`DISMISSED` 或清理过期数据时，系统自动删除该条目的本地原图备份文件且校验路径不得越界逃逸

### Requirement: 合规 Works 与曲目级幕后人员关系补录参考卡
鉴于 MusicBrainz Release Editor Seeding 无法单表单创建 Work 级词曲编关系，系统 SHALL 在绑定 Release MBID 后提供直达 `/release/<mbid>/edit-relationships` 的结构化幕后人员补录参考卡，并按 MusicBrainz 社区规范清洗创作者末尾的所属厂牌或社团括号。

#### Scenario: 剥离所属括号生成合规关系指引且不修改本地音频标签
- **WHEN** 某首曲目的已核验创作者原文为带末尾所属厂牌/社团括号的写法（例如 `tepe (OTOIRO)` 或 `雨良 Amala (ninelive.)`）
- **THEN** 参考卡展示对应的 MusicBrainz Artist 实体链接、规范主名、剥离末尾所属括号后的干净人名变体建议（例如 `tepe` 主名留空 `credited as`，`雨良 Amala` 作为可选 `credited as`），并将原所属机构提示放至 Edit Note 参考区
- **AND** 本地已归档音频文件中的创作者标签仍保持变更 1 确定的原文写法，不被该参考卡清洗规则覆盖

#### Scenario: 明确区分已核验与推测来源并附带出处引文
- **WHEN** 某首曲目的作曲、作词或编曲分别处于 `verified` 与 `probable` 状态
- **THEN** 参考卡清晰标注各字段的核验级别并展示对应来源 URL 与原文短引文，对 `probable` 字段显式提醒用户在写入 MusicBrainz Edit Note 前二次核对出处，且自动排除未通过核验或冲突未决的字段

### Requirement: 贴回 MusicBrainz Release 链接回填本地全套官方 MBID
当用户在待回馈条目（含 Case A 已有版本与 Case B/C 新建版本）提交真实 MusicBrainz Release URL 或 UUID 后，系统 SHALL 从 MusicBrainz 拉取该 Release 的权威完整元数据，校验曲目匹配后原位回填本地已归档音频文件的官方 MBID 与发行元数据，并保持处理记录与本地艺人缓存同步。

#### Scenario: 权威拉取并原位回填全套官方 MBID 且不破坏既有词曲编与监控血缘
- **WHEN** 用户提交有效的 `https://musicbrainz.org/release/<uuid>` 或纯 UUID，后端从 MusicBrainz 拉取该 Release（含 Release Group、Mediums、Tracks、Recordings、Artist Credits、Labels 与 Barcode），且每首本地已归档音频均通过曲序或时长容差安全匹配到唯一官方 Track
- **THEN** 系统按本地既有并发锁原位更新音频标签，写入真实的 `MUSICBRAINZ_ALBUMID`、`MUSICBRAINZ_RELEASEGROUPID`、`MUSICBRAINZ_TRACKID`、`MUSICBRAINZ_RELEASETRACKID`、`MUSICBRAINZ_ARTISTID`、`MUSICBRAINZ_RELEASEARTISTID` 及缺失的官方发行元数据（如目录号、厂牌、条码、发行国家、状态），且不覆盖或清空本地已有的有效作曲、作词、编曲标签与内嵌封面
- **AND** 系统原位更新 `processed_files` 中对应记录的 `recording_id` 与 `release_group_id`，保持原有监控目录 `file_path` 与 `target_file_path` 血缘不分裂，并将本次拉取到的真实艺人沉淀进本地艺人 MBID 缓存

#### Scenario: 非法 Release 链接、远端不存在或曲目时长失配时拒绝回填
- **WHEN** 用户提交格式非法的 Release 链接/UUID、MusicBrainz 返回 404 不存在，或官方 Release 曲目数/时长与本地已归档音频文件存在明显失配且无法安全一一对应
- **THEN** 接口返回 HTTP `400` 或 `404` 及明确错误码，绝不修改任何本地音频文件或 `processed_files` 记录

#### Scenario: Case A 直接关联已有 Release 完成回填无需新建
- **WHEN** 存在性预检命中 Case A（`EXISTING_RELEASE`），用户点击「关联此 Release 并回填本地标签」
- **THEN** 系统直接使用预检命中的真实 Release MBID 执行权威拉取与本地全套 MBID 回填，无需打开 `/release/add`

### Requirement: 可选 AcoustID 声纹提交闭环
系统 SHALL 支持在配置 `acoustid.userApiKey` 且条目已绑定官方 Release 与曲目 Recording MBID 时，按需计算本地已归档音频的 Chromaprint 声纹并通过 AcoustID `/v2/submit` 接口提交，完成「新专辑反哺声纹库」的生态闭环。

#### Scenario: 配置用户密钥后一键批量提交 Chromaprint 声纹与 Recording MBID
- **WHEN** 已配置合法的 `acoustid.apiKey`（应用密钥）与 `acoustid.userApiKey`（用户提交密钥），且待回馈条目已完成 Release 回填并具备本地已归档音频与对应的官方 Recording MBID
- **THEN** 用户点击「提交声纹到 AcoustID」后，系统使用 `fpcalc` 提取各曲目时长与 Chromaprint 声纹，向 `https://api.acoustid.org/v2/submit` 提交 `client`、`user`、`duration.#`、`fingerprint.#`、`mbid.#`
- **AND** 提交成功后记录提交时间与曲目数并避免重复盲提；提交失败时返回明确错误原因且不影响已完成的本地 MBID 回填结果

#### Scenario: 缺少用户密钥或未绑定 Recording MBID 时拒绝提交
- **WHEN** 未配置 `acoustid.userApiKey`，或条目尚未完成官方 Release 关联与 Recording MBID 匹配
- **THEN** 系统拒绝发起 AcoustID `/v2/submit` 请求并返回明确的前置条件错误码，前端展示清晰的配置引导提示

### Requirement: 回馈闭环支持热更新配置与待初始化安全守卫
系统 SHALL 通过配置文件、配置 HTTP API 与 Web 设置页提供支持热更新的 `mb.contribution.enabled`、`mb.contribution.autoCheckAfterArchive` 与 `acoustid.userApiKey` 配置项，且待回馈 API 在系统处于待初始化模式时 MUST 受统一热装配守卫保护。

#### Scenario: 热更新关闭回馈功能或自动预检开关后立即生效
- **WHEN** 运行期间通过配置 API 或设置页将 `mb.contribution.enabled` 或 `mb.contribution.autoCheckAfterArchive` 设为 `false`
- **THEN** 新的联网归档不再自动触发存在性预检与入队；当 `mb.contribution.enabled` 为 `false` 且无遗留待处理条目时，侧栏自动隐藏回馈入口（若有遗留待处理条目仍保留入口以便处置）

#### Scenario: 配置 API 对 acoustid.userApiKey 脱敏掩码并支持安全留空或清空
- **WHEN** 前端读取 `/api/config` 或提交配置更新，且 `acoustid.userApiKey` 已配置
- **THEN** `GET /api/config` 对 `acoustid.userApiKey` 返回掩码状态而非明文；`POST /api/config` 提交掩码占位符时保留原值、提交显式清空动作时清除密钥，且 `acoustid.userApiKey` 绝不写入普通运行日志或前端响应明文
