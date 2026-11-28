# Spec Delta: online-archive-credits

## MODIFIED Requirements

### Requirement: 按确定优先级逐字段合并曲目级幕后人员
在用户确认联网候选并归档音频文件时，系统 SHALL 对每首曲目的作曲、作词、编曲三个字段各自独立按 `手动编辑 > MusicBrainz 指纹识别结果 > 本地文件已有标签 > 联网搜索提取结果` 的优先级计算最终写入值，且仅允许通过确定性核验的非冲突联网字段参与自动合并。

#### Scenario: 四类来源并存时按优先级独立生效
- **WHEN** 同一首曲目同时存在 MusicBrainz 作曲、本地文件已有作词、联网核验通过的编曲，且用户未手动修改该曲目
- **THEN** 归档文件写入 MusicBrainz 的作曲及其对应的 MusicBrainz ID、保留本地文件的作词，并用联网结果补全空白的编曲
- **AND** 联网补全的编曲写入标准编曲标签与结构化复数伴随标签，仅在所有编曲成员均具备经 MusicBrainz 核验或人工选定的合法 UUID 时写入严格顺序对齐的编曲 MBID，绝不写入虚构或错位的 MBID

#### Scenario: 本地文件已有标签不被联网提取结果覆盖
- **WHEN** 曲目没有 MusicBrainz 指纹结果，但本地音频文件已包含作曲标签且联网候选也提取了不同的作曲名单，用户未在确认弹窗中手动修改
- **THEN** 归档文件保留本地音频文件原有的作曲标签，不以联网提取结果覆盖

#### Scenario: 低置信度匹配曲目不套用联网幕后人员
- **WHEN** 某首本地文件与联网候选曲目的匹配置信度低于可靠匹配阈值（`0.6`），且用户未手动指定该曲目的幕后人员
- **THEN** 系统忽略该联网候选曲目携带的作曲、作词、编曲名单，仅依据更早阶段已有的 MusicBrainz 结果或本地文件标签落盘

#### Scenario: 冲突或未核验通过的联网字段不自动写入
- **WHEN** 某首曲目的联网幕后人员字段核验状态为 `conflict`、`not_found` 或 `not_searched`，或配置关闭 `online.credits.applyProbable` 时字段状态为 `probable`，且用户未在确认弹窗中手动选择或填写
- **THEN** 系统不自动采用该联网字段值，仅沿用更高优先级的 MusicBrainz 或本地标签（均为空时留空）

## ADDED Requirements

### Requirement: 联网归档按全员对齐规则写入人物级 MusicBrainz ID 并清理陈旧艺人 ID
在用户确认联网候选并归档音频文件时，系统 SHALL 对专辑艺人、曲目艺人以及作曲、作词、编曲逐角色按“全有或全无”位置对齐规则写入经核验或人工选定的合法 MusicBrainz Artist UUID，并在联网覆盖艺人但无有效对齐 MBID 时显式清除原文件可能残留的陈旧艺人 MBID 标签。

#### Scenario: 单人及多人全员命中时按顺序对齐写入 MBID
- **WHEN** 联网候选的专辑艺人、可靠匹配曲目的艺人及核验通过的作曲、作词或编曲名单中每一位成员均存在已核验或人工选定的合法非零 UUID
- **THEN** 归档音频文件按成员顺序写入 `MUSICBRAINZ_RELEASEARTISTID`、`MUSICBRAINZ_ARTISTID` 及对应的 `MUSICBRAINZ_COMPOSERID`、`MUSICBRAINZ_LYRICISTID`、`MUSICBRAINZ_ARRANGERID`（多人以 `"; "` 分隔）
- **AND** 绝不写入任何未核验或虚构的 Release ID、Release Group ID 或 Recording ID

#### Scenario: 多人名单仅部分成员命中时整组不写 MBID
- **WHEN** 某首曲目的作曲名单包含两位创作者（例如 `["梶浦由記", "未收录新人"]`），其中仅首位具备合法 UUID 而第二位无有效 MBID
- **THEN** 系统写入两位创作者的标准标签与复数伴随标签，但该曲的 `MUSICBRAINZ_COMPOSERID` 整体不写入（且清除原文件可能残留的旧作曲 MBID），防止一维列表位置错位

