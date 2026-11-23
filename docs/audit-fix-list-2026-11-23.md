# MusicAutoTagger 审计问题修复清单（2026-11-23）

- **创建日期**：2026-11-23
- **审计基线**：`654e024`（全部 23 项基线与 P0 严重缺陷已完成）
- **来源报告**：`project_audit_report.md`

---

## 进度总览

- [x] **前期基线 + P0（23 / 23）**：已在提交 `654e024` 全部修复并通过 `mvn test -B`（352/352）
- [x] **任务 1（P1 核心管道：封面/歌词 + 识别/写盘）**：`C-1` ~ `C-6`、`A-1` ~ `A-5`（11 大项，2026-11-23 全部完成并通过 `mvn test -B` 363/363，含新增专项回归测试 `P1Task1CorePipelineRegressionTest`）
- [x] **任务 2（P1 数据库、持久化、LLM 与并发配置）**：`D-1` ~ `D-6`（6 大项，2026-11-23 全部完成并通过 `mvn test -B` 370/370，含新增专项回归测试 `P1Task2DataLlmConcurrencyRegressionTest`）
- [ ] **任务 3（P1 前端交互、i18n 与运维部署）**：`U-1` ~ `U-3`、`O-1`（4 大项）
- [ ] **任务 4（P2 中长期架构重构）**：God Classes 拆解、基础设施收敛、前端公共模块抽取（3 大方向）

---

## 任务 1：P1 核心管道 — 封面/歌词 & 识别/多碟/写盘（`C-1`~`C-6`、`A-1`~`A-5`）

### 1.1 封面工作流与歌词子系统（`C-1` ~ `C-6`）

- [x] **C-1：「确认保持无封面」仍隐式重新拉图并嵌入**
  - 增加显式封面策略（`AUTO` / `EXPLICIT` / `NONE`），`confirmedNoCover=true` 时在 `AlbumBatchProcessor` 与 `RecoveryService.archiveLocalTags()` 跳过全部 CAA 与目录兜底查图逻辑
- [x] **C-2：缺封面库扫描与处置多项缺陷**
  - [x] 从支持后缀列表移除 JAudioTagger 不支持的 `.opus`
  - [x] `outputRoot` 下无艺人散曲独立建项，禁止合并为同一专辑覆盖封面
  - [x] 持久化归档输出目录并在库扫描中跳过已 `REJECTED` 及已确认无封面的专辑（含 `STAGED_IMPORT` 场景）
  - [x] 库内补写封面持 `LibraryWriteGuard`
  - [x] `commitDeferred*` 成功后再持久化 `confirmedNoCover=true`
  - [x] `itemLocks` 改用有界分段锁，消除内存泄漏
  - [x] `enqueueLibraryMissingCover` 等入口在锁内二次检查状态，消除扫描取消/并发处置 TOCTOU 竞态
- [x] **C-3：搜封面中断误判、Prompt 冲突与散曲越界**
  - [x] `CoverSearchService` 捕获异常时先检测中断标志，不将取消误判并落盘为 `DONE`
  - [x] `TavilyWebSearchClient.renderEvidence()` 在 `includeImages=true` 时不追加专辑 `candidates`/`needs_second_round` 指令
  - [x] 散曲 `resolveAlbumRoot()` 返回 `null`，禁止越界抓取整个 `monitorDirectory` 下的任意图片
  - [x] 校验 `releaseGroupId` 为合法 UUID 再拼装 CAA 请求
- [x] **C-4：封面画质劣化、非原子缓存与代理不一致**
  - [x] `ImageCompressor` 修复 `Math.ceil(maxDim / 1200.0)` 导致 1201–2400px 封面被强制 2x 邻近子采样腰斩的问题，并对已合规 JPEG 跳过重复有损重编码
  - [x] `CoverCandidateService` 与 `CoverArtCache` 落盘改用同目录临时文件原子重命名，重入/命中时刷新 mtime 防止提前过期
  - [x] `UntrustedImageFetcher` 遵循 `MusicConfig` 代理配置，与 iTunes API 代理策略保持一致
