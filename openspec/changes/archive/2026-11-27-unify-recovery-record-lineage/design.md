# Design

## Context

详见 `proposal.md` 的背景与目标。当前系统在正常监控流程中已经全面采用「单条主记录」模型（`processed_files.file_path` 指向监控目录源文件，`target_file_path` 指向输出音乐库归档路径），包括：
- `FileMonitorService` 依赖 `file_path` 判断监控目录文件是否已处理；
- `ProcessedRecordService.reidentify()` 在 `status == SUCCESS && targetFilePath != null` 时登记 `ReidentifyReplacementRegistry`（`source -> oldTarget`），并在监控文件缺失时基于 `targetFilePath` 走 `submitStagedReidentify`；
- `DashboardServlet` 与 `ProcessedRecordService.pickCover()` 优先读取 `target_file_path` 展示归档封面与推导专辑艺术家。

唯独经由隔离目录（`failedDirectory` / `partialDirectory`）恢复入库时，因缺乏 `isolatedCopy -> originalMonitorFile` 的血缘记录，所有恢复出口（`RecoveryService`、`CoverMissingService`）只能按 `isTemporarySourceItem(item)` 回退写入 `file_path = finalTarget`，导致原监控源的 `FAILED` 记录永久滞留并与新 `SUCCESS` 记录分裂并存；同时 `ReviewResolutionService` 遗漏了来自隔离目录的 `recoverySourcePath` 回收与记录迁移，`RecoveryService` 的 `LOCAL_ARTIST_MATCH` 分支遗漏了处理记录写入。

存储约束：项目同时支持 SQLite（默认）、MySQL 与文本文件日志三种 `ProcessedFileLogger` 模式，现有辅助注册表（`AutoArchiveRegistry`、`ReidentifyReplacementRegistry`）均以原子写 JSON 存于 `review-queue.json` 同目录。

## Goals / Non-Goals

**Goals:**
- 建立轻量、持久化、跨 SQLite/MySQL/文件模式通用的隔离副本血缘追踪（`RecoveryOriginRegistry`），完整覆盖 `failed/`、`partial/`、重试上限转失败、单文件恢复工作区及回收站往返。
- 将全部 6 个恢复入库出口收敛至同一个记录回写协作者，确保正常场景下 `processed_files` 始终保持单条主记录。
- 确保监控记录重新识别失败时保留 `target_file_path` 与替换映射，重识别成功时无缝清理旧归档；保持「忽略隔离副本」行为简洁。
- 在启动期（监控扫描启动前）执行一次严格、幂等、纯记录层的历史分裂记录对账合并，无损修复升级前已分裂的存量数据。
- 补齐处理记录页针对 `ONLINE_SEARCH` / `LOCAL_TAGS` 来源重识别的预览统计与清晰提示。

**Non-Goals:**
- 不修改常规监控自动入库（`AudioFileProcessorService` / `AtomicAudioPublisher`）的无交互冲突策略，也不在每次常规监控写入时引入全库 MD5 扫描。
- 不在「重新识别」页点「忽略（dismiss）」时维护历史 `SUCCESS` 记录快照并自动回滚状态（用户确认保持简洁，仅保留原归档文件与 `target_file_path`）。
- 不修改 `processed_files` 数据库表结构（无需新增列或 DDL 迁移）。
- 历史对账绝不删除、移动或修改任何磁盘上的音频文件。

## Decisions

### 1. 血缘持久化方案：独立 JSON 注册表 `RecoveryOriginRegistry`

- **选择**：在 `review-queue.json` 同目录新增 `recovery-origins.json`（由 `RecoveryOriginRegistry` 管理，读写模式与 `ReidentifyReplacementRegistry` / `AutoArchiveRegistry` 一致：同步锁 + 临时文件原子替换 + 读取异常拒绝覆写以防数据丢失）。
- **数据结构**：`Map<String, Entry>`，键为规范化后的隔离文件绝对路径，值为 `{ originPath: "<原监控文件绝对路径>" }`；提供 `record`、`recordAll`（批量一次读写避免 O(N^2) 写放大）、`recordAlias`（支持 `single-<jobId>` 与路径链式传递）、`findOrigin`、`remove` / `removeUnder`（提交入回收站或清理时移除条目，避免 JSON 无限增长）。
- **备选方案与取舍**：
  - *备选 A：给 `processed_files` 加新列或新建数据库表* -> 无法在纯文件日志模式下复用，且 `failed/` 副本本身不一定要先写入 `processed_files`，增加 SQLite/MySQL 双 DDL 维护成本。
  - *备选 B：仅靠 `failed/` 相对路径反推，不落盘映射* -> 无法覆盖 `partial/`（仅保留最后一级专辑目录名，且音频降级会把 `.wav/.ape` 转成 `.flac`）以及 `FileMonitorService.moveToFailedDirectory` 撞名追加 `_timestamp` 的情况。
