# Tasks

## 1. 数据模型与标签显式清空支持

- [x] 1.1 在 `ReviewItem` 中为 `OnlineTrack` 新增 `composers`、`lyricists`、`arrangers` 列表字段，新增 `TrackCreditsOverride`（区分 `null` 未改、非空列表覆盖、空列表显式清空），并在 `MissingCover` 中新增 `onlineTrackCredits` 映射；在 `OnlineCandidateDeepCopyTest`（或新增模型序列化测试）中验证新字段深拷贝隔离与旧 JSON 向后兼容反序列化，运行对应 `mvn test -Dtest=OnlineCandidateDeepCopyTest` 通过
- [x] 1.2 在 `MusicMetadata` 中新增 `clearComposer`、`clearLyricist`、`clearArranger` 标志（同步更新 `copyWithoutHeavyFields()`），并在 `TagWriterService.updateTextTags()` 中实现显式清空作曲/作词/编曲（同步删除标准字段、复数伴随标签与 MBID 别名标签）；在 `TagWriterServiceTest` 中覆盖 FLAC/MP3/M4A/WAV 的显式清空与保留行为并运行 `mvn test -Dtest=TagWriterServiceTest` 通过

## 2. 联网搜索提取幕后人员与发行地区偏好对齐

- [x] 2.1 在 `OnlineIdentificationService` 中扩展 `appendJsonSchema()` 与 `parseCandidate()`，为 `tracks[]` 增加可选的 `composers`、`lyricists`、`arrangers` 解析与清洗（保序去重、空值规范化、严禁由演唱者推断与捏造 MBID）；编写单元测试验证含 credits、字段省略、脏值清洗与无 MBID 生成，运行 `mvn test -Dtest=OnlineIdentificationService*Test` 通过
- [x] 2.2 在 `OnlineIdentificationService` 的 `buildPrompt()` / `buildMissingTracksPrompt()` 中动态读取 `config.getReleaseCountryPriority()` 注入地区优先级与名称本地化指令（覆盖所有艺术家与幕后人员字段、保持显示名与数组同语种、保留官方拉丁艺名与欧美艺人拉丁名、不猜测生僻汉字、保持官方曲名），并在 `TavilyWebSearchClient.QUERY_SYSTEM` 中增加对 `PREFERRED RELEASE REGIONS` 的检索词引导；补充单元测试验证空配置、单地区 `JP` 与多地区 `JP,US` 下 Prompt 与检索词行为并通过 `mvn test` 对应测试类

## 3. 联网归档合并策略、单文件修复与缺封面中转

- [x] 3.1 在 `MetadataUtils` 中实现逐字段（作曲/作词/编曲独立）的多源幕后人员解析与应用方法（优先级 `手动编辑 > MusicBrainz > 本地文件已有标签 > 联网提取`，仅 MB 来源保留 MBID，非 MB 覆盖置空 MBID，手动 `[]` 触发 `clear*`）；在 `MetadataUtilsTest` 中补充完整优先级组合与边界单测并运行 `mvn test -Dtest=MetadataUtilsTest` 通过
- [x] 3.2 在 `RecoveryService` 中修复单文件隔离重识别（`single-<jobId>`）在联网确认时无法命中 `queuedMetadataByOriginalPath` 的路径错位问题，并扩展 `confirmOnlineCandidate` / `confirmOnlineCandidateInternal` 接收可选 `manualTrackCredits`（低置信度曲目 `< 0.6` 不采信联网幕后人员）；在 `RecoveryService` 端到端测试中验证四级合并、低置信度拦截、手动覆盖/显式清空以及单文件重识别转联网归档保留 MB 幕后人员，运行对应 `mvn test` 通过
- [x] 3.3 打通 `RecoveryService` 与 `CoverMissingService` 的缺封面中转链路：`transitionToMissingCover` 将确认时的 `manualTrackCredits` 持久化进 `MissingCover.onlineTrackCredits`，`commitDeferredOnlineMissingCover` 透传回 `confirmOnlineCandidateInternal`；在 `CoverMissingWorkflowTest`（或对应集成测试）中验证联网确认编辑幕后人员 -> 缺封面拦截 -> 模拟重载队列 -> 补图/无封面入库后标签完整生效，运行对应 `mvn test` 通过

## 4. Web 接口、确认弹窗 UI 与国际化

- [x] 4.1 在 `ReviewServlet.toDetail()` 的 `files[]` 中返回曲目已有幕后人员与来源信息，并在 `POST /api/review/online-confirm` 中解析校验可选 `trackCredits` 参数（缺省保持完全向后兼容）；补充 Servlet 测试验证详情返回、合法 `trackCredits` 透传、非法结构返回 400 及缺省兼容，运行对应 `mvn test` 通过
- [x] 4.2 在 `review.html` 中为联网候选卡片的逐曲匹配行下方添加紧凑 `曲 / 词 / 编` 展示，在 `onlineDialog` 中新增带内部滚动的可折叠「逐曲幕后人员」区块（展示按合并规则计算出的默认名单与来源徽章 `MusicBrainz` / `本地` / `联网` / `手动`，支持编辑与显式清空且仅提交改动项），并同步补齐 `messages_zh_CN.properties` 与 `messages_en_US.properties` 的全部文案键；人工检查页面脚本无语法错误及中英文键一一对应

## 5. 全链路回归验证

- [x] 5.1 运行完整测试套件 `mvn test`，确认新增与既有单元测试、集成测试全部通过且无回归
