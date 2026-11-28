# online-identification Specification

## Purpose

规范联网音乐识别在搜索阶段对曲目级幕后人员（作曲、作词、编曲）的结构化提取，以及按用户设置的发行地区优先级对版本选择与艺术家、创作者名称进行地区本地化的可观测行为。

## Requirements

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

### Requirement: 联网识别遵循发行地区优先级进行版本与名称本地化
当系统配置了非空的发行地区优先级（`release.countryPriority`）时，系统在每次联网识别中 SHALL 动态读取当前配置，并将地区优先级传递给检索与归纳流程，使候选版本选择以及所有艺术家和幕后人员名称尽可能与偏好地区的通行写法对齐。

#### Scenario: 偏好 JP 时还原日系艺人及幕后人员日文名
- **WHEN** 发行地区优先级将 `JP` 排在首位，且检索来源仅给出日系艺人或创作者的罗马音或英文拼写（例如 `Yuki Kajiura`）
- **THEN** 系统在检索词与归纳提示中要求优先提取偏好地区原生署名，并在来源仅有外文拼写时允许模型转换为该地区公认官方名称（例如 `梶浦由記`）
- **AND** 显示名与对应的拆分名单数组 MUST 使用同一语种与书写体系以保持顺序对齐

#### Scenario: 保留官方拉丁艺名与欧美艺人常用拉丁名
- **WHEN** 发行地区优先级将 `JP` 排在首位，遇到官方拉丁艺名（例如 `YOASOBI`、`LiSA`）或在日本发行亦通行拉丁署名的欧美艺人（例如 `Taylor Swift`）
- **THEN** 系统提示要求保留其原拉丁署名，不强行改写为片假名
- **AND** 对生僻或无法确定唯一汉字写法的人名保留检索来源原文，不凭空编造汉字

#### Scenario: 保持版本官方曲目标题且热更新配置生效
- **WHEN** 用户在运行期间修改发行地区优先级配置或将其清空后发起新的联网搜索
- **THEN** 新的联网搜索立即按最新配置生成检索与归纳指令（为空时不附加地区本地化指令）
- **AND** 曲目标题保持所选发行版本的官方曲目名，不因名称本地化规则强行意译曲名

### Requirement: 原生联网搜索完整采集服务端来源池并规范化引用匹配
当系统使用 OpenAI `/v1/responses` 原生联网搜索时，系统 SHALL 请求并采集服务端真实检索与打开页面的来源 URL 池，并在去除追踪参数与规范化后与模型输出的 `source_urls` 及证据 URL 进行比对。

#### Scenario: 严格 JSON 模式下通过服务端来源池通过候选门槛
- **WHEN** 模型在 OpenAI `/v1/responses` 原生联网搜索中返回严格 JSON 导致 `content[].annotations` 为空，但服务端在 `web_search_call.action.sources` 或 `open_page`/`find_in_page` 动作中返回了真实检索 URL
- **THEN** 系统从 `web_search_call.action.sources`、动作 `url` 与 `annotations` 合并构建本轮真实引用池
- **AND** 与候选 `source_urls` 匹配成功的权威来源正常计入正式候选门槛，不因 `annotations` 为空而丢弃有效候选

#### Scenario: 自动剥离追踪参数完成引用匹配
- **WHEN** 原生联网响应返回的来源 URL 带有 `?utm_source=openai` 等追踪参数或尾斜杠差异，而候选 `source_urls` 为不含追踪参数的干净 URL
- **THEN** 系统按规范化后的 URL 完成匹配并关联到对应证据项
- **AND** 未出现在服务端真实引用池中的捏造 URL MUST 被拒收，不计入正式候选来源或字段有效证据

#### Scenario: 端点不支持扩展参数时自动降级兼容
- **WHEN** 第三方 OpenAI `/v1/responses` 端点拒绝 `search_context_size` 或 `include` 字段并返回 HTTP 400
- **THEN** 系统自动移除不被支持的扩展参数重试一次
- **AND** 若仍失败才按既有故障转移策略切换下一个可用端点

### Requirement: 来源可信度表覆盖国际与东亚正版发行及权威音乐来源
系统 SHALL 在保持精确域名与真子域匹配防仿冒规则的前提下，将主流国际与东亚正版数字发行平台、音乐数据库、音乐零售、官方通稿及日文歌词库纳入分层来源可信度注册表。

#### Scenario: 正版日系数字发行平台与厂牌媒体参与正式候选评估
- **WHEN** 联网识别检索到 `ototoy.jp`、`mora.jp`、`recochoku.jp`、`linkco.re`、`tunecore.co.jp`、`vocadb.net` 等正版发行平台/数据库，或两个及以上独立的日系主流媒体/零售/歌词站（如 `prtimes.jp`、`natalie.mu`、`realsound.jp`、`tower.jp`、`uta-net.com`、`utaten.com`）
- **THEN** 系统按其分层可信度级别（`HIGH` 或 `MEDIUM`）计算正式候选资格
- **AND** 仿冒域名（例如 `fake-ototoy.example.com` 或 `my-vgmdb.net`）仍被精确判定为 `LOW`