- [x] **C-5：连接池嵌套死锁 & 封面回填污染同名专辑**
  - [x] `CoverArtCache` 修复持 SQLite 连接读取 `ResultSet` 期间再申连接删除引发的连接池死锁
  - [x] `CoverBackfillService` 按 `(album, artist)` 匹配并校验艺人一致，避免污染 `"Greatest Hits"` 等同名专辑
  - [x] `CoverArtService.folderCoverCache` 改为有界 LRU 缓存
- [x] **C-6：歌词回填在非 root/NAS 必败、解析崩溃与熔断器错误**
  - [x] `LyricsOnlyWriter` 仅在属主变化时尽力恢复 owner/group 并兼容 `EPERM`/不支持异常；兼容外链封面 `getBinaryData() == null` 防 NPE
  - [x] `LyricsMatcher.validSynced()` 限制正则分钟位数并捕获 `NumberFormatException`，同时支持多时间戳副歌 LRC 行（如 `[00:10.00][01:20.00]...`）
  - [x] `LyricsService` 熔断冷却后重置失败计数、避免 5xx 缩短 429 `Retry-After`、JSON 解析成功后再重置健康状态、合并为单实例调度器并在关闭时释放
  - [x] `LyricsNegativeCache` 为 `ATOMIC_MOVE` 增加 `AtomicMoveNotSupportedException` 降级

### 1.2 音频识别、多碟处理与标签写入（`A-1` ~ `A-5`）

- [x] **A-1：多碟专辑 5 处联动缺陷**
  - [x] `FolderAlbumCache` 采样器键改用相对路径，防止 `CD2/01.flac` 覆盖 `CD1/01.flac`
  - [x] `CueSplitService` 爬到专辑根目录后一并查找碟片子目录（如 `CD1/`、`Disc 1/`）下的 CUE
  - [x] `QuickScanService` 使用专辑根目录名而非 `"Disc 1"` 搜索
  - [x] `FailedFileHandler` 从碟片子目录加载封面后同步更新 `hasFolderCover`
  - [x] `AudioFileOrdering` 仅在目录名匹配 `isDiscFolderName` 时提取碟号，不把 `"2020 - Album"` 年份当碟号
- [x] **A-2：匹配算法误判**
  - [x] `DurationSequenceService.parseMediaFormat()` 将 `sacd` 判断前移至 `cd` 之前
  - [x] `DurationSequenceService` 先过滤满足 DTW 门槛（`>= 0.75`）的候选再按综合分排序，防止低 DTW 候选遮蔽真候选
  - [x] `MetadataUtils.hasVersionQualifier()` 改用词边界正则匹配，避免 `Alive`/`Believe`/`Demon`/`Instead`/`Credit` 误判
- [x] **A-3：置信度钳位、`REJECT` 误入库与封面缓存污染**
  - [x] `ConfidenceModel.map()` 当 `raw < inLow` 时向下衰减而非向上钳位到 `0.70`/`0.55`/`0.35`
  - [x] `AlbumBatchProcessor` 拦截 `Decision.REJECT` 并路由至失败处置流程而非直接入库
  - [x] `AlbumBatchProcessor.writeWithFolderLock()` 不把单曲旧封面当 `explicitCoverArt` 传入，恢复 CAA 查询并防污染专辑封面缓存
  - [x] `AudioFileProcessorService` QuickScan 锁定首曲时补齐 `lockedReleaseId` 赋值
