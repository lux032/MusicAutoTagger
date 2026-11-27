package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessedRecord;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.util.AudioFileOrdering;
import com.lux032.musicautotagger.util.FileSystemUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 启动期历史分裂处理记录幂等对账器。
 *
 * <p>升级前经由隔离目录（failed / partial）恢复入库的专辑，在 {@code processed_files} 中往往同时残留：
 * <ul>
 *   <li>监控目录源路径的 {@code FAILED} 记录</li>
 *   <li>输出音乐库路径的 {@code SUCCESS} 记录</li>
 * </ul>
 * 本对账器在文件监控启动前以两级严格证据（已确认恢复历史 / 整夹双向唯一 FLAC STREAMINFO MD5 精确一一匹配）
 * 将确凿对应的历史分裂记录按专辑原子合并为单条监控主记录，保留原 {@code processed_time}，绝不修改或删除任何磁盘音频文件。
 */
@Slf4j
public class RecoveryRecordReconciler {

    private final MusicConfig config;
    private final ProcessedFileLogger processedLogger;
    private final ReviewQueueService reviewQueueService;
    private final FileSystemUtils fileSystemUtils;
    private final Gson gson = new Gson();

    public record ReconcileResult(int mergedAlbums, int mergedRecords, int cleanedDeletedSourceRecords) {
        public boolean hasChanges() {
            return mergedAlbums > 0 || mergedRecords > 0 || cleanedDeletedSourceRecords > 0;
        }
    }

    public RecoveryRecordReconciler(MusicConfig config,
                                    ProcessedFileLogger processedLogger,
                                    ReviewQueueService reviewQueueService) {
        this.config = config;
        this.processedLogger = processedLogger;
        this.reviewQueueService = reviewQueueService;
        this.fileSystemUtils = config == null ? null : new FileSystemUtils(config);
    }

