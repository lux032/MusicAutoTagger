package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.util.MetadataUtils;
import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFileIO;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 音频文件原子发布与防静默覆盖发布器（从 TagWriterService 抽离）。
 * 负责临时文件原子落盘、冲突后缀避让、FLAC STREAMINFO MD5 与时长/元数据差异判定，以及 POSIX 权限规范化。
 */
@Slf4j
public class AtomicAudioPublisher {

    /** 判定「同一段音频」的时长容差（秒）。重编码 / 降采样带来的时长偏差远小于此值。 */
    public static final double SAME_AUDIO_DURATION_TOLERANCE_SECONDS = 1.0;
    private static final int MAX_CONFLICT_SUFFIX = 99;

    private final Function<File, MusicMetadata> tagReader;

    public AtomicAudioPublisher(Function<File, MusicMetadata> tagReader) {
        this.tagReader = tagReader;
    }

    public File publishWithoutClobbering(File sourceFile,
                                         File targetFile,
                                         Path staged,
                                         TagWriterService.CollisionPolicy collisionPolicy,
                                         Path expectedReplaceTarget,
                                         Set<Path> batchWritten) throws IOException {
        Double sourceDuration = null;
        boolean sourceDurationRead = false;

        File candidate = targetFile;
        for (int attempt = 1; attempt <= MAX_CONFLICT_SUFFIX; attempt++) {
            Path normalizedCandidate = candidate.toPath().toAbsolutePath().normalize();
            if (!candidate.exists()) {
                try {
                    publish(sourceFile, staged, candidate.toPath(), false);
                    if (batchWritten != null) batchWritten.add(normalizedCandidate);
                    logCopy(sourceFile, candidate, targetFile);
                    return candidate;
                } catch (FileAlreadyExistsException raced) {
                    // 并发抢先创建，落入已存在判定
                }
            }

            if (collisionPolicy == TagWriterService.CollisionPolicy.PRESERVE_ALL) {
                candidate = TargetPathResolver.withConflictSuffix(targetFile, attempt + 1);
                continue;
            }
            if (batchWritten != null && batchWritten.contains(normalizedCandidate)) {
                log.warn("⚠ 同批次目标路径冲突，保留先写入音轨并另存: {}", candidate.getName());
                candidate = TargetPathResolver.withConflictSuffix(targetFile, attempt + 1);
                continue;
            }
            if (expectedReplaceTarget != null && normalizedCandidate.equals(expectedReplaceTarget)) {
                publish(sourceFile, staged, candidate.toPath(), true);
                if (batchWritten != null) batchWritten.add(normalizedCandidate);
                logCopy(sourceFile, candidate, targetFile);
                return candidate;
            }
            if (!sourceDurationRead) {
                sourceDuration = readPreciseDuration(sourceFile);
                sourceDurationRead = true;
            }
            Double existingDuration = readPreciseDuration(candidate);
            File contentToCheck = staged != null ? staged.toFile() : sourceFile;
            if (sourceDuration != null && existingDuration != null
                && Math.abs(sourceDuration - existingDuration) <= SAME_AUDIO_DURATION_TOLERANCE_SECONDS
                && !isProvablyDifferentAudio(contentToCheck, candidate)) {
                publish(sourceFile, staged, candidate.toPath(), true);
                if (batchWritten != null) batchWritten.add(normalizedCandidate);
                logCopy(sourceFile, candidate, targetFile);
                return candidate;
            }

            log.warn("⚠ 目标文件已存在且音频不同（新 {}s / 已有 {}s），不覆盖: {}",
                formatDuration(sourceDuration), formatDuration(existingDuration), candidate.getName());
            candidate = TargetPathResolver.withConflictSuffix(targetFile, attempt + 1);
        }
        throw new IOException("目标文件名冲突过多，放弃写入: " + targetFile.getAbsolutePath());
    }