- [x] **A-4：标签写入与文件操作隐患**
  - [x] `TagWriterService.extractTrackPrefix()` 先去除扩展名，修复 `"01.flac"` 截出 `"01.f"` 及 `"01."` 越界异常
  - [x] `TagWriterService.refreshAlbumArtist()` 补齐 `AtomicMoveNotSupportedException` 降级（注：`publish()` 已在 `654e024` 补齐）
  - [x] `updateTagsOnExistingFile()` / `writeLocalArchiveTags()` / `embedFolderCover()` 改为临时文件原子替换
  - [x] 校验 `FlacId3Stripper` 的 `FAILED` 返回值，并在 `FlacId3Stripper` 中缩短临时文件名前缀避免超 255 字节
  - [x] `TagWriterService` 默认文件/目录权限从 `0777/0666` 收紧为 `0755/0644`
  - [x] `AudioFormatNormalizer.normalizeToTargetIfNeeded()` 严禁在持有全局 `LibraryWriteGuard` 期间执行 ffmpeg
- [x] **A-5：格式漏配、符号链接误删与查询转义缺陷**
  - [x] `QuickScanService`、`FolderAlbumCache`、`FailedFileHandler` 等统一使用 `FileSystemUtils.isMusicFile()`（补齐 `.ogg` 及大小写不敏感匹配）
  - [x] `FailedFileHandler.deleteDirectory()` 改用 `NOFOLLOW_LINKS` 防止跟随软链接删外部文件，且 `failedDirectory` 保留相对路径避免同名失败专辑被静默丢弃
  - [x] `MusicBrainzClient.searchAlbum()` 统一转义 Lucene 特殊字符（引号/反斜杠等），并在 `resolveCreditedArtistText` / `AlbumArtistPolicy` 保留末尾 `joinphrase`

---

## 任务 2：P1 数据库、持久化、LLM 与并发配置（`D-1` ~ `D-6`）

- [x] **D-1：MySQL 建表失败与大小写路径碰撞**
  - `schema.sql` 修复 `file_path VARCHAR(1000) UNIQUE` 超 InnoDB 3072B 上限及 `utf8mb4_unicode_ci` 路径大小写碰撞（改用 `VARCHAR(768) COLLATE utf8mb4_bin` 并补充 `folder_path` 索引列）；`DatabaseService` 移除硬编码 `Asia/Shanghai` 时区并校验 JDBC 连接参数防注入；SQLite/MySQL 建表或初始化失败时关闭 `HikariDataSource`
- [x] **D-2：记录库前缀误伤、Unicode 搜索失效与文件模式损坏**
  - `ProcessedFileLogger` 前缀匹配补路径分隔符并改用 `codePointCount` 修正 UTF-16/码点错位；SQLite 注册 Unicode `LOWER()` UDF 恢复非 ASCII 大小写无关搜索；`findAlbumsMissingReleaseGroupId()` SQL 过滤哨兵 ID；批删包事务；File 模式统一 UTF-8、转义分隔符/换行并改用 `ReentrantReadWriteLock` 读写锁
- [x] **D-3：文件夹视图内存爆炸与重识别竞态**
  - 为 `processed_files` 增加持久化 `folder_path` 索引列并自动分批回填，文件夹视图翻页/展开/批量操作/导出全部改走 SQL 分页，不再全表 `loadAllRecords()`；暂存重识别同步删记录并追踪在途 `runningStagedTasks` 防重复提交；`isUnderReview()` 先查散曲自身路径；区分输出库单层艺人目录散曲
- [x] **D-4：注册表偶发清空与 O(N²) 磁盘写放大**
  - `AutoArchiveRegistry` 与 `ReidentifyReplacementRegistry` 区分文件不存在与读取/解析异常（异常拒绝覆写）；新增批量 `recordAll()` 一次落盘并在 `ProcessedRecordService`/`RecoveryService` 接入；补父目录空指针检查与 `finally` `.tmp` 清理