    public ReconcileResult reconcile() {
        if (config == null || processedLogger == null) {
            return new ReconcileResult(0, 0, 0);
        }
        Path monitorRoot = configuredDirPath(config.getMonitorDirectory());
        Path outputRoot = configuredDirPath(config.getOutputDirectory());
        if (!isDirectoryAccessible(monitorRoot) || !isDirectoryAccessible(outputRoot)) {
            log.debug("跳过启动期历史分裂记录对账（监控目录或输出目录未就绪或不可读）");
            return new ReconcileResult(0, 0, 0);
        }

        Path canonicalMonitor = canonicalPathOrNull(monitorRoot);
        Path canonicalOutput = canonicalPathOrNull(outputRoot);

        Map<Path, List<ProcessedRecord>> failedByGroup = loadMonitorFailedGroups(monitorRoot, canonicalMonitor);
        if (failedByGroup.isEmpty()) {
            return new ReconcileResult(0, 0, 0);
        }
        Map<Path, List<ProcessedRecord>> outputByGroup = loadOutputSuccessGroups(outputRoot, canonicalOutput);
        if (outputByGroup.isEmpty()) {
            return new ReconcileResult(0, 0, 0);
        }

        // 先基于完整原始候选集建立证据链 2 双向歧义表，避免证据链 1 消费后把原本有歧义的组错误降级为唯一命中
        Set<Integer> allFailedSizes = new HashSet<>();
        for (List<ProcessedRecord> group : failedByGroup.values()) {
            allFailedSizes.add(group.size());
        }
        Map<Path, Map<String, ProcessedRecord>> fullFailedMd5ByGroup = new LinkedHashMap<>();
        Map<Set<String>, List<Path>> allFailedByMd5Set = new HashMap<>();
        for (Map.Entry<Path, List<ProcessedRecord>> entry : failedByGroup.entrySet()) {
            Path groupPath = entry.getKey();
            List<ProcessedRecord> recs = entry.getValue();
            if (!coversAllDiskAudioFiles(groupPath, recs, monitorRoot, canonicalMonitor)) {
                continue;
            }
            Map<String, ProcessedRecord> byMd5 = collectStrictFolderFlacMd5(recs, false);
            if (byMd5 != null && !byMd5.isEmpty()) {
                fullFailedMd5ByGroup.put(groupPath, byMd5);
                allFailedByMd5Set.computeIfAbsent(Set.copyOf(byMd5.keySet()), k -> new ArrayList<>()).add(groupPath);
            }
        }
        Map<Set<String>, List<OutputMd5Group>> allOutputByMd5Set = new HashMap<>();
        if (!fullFailedMd5ByGroup.isEmpty()) {
            for (Map.Entry<Path, List<ProcessedRecord>> entry : outputByGroup.entrySet()) {
                Path groupPath = entry.getKey();
                List<ProcessedRecord> recs = entry.getValue();
                // 仅对曲目数命中待对账失败组的输出目录读取 FLAC 头，避免全库扫描
                if (!allFailedSizes.contains(recs.size())) {
                    continue;
                }
                if (!coversAllDiskAudioFiles(groupPath, recs, outputRoot, canonicalOutput)) {
                    continue;
                }
                Map<String, ProcessedRecord> byMd5 = collectStrictFolderFlacMd5(recs, true);
                if (byMd5 != null && !byMd5.isEmpty()) {
                    allOutputByMd5Set.computeIfAbsent(Set.copyOf(byMd5.keySet()), k -> new ArrayList<>())
                        .add(new OutputMd5Group(groupPath, byMd5));
                }
            }
        }

        Set<Path> consumedFailed = new HashSet<>();
        Set<Path> consumedOutput = new HashSet<>();
        int mergedAlbums = 0;
        int mergedRecords = 0;
        int cleanedDeleted = 0;

        // 证据链 1：review-queue.json 与 review-queue-archive.jsonl 中已 CONFIRMED 的恢复记录
        for (ReviewItem item : loadConfirmedRecoveryItems()) {
            try {
                Path monitorGroup = matchMonitorGroupFromReviewItem(item, failedByGroup, consumedFailed, monitorRoot, canonicalMonitor);
                if (monitorGroup == null) {
                    continue;
                }
                Path outputGroup = matchOutputGroupFromReviewItem(item, outputByGroup, consumedOutput);
                if (outputGroup == null) {
                    continue;
                }
                List<ProcessedRecord> left = failedByGroup.get(monitorGroup);
                List<ProcessedRecord> right = outputByGroup.get(outputGroup);
                List<RecordPair> pairs = pairTracksForEvidence1(left, right);
                if (pairs.isEmpty()) {
                    continue;
                }
                MergeCounts counts = applyMergeAtomically(pairs);
                if (counts != null && (counts.merged > 0 || counts.cleanedDeleted > 0)) {
                    consumedFailed.add(monitorGroup);
                    consumedOutput.add(outputGroup);
                    mergedAlbums++;
                    mergedRecords += counts.merged;
                    cleanedDeleted += counts.cleanedDeleted;
                }
            } catch (Exception e) {
                log.warn("启动对账处理历史恢复条目失败，已跳过 ({}): {}",
                    item == null ? "" : item.getFolderName(), e.getMessage());
            }
        }

        // 证据链 2：整夹逐曲 FLAC STREAMINFO MD5 双向精确一一匹配（原始集合双向唯一，任何歧义直接跳过）
        if (!allOutputByMd5Set.isEmpty()) {
            for (Map.Entry<Path, Map<String, ProcessedRecord>> entry : fullFailedMd5ByGroup.entrySet()) {
                Path monitorGroup = entry.getKey();
                if (consumedFailed.contains(monitorGroup)) {
                    continue;
                }
                try {
                    Map<String, ProcessedRecord> leftByMd5 = entry.getValue();
                    Set<String> md5Set = Set.copyOf(leftByMd5.keySet());
                    List<Path> allMatchingFailed = allFailedByMd5Set.get(md5Set);
                    List<OutputMd5Group> allMatchingOutputs = allOutputByMd5Set.get(md5Set);
                    // 双向唯一性校验（基于原始全量集合，防止两个同音频失败夹先到先得或消费后消除歧义）
                    if (allMatchingFailed == null || allMatchingFailed.size() != 1
                            || allMatchingOutputs == null || allMatchingOutputs.size() != 1) {
                        continue;
                    }
                    OutputMd5Group target = allMatchingOutputs.get(0);
                    if (consumedOutput.contains(target.groupPath) || target.byMd5.size() != leftByMd5.size()) {
                        continue;
                    }
                    List<RecordPair> pairs = new ArrayList<>(leftByMd5.size());
                    for (Map.Entry<String, ProcessedRecord> track : leftByMd5.entrySet()) {
                        ProcessedRecord outRec = target.byMd5.get(track.getKey());
                        if (outRec == null) {
                            pairs.clear();
                            break;
                        }
                        pairs.add(new RecordPair(track.getValue(), outRec));
                    }
                    if (pairs.isEmpty()) {
                        continue;
                    }
                    MergeCounts counts = applyMergeAtomically(pairs);
                    if (counts != null && (counts.merged > 0 || counts.cleanedDeleted > 0)) {
                        consumedFailed.add(monitorGroup);
                        consumedOutput.add(target.groupPath);
                        mergedAlbums++;
                        mergedRecords += counts.merged;
                        cleanedDeleted += counts.cleanedDeleted;
                    }
                } catch (Exception e) {
                    log.warn("启动对账按 FLAC MD5 匹配目录失败，已跳过 ({}): {}", monitorGroup, e.getMessage());
                }
            }
        }

        ReconcileResult result = new ReconcileResult(mergedAlbums, mergedRecords, cleanedDeleted);
        if (result.hasChanges()) {
            String msg = cleanedDeleted > 0
                ? String.format("启动对账已合并 %d 个历史分裂专辑（%d 条记录已升级，清理 %d 条已删源失效失败记录）",
                    mergedAlbums, mergedRecords, cleanedDeleted)
                : String.format("启动对账已合并 %d 个历史分裂专辑（%d 条处理记录）", mergedAlbums, mergedRecords);
            log.info(msg);
            LogCollector.addLog("INFO", msg);
        }
        return result;
    }