#### Scenario: 联网改写艺人无有效 MBID 或折叠为合辑时清理旧艺人 ID
- **WHEN** 原音频文件携带旧的 `MUSICBRAINZ_ARTISTID` 或 `MUSICBRAINZ_RELEASEARTISTID`，联网归档改写了曲目艺人或专辑艺人但新名单未全员命中合法 MBID，或专辑艺人按策略折叠为 `Various Artists`
- **THEN** 系统从归档后的音频文件中显式清除对应的陈旧 `MUSICBRAINZ_ARTISTID` 或 `MUSICBRAINZ_RELEASEARTISTID` 标签，不保留过期的错误 ID

#### Scenario: 低置信度未匹配曲目保留原本地艺人及其既有 MBID
- **WHEN** 某首本地音频与联网候选曲目未达到可靠匹配阈值（`0.6`），归档时保留该曲目原有的本地 `ARTIST` 标签且用户未对该艺人提交显式 MBID 取消绑定
- **THEN** 系统不误删该曲目原文件内既有的 `MUSICBRAINZ_ARTISTID` 标签

### Requirement: 联网确认弹窗支持预览、改选、手填与取消绑定人物级 MBID
系统 SHALL 在待确认详情与联网确认弹窗中展示候选涉及人物的 MusicBrainz ID 解析状态、可点击外部链接与同名候选明细，并允许用户在提交联网归档时通过可选的 `entityMbidOverrides` 参数点选同名候选、手动指定合法 UUID 或显式取消绑定。

#### Scenario: 确认弹窗展示已绑定链接与同名待选候选
- **WHEN** 用户在待确认页面选中包含人物级 MBID 解析结果的联网候选并打开确认归档弹窗
- **THEN** 弹窗展示涉及人物的解析状态徽章（如本地缓存、外链核验、MB 精确匹配、LLM 消歧、重名待选、未匹配）、已绑定的可点击 `musicbrainz.org/artist/<mbid>` 链接及 `disambiguation` 备注
- **AND** 当存在 `AMBIGUOUS` 同名待选条目时，弹窗默认展开 MBID 绑定区域并列出同名候选供用户一键点选，未点选前不自动写入该人物的 MBID

#### Scenario: 用户手填合法 UUID 或显式取消绑定生效
- **WHEN** 用户在确认弹窗中为某位人物手动输入合法的 MusicBrainz Artist UUID（或粘贴 `musicbrainz.org/artist/<uuid>` 链接），或对已自动绑定的人物点击取消绑定（提交空字符串 `""`）并提交联网归档
- **THEN** 手填的合法 UUID 规范化为小写并覆盖自动解析结果参与落盘对齐，显式取消绑定的人物不写入任何 MBID
- **AND** 若 `entityMbidOverrides` 中包含非空且不符合非零 UUID 格式的非法值，接口 MUST 返回 HTTP `400` 拒绝归档

#### Scenario: 未传人物 MBID 覆盖参数或历史队列条目保持向后兼容
- **WHEN** 调用方提交联网确认请求时未传递 `entityMbidOverrides`，或待确认条目为升级前落盘且缺少 `entityMbids` 字段的历史队列条目
- **THEN** 系统按既有接口语义正常渲染与完成归档，已解析的 `RESOLVED` 条目自动生效，历史条目无 MBID 时保持纯姓名写入不报错

### Requirement: 人工调整的人物级 MBID 跨缺封面拦截流程持久化
当联网确认因缺少封面转入「缺少封面」等待队列时，系统 SHALL 持久化用户提交的人物级 MBID 覆盖（包括选定/手填的 UUID 与显式取消绑定），并在后续补全封面或确认无封面入库时原样生效。

#### Scenario: 缺封面中转与进程重启后仍保留人工 MBID 调整
- **WHEN** 用户在联网确认弹窗中为同名艺人选定了特定 MBID 或取消了某位创作者的自动绑定，提交后因缺少封面转入「缺少封面」队列，随后在「缺少封面」页面补图或确认无封面入库（包括中途经历进程重启）
- **THEN** 最终落盘的音频文件完整应用此前持久化的人物级 MBID 覆盖结果
