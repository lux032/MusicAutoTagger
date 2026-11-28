# Tasks

## 1. 配置扩展与待回馈持久化模型

- [x] 1.1 在 `MusicConfig`、`ConfigFieldSpec`、`ConfigServlet`、`config.properties.example` 与 `config.properties.example.zh` 中新增支持热更新的配置项 `mb.contribution.enabled`（默认 `true`）、`mb.contribution.autoCheckAfterArchive`（默认 `true`）、`acoustid.userApiKey`（敏感掩码，默认空）与 `acoustid.submitApiUrl`（默认 `https://api.acoustid.org/v2/submit`），补充配置读写、掩码保值与显式清空单测并运行验证
- [x] 1.2 实现 `MbContributionItem` 模型与 `MbContributionStore` 存储服务（原子持久化 `mb-contributions.json`、按 `reviewItemId`/规范化输出目录去重、`mb-contribution-covers/` 未压缩原图备份与防路径穿越校验、终态删除及启动清理 30 天孤儿文件），新增 `MbContributionStoreTest` 验证跨重启读写、并发安全、路径越界拦截与自动清理通过

## 2. MusicBrainz 存在性预检与 AcoustID 提交客户端能力

- [x] 2.1 在 `MusicBrainzClient` 中新增 `searchReleasesByBarcode`、`searchReleasesByCatalogNumber`、`searchReleaseGroups`（支持 `arid:<mbid>` 检索）与 `getFullReleaseWithTracklist`（解析完整 `ReleaseDetail` 含 Release/RG/Medium/Track/Recording/ArtistCredit/Label/Barcode 并顺带沉淀艺人缓存），新增 `MusicBrainzClientContributionTest` 验证 Lucene 转义、空结果/404 与完整曲目解析通过
- [x] 2.2 在 `OnlineIdentificationService` 中向后兼容扩展可选 `barcode`/`release_type`/`media_format` 候选解析，并实现 `MusicBrainzExistenceChecker` 四类存在性预检分流（Case A `EXISTING_RELEASE`、Case B `MISSING_EDITION`、Case C `MISSING_RELEASE_GROUP`、Case D `NOT_ELIGIBLE`，含单次查询预算上限与时长序列容差校验），新增 `MusicBrainzExistenceCheckerTest` 覆盖全部四类分支通过
- [x] 2.3 在 `AudioFingerprintService` 中新增 `submitFingerprints(...)` 支持按 AcoustID `/v2/submit` 协议分批提交 `client`/`user`/`duration.#`/`fingerprint.#`/`mbid.#` 并校验响应状态，新增 `AudioFingerprintSubmitTest` 验证批量提交、缺 Key 拒绝与错误响应处理通过

## 3. Seeding 构造、CAA 高画质封面引导与合规 Works 参考卡

- [x] 3.1 实现 `MusicBrainzSeedBuilder`，按 MusicBrainz 官方 `/release/add` 规范生成扁平键值对（`name`/`comment`/`release_group`/`type`/`status`/`packaging`/`language`/`script`/`events.0.*`/`labels.0.*`/`barcode`/`artist_credit.names.i.*`/`mediums.m.format`/`mediums.m.track.t.*` 完整官方曲目表与匹配曲目精准毫秒时长/`urls.u.url`/`edit_note`），新增 `MusicBrainzSeedBuilderTest` 验证 Case B/C、多人 MBID 与 `join_phrase`、缺轨保完整曲目表及不输出非法 UUID 通过
- [x] 3.2 实现 `MusicBrainzCreditSanitizer`（剥离创作者末尾所属厂牌/社团括号输出干净的 MB 实体链接、规范主名与可选人名变体 `credited as`，附出处与 `verified`/`probable` 标注且不改写本地标签）与 CAA 双模式上传引导构造器（公网原图 URL 如 iTunes 3000px 升格生成 `x_seed` 链接、本地未压缩原图备份下载与建议 Comment），新增 `MusicBrainzCreditSanitizerTest` 验证去括号清洗、`x_seed` 编码与冲突/未核验字段排除通过

## 4. 本地官方 MBID 回填、归档接入与服务编排

- [x] 4.1 实现 `MusicBrainzReleaseBackfillService`：解析校验 MusicBrainz Release URL/UUID、拉取权威 `ReleaseDetail`、按碟号/曲号/标题与时长容差建立严格一对一本地匹配、在 `LibraryWriteGuard` 下原位回填官方 MBID 与缺失发行元数据（绝不覆盖或清空本地既有词曲编标签与内嵌封面），并原子升级 `processed_files` 记录的 `recording_id`/`release_group_id`（保持原监控源 `file_path` 与 `target_file_path` 血缘不分裂），新增 `MusicBrainzReleaseBackfillServiceTest` 验证回填成功、曲目失配拒写、既有词曲编保留与监控血缘不分裂通过
- [x] 4.2 实现 `MusicBrainzContributionService`（独立低优先级单线程异步预检执行器、高画质原图捕获、`enqueueAfterArchive`/`createFromReviewItem`/`recheck`/`updateDraft`/`linkRelease`/`submitAcoustId`/`complete`/`dismiss`），在 `CoverCandidateService`、`RecoveryService`（联网归档完成及缺封面补图完成出口）与 `ApplicationLifecycleManager` 完成装配（保留旧构造兼容），新增 `MusicBrainzContributionServiceTest` 验证非阻塞入队、缺封面补图衔接、Case A 直接回填与完整闭环通过

## 5. Web API、独立工作台界面与国际化

- [x] 5.1 实现 `MbContributionServlet`（`/api/mb-contributions/stats`、`list`、`item`、`create-from-review`、`recheck`、`update-draft`、`link-release`、`submit-acoustid`、`complete`、`dismiss`、`cover`）并挂载到 `WebServer` 可热装配 `SwappableServlet` 槽位（待初始化模式返回 `503 setup.required`），新增 `MbContributionServletTest` 验证鉴权/待初始化守卫、参数校验（400/404）、原图下载防越界与完整 API 契约通过
- [x] 5.2 新增 `src/main/resources/static/contributions.html`（Case A/B/C 分流提示、发行字段微调、动态构造 `POST multipart/form-data` 打开 MusicBrainz Release Editor、贴回 Release 链接一键回填、CAA 双模式上传与原图下载、去所属括号 Works 补录参考卡与一键复制 Edit Note、AcoustID 声纹提交），并在 `index.html`、`review.html`、`covers.html`、`recovery.html`、`records.html`、`shell.js`、`errors.js` 与 `messages_zh_CN.properties`/`messages_en_US.properties` 中补齐侧栏「回馈 MB」入口与计数徽章、设置页 3 项配置及已确认联网条目的手动发起按钮，更新并运行前端/i18n 回归测试通过

## 6. 端到端集成验证

- [x] 6.1 运行全量 `mvn test` 与 `openspec validate "musicbrainz-contribution-loop" --strict`，确认全部单元测试、集成测试与规范校验 0 失败通过

## Workflow follow-up

- 实现完成后提交审查并推送实机验证。
- 实机验证通过后归档变更 `musicbrainz-contribution-loop` 并同步主规范 `openspec/specs/musicbrainz-contribution/spec.md`。