    private record RecordPair(ProcessedRecord monitorFailed, ProcessedRecord outputSuccess) { }
    private record MergeCounts(int merged, int cleanedDeleted) { }
    private record OutputMd5Group(Path groupPath, Map<String, ProcessedRecord> byMd5) { }

    /**
     * 按专辑打包全部合并操作，在单事务（DB 模式）或单次写锁原子重写（文件模式）中提交，
     * 杜绝写失败仍删输出行或中途崩溃留下半合并脏态。
     */
    private MergeCounts applyMergeAtomically(List<RecordPair> pairs) {
        List<ProcessedFileLogger.RecordMigration> migrations = new ArrayList<>(pairs.size());
        int plannedMerged = 0;
        int plannedCleanedDeleted = 0;
        for (RecordPair pair : pairs) {
            ProcessedRecord monRec = pair.monitorFailed();
            ProcessedRecord outRec = pair.outputSuccess();
            Path monPath = Path.of(monRec.filePath);
            if (Files.isRegularFile(monPath)) {
                LocalDateTime origTime = processedLogger.parseProcessedTime(outRec.processedTime);
                String targetPath = effectiveOutputPath(outRec);
                List<String> delOutput = sameNormalizedPath(monRec.filePath, outRec.filePath)
                    ? List.of() : List.of(outRec.filePath);
                migrations.add(new ProcessedFileLogger.RecordMigration(
                    monPath.toFile(),
                    outRec.recordingId,
                    outRec.artist,
                    outRec.title,
                    outRec.album,
                    outRec.releaseGroupId,
                    targetPath,
                    origTime,
                    delOutput,
                    List.of()
                ));
                plannedMerged++;
            } else if (Files.notExists(monPath)) {
                // 仅当确认不存在（非 IO/权限错误）且输出文件有效时，仅删除失效 FAILED 行
                migrations.add(new ProcessedFileLogger.RecordMigration(
                    null, null, null, null, null, null, null, null,
                    List.of(),
                    List.of(monRec.filePath)
                ));
                plannedCleanedDeleted++;
            } else {
                // 存在性不确定（权限或挂载异常），整张专辑放弃合并
                return null;
            }
        }
        if (migrations.isEmpty()) {
            return new MergeCounts(0, 0);
        }
        if (!processedLogger.applyRecordMigrationsAtomically(migrations)) {
            return null;
        }
        return new MergeCounts(plannedMerged, plannedCleanedDeleted);
    }

