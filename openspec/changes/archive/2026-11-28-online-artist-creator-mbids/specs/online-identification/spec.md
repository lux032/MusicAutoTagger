# Spec Delta: online-identification

## MODIFIED Requirements

### Requirement: 联网识别提取曲目级作曲、作词与编曲名单
系统在执行联网识别时 SHALL 允许每个候选曲目携带可选的结构化 `composers`、`lyricists`、`arrangers` 人员名单数组及其字段级证据明细（`creditDetail`），记录经可信检索或网页正文核验归属的创作者信息，并支持在配置允许的预算上限内执行受控的网页正文读取与多轮补漏检索。

#### Scenario: 检索证据包含明确词曲编信息
- **WHEN** 联网检索或网页正文读取结果中包含可追溯的真实页面，且页面明确标注了曲目的作曲、作词或编曲人员
- **THEN** 系统将该曲目的作曲、作词、编曲分别解析为去空白且保序去重的字符串数组，并持久化对应的字段级状态、来源 URL、来源类型、原文证据摘录与版本匹配信息
- **AND** 模型输出与幕后人员提取阶段不生成或捏造任何 MusicBrainz ID（专辑级与录音级 MBID 始终留空；人物级 MBID 仅可由后续经 MusicBrainz 真实实体核验的解析流程补充）

#### Scenario: 检索证据未标注幕后人员或省略字段
- **WHEN** 联网检索与受控补漏均未给出某首曲目的幕后人员信息，或模型输出省略了对应的数组字段
- **THEN** 系统将缺失的 `composers`、`lyricists`、`arrangers` 字段规范化为空列表，并将对应字段状态标记为 `not_found` 或 `not_searched`
- **AND** 候选专辑仍正常完成解析与逐曲匹配，不因缺少幕后人员而失败

#### Scenario: 禁止从演唱者名推断幕后人员
- **WHEN** 系统向联网搜索模型构造提示词与结构化输出要求
- **THEN** 提示词 MUST 明确要求仅在引用来源明确写明词/曲/编归属时填写对应字段，严禁把歌手、专辑艺术家或专辑级制作人默认推断为曲目的作曲、作词或编曲

## ADDED Requirements

### Requirement: 本地 MusicBrainz 艺人缓存被动沉淀与同名感知
系统 SHALL 维护本地 MusicBrainz 艺人 MBID 缓存，在常规 MusicBrainz 识别解析出艺人或幕后人员实体时被动沉淀其 MBID、主名、排序名、备注与别名索引，并在同一规范化人名对应多个不同实体时自动感知同名冲突，避免跨专辑误绑定。

#### Scenario: 常规识别沉淀艺人后联网识别零请求命中
- **WHEN** 常规 MusicBrainz 指纹或专辑查询解析出艺人或创作者（例如 `梶浦由記` 及其唯一 MBID），随后另一张专辑的联网识别再次出现该人名
- **THEN** 系统直接从本地缓存以零外部 API 请求命中该唯一 MBID，并将其解析来源标记为 `LOCAL_CACHE`
- **AND** 复用该缓存记录中的排序名与 `disambiguation` 备注信息

#### Scenario: 本地缓存存在同名多实体时不直接短路绑定
- **WHEN** 本地缓存索引中同一规范化人名关联了两个及以上不同的 MusicBrainz Artist MBID
- **THEN** 系统 MUST NOT 在本地缓存层直接拍板绑定其中某一个 MBID
- **AND** 系统将该人名交给后续外链核验或上下文消歧流程处理

#### Scenario: MySQL 未建缓存表或文件模式下安全降级
- **WHEN** 系统运行在文件模式（无数据库）或外部 MySQL 尚未创建艺人缓存表
- **THEN** 系统自动降级为进程内有界缓存并仅记录告警，不中断常规识别或联网识别流程

### Requirement: 联网识别三层解析人物级 MusicBrainz ID
当联网识别产出正式候选并启用人物级 MBID 解析时，系统 SHALL 对候选涉及的去重人名（专辑艺人、可靠匹配曲目艺人及准入核验通过的作曲、作词、编曲）按「本地缓存 -> 已抓网页 MB 外链提取核验 -> MusicBrainz Artist API 检索与封闭式消歧」三层流程解析真实 Artist MBID，严禁模型直接生成或猜测 UUID。

