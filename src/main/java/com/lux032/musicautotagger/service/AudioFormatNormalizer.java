package com.lux032.musicautotagger.service;

import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.I18nUtil;
import com.lux032.musicautotagger.util.ProcessRunner;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalize high-res audio to 24-bit/48kHz before downstream processing.
 */
@Slf4j
public class AudioFormatNormalizer {
    private static final int TARGET_SAMPLE_RATE = 48000;
    private static final int TARGET_BIT_DEPTH = 24;
    /** 重采样整首曲目的超时上限,防止 ffmpeg 挂起拖垮唯一的处理线程 */
    private static final long FFMPEG_TIMEOUT_SECONDS = 1800;

    private final MusicConfig config;

    public AudioFormatNormalizer(MusicConfig config) {
        this.config = config;
    }

    public NormalizationResult normalizeIfNeeded(File sourceFile) {
        if (!config.isAudioNormalizeEnabled()) {
            LogCollector.addLog("INFO", I18nUtil.getMessage("audio.normalize.disabled", sourceFile.getName()));
            return NormalizationResult.noop(sourceFile);
        }

        AudioSpecs specs = readSpecs(sourceFile);
        if (!specs.isKnown()) {
            log.debug("Audio specs unavailable, skip normalization: {}", sourceFile.getName());
            return NormalizationResult.noop(sourceFile);
        }

        if (!needsNormalization(specs.sampleRate, specs.bitDepth)) {
            return NormalizationResult.noop(sourceFile);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("music-normalize-");
            File outputFile = buildFlacOutputFile(sourceFile, tempDir.toFile());

            if (!runFfmpeg(sourceFile, outputFile, specs.bitDepth)) {
                cleanupTemp(tempDir, outputFile);
                return NormalizationResult.noop(sourceFile);
            }

            log.info("Normalized audio: {} ({}Hz/{}bit -> {}Hz/{}bit)",
                sourceFile.getName(),
                specs.sampleRate,
                specs.bitDepth > 0 ? specs.bitDepth : 0,
                TARGET_SAMPLE_RATE,
                TARGET_BIT_DEPTH);
            LogCollector.addLog(
                "INFO",
                I18nUtil.getMessage(
                    "audio.normalize.success",
                    sourceFile.getName(),
                    specs.sampleRate,
                    specs.bitDepth > 0 ? specs.bitDepth : 0,
                    TARGET_SAMPLE_RATE,
                    TARGET_BIT_DEPTH
                )
            );
            return NormalizationResult.converted(outputFile, tempDir);
        } catch (IOException e) {
            log.warn("Failed to normalize audio: {} - {}", sourceFile.getName(), e.getMessage());
            LogCollector.addLog("WARN", I18nUtil.getMessage("audio.normalize.failed", sourceFile.getName()));
            cleanupTemp(tempDir, null);
            return NormalizationResult.noop(sourceFile);
        }
    }