    private Map<Path, List<ProcessedRecord>> loadMonitorFailedGroups(Path monitorRoot, Path canonicalMonitor) {
        ProcessedRecordQuery q = new ProcessedRecordQuery();
        q.status = ProcessedRecord.Status.FAILED;
        List<ProcessedRecord> allFailed = processedLogger.findRecords(q, Integer.MAX_VALUE);
        Map<Path, List<ProcessedRecord>> groups = new LinkedHashMap<>();
        if (allFailed == null || allFailed.isEmpty()) {
            return groups;
        }
        for (ProcessedRecord rec : allFailed) {
            if (rec == null || !meaningful(rec.filePath)) continue;
            try {
                Path recPath = Path.of(rec.filePath).toAbsolutePath().normalize();
                if (!isInsideEither(recPath, monitorRoot, canonicalMonitor)) continue;
                Path key = groupKeyForRecord(recPath, monitorRoot, canonicalMonitor);
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(rec);
            } catch (Exception e) {
                log.debug("启动对账跳过非法监控记录路径 ({}): {}", rec.filePath, e.getMessage());
            }
        }
        return groups;
    }

    private Map<Path, List<ProcessedRecord>> loadOutputSuccessGroups(Path outputRoot, Path canonicalOutput) {
        ProcessedRecordQuery q = new ProcessedRecordQuery();
        q.status = ProcessedRecord.Status.SUCCESS;
        List<ProcessedRecord> allSuccess = processedLogger.findRecords(q, Integer.MAX_VALUE);
        Map<Path, List<ProcessedRecord>> groups = new LinkedHashMap<>();
        if (allSuccess == null || allSuccess.isEmpty()) {
            return groups;
        }
        for (ProcessedRecord rec : allSuccess) {
            if (rec == null || !meaningful(rec.filePath)) continue;
            try {
                Path recPath = Path.of(rec.filePath).toAbsolutePath().normalize();
                if (!isInsideEither(recPath, outputRoot, canonicalOutput)) continue;
                Path key = groupKeyForRecord(recPath, outputRoot, canonicalOutput);
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(rec);
            } catch (Exception e) {
                log.debug("启动对账跳过非法输出记录路径 ({}): {}", rec.filePath, e.getMessage());
            }
        }
        return groups;
    }

    /** 散落于根目录直接子项的单曲按自身文件路径独立成组，避免把无关散曲混为同一专辑。 */
    private Path groupKeyForRecord(Path recPath, Path root, Path canonicalRoot) {
        Path parent = recPath.getParent();
        if (parent != null && (parent.equals(root) || (canonicalRoot != null && parent.equals(canonicalRoot)))) {
            return recPath;
        }
        String folder = ProcessedFileLogger.computeFolderPath(recPath.toString(), config);
        return meaningful(folder) ? Path.of(folder).toAbsolutePath().normalize() : recPath;
    }