- **回收站透传**：`RecoveryTrashService.moveToTrash()` 入站时查询该隔离路径（单文件或目录内所有音频）在 `RecoveryOriginRegistry` 中的映射，写入 `<trashEntry>.json` 的可选字段 `origins`（`relativePathInsideEntry -> originPath`）并从活态 `recovery-origins.json` 移除；`restoreFromTrash()` 还原时若 manifest 含 `origins`，按还原后的新绝对路径写回 `RecoveryOriginRegistry`。旧 manifest 无此字段时静默跳过，完全向后兼容。

### 2. 存量隔离副本（升级前已在 `failed/` 或 `partial/`）的保守兜底反推

当恢复提交时 `RecoveryOriginRegistry.findOrigin(isolatedFile)` 未命中，执行只读兜底推导（仅针对 `monitorRoot` 存在且可读的场景）：
1. **来自 `failedDirectory`**：按 `failedRoot.relativize(isolatedFile)` 拼出 `candidate = monitorRoot.resolve(relative)`；若 `candidate` 存在或 `processed_files` 中存在对应 `FAILED` 行，且（文件在磁盘时）FLAC 校验 `ArchiveDuplicateDetector.flacAudioMd5` 一致（非 FLAC 校验文件大小一致），则认定命中 `candidate`。
2. **来自 `partialDirectory`**：仅对文件夹级/单文件在 `processed_files` 中查询 `monitorRoot` 下的 `FAILED` 记录：按目录名匹配（或散落文件按同相对路径/去扩展名同 stem 匹配），且候选监控目录唯一、同时文件满足 FLAC MD5 一致（或未降级时大小一致）时才绑定；任何多候选歧义直接返回空，回退为记最终输出路径。

### 3. 全 6 个恢复出口的统一回写协作者

在 `RecoveryService` 中提供统一公开/包内方法（例如 `recordRecoveredFiles(...)` 与 `retireRecoveryAndFinalize(...)`），并让 `CoverMissingService` 与 `ReviewResolutionService`（通过 `ApplicationLifecycleManager` 注入 `recoveryService`）复用，消除散落重复的 `finalize*` 逻辑：

```text
resolveRecordTarget(isolatedFile, finalOutputFile):
  origin = recoveryOriginRegistry.findOrigin(isolatedFile)
           .or(() -> inferLegacyOrigin(isolatedFile, sourceType))
  if origin == null:
      write processed_files(file_path = finalOutputFile, ..., target = finalOutputFile)
      return

  monitorRoot = configured monitorDirectory (normalized)
  if monitorRoot is accessible directory:
      if Files.isRegularFile(origin):
          // 原监控文件仍在：原位 UPSERT 升级旧 FAILED -> SUCCESS
          write processed_files(file_path = origin, ..., target = finalOutputFile)
      else:
          // 原监控文件已被用户删除：清理旧 FAILED 行，改记最终归档路径
          removeProcessedRecord(origin)
          write processed_files(file_path = finalOutputFile, ..., target = finalOutputFile)
  else:
      // 监控盘暂未挂载：保留以 origin 为键写回，避免重新挂载后重扫
      write processed_files(file_path = origin, ..., target = finalOutputFile)
```

需要接入该统一规则的 6 个出口：
1. `RecoveryService.confirmOnlineCandidateInternal()`（`ONLINE_SEARCH`）：提交成功并落盘 `COMMITTED` 后调用统一回写；顺带删除可能存在的临时路径记录（仅当 `isTemporarySourceItem` 时生效；普通监控目录待确认条目仍直接写原监控 `file`）。
2. `RecoveryService.archiveLocalTags()`（`LOCAL_TAGS`）：同上。
3. `RecoveryService.finalizeCommittedRecords()`（隔离目录 `REIDENTIFY` 直接成功，以及 `submitStagedReidentify`）：
   - 区分来源：`submitStagedReidentify`（来自处理记录页的已删源归档重识别）维持迁移到 `finalTarget`；来自 `failed/`/`partial/` 的 `runJob(REIDENTIFY)` 走统一血缘回写。