    /**
     * 输出将始终为 .flac，targetFile 仅作为基名/目录参考。
     * 耗时的 specs 读取与 ffmpeg 重采样在 LibraryWriteGuard 之外写入临时文件，
     * 仅在最终原子替换目标文件时短暂持有写锁，避免阻塞并发音乐库写入与半成品落盘。
     */
    public boolean normalizeToTargetIfNeeded(File sourceFile, File targetFile) {
        if (!config.isAudioNormalizeEnabled()) {
            LogCollector.addLog("INFO", I18nUtil.getMessage("audio.normalize.disabled", sourceFile.getName()));
            return false;
        }

        AudioSpecs specs = readSpecs(sourceFile);
        if (!specs.isKnown()) {
            return false;
        }
        if (!needsNormalization(specs.sampleRate, specs.bitDepth)) {
            return false;
        }

        File targetDir = targetFile.getAbsoluteFile().getParentFile();
        File outputFile = buildFlacOutputFile(targetFile, targetDir);
        Path staged = null;
        try {
            if (targetDir != null) {
                Files.createDirectories(targetDir.toPath());
            }
            staged = Files.createTempFile(targetDir.toPath(), ".normalize-", ".flac");
            boolean ok = runFfmpeg(sourceFile, staged.toFile(), specs.bitDepth);
            if (!ok) {
                LogCollector.addLog("WARN", I18nUtil.getMessage("audio.normalize.failed", sourceFile.getName()));
                return false;
            }

            try (var libraryWrite = LibraryWriteGuard.acquire()) {
                try {
                    Files.move(staged, outputFile.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(staged, outputFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                staged = null;
            }
            LogCollector.addLog(
                "INFO",
                I18nUtil.getMessage(
                    "audio.normalize.success",
                    sourceFile.getName(),
                    specs.sampleRate,
                    specs.bitDepth > 0 ? specs.bitDepth : 0,
                    TARGET_SAMPLE_RATE,
                    TARGET_BIT_DEPTH
                )
            );
            return true;
        } catch (IOException e) {
            log.warn("Failed to normalize audio for partial output: {} - {}", sourceFile.getName(), e.getMessage());
            LogCollector.addLog("WARN", I18nUtil.getMessage("audio.normalize.failed", sourceFile.getName()));
            return false;
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public void cleanup(NormalizationResult result) {
        if (result == null || result.getTempDirectory() == null) {
            return;
        }
        cleanupTemp(result.getTempDirectory(), result.getProcessingFile());
    }

    private AudioSpecs readSpecs(File sourceFile) {
        try {
            AudioFile audioFile = AudioFileIO.read(sourceFile);
            AudioHeader header = audioFile.getAudioHeader();
            int sampleRate = parsePositiveInt(header.getSampleRate());
            int bitDepth = parsePositiveInt(String.valueOf(header.getBitsPerSample()));
            return new AudioSpecs(sampleRate, bitDepth);
        } catch (Exception e) {
            log.debug("Failed to read audio specs: {} - {}", sourceFile.getName(), e.getMessage());
            return AudioSpecs.unknown();
        }
    }

    private boolean needsNormalization(int sampleRate, int bitDepth) {
        return requiresNormalization(sampleRate, bitDepth);
    }

    /** 音频规格是否触发格式规范化降采样/降位深（>48kHz 或 >24bit）。 */
    public static boolean requiresNormalization(int sampleRate, int bitDepth) {
        return sampleRate > TARGET_SAMPLE_RATE || bitDepth > TARGET_BIT_DEPTH;
    }

    /**
     * 判断 {@code candidate} 是否可能是 {@code original} 经本规范化器降采样/转码后的 FLAC 产物：
     * 仅当原文件确实满足 {@link #requiresNormalization}（如 96kHz/24bit），且目标为 <=48kHz / <=24bit 的 FLAC、
     * 且两者播放时长相差不超过 2 秒时返回 {@code true}。
     * 普通 44.1kHz/48kHz 文件绝不触发此回退，保持严格 FLAC STREAMINFO MD5 校验不变。
     */
    public static boolean isNormalizedFlacCounterpart(Path original, Path candidate) {
        if (original == null || candidate == null
                || !Files.isRegularFile(original) || !Files.isRegularFile(candidate)) {
            return false;
        }
        String candName = candidate.getFileName() == null ? "" : candidate.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!candName.endsWith(".flac")) {
            return false;
        }
        AudioStreamMeta origMeta = readStreamMeta(original);
        if (origMeta == null || !requiresNormalization(origMeta.sampleRate(), origMeta.bitDepth())) {
            return false;
        }
        AudioStreamMeta candMeta = readFlacStreamInfo(candidate);
        if (candMeta == null || candMeta.sampleRate() <= 0 || candMeta.sampleRate() > TARGET_SAMPLE_RATE) {
            return false;
        }
        if (candMeta.bitDepth() > TARGET_BIT_DEPTH) {
            return false;
        }
        if (origMeta.durationSeconds() <= 0.0 || candMeta.durationSeconds() <= 0.0) {
            return false;
        }
        return Math.abs(origMeta.durationSeconds() - candMeta.durationSeconds()) <= 2.0;
    }

    private record AudioStreamMeta(int sampleRate, int bitDepth, double durationSeconds) { }

    private static AudioStreamMeta readStreamMeta(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".flac")) {
            AudioStreamMeta flac = readFlacStreamInfo(path);
            if (flac != null) {
                return flac;
            }
        }
        try {
            AudioFile af = AudioFileIO.read(path.toFile());
            AudioHeader h = af.getAudioHeader();
            int rate = parsePositiveIntStatic(h.getSampleRate());
            int bits = parsePositiveIntStatic(String.valueOf(h.getBitsPerSample()));
            double dur = h.getPreciseTrackLength() > 0 ? h.getPreciseTrackLength() : h.getTrackLength();
            return new AudioStreamMeta(rate, bits, dur);
        } catch (Exception e) {
            return null;
        }
    }

    /** 直接解析 FLAC STREAMINFO（兼容前置 ID3v2），零重型解码依赖且无日志噪音。 */
    private static AudioStreamMeta readFlacStreamInfo(Path path) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path.toFile(), "r")) {
            long offset = 0;
            byte[] header = new byte[10];
            if (raf.read(header) < 10) return null;
            if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
                long size = ((header[6] & 0x7F) << 21) | ((header[7] & 0x7F) << 14)
                    | ((header[8] & 0x7F) << 7) | (header[9] & 0x7F);
                boolean footer = (header[5] & 0x10) != 0;
                offset = 10 + size + (footer ? 10 : 0);
            }
            raf.seek(offset);
            byte[] block = new byte[4 + 4 + 34];
            raf.readFully(block);
            if (block[0] != 'f' || block[1] != 'L' || block[2] != 'a' || block[3] != 'C') {
                return null;
            }
            int blockType = block[4] & 0x7F;
            int length = ((block[5] & 0xFF) << 16) | ((block[6] & 0xFF) << 8) | (block[7] & 0xFF);
            if (blockType != 0 || length < 34) {
                return null;
            }
            int off = 8;
            int b10 = block[off + 10] & 0xFF;
            int b11 = block[off + 11] & 0xFF;
            int b12 = block[off + 12] & 0xFF;
            int b13 = block[off + 13] & 0xFF;
            int b14 = block[off + 14] & 0xFF;
            int b15 = block[off + 15] & 0xFF;
            int b16 = block[off + 16] & 0xFF;
            int b17 = block[off + 17] & 0xFF;
            int sampleRate = (b10 << 12) | (b11 << 4) | (b12 >>> 4);
            int bitDepth = (((b12 & 0x01) << 4) | (b13 >>> 4)) + 1;
            long totalSamples = (((long) (b13 & 0x0F)) << 32)
                | (((long) b14) << 24)
                | (((long) b15) << 16)
                | (((long) b16) << 8)
                | ((long) b17);
            double duration = (sampleRate > 0 && totalSamples > 0) ? (totalSamples / (double) sampleRate) : 0.0;
            return new AudioStreamMeta(sampleRate, bitDepth, duration);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int parsePositiveIntStatic(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(value.trim()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean runFfmpeg(File sourceFile, File outputFile, int bitDepth) throws IOException {
        List<String> command = new ArrayList<>();
        String ffmpegPath = config.getAudioNormalizeFfmpegPath();
        if (ffmpegPath == null || ffmpegPath.isEmpty()) {
            ffmpegPath = "ffmpeg";
        }
        command.add(ffmpegPath);
        command.add("-nostdin");
        command.add("-y");
        command.add("-i");
        command.add(sourceFile.getAbsolutePath());
        command.add("-vn");
        command.add("-af");
        String dither = bitDepth > TARGET_BIT_DEPTH ? ":dither_method=triangular" : "";
        command.add(String.format(
            "aresample=%d:resampler=soxr:precision=28:cutoff=0.95%s",
            TARGET_SAMPLE_RATE,
            dither
        ));
        command.add("-c:a");
        command.add("flac");
        command.add("-f");
        command.add("flac");
        command.add(outputFile.getAbsolutePath());

        ProcessRunner.Result result;
        try {
            result = ProcessRunner.execute(command, FFMPEG_TIMEOUT_SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg interrupted", e);
        }

        if (!result.isSuccess()) {
            log.warn("ffmpeg normalize failed (code {}): {}", result.getExitCode(), result.getOutput().trim());
            return false;
        }
        return outputFile.exists();
    }

    private File buildFlacOutputFile(File baseFile, File targetDir) {
        String name = baseFile.getName();
        int dotIndex = name.lastIndexOf('.');
        String baseName = dotIndex > 0 ? name.substring(0, dotIndex) : name;
        return new File(targetDir, baseName + ".flac");
    }

    private void cleanupTemp(Path tempDir, File outputFile) {
        if (outputFile != null && outputFile.exists()) {
            try {
                Files.deleteIfExists(outputFile.toPath());
            } catch (IOException e) {
                log.debug("Failed to delete temp file: {} - {}", outputFile.getAbsolutePath(), e.getMessage());
            }
        }
        if (tempDir != null) {
            try {
                Files.deleteIfExists(tempDir);
            } catch (IOException e) {
                log.debug("Failed to delete temp dir: {} - {}", tempDir, e.getMessage());
            }
        }
    }

    private int parsePositiveInt(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(parsed, 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static class AudioSpecs {
        private final int sampleRate;
        private final int bitDepth;

        private AudioSpecs(int sampleRate, int bitDepth) {
            this.sampleRate = sampleRate;
            this.bitDepth = bitDepth;
        }

        private static AudioSpecs unknown() {
            return new AudioSpecs(0, 0);
        }

        private boolean isKnown() {
            return sampleRate > 0 || bitDepth > 0;
        }
    }

    public static class NormalizationResult {
        private final File processingFile;
        private final Path tempDirectory;
        private final boolean converted;

        private NormalizationResult(File processingFile, Path tempDirectory, boolean converted) {
            this.processingFile = processingFile;
            this.tempDirectory = tempDirectory;
            this.converted = converted;
        }

        public static NormalizationResult noop(File sourceFile) {
            return new NormalizationResult(sourceFile, null, false);
        }

        public static NormalizationResult converted(File processingFile, Path tempDirectory) {
            return new NormalizationResult(processingFile, tempDirectory, true);
        }

        public File getProcessingFile() {
            return processingFile;
        }

        public Path getTempDirectory() {
            return tempDirectory;
        }

        public boolean isConverted() {
            return converted;
        }
    }
}