    /**
     * 校验目录级分组中的处理记录是否完整覆盖磁盘上的全部音频文件，
     * 防止「3 首里只有 1 首失败」的半残失败组误匹配 1 首单曲输出夹。
     */
    private boolean coversAllDiskAudioFiles(Path groupKey, List<ProcessedRecord> records,
                                            Path root, Path canonicalRoot) {
        if (groupKey == null || records == null || records.isEmpty()) {
            return false;
        }
        // 单曲散落组（groupKey 直接是文件）
        if (Files.isRegularFile(groupKey)) {
            return records.size() == 1;
        }
        if (!Files.isDirectory(groupKey)) {
            return false;
        }
        // 确保不扫描监控/输出根目录本身
        if (groupKey.equals(root) || (canonicalRoot != null && groupKey.equals(canonicalRoot))) {
            return false;
        }
        List<File> diskAudio = new ArrayList<>();
        if (fileSystemUtils != null) {
            fileSystemUtils.collectAudioFilesForMarking(groupKey.toFile(), diskAudio);
        } else {
            collectDefaultAudioFiles(groupKey.toFile(), diskAudio);
        }
        if (diskAudio.size() != records.size()) {
            return false;
        }
        Set<Path> recordPaths = new HashSet<>(records.size());
        for (ProcessedRecord r : records) {
            Path p = Path.of(r.filePath).toAbsolutePath().normalize();
            recordPaths.add(p);
            Path canon = canonicalPathOrNull(p);
            if (canon != null) recordPaths.add(canon);
        }
        for (File f : diskAudio) {
            Path p = f.toPath().toAbsolutePath().normalize();
            Path canon = canonicalPathOrNull(p);
            if (!recordPaths.contains(p) && (canon == null || !recordPaths.contains(canon))) {
                return false;
            }
        }
        return true;
    }