    /**
     * 当两首文件时长接近时，进一步基于无损音频流指纹（FLAC STREAMINFO MD5）及元数据特征判断是否为不同音轨
     * （例如同长度的原唱与伴奏/Off Vocal），避免误覆盖导致曲目丢失。
     */
    boolean isProvablyDifferentAudio(File incoming, File existing) {
        if (incoming == null || existing == null) {
            return false;
        }
        String md5A = readFlacStreaminfoMd5(incoming);
        String md5B = readFlacStreaminfoMd5(existing);
        if (md5A != null && md5B != null && !md5A.equals(md5B)) {
            return true;
        }
        if (tagReader != null) {
            try {
                MusicMetadata mdA = tagReader.apply(incoming);
                MusicMetadata mdB = tagReader.apply(existing);
                if (mdA != null && mdB != null) {
                    if (mdA.getRecordingId() != null && !mdA.getRecordingId().isBlank()
                        && mdB.getRecordingId() != null && !mdB.getRecordingId().isBlank()
                        && !mdA.getRecordingId().equals(mdB.getRecordingId())) {
                        return true;
                    }
                    if (mdA.getTitle() != null && !mdA.getTitle().isBlank()
                        && mdB.getTitle() != null && !mdB.getTitle().isBlank()
                        && MetadataUtils.hasVersionQualifier(mdA.getTitle()) != MetadataUtils.hasVersionQualifier(mdB.getTitle())) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public static String readFlacStreaminfoMd5(File file) {
        if (file == null || !file.getName().toLowerCase(Locale.ROOT).endsWith(".flac")) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] head = in.readNBytes(42);
            if (head.length < 42 || head[0] != 'f' || head[1] != 'L' || head[2] != 'a' || head[3] != 'C') {
                return null;
            }
            if ((head[4] & 0x7f) != 0) {
                return null;
            }
            int blockLen = ((head[5] & 0xff) << 16) | ((head[6] & 0xff) << 8) | (head[7] & 0xff);
            if (blockLen < 34) {
                return null;
            }
            boolean allZero = true;
            for (int i = 26; i < 42; i++) {
                if (head[i] != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                return null;
            }
            return HexFormat.of().formatHex(head, 26, 42);
        } catch (IOException ignored) {
            return null;
        }
    }

    public static void moveAtomically(Path staged, Path target) throws IOException {
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void publish(File source, Path staged, Path target, boolean replace) throws IOException {
        if (staged == null) {
            if (replace) {
                Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(source.toPath(), target);
            }
        } else if (replace) {
            moveAtomically(staged, target);
        } else {
            Files.move(staged, target);
        }
    }

    private void logCopy(File sourceFile, File actual, File intended) {
        if (actual.equals(intended)) {
            log.info("复制文件: {} -> {}", sourceFile.getName(), actual.getName());
        } else {
            log.warn("⚠ 目标路径冲突，已另存: {} -> {}（请检查该曲目的识别结果）",
                sourceFile.getName(), actual.getName());
            LogCollector.addLog("WARN", "目标路径冲突，未覆盖已有文件，已另存为: " + actual.getName()
                + "（源文件: " + sourceFile.getName() + "，请检查识别结果）");
        }
    }

    public static Double readPreciseDuration(File file) {
        try {
            double seconds = AudioFileIO.read(file).getAudioHeader().getPreciseTrackLength();
            return Double.isFinite(seconds) && seconds > 0 ? seconds : null;
        } catch (Exception e) {
            log.debug("读取音频时长失败: {} - {}", file.getName(), e.getMessage());
            return null;
        }
    }

    private static String formatDuration(Double seconds) {
        return seconds == null ? "?" : String.format("%.1f", seconds);
    }

    public static void ensureWritablePermissions(Path path, boolean isDirectory) {
        try {
            if (Files.getFileAttributeView(path, PosixFileAttributeView.class) == null) {
                return;
            }
            String modeKey = isDirectory ? "MTG_DIR_MODE" : "MTG_FILE_MODE";
            String configuredMode = System.getenv(modeKey);
            if (configuredMode == null || configuredMode.isBlank()) {
                String enabled = System.getenv("MTG_CHMOD");
                if (enabled != null && enabled.equalsIgnoreCase("false")) {
                    return;
                }
                configuredMode = isDirectory ? "rwxr-xr-x" : "rw-r--r--";
            }
            Set<PosixFilePermission> permissions = parsePermissions(configuredMode.trim());
            Files.setPosixFilePermissions(path, permissions);
        } catch (Exception e) {
            log.debug("无法设置文件权限: {}", path);
        }
    }

    private static Set<PosixFilePermission> parsePermissions(String mode) {
        if (mode.matches("[0-7]{3,4}")) {
            String digits = mode.length() == 4 ? mode.substring(1) : mode;
            Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
            applyPermissions(permissions, digits.charAt(0), PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
            applyPermissions(permissions, digits.charAt(1), PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE);
            applyPermissions(permissions, digits.charAt(2), PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
            return permissions;
        }
        return PosixFilePermissions.fromString(mode);
    }

    private static void applyPermissions(Set<PosixFilePermission> permissions, char digit,
                                         PosixFilePermission read, PosixFilePermission write,
                                         PosixFilePermission execute) {
        int value = digit - '0';
        if ((value & 4) != 0) {
            permissions.add(read);
        }
        if ((value & 2) != 0) {
            permissions.add(write);
        }
        if ((value & 1) != 0) {
            permissions.add(execute);
        }
    }
}
