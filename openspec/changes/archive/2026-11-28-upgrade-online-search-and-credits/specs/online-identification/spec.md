# Spec Delta: online-identification

## MODIFIED Requirements

### Requirement: 联网识别提取曲目级作曲、作词与编曲名单
系统在执行联网识别时 SHALL 允许每个候选曲目携带可选的结构化 `composers`、`lyricists`、`arrangers` 人员名单数组及其字段级证据明细（`creditDetail`），记录经可信检索或网页正文核验归属的创作者信息，并支持在配置允许的预算上限内执行受控的网页正文读取与多轮补漏检索。

#### Scenario: 检索证据包含明确词曲编信息
- **WHEN** 联网检索或网页正文读取结果中包含可追溯的真实页面，且页面明确标注了曲目的作曲、作词或编曲人员
- **THEN** 系统将该曲目的作曲、作词、编曲分别解析为去空白且保序去重的字符串数组，并持久化对应的字段级状态、来源 URL、来源类型、原文证据摘录与版本匹配信息
- **AND** 该候选曲目不生成或捏造任何 MusicBrainz ID

#### Scenario: 检索证据未标注幕后人员或省略字段
- **WHEN** 联网检索与受控补漏均未给出某首曲目的幕后人员信息，或模型输出省略了对应的数组字段
- **THEN** 系统将缺失的 `composers`、`lyricists`、`arrangers` 字段规范化为空列表，并将对应字段状态标记为 `not_found` 或 `not_searched`
- **AND** 候选专辑仍正常完成解析与逐曲匹配，不因缺少幕后人员而失败

#### Scenario: 禁止从演唱者名推断幕后人员
- **WHEN** 系统向联网搜索模型构造提示词与结构化输出要求
- **THEN** 提示词 MUST 明确要求仅在引用来源明确写明词/曲/编归属时填写对应字段，严禁把歌手、专辑艺术家或专辑级制作人默认推断为曲目的作曲、作词或编曲

## ADDED Requirements

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