    private static void collectDefaultAudioFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) {
                collectDefaultAudioFiles(c, out);
            } else if (c.isFile() && FileSystemUtils.isDefaultAudioFile(c)) {
                out.add(c);
            }
        }
    }

    private List<ReviewItem> loadConfirmedRecoveryItems() {
        List<ReviewItem> items = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        if (reviewQueueService == null) {
            return items;
        }
        for (ReviewItem item : reviewQueueService.list(ReviewItem.Status.CONFIRMED)) {
            if (isConfirmedRecoveryCandidate(item) && (item.getId() == null || seenIds.add(item.getId()))) {
                items.add(item);
            }
        }
        Path archiveFile = reviewQueueService.getArchiveFile();
        if (archiveFile != null && Files.isRegularFile(archiveFile)) {
            try (BufferedReader reader = Files.newBufferedReader(archiveFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        ReviewItem archived = gson.fromJson(line, ReviewItem.class);
                        if (isConfirmedRecoveryCandidate(archived)
                                && (archived.getId() == null || seenIds.add(archived.getId()))) {
                            items.add(archived);
                        }
                    } catch (Exception ignored) {
                        // 单行损坏不影响其余归档条目
                    }
                }
            } catch (IOException e) {
                log.warn("读取历史待确认归档文件失败（跳过证据链 1 归档部分）: {}", e.getMessage());
            }
        }
        return items;
    }

    private static boolean isConfirmedRecoveryCandidate(ReviewItem item) {
        return item != null
            && item.getStatus() == ReviewItem.Status.CONFIRMED
            && meaningful(item.getRecoverySourceType())
            && meaningful(item.getRecoverySourcePath())
            && item.getCommittedOutputPaths() != null
            && !item.getCommittedOutputPaths().isEmpty();
    }

    private Path matchMonitorGroupFromReviewItem(ReviewItem item,
                                                 Map<Path, List<ProcessedRecord>> failedByGroup,
                                                 Set<Path> consumedFailed,
                                                 Path monitorRoot,
                                                 Path canonicalMonitor) {
        String sourceType = item.getRecoverySourceType().trim();
        Path sourcePath = Path.of(item.getRecoverySourcePath()).toAbsolutePath().normalize();
        if ("FAILED".equals(sourceType)) {
            Path failedRoot = configuredDirPath(config.getFailedDirectory());
            if (failedRoot == null) return null;
            Path rel = relativizeStrictlyInside(sourcePath, failedRoot);
            if (rel == null) return null;
            return findMatchingFailedGroup(monitorRoot.resolve(rel).toAbsolutePath().normalize(),
                canonicalMonitor == null ? null : canonicalMonitor.resolve(rel).toAbsolutePath().normalize(),
                failedByGroup, consumedFailed);
        }
        if ("PARTIAL".equals(sourceType)) {
            Path partialRoot = configuredDirPath(config.getPartialDirectory());
            if (partialRoot == null) return null;
            Path rel = relativizeStrictlyInside(sourcePath, partialRoot);
            if (rel == null) return null;
            // 同时汇总直接相对路径与扁平目录名全部候选，避免 /monitor/Greatest Hits 覆盖 /monitor/Artist B/Greatest Hits 的歧义
            Set<Path> candidates = new LinkedHashSet<>();
            Path exact = findMatchingFailedGroup(monitorRoot.resolve(rel).toAbsolutePath().normalize(),
                canonicalMonitor == null ? null : canonicalMonitor.resolve(rel).toAbsolutePath().normalize(),
                failedByGroup, consumedFailed);
            if (exact != null) {
                candidates.add(exact);
            }
            String targetName = rel.getName(0).toString();
            for (Path groupPath : failedByGroup.keySet()) {
                if (consumedFailed.contains(groupPath)) continue;
                if (groupPath.getFileName() != null && targetName.equals(groupPath.getFileName().toString())) {
                    candidates.add(groupPath);
                }
            }
            return candidates.size() == 1 ? candidates.iterator().next() : null;
        }
        return null;
    }

    private Path findMatchingFailedGroup(Path normalizedCandidate,
                                         Path canonicalCandidate,
                                         Map<Path, List<ProcessedRecord>> failedByGroup,
                                         Set<Path> consumedFailed) {
        if (normalizedCandidate != null && failedByGroup.containsKey(normalizedCandidate)
                && !consumedFailed.contains(normalizedCandidate)) {
            return normalizedCandidate;
        }
        if (canonicalCandidate != null && failedByGroup.containsKey(canonicalCandidate)
                && !consumedFailed.contains(canonicalCandidate)) {
            return canonicalCandidate;
        }
        for (Path key : failedByGroup.keySet()) {
            if (consumedFailed.contains(key)) continue;
            Path canonKey = canonicalPathOrNull(key);
            if (canonKey != null && (canonKey.equals(normalizedCandidate) || canonKey.equals(canonicalCandidate))) {
                return key;
            }
        }
        return null;
    }

    /**
     * 校验 {@code committedOutputPaths} 必须精确命中唯一一个未消费输出分组，
     * 且覆盖该分组内的全部输出记录（防止复用输出目录时将无关曲目一并卷入合并）。
     */
    private Path matchOutputGroupFromReviewItem(ReviewItem item,
                                                Map<Path, List<ProcessedRecord>> outputByGroup,
                                                Set<Path> consumedOutput) {
        List<Path> committedPaths = new ArrayList<>();
        List<Path> committedCanon = new ArrayList<>();
        for (String raw : item.getCommittedOutputPaths()) {
            if (meaningful(raw)) {
                Path p = Path.of(raw.trim()).toAbsolutePath().normalize();
                committedPaths.add(p);
                committedCanon.add(canonicalPathOrNull(p));
            }
        }
        if (committedPaths.isEmpty()) {
            return null;
        }
        Map<Path, Integer> matchedCountByGroup = new LinkedHashMap<>();
        for (Map.Entry<Path, List<ProcessedRecord>> entry : outputByGroup.entrySet()) {
            Path groupPath = entry.getKey();
            if (consumedOutput.contains(groupPath)) continue;
            int matchedInGroup = 0;
            for (ProcessedRecord rec : entry.getValue()) {
                Path recPath = Path.of(effectiveOutputPath(rec)).toAbsolutePath().normalize();
                Path canonRec = canonicalPathOrNull(recPath);
                for (int i = 0; i < committedPaths.size(); i++) {
                    Path committed = committedPaths.get(i);
                    Path canonCommitted = committedCanon.get(i);
                    if (recPath.equals(committed) || recPath.startsWith(committed)
                            || (canonRec != null && canonCommitted != null
                                && (canonRec.equals(canonCommitted) || canonRec.startsWith(canonCommitted)))) {
                        matchedInGroup++;
                        break;
                    }
                }
            }
            if (matchedInGroup > 0) {
                matchedCountByGroup.put(groupPath, matchedInGroup);
            }
        }
        if (matchedCountByGroup.size() != 1) {
            return null;
        }
        Map.Entry<Path, Integer> only = matchedCountByGroup.entrySet().iterator().next();
        List<ProcessedRecord> groupRecords = outputByGroup.get(only.getKey());
        if (groupRecords == null || only.getValue() != groupRecords.size()) {
            return null;
        }
        return only.getKey();
    }

    private List<RecordPair> pairTracksForEvidence1(List<ProcessedRecord> left, List<ProcessedRecord> right) {
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) {
            return List.of();
        }
        // 所有输出归档文件必须实际存在，绝不向缺失归档合并或据此删除原监控记录
        boolean allRightExist = right.stream().allMatch(r -> Files.isRegularFile(Path.of(effectiveOutputPath(r))));
        if (!allRightExist) {
            return List.of();
        }

        boolean allLeftExist = left.stream().allMatch(r -> Files.isRegularFile(Path.of(r.filePath)));
        boolean allLeftDeleted = left.stream().allMatch(r -> Files.notExists(Path.of(r.filePath)));
        if (!allLeftExist && !allLeftDeleted) {
            // 部分源文件已删/不可访问、部分仍在的混合状态，保守跳过
            return List.of();
        }

        // 1) 若左侧源文件全部在磁盘，优先按全组 FLAC MD5 精确配对
        if (allLeftExist) {
            Map<String, ProcessedRecord> leftMd5 = collectStrictFolderFlacMd5(left, false);
            Map<String, ProcessedRecord> rightMd5 = collectStrictFolderFlacMd5(right, true);
            if (leftMd5 != null && rightMd5 != null) {
                if (!leftMd5.keySet().equals(rightMd5.keySet())) {
                    return List.of();
                }
                List<RecordPair> pairs = new ArrayList<>(leftMd5.size());
                for (Map.Entry<String, ProcessedRecord> entry : leftMd5.entrySet()) {
                    pairs.add(new RecordPair(entry.getValue(), rightMd5.get(entry.getKey())));
                }
                return pairs;
            }
        }

        // 2) 非全 FLAC（例如 MP3、混编格式）或已确认全删源的清理：构造候选配对后，仍逐曲校验任何存在的 FLAC MD5 不得冲突
        List<RecordPair> candidatePairs;
        if (left.size() == 1) {
            candidatePairs = List.of(new RecordPair(left.get(0), right.get(0)));
        } else {
            candidatePairs = pairByUniqueFileName(left, right);
            if (candidatePairs.isEmpty()) {
                Comparator<File> order = AudioFileOrdering.comparator();
                List<ProcessedRecord> sortedLeft = new ArrayList<>(left);
                List<ProcessedRecord> sortedRight = new ArrayList<>(right);
                sortedLeft.sort((a, b) -> order.compare(new File(a.filePath), new File(b.filePath)));
                sortedRight.sort((a, b) -> order.compare(new File(effectiveOutputPath(a)), new File(effectiveOutputPath(b))));
                candidatePairs = new ArrayList<>(sortedLeft.size());
                for (int i = 0; i < sortedLeft.size(); i++) {
                    candidatePairs.add(new RecordPair(sortedLeft.get(i), sortedRight.get(i)));
                }
            }
        }

        // 对混合格式中任意实际存在的 FLAC 曲目强制校验音频 MD5 一致，禁止忽略任何可用指纹冲突
        if (allLeftExist) {
            for (RecordPair pair : candidatePairs) {
                Optional<String> lMd5 = ArchiveDuplicateDetector.flacAudioMd5(Path.of(pair.monitorFailed().filePath));
                Optional<String> rMd5 = ArchiveDuplicateDetector.flacAudioMd5(Path.of(effectiveOutputPath(pair.outputSuccess())));
                if (lMd5.isPresent() || rMd5.isPresent()) {
                    if (lMd5.isEmpty() || rMd5.isEmpty() || !lMd5.get().equals(rMd5.get())) {
                        return List.of();
                    }
                }
            }
        }
        return candidatePairs;
    }

    private static List<RecordPair> pairByUniqueFileName(List<ProcessedRecord> left, List<ProcessedRecord> right) {
        Map<String, ProcessedRecord> rightByName = new HashMap<>();
        for (ProcessedRecord r : right) {
            String name = new File(effectiveOutputPath(r)).getName();
            if (rightByName.put(name, r) != null) {
                return List.of();
            }
        }
        List<RecordPair> byName = new ArrayList<>(left.size());
        Set<String> usedRight = new HashSet<>();
        for (ProcessedRecord l : left) {
            String name = new File(l.filePath).getName();
            ProcessedRecord match = rightByName.get(name);
            if (match == null || !usedRight.add(name)) {
                return List.of();
            }
            byName.add(new RecordPair(l, match));
        }
        return byName;
    }

    /**
     * 仅当文件夹内所有记录均存在磁盘文件、均为有效非零 FLAC STREAMINFO MD5 且组内 MD5 互不重复时返回映射；否则返回 null。
     */
    private static Map<String, ProcessedRecord> collectStrictFolderFlacMd5(List<ProcessedRecord> records, boolean useEffectiveOutput) {
        if (records == null || records.isEmpty()) {
            return null;
        }
        Map<String, ProcessedRecord> byMd5 = new LinkedHashMap<>(records.size());
        for (ProcessedRecord rec : records) {
            if (rec == null) return null;
            String rawPath = useEffectiveOutput ? effectiveOutputPath(rec) : rec.filePath;
            if (!meaningful(rawPath)) {
                return null;
            }
            Path path = Path.of(rawPath);
            if (!Files.isRegularFile(path)) {
                return null;
            }
            Optional<String> md5 = ArchiveDuplicateDetector.flacAudioMd5(path);
            if (md5.isEmpty()) {
                return null;
            }
            if (byMd5.put(md5.get(), rec) != null) {
                return null;
            }
        }
        return byMd5;
    }

    private static String effectiveOutputPath(ProcessedRecord outRec) {
        return meaningful(outRec.targetFilePath) ? outRec.targetFilePath : outRec.filePath;
    }

    private static boolean isDirectoryAccessible(Path dir) {
        if (dir == null || !Files.isDirectory(dir) || !Files.isReadable(dir)) {
            return false;
        }
        try (var stream = Files.list(dir)) {
            stream.findFirst();
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static Path configuredDirPath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return Path.of(raw.trim()).toAbsolutePath().normalize();
    }

    private static Path canonicalPathOrNull(Path path) {
        if (path == null) return null;
        try {
            return path.toFile().getCanonicalFile().toPath().normalize();
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isInsideEither(Path candidate, Path root, Path canonicalRoot) {
        if (candidate == null) return false;
        if (root != null && candidate.startsWith(root) && !candidate.equals(root)) {
            return true;
        }
        if (canonicalRoot != null) {
            if (candidate.startsWith(canonicalRoot) && !candidate.equals(canonicalRoot)) {
                return true;
            }
            Path canon = canonicalPathOrNull(candidate);
            if (canon != null && canon.startsWith(canonicalRoot) && !canon.equals(canonicalRoot)) {
                return true;
            }
        }
        return false;
    }

    private static Path relativizeStrictlyInside(Path child, Path root) {
        if (child == null || root == null) return null;
        Path normChild = child.toAbsolutePath().normalize();
        Path normRoot = root.toAbsolutePath().normalize();
        if (normChild.startsWith(normRoot) && !normChild.equals(normRoot)) {
            return normRoot.relativize(normChild);
        }
        Path canonChild = canonicalPathOrNull(normChild);
        Path canonRoot = canonicalPathOrNull(normRoot);
        if (canonChild != null && canonRoot != null
                && canonChild.startsWith(canonRoot) && !canonChild.equals(canonRoot)) {
            return canonRoot.relativize(canonChild);
        }
        return null;
    }

    private static boolean sameNormalizedPath(String a, String b) {
        if (a == null || b == null) return false;
        return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize());
    }

    private static boolean meaningful(String s) {
        return s != null && !s.isBlank();
    }
}
