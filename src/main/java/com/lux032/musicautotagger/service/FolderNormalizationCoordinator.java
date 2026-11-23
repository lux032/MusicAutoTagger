package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.util.AudioFileOrdering;
import com.lux032.musicautotagger.util.FileSystemUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件夹级高规格音频降级（标准化）计划协调器（从 AudioFileProcessorService 抽离）。
 * 负责整夹预处理缓存、单曲散落文件隔离、重试前文件存活校验以及终态临时文件清理。
 */
public class FolderNormalizationCoordinator {

    private final AudioFormatNormalizer audioFormatNormalizer;
    private final FileSystemUtils fileSystemUtils;
    private final Map<String, Plan> folderPlans = new ConcurrentHashMap<>();

    public FolderNormalizationCoordinator(AudioFormatNormalizer audioFormatNormalizer,
                                          FileSystemUtils fileSystemUtils) {
        this.audioFormatNormalizer = audioFormatNormalizer;
        this.fileSystemUtils = fileSystemUtils;
    }

    public void cleanupPlan(String folderPath) {
        if (folderPath == null) {
            return;
        }
        Plan removed = folderPlans.remove(folderPath);
        if (removed != null) {
            removed.cleanupAll(audioFormatNormalizer);
        }
    }

    public int activePlanCount() {
        return folderPlans.size();
    }

    public Plan getOrPreparePlan(File originalAudioFile, File albumRootDir, boolean isLooseFileInMonitorRoot) {
        if (isLooseFileInMonitorRoot) {
            Map<String, AudioFormatNormalizer.NormalizationResult> singleResult = new HashMap<>();
            AudioFormatNormalizer.NormalizationResult normalized = audioFormatNormalizer.normalizeIfNeeded(originalAudioFile);
            singleResult.put(originalAudioFile.getAbsolutePath(), normalized);
            return new Plan(singleResult, new ArrayList<>(List.of(originalAudioFile)));
        }

        String folderPath = albumRootDir.getAbsolutePath();
        Plan existing = folderPlans.get(folderPath);
        if (existing != null) {
            existing.ensureFilePrepared(originalAudioFile, audioFormatNormalizer);
            return existing;
        }

        List<File> audioFiles = new ArrayList<>();
        fileSystemUtils.collectAudioFilesForMarking(albumRootDir, audioFiles);
        AudioFileOrdering.sort(audioFiles);

        Map<String, AudioFormatNormalizer.NormalizationResult> results = new HashMap<>();
        List<File> orderedOriginalFiles = new ArrayList<>(audioFiles.size());
        for (File file : audioFiles) {
            AudioFormatNormalizer.NormalizationResult result = audioFormatNormalizer.normalizeIfNeeded(file);
            results.put(file.getAbsolutePath(), result);
            orderedOriginalFiles.add(file);
        }

        Plan plan = new Plan(results, orderedOriginalFiles);
        Plan raced = folderPlans.putIfAbsent(folderPath, plan);
        if (raced != null) {
            plan.cleanupAll(audioFormatNormalizer);
            raced.ensureFilePrepared(originalAudioFile, audioFormatNormalizer);
            return raced;
        }
        plan.ensureFilePrepared(originalAudioFile, audioFormatNormalizer);
        return plan;
    }

    public static class Plan {
        private final Map<String, AudioFormatNormalizer.NormalizationResult> results;
        private final List<File> orderedOriginalFiles;
        private List<Integer> durationSequence;
        private boolean orderDirty;

        Plan(Map<String, AudioFormatNormalizer.NormalizationResult> results,
             List<File> orderedOriginalFiles) {
            this.results = results;
            this.orderedOriginalFiles = orderedOriginalFiles;
        }

        public synchronized AudioFormatNormalizer.NormalizationResult getResult(File originalFile) {
            AudioFormatNormalizer.NormalizationResult result = results.get(originalFile.getAbsolutePath());
            if (result == null || (result.isConverted() && !result.getProcessingFile().exists())) {
                return AudioFormatNormalizer.NormalizationResult.noop(originalFile);
            }
            return result;
        }

        public synchronized void invalidateFile(File originalFile) {
            if (originalFile == null) {
                return;
            }
            results.remove(originalFile.getAbsolutePath());
        }

        public synchronized void cleanupAll(AudioFormatNormalizer normalizer) {
            for (AudioFormatNormalizer.NormalizationResult r : results.values()) {
                if (r != null && r.isConverted()) {
                    normalizer.cleanup(r);
                }
            }
            results.clear();
        }

        public synchronized void ensureFilePrepared(File originalFile, AudioFormatNormalizer normalizer) {
            String key = originalFile.getAbsolutePath();
            AudioFormatNormalizer.NormalizationResult existing = results.get(key);
            if (existing != null && (!existing.isConverted() || existing.getProcessingFile().exists())) {
                return;
            }
            AudioFormatNormalizer.NormalizationResult result = normalizer.normalizeIfNeeded(originalFile);
            results.put(key, result);
            if (orderedOriginalFiles.stream().noneMatch(f -> key.equals(f.getAbsolutePath()))) {
                orderedOriginalFiles.add(originalFile);
                orderDirty = true;
            }
            durationSequence = null;
        }

        public synchronized List<Integer> getOrComputeDurationSequence(AudioFingerprintService fingerprintService) {
            if (durationSequence != null && !durationSequence.isEmpty()) {
                return durationSequence;
            }
            if (orderDirty) {
                AudioFileOrdering.sort(orderedOriginalFiles);
                orderDirty = false;
            }
            List<File> processingFiles = new ArrayList<>(orderedOriginalFiles.size());
            for (File originalFile : orderedOriginalFiles) {
                AudioFormatNormalizer.NormalizationResult result = results.get(originalFile.getAbsolutePath());
                File candidate = (result != null && result.getProcessingFile().exists())
                    ? result.getProcessingFile() : originalFile;
                processingFiles.add(candidate);
            }
            durationSequence = fingerprintService.extractDurationSequence(processingFiles);
            return durationSequence;
        }
    }
}
