package com.lux032.musicautotagger.service;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 提交恢复结果前，检查音乐库里是否已经有同一份音频。
 *
 * 只按目录名防撞（{@code online.destination.exists}）拦不住「同一首歌换了个目录名再入库」，
 * 例如自动归档到 {@code 艺术家/ぴ}，联网确认又写到 {@code 艺术家/ぴ (Single)}。
 *
 * 身份依据是 FLAC STREAMINFO 里的解码后音频 MD5：改标签、换封面、改文件名都不影响它，
 * 读取只需文件开头几十个字节。非 FLAC 或编码器没写 MD5 的文件不参与判定（不会误报）。
 */
@Slf4j
public class ArchiveDuplicateDetector {

    private final Predicate<File> isAudioFile;

    public ArchiveDuplicateDetector(Predicate<File> isAudioFile) {
        this.isAudioFile = isAudioFile;
    }

    /**
     * 一个已存在的归档单元（artist/album 目录，或没有专辑层时的单个文件）中与本次输入重复的情况。
     *
     * @param path            归档单元的绝对路径
     * @param matchedFiles    该单元内与本次输入音频相同的文件
     * @param totalAudioFiles 该单元内的音频文件总数
     */
    public record DuplicateGroup(Path path, List<Path> matchedFiles, int totalAudioFiles) {
        /** 单元里的每首都能在本次输入中找到，替换它不会丢掉任何别的曲目。 */
        public boolean replaceable() {
            return !matchedFiles.isEmpty() && matchedFiles.size() == totalAudioFiles;
        }

        public Map<String, Object> toView(Path outputRoot) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("path", display(outputRoot, path));
            view.put("matched", matchedFiles.size());
            view.put("total", totalAudioFiles);
            view.put("replaceable", replaceable());
            return view;
        }

        private static String display(Path outputRoot, Path path) {
            try {
                return outputRoot.toAbsolutePath().normalize().relativize(path).toString();
            } catch (IllegalArgumentException e) {
                return path.toString();
            }
        }
    }

    /**
     * @param incomingAudio 即将提交的音频文件
     * @param outputRoot    输出根目录，用于把命中文件归并到归档单元
     * @param searchRoots   要扫描的目录（通常是本次将写入的艺术家目录）
     * @param excluded      不参与比较的路径（例如已确定要替换的自动归档）
     */
    public List<DuplicateGroup> find(Collection<Path> incomingAudio, Path outputRoot,
                                     Collection<Path> searchRoots, Collection<Path> excluded) {
        Set<String> incoming = new LinkedHashSet<>();
        for (Path file : incomingAudio) {
            flacAudioMd5(file).ifPresent(incoming::add);
        }
        if (incoming.isEmpty()) return List.of();

        Path root = outputRoot.toAbsolutePath().normalize();
        List<Path> excludedPaths = excluded == null ? List.of()
            : excluded.stream().map(p -> p.toAbsolutePath().normalize()).toList();

        Map<Path, List<Path>> matchedByUnit = new LinkedHashMap<>();
        Map<Path, Integer> totalByUnit = new HashMap<>();
        Set<Path> scanned = new LinkedHashSet<>();
        for (Path searchRoot : searchRoots) {
            Path normalized = searchRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized) || !scanned.add(normalized)) continue;
            for (Path existing : listAudio(normalized, root)) {
                if (excludedPaths.stream().anyMatch(existing::startsWith)) continue;
                Path unit = unitOf(root, existing);
                totalByUnit.merge(unit, 1, Integer::sum);
                Optional<String> md5 = flacAudioMd5(existing);
                if (md5.isPresent() && incoming.contains(md5.get())) {
                    matchedByUnit.computeIfAbsent(unit, k -> new ArrayList<>()).add(existing);
                }
            }
        }

        List<DuplicateGroup> groups = new ArrayList<>();
        matchedByUnit.forEach((unit, matched) -> groups.add(
            new DuplicateGroup(unit, List.copyOf(matched), totalByUnit.getOrDefault(unit, matched.size()))));
        return groups;
    }

    /** 与 RecoveryService 的提交单元保持一致：有专辑层时为 artist/album，否则为文件本身。 */
    private Path unitOf(Path root, Path file) {
        if (!file.startsWith(root)) return file;
        Path relative = root.relativize(file);
        return relative.getNameCount() >= 3 ? root.resolve(relative.subpath(0, 2)) : file;
    }

    private List<Path> listAudio(Path dir, Path root) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                .filter(Files::isRegularFile)
                // 跳过提交暂存目录、隐藏工作目录等不属于音乐库的内容
                .filter(p -> !hasHiddenSegment(root, p))
                .filter(p -> isAudioFile.test(p.toFile()))
                .sorted()
                .toList();
        } catch (IOException e) {
            log.warn("扫描重复音频失败，跳过目录: {} - {}", dir, e.getMessage());
            return List.of();
        }
    }

    private static boolean hasHiddenSegment(Path root, Path file) {
        Path relative = file.startsWith(root) ? root.relativize(file) : file;
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) return true;
        }
        return false;
    }

    /**
     * 读取 FLAC STREAMINFO 中的音频 MD5（十六进制）。
     * 兼容文件头前带 ID3v2 的 FLAC；不是 FLAC、读取失败或 MD5 全零时返回空。
     */
    public static Optional<String> flacAudioMd5(Path file) {
        if (file == null || !file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".flac")) {
            return Optional.empty();
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long offset = 0;
            byte[] header = new byte[10];
            if (raf.read(header) < 10) return Optional.empty();
            if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
                long size = ((header[6] & 0x7F) << 21) | ((header[7] & 0x7F) << 14)
                    | ((header[8] & 0x7F) << 7) | (header[9] & 0x7F);
                boolean footer = (header[5] & 0x10) != 0;
                offset = 10 + size + (footer ? 10 : 0);
            }
            raf.seek(offset);
            byte[] magicAndBlock = new byte[4 + 4 + 34];
            raf.readFully(magicAndBlock);
            if (magicAndBlock[0] != 'f' || magicAndBlock[1] != 'L' || magicAndBlock[2] != 'a' || magicAndBlock[3] != 'C') {
                return Optional.empty();
            }
            int blockType = magicAndBlock[4] & 0x7F;
            int length = ((magicAndBlock[5] & 0xFF) << 16) | ((magicAndBlock[6] & 0xFF) << 8) | (magicAndBlock[7] & 0xFF);
            if (blockType != 0 || length < 34) return Optional.empty();
            StringBuilder hex = new StringBuilder(32);
            boolean allZero = true;
            for (int i = 8 + 18; i < 8 + 34; i++) {
                int b = magicAndBlock[i] & 0xFF;
                if (b != 0) allZero = false;
                hex.append(Character.forDigit(b >> 4, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return allZero ? Optional.empty() : Optional.of(hex.toString());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