#### Scenario: 从已抓权威网页提取 MusicBrainz 艺人外链并回查核验
- **WHEN** 本轮联网抓取的网页或真实引用池中包含 `musicbrainz.org/artist/<uuid>` 链接，且经 `GET /ws/2/artist/<uuid>` 回查确认该实体真实存在且主名或别名与目标人名一致
- **THEN** 系统将该人名解析状态标记为 `RESOLVED`、来源标记为 `WEB_LINK_VERIFIED`，并绑定该真实 UUID
- **AND** 若回查发现实体不存在或主名与别名均与目标人名不符，系统拒收该外链并继续后续检索

#### Scenario: MusicBrainz Artist 检索唯一精确命中时自动绑定
- **WHEN** 目标人名未命中本地缓存与网页外链，调用 MusicBrainz `/ws/2/artist` 检索返回唯一一个主名或官方别名精确匹配的实体且无其他高分同名竞争者
- **THEN** 系统将其状态标记为 `RESOLVED`、来源标记为 `MB_SEARCH_EXACT`，并绑定该真实 UUID 与排序名

#### Scenario: 同名多候选经封闭式 LLM 消歧或保留待人工选择
- **WHEN** MusicBrainz `/ws/2/artist` 检索返回多个同名实体（例如同名日本作曲家与欧美乐手）
- **THEN** 系统仅向封闭式 LLM 消歧器提供真实检索候选编号与上下文线索（专辑名、地区偏好、角色、合作者、候选 `disambiguation`/地区/类型/别名/标签），仅允许模型返回候选序号或无法确定，绝不要求模型输出 UUID
- **AND** 当模型选出合法序号且置信度达到阈值（`>= 0.85`）时，系统将状态标记为 `RESOLVED`（来源 `MB_SEARCH_LLM_DISAMBIGUATED`）并保留竞争候选供人工复核
- **AND** 当模型返回无法确定、置信度低于阈值或消歧不可用时，系统将状态标记为 `AMBIGUOUS`、`selectedMbid` 置空，并持久化候选列表供确认弹窗人工点选
- **AND** 系统不得将多实体同名映射写入可单条短路的本地唯一缓存

#### Scenario: 跳过占位艺人并遵守请求预算与中断信号
- **WHEN** 候选包含 `Various Artists` 等占位艺人名，或唯一人名数量超过 `online.mbid.maxLookupsPerAlbum` 上限，或搜索线程收到取消中断信号
- **THEN** 系统跳过占位艺人名，并按「专辑艺人 > 曲目艺人 > 高频幕后人员」顺序在预算上限内执行查询
- **AND** 达到预算上限或收到中断信号时立即停止后续外部查询，将未查人名标记为 `SKIPPED` 并完整保留已解析结果

### Requirement: 人物级 MBID 解析支持热更新配置
系统 SHALL 通过配置文件、配置 HTTP API 与 Web 设置页提供支持热更新的 `online.mbid.resolve.enabled`、`online.mbid.maxLookupsPerAlbum` 与 `online.mbid.llmDisambiguate.enabled` 配置项，并在每次联网识别时按最新配置生效。

#### Scenario: 关闭人物级 MBID 解析开关后跳过解析
- **WHEN** 运行期间将 `online.mbid.resolve.enabled` 设为 `false` 并触发新的联网搜索
- **THEN** 系统跳过人物级 MBID 的缓存检索、外链回查与 MusicBrainz Artist API 查询
- **AND** 联网搜索其余流程（专辑识别、逐曲匹配与幕后人员核验）仍正常完成

#### Scenario: 关闭 LLM 消歧开关时同名候选直接保留待人工选择
- **WHEN** `online.mbid.resolve.enabled` 为 `true` 且 `online.mbid.llmDisambiguate.enabled` 设为 `false`，某人名在 MusicBrainz 返回多个同名候选
- **THEN** 系统不调用 LLM 消歧，直接将该人名标记为 `AMBIGUOUS` 并保留候选列表交由人工确认