- [x] **D-5：LLM 解析崩溃与异常缓存**
  - [x] ① `LlmModelCatalog` 改用 `LlmClient.httpClientBuilder(config)` 并实现 `AutoCloseable`，在 `LlmProviderServlet.destroy()` 显式关闭释放连接池
  - [x] ② `LlmClient.complete()` 遇到 `interrupted` 立即上抛，不继续轮询其余端点
  - [x] ③ `NativeWebSearchClient`、`LlmProvider`、`OnlineIdentificationService` 所有 JSON 字段增加空安全与类型校验防 `ClassCastException`
  - [x] ④ `OnlineIdentificationService` 二轮搜索合并一二轮引用池，二轮引用一轮 HIGH 来源可通过可信度门槛
  - [x] ⑤ `LlmArtistSplitter` 捕获瞬时 `LlmException` 不写入缓存
- [x] **D-6：并发竞态、热更新脑裂与 Jetty 阻塞**
  - `ReviewItem` 嵌套集合升级为线程安全实现（`CopyOnWriteArrayList`/`ConcurrentHashMap`）防并发 `CME`，`ReviewQueueService` 为列表/回调/Web 读接口提供隔离深拷贝快照（`getSnapshot`/`list`/`listByKinds`），`LockedReleaseTrackResolver` 使用 `identified.copy()` 不原地破坏原元数据；`ReviewResolutionService` 拆为 256 路条目级分段锁并将 MusicBrainz/LLM 网络 I/O 移出锁外；`OnlineSearchQueueService` 按封面/专辑类型隔离去重、升格同步更新目标字段、`listRecoveryJobViews()` 锁内拍摄不可变快照并补充 `volatile`；`ConfigServlet` 需重启字段仅落盘并记录待重启视图不篡改活态 `MusicConfig`、修复 `tavilyIncludeDomains` 清空回弹；`MusicConfig` 容错解析含空格 `monitor.scanInterval` 并在 `saveToFile()` 补写 `llm.webSearch.maxConcurrency`

---

## 任务 3：P1 前端交互、i18n 与运维部署（`U-1` ~ `U-3`、`O-1`）

- [ ] **U-1：高频整页重绘与切项竞态误操作**
  - `review.html` 改为局部更新搜索进度条；`covers.html` 增加单调递增 `reqSeq` 与 `state.currentId === id` 校验并重置 `uploadInput.value`
- [ ] **U-2：错误码失配与 i18n 缺陷**
  - 对齐 `covers.html` 6 个后端错误码；清理中英文重复 `config.scan.interval`；修复 `I18nUtil` 线程发布与含换行符占位符匹配；清理英文模式硬编码中文
- [ ] **U-3：会话过期失联、轮询泄漏与高频扫盘**
  - 统一处理 401 跳转与停止轮询；`SearchQueue` 修复首次加载误全量刷新；页面隐藏时暂停轮询并新增轻量角标统计接口替代 `/api/recovery/list` 扫盘；修复 `index.html` 改密提示位置、清空日志回弹与按钮防连点
- [ ] **O-1：部署与打包缺陷**
  - `docker-compose.yml` 补全 `8080:8080` 端口映射；`entrypoint.sh` 仅 `chown` 自身数据目录并用 `gosu`/`setpriv` 替代 `su -c`；支持 `web.port` 配置；移除或迁移 `src/main/java/TestAcoustIDMeta.java`；`ControlServlet` 重启前触发优雅关机

---

## 任务 4：P2 中长期架构演进（按需分步实施）

- [ ] **P2-1：拆解超大型 God Classes 与消除循环依赖**（`MusicBrainzClient`、`RecoveryService`、`FolderAlbumCache`、`TagWriterService`、`ProcessedFileLogger`、`AudioFileProcessorService`、`MusicConfig`）
- [ ] **P2-2：基础设施与依赖收敛**（统一 Jackson/Gson、统一共享 HTTP 客户端工厂、`review-queue.json` 历史归档、流式 CSV 导出）
- [ ] **P2-3：前端工程化去重**（从 5 个内联 HTML 页面抽离共享 `static/api.js`、`static/ui.js`、`static/errors.js`）
