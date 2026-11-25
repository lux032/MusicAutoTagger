# Proposal

## Why

当前项目在用户操作联网归档（Online Confirm）环节存在三个体验与数据缺口：联网搜索本身完全不提取曲目级幕后人员（作曲/作词/编曲），导致指纹识别失败后走联网归档的歌曲缺失创作者标签；联网搜索没有对齐设置中的发行地区优先级（`release.countryPriority`），在用户偏好如 `JP` 时仍经常输出罗马音或英文艺人名；同时确认弹窗不可查看或修正即将写入的幕后人员，且单文件隔离重识别在转入联网归档时存在路径错位导致已查到的 MusicBrainz 幕后人员丢失。

## What Changes

- 扩展联网识别（`OnlineIdentificationService` 与 `TavilyWebSearchClient`）：
  - 在现有单轮联网搜索 schema 的曲目节点中新增可选的 `composers`、`lyricists`、`arrangers` 数组，仅从可信来源明确标注的证据中提取，严禁由演唱者推断或编造 MusicBrainz ID。
  - 动态读取设置中的 `release.countryPriority`，在检索词生成、版本倾向与名称本地化上对齐地区偏好：对艺术家与幕后人员优先采用偏好地区的官方/通行名称（抓取优先，来源仅为罗马音/外文时允许模型转换；保留官方拉丁艺名与欧美艺人常用拉丁名，不猜测生僻汉字）。
- 完善联网归档幕后人员合并与写入（`MetadataUtils`、`RecoveryService`、`CoverMissingService`、`TagWriterService`）：
  - 逐字段（作曲/作词/编曲独立）实施确定优先级：`手动编辑 > MusicBrainz 指纹结果 > 本地文件已有标签 > 联网提取`；仅高置信度匹配的联网曲目参与补缺。
  - 修复单文件隔离项经「普通重新识别 -> 待确认 -> 联网归档」路径错位（`single-<jobId>` vs 原始隔离路径）导致 MusicBrainz 幕后人员丢失的问题。
  - 支持人工显式清空作曲/作词/编曲标签（同步清理伴随复数标签与残留 MBID），并在触发「缺少封面」拦截时将手动覆盖持久化到 `MissingCover` 中，补图后继续生效。
- 升级待确认详情与联网确认弹窗（`ReviewServlet`、`review.html` 及中英文 i18n）：
  - 候选卡片逐曲匹配行下方紧凑展示提取到的 `曲 / 词 / 编`。
  - 联网确认弹窗新增可折叠的「逐曲幕后人员」区块，展示合并后生效值与来源徽章（`MusicBrainz` / `本地` / `联网` / `手动`），支持人工修改或清空后一并提交。

## Capabilities

### New Capabilities
- `online-identification`: 联网识别的曲目级幕后人员提取（作曲/作词/编曲）与发行地区偏好（`release.countryPriority`）对齐的名称本地化能力。
- `online-archive-credits`: 联网确认归档环节的幕后人员多源合并策略、人工预览与编辑、显式清空、缺封面中转持久化及单文件重识别元数据衔接能力。

### Modified Capabilities
<!-- 仓库刚初始化 OpenSpec，暂无既有 specs 需要变更 -->

## Impact

- **模型与持久化**：`ReviewItem`（`OnlineTrack`、`MissingCover` 及逐曲覆盖结构）、`MusicMetadata`（显式清空标志）。
- **检索与核心服务**：`OnlineIdentificationService`、`TavilyWebSearchClient`、`MetadataUtils`、`RecoveryService`、`CoverMissingService`、`TagWriterService`。
- **Web API 与前端**：`ReviewServlet`（`/api/review/item` 详情视图与 `/api/review/online-confirm` 可选 `trackCredits` 请求体）、`review.html`、`messages_zh_CN.properties`、`messages_en_US.properties`。
- **兼容性**：新增字段与 API 参数均向后兼容，不影响旧 `review-queue.json` 反序列化与既有客户端调用；不新增音频标签角色（仅沿用项目既有的作曲/作词/编曲三项）。
