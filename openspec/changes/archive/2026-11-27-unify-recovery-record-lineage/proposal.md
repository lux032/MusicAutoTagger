# Proposal

## Why

当前系统在常规自动识别时采用「单条主记录」模型（`file_path` 为监控目录源文件，`target_file_path` 为输出库归档路径），但当专辑首次识别失败被复制到隔离目录（`failed/` 或 `partial/`）后，系统没有记录「隔离副本 -> 监控目录原文件」的血缘映射。导致用户通过联网搜索、AI 辅助入库或隔离目录重新识别将其成功入库后，处理记录中会同时残留监控目录的旧 `FAILED` 行与输出库的新 `SUCCESS` 行；用户随后对旧失败记录点击「重新识别」会绕过替换追踪与重复保护，引发静默覆盖已入库文件、产生双份重复文件/目录或重新制造失败副本，直接点击「删除记录」又会导致下次重启/恢复监控重新扫描原文件。

## What Changes

- 引入隔离副本血缘持久化（`RecoveryOriginRegistry`）：
  - 在文件进入 `failed/`、`partial/`、重试上限转失败及单文件重识别临时工作区时，持久化记录 `隔离副本路径 -> 监控目录原始源文件路径` 的映射；隔离副本移入回收站与从回收站还原时同步透传该映射，避免还原后血缘丢失。
  - 针对升级前已存在于 `failed/`、`partial/` 的存量隔离副本，提供基于相对路径、文件大小与 FLAC STREAMINFO MD5 的保守兜底反推（有歧义时不强绑）。
- 统一收口全部 6 个恢复入库出口的处理记录回写：
  - 联网确认（`ONLINE_SEARCH`）、AI 辅助入库/本地标签入库（`LOCAL_TAGS`）、隔离目录完整重新识别直接成功、隔离目录流转「缺少封面」后补图或无封面入库、隔离目录重识别入「待确认」后的 MB 确认/未收录归档（同时补齐该路径遗漏的隔离副本移入回收站），以及部分识别「LLM 辅助艺术家匹配」（补齐原本缺失的处理记录写入）。
  - 统一回写策略：当监控目录可访问且原监控文件仍存在时，原地将旧 `FAILED` 记录升级为 `SUCCESS` 并写入 `target_file_path`；当监控目录可访问但原监控文件已被用户删除时，清理旧 `FAILED` 行并以最终归档路径写入单条 `SUCCESS` 记录；无可靠监控来源时维持以最终归档路径记账。
- 完善重识别失败保底与确认交互：
  - 监控目录已归档记录在手动「重新识别」后若再次识别失败，保留 `ReidentifyReplacementRegistry` 中记录的旧归档 `target_file_path`，防止详情失联且确保后续恢复或再次重识别继续安全替换旧归档；放弃/忽略恢复副本时保持简洁，不额外引入快照回滚。
  - 在处理记录页「重新识别」预览与确认弹窗中，对包含 `ONLINE_SEARCH` / `LOCAL_TAGS` 来源的记录明确提示普通重识别仅走 MusicBrainz 且失败会保留原归档并转入「重新识别」列表，同时更正已过时的旧归档替换说明文案。
- 增加启动期历史分裂记录幂等对账合并：
  - 服务启动时自动扫描监控目录内的历史 `FAILED` 文件夹与输出目录内的历史 `SUCCESS` 文件夹，仅在存在确凿证据（`review-queue.json` / 归档日志的 `CONFIRMED` 恢复路径记录，或整夹全部音轨 FLAC STREAMINFO MD5 一一匹配）时，将两行合并为监控源单条 `SUCCESS` 记录（仅更新处理记录存储，绝不删除或移动磁盘音频文件）。

## Capabilities

### New Capabilities
- `recovery-record-lineage`: 规范隔离恢复副本与监控目录源文件的血缘追踪、全恢复出口向单条处理记录的统一回写与回收站收敛、历史分裂处理记录的启动期幂等对账合并，以及联网/AI 入库记录重识别的失败保底与确认提示行为。

### Modified Capabilities
<!-- 本次不修改既有四个 specs（first-run-setup、musicbrainz-release-and-artist-naming、online-archive-credits、online-identification）的需求条款 -->

## Impact

- **持久化与注册表**：新增 `RecoveryOriginRegistry`（持久化于 `review-queue.json` 同目录 `recovery-origins.json`）；扩展 `RecoveryTrashService` 的回收站 manifest（向后兼容旧 manifest）。
- **核心业务服务**：`FailedFileHandler`、`FileMonitorService`、`RecoveryService`、`CoverMissingService`、`ReviewResolutionService`、`ProcessedRecordService`、`ProcessedFileLogger`、`ApplicationLifecycleManager`（启动对账挂载）。
- **Web API 与前端**：`ProcessedRecordService.preview()` 返回非 MB 来源统计字段（向后兼容）；`records.html` 与 `messages_zh_CN.properties` / `messages_en_US.properties` 更新确认框提示文案。
- **兼容性**：兼容 SQLite、MySQL 与文件日志三种记录存储模式；历史对账严格幂等且只操作记录层，不修改或删除任何音乐文件。