4. `CoverMissingService.finalizeTemporarySourceRecords()`：当 `item.getRecoverySourceType() != null` 时走统一血缘回写（同时移除隔离副本自身的临时处理记录）；当仅为 `reprocess-*` 时保持迁移至 `target`。
5. `ReviewResolutionService.finish()`：当前 `finalizeReprocessSourceRecords(item)` 仅判断 `reviewQueue.findReprocessRoot(item) != null`，补充处理 `item.getRecoverySourceType() != null && item.getRecoverySourcePath() != null`：
   - 迁移/回写处理记录（移除隔离路径或 `single-*` 上的记录，按统一规则回写到原监控源或最终输出路径）；
   - 调用 `recoveryService.retireRecoveryCopyIfNeeded(item)` 将原隔离副本移入回收站（同时处理 `single-<jobId>` 工作目录清理），彻底闭环该路径。
6. `RecoveryService.runJob(LOCAL_ARTIST_MATCH)`：扩展 `FailedFileHandler.retryPartialWithLlm()` 返回移动映射列表（`List<MovedArchiveFile>`，含原 `partialFile`、目标 `finalFile` 与读取到的元数据），成功移动后在 `RecoveryService` 中对每个文件调用统一血缘回写（`recordingId = "LOCAL_TAGS"` 或 `"LLM_MATCH"`——考虑到现有前端与统计对 `LOCAL_TAGS` 已有完整徽章与 `NON_MB_RECORDING_IDS` 放行语义，推荐复用 `"LOCAL_TAGS"`，保持前端/导出/仪表板完全一致，无需新增哨兵值）。

**失败回写时保留旧归档路径**：
- `FailedFileHandler.markAlbumAsProcessed()` 与 `handleLooseFileFailed()` 在写入 `"UNKNOWN"` 失败记录时，先查询 `replacementRegistry.findOldTarget(audioFile)`，若存在则作为 `targetFilePath` 传入 `processedLogger.markFileAsProcessed(...)`（与 `RecoveryService.recordStagedReidentifyFailure()` 一致）。
- 注意 `reprocess-*` 暂存路径若失败再次触发 `FailedFileHandler`，不把 `reprocess-*` 当作监控源写入 `RecoveryOriginRegistry`（写入前校验 `source` 位于 `monitorRoot` 下，或沿既有 origin 链透传）。

### 4. 启动期历史分裂记录幂等对账（`RecoveryRecordReconciler`）

- **挂载位置**：在 `ApplicationLifecycleManager.initializeServices()` 中，于 `recoveryService` 创建之后、`servicesInitialized = true` 及 `fileMonitor.start()` 之前同步执行（仅对监控目录内 `FAILED` 记录集操作，正常规模仅数十条，毫秒级完成；任何单条/IO 异常仅记日志告警，绝不阻断服务启动）。
- **两级严格证据匹配（均要求 100% 确定，宁缺毋滥）**：
  1. **构建候选集**：
     - 左侧：从 `processedLogger` 查询 `status == FAILED` 且 `file_path` 位于 `monitorRoot` 内的记录，按文件夹聚合；过滤掉 `monitorRoot` 不可访问的情况。
     - 右侧：从 `processedLogger` 查询 `status == SUCCESS` 且 `file_path` 位于 `outputRoot` 内的记录，按文件夹聚合。
     - 若左侧或右侧为空，直接快速返回。
  2. **证据链 1（`review-queue.json` + `review-queue-archive.jsonl` 已确认恢复条目）**：
     - 读取状态为 `CONFIRMED` 且 `recoverySourceType != null`、`recoverySourcePath != null`、`committedOutputPaths` 非空的条目。
     - 将 `recoverySourcePath` 映射为期望的监控目录路径（`FAILED` 按相对路径直拼；`PARTIAL` 按目录名唯一匹配左侧失败文件夹），并找到 `committedOutputPaths` 对应的右侧成功文件夹。
     - 对两文件夹内文件逐一配对：优先按 FLAC STREAMINFO MD5 精确配对；若均为非 FLAC 或无 MD5，则仅当双方文件数完全相等且按曲目排序/文件名可唯一建立一对一映射时配对成功。
  3. **证据链 2（整夹 FLAC STREAMINFO MD5 全量精确匹配）**：
     - 对证据链 1 未处理的左侧失败文件夹：仅当文件夹内**所有**失败文件均在磁盘存在、均为有效非零 MD5 的 FLAC、且每个文件在同一个右侧输出文件夹中匹配到**唯一**相同 MD5 的 `SUCCESS` 记录，且左右曲目数完全一致（排除同一首曲子在多张专辑重复出现导致的跨专辑歧义：若同一批 MD5 命中超过 1 个右侧输出文件夹，则视为歧义跳过）。
  4. **原子合并动作（纯记录层）**：
     - 对配对成功的每一对 `(monitorFailedRec, outputSuccessRec)`：
       - 若监控源文件仍在磁盘：以 `monitorFailedRec.filePath` 写入 `SUCCESS`（继承 `outputSuccessRec` 的 `recordingId`、`artist`、`title`、`album`、`releaseGroupId`、原始 `processedTime` 与 `targetFilePath = outputSuccessRec.targetFilePath != null ? outputSuccessRec.targetFilePath : outputSuccessRec.filePath`），随后删除 `outputSuccessRec.filePath` 的冗余行。
       - 若监控源文件已不在磁盘：仅删除 `monitorFailedRec.filePath` 的失效 `FAILED` 行，保留 `outputSuccessRec`。
     - 合并日志：仅在合并数量 > 0 时打印摘要（如 `启动对账已合并 X 个历史分裂专辑（Y 条处理记录）`）。
