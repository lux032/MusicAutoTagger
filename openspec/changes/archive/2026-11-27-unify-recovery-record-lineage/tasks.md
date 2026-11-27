# Tasks

## 1. 隔离副本血缘追踪与回收站透传

- [x] 1.1 新增 `RecoveryOriginRegistry`（持久化于 `review-queue.json` 同目录的 `recovery-origins.json`），支持 `record`、`recordAll`、`recordAlias`、`findOrigin`、`findOriginsUnder`、`remove` 与 `removeUnder`（原子写盘 + 损坏文件拒绝覆写，仅记录位于监控目录内或经既有血缘透传的源路径，避免登记 `reprocess-*` 暂存路径）；编写 `RecoveryOriginRegistryTest` 验证批量读写、别名传递、崩溃防覆写与清理并运行 `mvn test -Dtest=RecoveryOriginRegistryTest` 通过
- [x] 1.2 在全部 4 个隔离复制入口写入血缘映射：`FailedFileHandler` 的 `copyFailedFileToFailedDirectory` / `copyFailedFolderToFailedDirectory` / `handlePartialRecognitionFile` / `handlePartialRecognitionAlbum`（覆盖部分识别扁平目录名及音频降级扩展名变更）、`FileMonitorService.moveToFailedDirectory`（覆盖撞名追加时间戳后缀），以及 `RecoveryService.runJob` 创建 `single-<jobId>` 单文件工作区时透传血缘；在对应单元测试中验证 4 类入口均准确记录 `隔离副本 -> 原监控文件` 映射并运行对应 `mvn test` 通过
- [x] 1.3 扩展 `RecoveryTrashService` 的 `moveToTrash` 与 `restoreFromTrash`：隔离副本入回收站时把关联血缘写入 manifest 的可选 `origins` 字段并清理活态映射，从回收站还原时按还原路径自动写回 `RecoveryOriginRegistry`（向后兼容无 `origins` 字段的旧 manifest）；补充回收站往返测试并运行 `mvn test -Dtest=RecoveryTrashService*Test`（或对应回收站测试）通过

## 2. 统一全部 6 个恢复入库出口回写与失败保底

- [x] 2.1 在 `RecoveryService` 中实现统一的隔离副本恢复记录回写协作者与存量隔离副本保守兜底反推（`failed/` 相对路径 + 大小/FLAC MD5 校验、`partial/` 唯一失败目录名 + 大小/FLAC MD5 校验，存在歧义时不强绑）：按「监控根目录可访问且原监控文件存在 -> UPSERT 升级原监控行」、「监控根目录可访问且原监控文件已删 -> 删旧 `FAILED` 行并记最终归档路径」、「监控根目录不可访问 -> 保留监控源路径防重扫」、「无监控血缘 -> 记最终归档路径」执行；编写单元测试覆盖上述 4 种分支与存量兜底歧义场景并运行对应 `mvn test` 通过
- [x] 2.2 将 `RecoveryService` 内的 `confirmOnlineCandidateInternal`（`ONLINE_SEARCH`）、`archiveLocalTags`（`LOCAL_TAGS`）、`runJob` 的 `REIDENTIFY` 直接提交与 `LOCAL_ARTIST_MATCH`（扩展 `FailedFileHandler.retryPartialWithLlm` 返回移动文件与元数据映射并复用 `LOCAL_TAGS` 哨兵写入处理记录）全部接入统一回写协作者，确保仅保留单条 `SUCCESS` 记录；补充端到端测试验证恢复后原监控 `FAILED` 记录升级为 `SUCCESS`、再次重识别成功可自动替换清理旧归档且不留双份，运行 `mvn test -Dtest=RecoveryService*Test` 通过
- [x] 2.3 接入 `CoverMissingService.finalizeTemporarySourceRecords` 与 `ReviewResolutionService.finish`（在 `ApplicationLifecycleManager` 注入 `recoveryService`）：当待确认/缺封面条目带有 `recoverySourceType` 与 `recoverySourcePath` 时，统一通过回写协作者更新原监控记录，并在 `ReviewResolutionService` 补齐隔离副本移入回收站及 `single-<jobId>` 临时目录清理；在缺封面与待确认集成测试中验证全流程单条记录收敛与隔离副本回收，运行对应 `mvn test` 通过
- [x] 2.4 完善重识别失败保底：在 `FailedFileHandler.markAlbumAsProcessed` 与 `handleLooseFileFailed` 写入失败记录时，查询 `ReidentifyReplacementRegistry.findOldTarget(audioFile)` 并将非空旧归档路径作为 `targetFilePath` 传入 `processedLogger.markFileAsProcessed`，确保已归档监控记录重识别失败后原归档不被删除且 `target_file_path` 不丢失；补充测试验证「已恢复记录 -> 重识别失败 -> 原归档与 `target_file_path` 保留 -> 再次恢复自动替换旧归档」并通过 `mvn test`

## 3. 启动期历史分裂处理记录幂等对账

- [x] 3.1 在 `ProcessedFileLogger` 中新增支持指定既有 `processedTime` 的记录写入/更新重载（兼容 SQLite、MySQL 与文本文件日志三种模式），确保历史对账合并保留原归档完成时间、不扰乱处理记录与概览页排序；编写单元测试覆盖数据库模式与文件模式的时间保留与幂等更新，运行 `mvn test -Dtest=ProcessedFileLogger*Test` 通过
- [x] 3.2 新增 `RecoveryRecordReconciler` 并在 `ApplicationLifecycleManager.initializeServices()` 中于 `fileMonitor.start()` 之前自动执行：实现两级严格证据匹配（证据 1：`review-queue.json` 与 `review-queue-archive.jsonl` 的 `CONFIRMED` 恢复记录；证据 2：监控目录 `FAILED` 文件夹与输出目录 `SUCCESS` 文件夹整夹逐曲 FLAC STREAMINFO MD5 精确一一匹配且唯一命中），只合并处理记录行且绝不删除或移动任何磁盘文件，监控根目录不可访问时安全跳过并记录合并汇总日志；编写 `RecoveryRecordReconcilerTest` 覆盖证据 1、证据 2、已删源文件清理、多目录歧义跳过、重复执行幂等与异常隔离，运行 `mvn test -Dtest=RecoveryRecordReconcilerTest` 通过

## 4. 处理记录页预览统计与确认提示

- [x] 4.1 扩展 `ProcessedRecordService.preview(targets)` 返回向后兼容的 `nonMbConfirmedCount`（统计 `SUCCESS` 且 `recordingId` 为 `ONLINE_SEARCH` 或 `LOCAL_TAGS` 的记录数）；补充 `ProcessedRecordServiceTest` 验证文件夹视图与文件视图下 `nonMbConfirmedCount` 统计准确并运行 `mvn test -Dtest=ProcessedRecordServiceTest` 通过
- [x] 4.2 更新 `records.html` 的「重新识别」确认弹窗与 `messages_zh_CN.properties` / `messages_en_US.properties`：当 `preview.nonMbConfirmedCount > 0` 时展示醒目提示（说明普通重识别仅走 MusicBrainz，未收录时保留原归档并转入「重新识别」列表），同时更新 `records.confirm.archive.note` 准确描述旧归档在重识别成功后自动替换清理的行为；核对中英文 i18n 键一一对应且页面脚本无语法错误

## 5. 全链路回归验证

- [x] 5.1 运行完整测试套件 `mvn test`，确认所有新增与既有单元测试、集成测试全部通过且无回归