### Requirement: 受控网页正文读取与安全降级提取
当联网幕后人员补全需要读取检索命中页面的完整正文时，系统 SHALL 通过受 SSRF 防护与体积限制的抓取器优先直连提取网页正文，并在遭遇反爬拦截或空壳页面时按配置批量降级至外部提取器。

#### Scenario: 直连成功与反爬空壳批量降级
- **WHEN** 系统对高相关度候选页面执行正文读取，其中部分站点直连返回 HTTP 200 有效正文，另一些站点（如 `uta-net.com`、`youtube.com`）返回 403/429 或无正文 JS 空壳
- **THEN** 直连成功的页面直接完成编码识别与 HTML 正文清洗，不消耗外部提取额度
- **AND** 直连受阻且未返回 404 的页面在启用 `online.credits.allowTavilyExtract` 时按批次（每批最多 5 个 URL）降级调用 Tavily `/extract`

#### Scenario: 严格拦截私网 SSRF 与重定向越界
- **WHEN** 搜索引擎返回或页面 301/302 重定向指向环回地址、内网地址、云元数据地址或非 80/443 端口，或响应体超过安全上限（2 MB）
- **THEN** 系统立即中止该 URL 抓取并记录受阻状态
- **AND** 不向内网地址发起请求，也不因单个页面抓取失败中断整张专辑的联网搜索流程

### Requirement: Tavily 路径支持开放域首轮与状态压缩受控补漏
当使用 Tavily 作为联网搜索来源且启用幕后人员深度补全时，系统 SHALL 在专辑候选匹配完成后放开 13 域名硬白名单，执行开放域专辑级首轮检索与网页正文读取，并在仍有缺失字段时在预算上限内执行状态压缩的定向补漏循环。

#### Scenario: 首轮已集齐全部适用幕后人员时早停
- **WHEN** 开放域首轮检索与网页正文读取已为候选专辑的所有适用曲目字段提取到经核验的词/曲/编信息
- **THEN** 系统直接结束补全流程，不再发起多余的逐曲查询或后续补漏轮次

#### Scenario: 存在缺失字段时按状态压缩动态补漏并遵守预算上限
- **WHEN** 首轮结束后仍有曲目缺失作词、作曲或编曲，且未达到 `online.credits.maxFollowUpRounds`、`maxSearchCalls` 与 `maxFetchPages` 上限
- **THEN** 系统仅将压缩后的当前字段结果、缺失清单及已访问 URL 列表发送给模型（不重复拼接前轮完整网页正文），由模型动态生成针对缺失曲目/语种/别名/版本的后续查询并读取新页面
- **AND** 达到任一预算上限或线程被取消时立即停止循环并保留已获取的有效结果

### Requirement: 字段级五态确定性证据核验与版本角色防错护栏
系统 SHALL 对每首曲目的 `lyricists`、`composers`、`arrangers` 独立执行确定性代码级证据核验与状态计算（`verified`、`probable`、`conflict`、`not_found`、`not_searched`），不得仅凭模型主观置信度直接放行无证据、跨版本误继承或角色混淆的结果。

#### Scenario: 原文与来源池校验决定已核验与推测状态
- **WHEN** 某字段的提取结果引用了本会话实际抓取的完整页面，且原文引文与人名通过归一化校验、版本匹配为精确版本且无来源冲突
- **THEN** 系统将其状态计算为 `verified`
- **AND** 若仅命中搜索摘要未读全文、或非原版曲目的词/曲仅引用原曲证据（`original_only`），系统最高将其状态计算为 `probable`
- **AND** 若引用 URL 不在本会话真实检索/抓取池中，或完整正文中查无对应引文与人名，系统拒绝该无证据声称并将其降级为 `not_found`

#### Scenario: Remix 与特殊版本禁止继承原版编曲及混淆 Remixer
- **WHEN** 曲目标题或版本类型标识为 Remix、Latin、Acoustic、Live 等特殊版本，模型试图将原曲编曲人继承到该曲 `arrangers`，或将仅标注 `Remix:` / `Remixed by` / `Producer` / `Mix` 的人员填入 `arrangers`
- **THEN** 核验器 MUST 拦截该赋值并将该曲 `arrangers` 置空且状态标记为 `not_found`
- **AND** 允许在 `notes` 中保留 Remixer 等辅助说明供人工参考

#### Scenario: 伴奏曲目禁止写入作词人
- **WHEN** 曲目标题或版本类型明确标识为 `Instrumental`、`Off Vocal`、`Karaoke`、`伴奏` 或 `インスト`
- **THEN** 核验器 MUST 清空该曲联网提取的 `lyricists` 并将状态标记为 `not_found`

#### Scenario: 转载通稿去重与多源冲突保留候选
- **WHEN** 多个页面全文转载了高度重合的同一新闻通稿（5-gram 相似度达去重阈值）
- **THEN** 系统将其标注为转载副本，不计为独立多源佐证
- **AND** 当两个及以上独立可信来源对同一曲目的同一角色给出不一致的人名列表时，系统将该字段状态标记为 `conflict` 并在 `candidates` 中保留所有冲突候选及其来源 URL 与引文

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