- **保持原始 `processed_time`**：为避免启动对账把几个月前的历史专辑全部顶到「最近处理」列表最前面，`ProcessedFileLogger` 提供允许保留指定 `processedTime` 的更新重载（或在 SQL 中沿用 `outputSuccessRec.processedTime`），确保「处理记录」与「概览-最近整理专辑」的时间排序完全自然。

### 5. 前端预览与提示增强

- `ProcessedRecordService.preview(targets)` 新增统计字段 `nonMbConfirmedCount`（统计 `status == SUCCESS` 且 `recordingId` 为 `"ONLINE_SEARCH"` 或 `"LOCAL_TAGS"` 的记录数），完全向后兼容。
- `records.html` 在 `kind === 'reidentify'` 确认弹窗中：
  - 当 `preview.nonMbConfirmedCount > 0` 时追加一条醒目提示（`warn` 样式）：说明其中 N 个文件曾由联网搜索或 AI 辅助入库，普通重新识别仅执行 MusicBrainz 识别，未收录时保留原归档并转入「重新识别」列表。
  - 更新 `records.confirm.archive.note`（中英文），准确说明旧归档在重新识别成功前不会被删除，成功后会自动替换并清理旧路径副本。

## Risks / Trade-offs

- **[风险] 对账误合并同音频不同版本（例如同一首单曲既在单曲盘又在正规专辑盘中出现）**
  -> **缓解**：证据链 2 强制要求「整夹全部曲目均为有效 FLAC MD5、左右曲目数完全相等、且仅命中**唯一**一个输出专辑目录」，命中超过 1 个候选输出目录一律跳过不合并。
- **[风险] 文件日志模式下历史对账需要重写日志文件，性能与原子性**
  -> **缓解**：只在检测到实际需要合并的对子时才触发写入；通过 `ProcessedFileLogger` 既有的 `fileLock` 写锁与临时文件原子 rename 完成，不会产生半截文件。
- **[风险] 监控目录通过 NAS/SMB 挂载时启动瞬间暂不可达**
  -> **缓解**：对账与恢复回写前都先校验 `Files.isDirectory(monitorRoot)`；监控根目录不可访问时直接跳过“原文件已删除”的删除分支并跳过启动对账，绝不因网络挂载抖动误删记录。
- **[风险] 联网/AI 确认在 `COMMITTED` 落盘后、回写处理记录前发生进程崩溃**
  -> **缓解**：`RecoveryService.finishCommittedRecovery()` 是启动重入点，恢复收尾可与血缘清理协调：只有处理记录回写完成后才移除 `RecoveryOriginRegistry` 条目，若崩溃重启且记录尚未合并，启动期的 `RecoveryRecordReconciler` 仍可依据 `review-queue.json` 兜底收敛。
- **[取舍] 重识别失败后用户在「重新识别」页点忽略，记录保持 `FAILED` 而不回滚为原 `SUCCESS`**
  -> 避免引入额外的「重识别前记录快照」持久化与状态机复杂度；原归档文件在音乐库中保持完好，且失败记录保留 `target_file_path` 可追溯。

## Migration Plan

1. **零侵入升级**：无需人工执行 SQL 脚本或修改 `config.properties`；首次启动自动创建空的 `recovery-origins.json`（首次写入时生成），并自动运行一次幂等历史分裂记录对账。
2. **回滚策略**：本变更不修改表结构且不删除任何磁盘音频文件；若需回退旧版，删除 `recovery-origins.json` 即可完全回到原有代码行为，已合并的数据库行本身符合旧版既有单条主记录格式，向下完全兼容。
