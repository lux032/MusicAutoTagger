package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.config.MusicConfig;
import org.example.util.I18nUtil;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
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
            File outputFile = tempDir.resolve(sourceFile.getName()).toFile();

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

        try {
            boolean ok = runFfmpeg(sourceFile, targetFile, specs.bitDepth);
            if (ok) {
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
            } else {
                LogCollector.addLog("WARN", I18nUtil.getMessage("audio.normalize.failed", sourceFile.getName()));
            }
            return ok;
        } catch (IOException e) {
            log.warn("Failed to normalize audio for partial output: {} - {}", sourceFile.getName(), e.getMessage());
            LogCollector.addLog("WARN", I18nUtil.getMessage("audio.normalize.failed", sourceFile.getName()));
            return false;
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
        boolean sampleRateHigh = sampleRate > TARGET_SAMPLE_RATE;
        boolean bitDepthHigh = bitDepth > TARGET_BIT_DEPTH;
        return sampleRateHigh || bitDepthHigh;
    }

    private boolean runFfmpeg(File sourceFile, File outputFile, int bitDepth) throws IOException {
        List<String> command = new ArrayList<>();
        String ffmpegPath = config.getAudioNormalizeFfmpegPath();
        if (ffmpegPath == null || ffmpegPath.isEmpty()) {
            ffmpegPath = "ffmpeg";
        }
        command.add(ffmpegPath);
        command.add("-y");
        command.add("-i");
        command.add(sourceFile.getAbsolutePath());
        command.add("-vn");
        command.add("-ar");
        command.add(String.valueOf(TARGET_SAMPLE_RATE));
        if (bitDepth > TARGET_BIT_DEPTH) {
            command.add("-sample_fmt");
            command.add("s24");
        }
        command.add(outputFile.getAbsolutePath());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg interrupted", e);
        }

        if (exitCode != 0) {
            log.warn("ffmpeg normalize failed (code {}): {}", exitCode, output.toString().trim());
            return false;
        }
        return outputFile.exists();
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
