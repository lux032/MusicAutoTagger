package com.lux032.musicautotagger.service;

import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.util.ArtistCreditSupport;
import com.lux032.musicautotagger.util.FileNameSanitizer;
import com.lux032.musicautotagger.util.FlacId3Stripper;
import com.lux032.musicautotagger.util.MetadataUtils;
import com.lux032.musicautotagger.util.TagQualityEvaluator;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.mp3.MP3File;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.wav.WavTag;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagField;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.id3.AbstractID3v2Frame;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v24Frames;
import org.jaudiotagger.tag.id3.framebody.AbstractFrameBodyTextInfo;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.id3.valuepair.TextEncoding;
import org.jaudiotagger.tag.mp4.Mp4Tag;
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.StandardArtwork;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 音乐标签写入服务
 * 使用 JAudioTagger 库写入音频文件的元数据标签
 */
@Slf4j
public class TagWriterService {

    private final MusicConfig config;
    private final TargetPathResolver targetPathResolver;
    private final AtomicAudioPublisher publisher;
    /**
     * 恢复任务线程专用输出根目录，不影响并发运行的常规监控线程。
     *
     * 重要约束：覆盖值不会跨线程传递。调用方必须保证
     * setThreadOutputRoot → processFile → clearThreadOutputRoot 全程在同一线程内完成。
     * 若处理链路中途派发到线程池，文件会静默写入真实 outputDirectory 而绕过原子提交。
     * RecoveryService.commitWorkspace 的文件数校验是针对这种情况的运行时兵库。
     */
    private final ThreadLocal<Path> outputRootOverride = new ThreadLocal<>();
    public enum CollisionPolicy { REPLACE_SAME_AUDIO, PRESERVE_ALL }
    private final ThreadLocal<CollisionPolicy> collisionPolicyOverride = new ThreadLocal<>();
    private final ThreadLocal<Path> expectedReplaceTargetOverride = new ThreadLocal<>();
    private final ThreadLocal<Set<Path>> batchWrittenTargets = new ThreadLocal<>();

    /** 同批次专辑写入守卫：防止同一批次内两首等长曲目（如原唱与伴奏）写到同一目标路径互相覆盖。 */
    public AutoCloseable batchNoIntraClobberScope() {
        Set<Path> previous = batchWrittenTargets.get();
        batchWrittenTargets.set(new java.util.HashSet<>());
        return () -> {
            if (previous == null) {
                batchWrittenTargets.remove();
            } else {
                batchWrittenTargets.set(previous);
            }
        };
    }

    /** Same-thread scope; nested scopes restore both values, including on exceptions. */
    public final class OutputScope implements AutoCloseable {
        private final Path previousRoot = outputRootOverride.get();
        private final CollisionPolicy previousPolicy = collisionPolicyOverride.get();
        private boolean closed;

        private OutputScope(File root, CollisionPolicy policy) {
            setThreadOutputRoot(root);
            collisionPolicyOverride.set(policy);
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previousRoot == null) outputRootOverride.remove();
            else outputRootOverride.set(previousRoot);
            if (previousPolicy == null) collisionPolicyOverride.remove();
            else collisionPolicyOverride.set(previousPolicy);
        }
    }

    public OutputScope recoveryWorkspace(File root) {
        return new OutputScope(root, CollisionPolicy.PRESERVE_ALL);
    }

    public TagWriterService(MusicConfig config) {
        this.config = config;
        this.targetPathResolver = new TargetPathResolver(config);
        this.publisher = new AtomicAudioPublisher(this::readTags);
    }

    public void setThreadOutputRoot(File root) {
        outputRootOverride.set(root.toPath().toAbsolutePath());
    }

    public void clearThreadOutputRoot() {
        outputRootOverride.remove();
    }

    public boolean isOutputRootOverridden() {
        return outputRootOverride.get() != null;
    }

    private Path outputRoot() {
        Path override = outputRootOverride.get();
        return override != null ? override : Paths.get(config.getOutputDirectory());
    }

    /** 标签写入结果，同时暴露实际归档文件供处理历史记录。 */
    public static final class TagProcessResult {
        private final boolean success;
        private final File targetFile;

        private TagProcessResult(boolean success, File targetFile) {
            this.success = success;
            this.targetFile = targetFile;
        }

        public static TagProcessResult failed() { return new TagProcessResult(false, null); }
        public boolean isSuccess() { return success; }
        public File getTargetFile() { return targetFile; }
    }

    /** 兼容旧调用；新调用需要目标路径时应使用 {@link #processFileWithResult}. */
    public boolean processFile(File sourceFile, MusicMetadata metadata, byte[] coverArtData) {
        return processFileWithResult(sourceFile, metadata, coverArtData).isSuccess();
    }

    /** 处理音频文件，并在预期覆盖目标路径匹配时直接替换旧归档。 */
    public TagProcessResult processFileWithResult(File sourceFile, MusicMetadata metadata, byte[] coverArtData,
                                                  Path expectedReplaceTarget) {
        Path previous = expectedReplaceTargetOverride.get();
        if (expectedReplaceTarget == null) {
            expectedReplaceTargetOverride.remove();
        } else {
            expectedReplaceTargetOverride.set(expectedReplaceTarget.toAbsolutePath().normalize());
        }
        try {
            return processFileWithResult(sourceFile, metadata, coverArtData);
        } finally {
            if (previous == null) {
                expectedReplaceTargetOverride.remove();
            } else {
                expectedReplaceTargetOverride.set(previous);
            }
        }
    }

    /** 处理音频文件，并返回实际写入的目标文件。 */
    public TagProcessResult processFileWithResult(File sourceFile, MusicMetadata metadata, byte[] coverArtData) {
        try (var libraryWrite = LibraryWriteGuard.acquire()) {
            if (!sourceFile.exists()) {
                log.error("源文件不存在: {}", sourceFile.getAbsolutePath());
                return new TagProcessResult(false, null);
            }

            if (FlacId3Stripper.hasUnsafeId3(sourceFile)) {
                log.warn("跳过无法安全保护 ID3 歌词的 FLAC: {}", sourceFile);
                return new TagProcessResult(false, null);
            }
            metadata.discardStaleAutomaticLyrics();
            File targetFile = null;
            Path staged = null;
            try {
                // 1. 确定目标文件路径
                targetFile = determineTargetFile(sourceFile, metadata);

                // 2. 创建目标目录
                if (!targetFile.getParentFile().exists()) {
                    targetFile.getParentFile().mkdirs();
                    ensureWritablePermissions(targetFile.getParentFile().toPath(), true);
                }

                String name = targetFile.getName();
                int dot = name.lastIndexOf('.');
                String extension = dot >= 0 ? name.substring(dot) : "";
                staged = Files.createTempFile(targetFile.getParentFile().toPath(), ".tag-write-", extension);
                Files.copy(sourceFile.toPath(), staged, StandardCopyOption.REPLACE_EXISTING);
                File stagedFile = staged.toFile();
                FlacId3Stripper.Result stripResult = FlacId3Stripper.stripId3FromFlac(stagedFile);
                if (isStripAborted(stripResult)) {
                    log.warn("FLAC ID3 剥离失败或存在不安全标签 ({})，中止写入: {}", stripResult, sourceFile.getName());
                    return TagProcessResult.failed();
                }

                log.info("开始更新标签: {}", targetFile.getName());
                AudioFile audioFileObj = AudioFileIO.read(stagedFile);
                Tag tag = audioFileObj.getTagOrCreateAndSetDefault();

                // 更新文本标签
                updateTextTags(tag, metadata);

                // 更新封面
                if (coverArtData != null && coverArtData.length > 0) {
                    log.info("写入封面图片...");
                    applyArtwork(tag, coverArtData);
                }

                // 保存更改
                audioFileObj.commit();
                ensureWritablePermissions(staged, false);
                targetFile = publishWithoutClobbering(sourceFile, targetFile, staged);
                log.info("文件处理完成: {}", targetFile.getName());

                // 如果配置开启,导出歌词到独立文件
                if (config.isExportLyricsToFile() && metadata.getLyrics() != null && !metadata.getLyrics().isEmpty()) {
                    exportLyricsToFile(targetFile, metadata.getLyrics());
                }

                return new TagProcessResult(true, targetFile.getAbsoluteFile());

            } catch (Exception e) {
                log.error("处理文件失败: {}", sourceFile.getName(), e);
                return new TagProcessResult(false, null);
            } finally {
                if (staged != null) {
                    try {
                        Files.deleteIfExists(staged);
                    } catch (IOException e) {
                        log.warn("无法清理标签写入暂存文件: {}", staged, e);
                    }
                }
            }

        }
    }

    /** 判定「同一段音频」的时长容差（秒）。重编码 / 降采样带来的时长偏差远小于此值。 */
    static final double SAME_AUDIO_DURATION_TOLERANCE_SECONDS = AtomicAudioPublisher.SAME_AUDIO_DURATION_TOLERANCE_SECONDS;

    File copyWithoutClobbering(File sourceFile, File targetFile) throws IOException {
        return publishWithoutClobbering(sourceFile, targetFile, null);
    }

    private File publishWithoutClobbering(File sourceFile, File targetFile, Path staged) throws IOException {
        return publisher.publishWithoutClobbering(
            sourceFile,
            targetFile,
            staged,
            collisionPolicyOverride.get(),
            expectedReplaceTargetOverride.get(),
            batchWrittenTargets.get()
        );
    }

    static File withConflictSuffix(File targetFile, int n) {
        return TargetPathResolver.withConflictSuffix(targetFile, n);
    }

    public static Double readPreciseDuration(File file) {
        return AtomicAudioPublisher.readPreciseDuration(file);
    }

    /**
     * 确定目标文件路径（路径解析委托给 {@link TargetPathResolver}）
     */
    private File determineTargetFile(File sourceFile, MusicMetadata metadata) {
        File targetFile = targetPathResolver.resolveTargetFile(sourceFile, metadata, outputRoot());
        File parentDir = targetFile.getParentFile();
        if (!parentDir.exists()) {
            log.info("创建目录: {}", parentDir.getAbsolutePath());
            parentDir.mkdirs();
            ensureWritablePermissions(parentDir.toPath(), true);
        }
        return targetFile;
    }

    /**
     * 将单个文件处理到指定根目录。恢复任务用它先构建完整工作区，
     * 避免逐首直接写入最终 outputDirectory。
     */
    public boolean processFileToRoot(File sourceFile, MusicMetadata metadata, byte[] coverArtData, File rootDirectory) {
        return processFileToRootWithResult(sourceFile, metadata, coverArtData, rootDirectory).isSuccess();
    }

    public TagProcessResult processFileToRootWithResult(File sourceFile, MusicMetadata metadata,
                                                       byte[] coverArtData, File rootDirectory) {
        try (OutputScope scope = new OutputScope(rootDirectory, collisionPolicyOverride.get())) {
            return processFileWithResult(sourceFile, metadata, coverArtData);
        }
    }

    private static boolean isStripAborted(FlacId3Stripper.Result result) {
        return result == FlacId3Stripper.Result.FAILED || result == FlacId3Stripper.Result.SKIPPED_UNSAFE;
    }

    static String extractTrackPrefix(String fileName) {
        return TargetPathResolver.extractTrackPrefix(fileName);
    }

    static String[] splitPosition(String value, String explicitTotal) {
        String number = value == null ? null : value.trim();
        String total = explicitTotal == null ? null : explicitTotal.trim();
        if (number != null && number.contains("/")) {
            String[] parts = number.split("/", 2);
            number = parts[0].trim();
            if ((total == null || total.isEmpty()) && parts.length > 1) total = parts[1].trim();
        }
        return new String[] { emptyToNull(number), emptyToNull(total) };
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private void setFieldSafely(Tag tag, FieldKey key, String value) {
        if (value == null || value.isEmpty()) return;
        try {
            tag.setField(key, value);
        } catch (Exception e) {
            log.debug("当前音频容器不支持标签 {}: {}", key, e.getMessage());
        }
    }

    static final String EXTRA_TRACK_COMMENT = "不在 MusicBrainz 所选版本中（附加曲目）";

    /** 在注释里追加附加曲目标记，保留已有注释；重复处理不会重复追加 */
    private void appendExtraTrackComment(Tag tag) {
        try {
            String existing = tag.getFirst(FieldKey.COMMENT);
            if (existing != null && existing.contains(EXTRA_TRACK_COMMENT)) return;
            String value = existing == null || existing.isBlank()
                ? EXTRA_TRACK_COMMENT : existing + " | " + EXTRA_TRACK_COMMENT;
            tag.setField(FieldKey.COMMENT, value);
        } catch (Exception e) {
            log.debug("写入附加曲目注释失败: {}", e.getMessage());
        }
    }

    private void deleteFieldSafely(Tag tag, FieldKey key) {
        try {
            tag.deleteField(key);
        } catch (Exception e) {
            log.debug("当前音频容器不支持删除标签 {}: {}", key, e.getMessage());
        }
    }

    /**
     * 更新文本标签
     */
    private static List<String> readArtistNames(Tag tag, FieldKey key) throws Exception {
        List<String> names = new ArrayList<>();
        for (String value : tag.getAll(key)) {
            for (String name : value.split("[;\\u0000]")) {
                if (!name.isBlank()) names.add(name.trim());
            }
        }
        return names.isEmpty() ? null : List.copyOf(names);
    }

    private static void writeArtistNames(Tag tag, FieldKey key, List<String> names) throws Exception {
        // Never leave a previous recording's structured credit alongside new text.
        tag.deleteField(key);
        if (names == null || names.isEmpty()) return;
        String[] values = names.stream().filter(n -> n != null && !n.isBlank())
            .map(String::trim).toArray(String[]::new);
        if (values.length == 0) return;
        // Preserve each person's name exactly, including punctuation. ID3v2.3
        // and MP4 encode the list using their container-specific representation.
        tag.setField(key, values[0]);
        for (int i = 1; i < values.length; i++) tag.addField(key, values[i]);
    }

    private static final List<String> COMPOSER_MBID_KEYS = List.of("MUSICBRAINZ_COMPOSERID", "MUSICBRAINZ COMPOSER ID");
    private static final List<String> LYRICIST_MBID_KEYS = List.of("MUSICBRAINZ_LYRICISTID", "MUSICBRAINZ LYRICIST ID");
    private static final List<String> ARRANGER_MBID_KEYS = List.of("MUSICBRAINZ_ARRANGERID", "MUSICBRAINZ ARRANGER ID");
    private static final List<String> MP4_CUSTOM_MEANS = List.of("com.apple.iTunes", "org.musicbrainz.iTunes");
    private static final java.util.regex.Pattern MBID_UUID_PATTERN =
        java.util.regex.Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private List<List<String>> readCustomTextTagGroups(Tag tag, String name) throws Exception {
        if (tag instanceof WavTag wav) {
            tag = wav.getActiveTag();
        }
        List<List<String>> groups = new ArrayList<>();
        if (tag instanceof FlacTag || tag instanceof VorbisCommentTag) {
            List<String> values = new ArrayList<>();
            for (TagField field : tag.getFields(name)) {
                if (field instanceof org.jaudiotagger.tag.TagTextField textField && textField.getContent() != null) {
                    values.add(textField.getContent());
                }
            }
            if (!values.isEmpty()) groups.add(values);
        } else if (tag instanceof Mp4Tag mp4) {
            java.util.Map<String, List<String>> byMean = new java.util.LinkedHashMap<>();
            for (TagField field : mp4.getAll()) {
                if (field instanceof Mp4TagReverseDnsField rdns && isSupportedMp4Mean(rdns.getIssuer())
                    && name.equalsIgnoreCase(rdns.getDescriptor())) {
                    String content = rdns.getContent();
                    if (content != null) {
                        byMean.computeIfAbsent(rdns.getIssuer().toLowerCase(java.util.Locale.ROOT), k -> new ArrayList<>())
                            .add(content);
                    }
                }
            }
            groups.addAll(byMean.values());
        } else if (tag instanceof AbstractID3v2Tag id3) {
            List<String> values = new ArrayList<>();
            List<TagField> frames = id3.getFrame("TXXX");
            if (frames != null) {
                for (TagField field : frames) {
                    if (field instanceof AbstractID3v2Frame frame
                        && frame.getBody() instanceof FrameBodyTXXX body
                        && name.equalsIgnoreCase(body.getDescription())) {
                        List<String> frameValues = body.getValues();
                        if (frameValues != null && !frameValues.isEmpty()) {
                            values.addAll(frameValues);
                        } else if (body.getText() != null) {
                            values.add(body.getText());
                        }
                    }
                }
            }
            if (!values.isEmpty()) groups.add(values);
        }
        return groups;
    }

    private void writeCustomTextTag(Tag tag, String name, String value) throws Exception {
        if (tag instanceof WavTag wav) {
            tag = wav.getActiveTag();
        }
        if (tag instanceof FlacTag flac) {
            flac.deleteField(name);
            if (value != null) flac.setField(name, value);
        } else if (tag instanceof VorbisCommentTag vorbis) {
            vorbis.deleteField(name);
            if (value != null) vorbis.setField(name, value);
        } else if (tag instanceof AbstractID3v2Tag id3) {
            removeTxxxByDescription(id3, name);
            if (value != null) {
                AbstractID3v2Frame frame = id3.createFrame("TXXX");
                byte encoding = id3.getMajorVersion() >= 4 ? TextEncoding.UTF_8 : TextEncoding.UTF_16;
                frame.setBody(new FrameBodyTXXX(encoding, name, value));
                id3.setField(frame);
            }
        } else if (tag instanceof Mp4Tag mp4) {
            List<String> toDelete = new ArrayList<>();
            for (TagField field : mp4.getAll()) {
                if (field instanceof Mp4TagReverseDnsField rdns && isSupportedMp4Mean(rdns.getIssuer())
                    && name.equalsIgnoreCase(rdns.getDescriptor())) {
                    toDelete.add(rdns.getId());
                }
            }
            for (String id : toDelete) {
                mp4.deleteField(id);
            }
            for (String mean : MP4_CUSTOM_MEANS) {
                mp4.deleteField("----:" + mean + ":" + name);
            }
            if (value != null) {
                String atom = "----:com.apple.iTunes:" + name;
                mp4.setField(new Mp4TagReverseDnsField(atom, "com.apple.iTunes", name, value));
            }
        }
    }

    private static boolean isSupportedMp4Mean(String issuer) {
        if (issuer == null) return false;
        for (String mean : MP4_CUSTOM_MEANS) {
            if (mean.equalsIgnoreCase(issuer)) return true;
        }
        return false;
    }

    private List<String> readCreatorNames(Tag tag, FieldKey key, String plural) throws Exception {
        List<List<String>> groups = readCustomTextTagGroups(tag, plural);
        if (!groups.isEmpty()) {
            List<String> agreed = null;
            for (List<String> group : groups) {
                List<String> parsed = parseStrictCreatorNameSlots(group);
                if (parsed == null) return null;
                if (agreed == null) agreed = parsed;
                else if (!agreed.equals(parsed)) return null;
            }
            return agreed;
        }
        List<String> nativeValues = tag.getAll(key);
        // A lone legacy value is display text, not a proven list.
        return nativeValues.size() > 1 ? List.copyOf(nativeValues) : null;
    }

    private static List<String> parseStrictCreatorNameSlots(List<String> rawValues) {
        List<String> names = new ArrayList<>();
        for (String raw : rawValues) {
            if (raw == null) return null;
            String cleaned = stripWhitespaceNotNul(raw);
            if (cleaned.startsWith("\ufeff")) cleaned = stripWhitespaceNotNul(cleaned.substring(1));
            for (String part : cleaned.split("[;\\u0000]", -1)) {
                String trimmed = stripWhitespaceNotNul(part);
                if (trimmed.startsWith("\ufeff")) trimmed = stripWhitespaceNotNul(trimmed.substring(1));
                if (trimmed.isEmpty()) return null;
                names.add(trimmed);
            }
        }
        return names.isEmpty() ? null : List.copyOf(names);
    }

    private List<String> readCreatorIds(Tag tag, String mbidKey, List<String> names) throws Exception {
        return readCreatorIds(tag, List.of(mbidKey), names, null, false);
    }

    private List<String> readCreatorIds(Tag tag, FieldKey key, String plural, List<String> mbidKeys, List<String> names, String singleDisplay) throws Exception {
        boolean hasStructured = !readCustomTextTagGroups(tag, plural).isEmpty();
        boolean singleNative = !hasStructured && names == null && singleDisplay != null && !singleDisplay.isBlank()
            && tag.getAll(key).size() == 1 && !looksCompositeDisplay(singleDisplay);
        return readCreatorIds(tag, mbidKeys, names, singleDisplay, singleNative);
    }

    private List<String> readCreatorIds(Tag tag, List<String> mbidKeys, List<String> names, String singleDisplay, boolean singleNative) throws Exception {
        if ((names == null || names.isEmpty()) && !singleNative) return null;
        List<String> agreed = null;
        for (String mbidKey : mbidKeys) {
            List<List<String>> groups = readCustomTextTagGroups(tag, mbidKey);
            for (List<String> group : groups) {
                List<String> parsed = parseStrictMbidList(group);
                if (parsed == null) return null;
                if (agreed == null) {
                    agreed = parsed;
                } else if (!agreed.equals(parsed)) {
                    return null;
                }
            }
        }
        if (agreed == null) return null;
        if (names != null && !names.isEmpty()) {
            return agreed.size() == names.size() ? agreed : null;
        }
        return singleNative && agreed.size() == 1 ? agreed : null;
    }

    private static List<String> parseStrictMbidList(List<String> rawValues) {
        List<String> ids = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String raw : rawValues) {
            if (raw == null) return null;
            String cleaned = stripWhitespaceNotNul(raw);
            if (cleaned.startsWith("\ufeff")) cleaned = stripWhitespaceNotNul(cleaned.substring(1));
            for (String part : cleaned.split("[;\\u0000/]", -1)) {
                String candidate = stripWhitespaceNotNul(part);
                if (candidate.startsWith("\ufeff")) candidate = stripWhitespaceNotNul(candidate.substring(1));
                String normalized = candidate.toLowerCase(java.util.Locale.ROOT);
                if (!MBID_UUID_PATTERN.matcher(normalized).matches()
                    || "00000000-0000-0000-0000-000000000000".equals(normalized)
                    || !seen.add(normalized)) {
                    return null;
                }
                ids.add(normalized);
            }
        }
        return ids.isEmpty() ? null : List.copyOf(ids);
    }

    private static String stripWhitespaceNotNul(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) != '\u0000' && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && value.charAt(end - 1) != '\u0000' && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean looksCompositeDisplay(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String sep : List.of(", ", " feat.", " feat ", " featuring ", " ft.", " ft ", " & ", " / ", "、", ";", "，")) {
            if (lower.contains(sep)) return true;
        }
        return false;
    }

    private void writeCreatorNames(Tag tag, FieldKey key, String plural, List<String> names, String display,
                                   List<String> mbidKeys, List<String> mbids) throws Exception {
        if (display == null || display.isEmpty()) return;
        if (names == null || names.isEmpty()) {
            tag.deleteField(key);
            tag.setField(key, display);
        } else writeArtistNames(tag, key, names);
        // Plural companion disambiguates a single name containing a comma,
        // even when a container/reader flattens its native multiple values.
        String value = names == null || names.isEmpty() ? null : String.join("; ", names);
        writeCustomTextTag(tag, plural, value);
        String mbidValue = alignedCreatorMbidValue(names, display, mbids);
        for (String alias : mbidKeys) {
            writeCustomTextTag(tag, alias, null);
        }
        if (mbidValue != null) {
            writeCustomTextTag(tag, mbidKeys.get(0), mbidValue);
        }
    }

    private static String alignedCreatorMbidValue(List<String> names, String display, List<String> mbids) {
        if (mbids == null || mbids.isEmpty()) return null;
        if (names != null && !names.isEmpty()) {
            if (mbids.size() != names.size()) return null;
        } else if (mbids.size() != 1 || looksCompositeDisplay(display)) {
            return null;
        }
        List<String> cleaned = new ArrayList<>(mbids.size());
        for (String id : mbids) {
            if (id == null || id.isBlank()) return null;
            cleaned.add(id.trim());
        }
        return String.join("; ", cleaned);
    }

    private static final String ARTIST_CREDIT_TAG = "MUSICBRAINZ_ARTIST_CREDIT";
    private static final String ALBUM_ARTIST_CREDIT_TAG = "MUSICBRAINZ_ALBUM_ARTIST_CREDIT";

    private String readArtistCreditTag(Tag tag, String name) throws Exception {
        List<List<String>> fields = readCustomTextTagGroups(tag, name);
        return fields.size() == 1 && fields.get(0).size() == 1 ? fields.get(0).get(0) : null;
    }

    private void updateTextTags(Tag tag, MusicMetadata metadata) throws Exception {
        if (metadata.getTitle() != null && !metadata.getTitle().isEmpty()) {
            tag.setField(FieldKey.TITLE, metadata.getTitle());
        }

        if (metadata.getArtist() != null && !metadata.getArtist().isEmpty()) {
            tag.setField(FieldKey.ARTIST, metadata.getArtist());
            writeArtistNames(tag, FieldKey.ARTISTS, metadata.getArtists());
            writeCustomTextTag(tag, ARTIST_CREDIT_TAG, metadata.getArtistCredit());
        }

        // 写入专辑艺术家标签
        if (metadata.getAlbumArtist() != null && !metadata.getAlbumArtist().isEmpty()) {
            tag.setField(FieldKey.ALBUM_ARTIST, metadata.getAlbumArtist());
            writeArtistNames(tag, FieldKey.ALBUM_ARTISTS, metadata.getAlbumArtists());
            writeCustomTextTag(tag, ALBUM_ARTIST_CREDIT_TAG, metadata.getAlbumArtistCredit());
        }

        if (metadata.getAlbum() != null && !metadata.getAlbum().isEmpty()) {
            tag.setField(FieldKey.ALBUM, metadata.getAlbum());
        }

        if (metadata.isClearReleaseDate()) {
            // 专辑未确定：必须显式删除原文件中可能残留的旧专辑年份，
            // 否则会出现「专辑名已更新、年份还是旧专辑」的矛盾结果。
            try {
                tag.deleteField(FieldKey.YEAR);
                log.info("专辑未确定，已清空年份标签（避免残留旧专辑年份）");
            } catch (Exception e) {
                log.warn("清空年份标签失败: {}", e.getMessage());
            }
        } else if (metadata.getReleaseDate() != null && !metadata.getReleaseDate().isEmpty()) {
            // 直接写入完整日期,不再只提取年份
            tag.setField(FieldKey.YEAR, metadata.getReleaseDate());
        }

        if (metadata.getGenres() != null && !metadata.getGenres().isEmpty()) {
            tag.setField(FieldKey.GENRE, String.join(", ", metadata.getGenres()));
        }

        if (metadata.getRecordingId() != null && !metadata.getRecordingId().isEmpty()) {
            tag.setField(FieldKey.MUSICBRAINZ_TRACK_ID, metadata.getRecordingId());
        }

        if (metadata.isClearReleaseType()) {
            try {
                tag.deleteField(FieldKey.MUSICBRAINZ_RELEASE_TYPE);
            } catch (Exception e) {
                log.debug("当前音频容器不支持清空 MusicBrainz 专辑类型: {}", e.getMessage());
            }
            try {
                tag.deleteField(FieldKey.IS_COMPILATION);
            } catch (Exception e) {
                log.debug("当前音频容器不支持清空合辑标志: {}", e.getMessage());
            }
            try {
                tag.deleteField(FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID);
            } catch (Exception e) {
                log.debug("当前音频容器不支持清空 MusicBrainz Release Group ID: {}", e.getMessage());
            }
            try {
                tag.deleteField(FieldKey.MUSICBRAINZ_RELEASEID);
            } catch (Exception e) {
                log.debug("当前音频容器不支持清空 MusicBrainz Release ID: {}", e.getMessage());
            }
        }
        // clearReleaseType 表示“先清旧值”，不是禁止写入当前已确定的值。
        setFieldSafely(tag, FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID, metadata.getReleaseGroupId());
        setFieldSafely(tag, FieldKey.MUSICBRAINZ_RELEASEID, metadata.getReleaseId());
        if (metadata.getReleaseType() != null && !metadata.getReleaseType().isEmpty()) {
            setFieldSafely(tag, FieldKey.MUSICBRAINZ_RELEASE_TYPE,
                metadata.getReleaseType().toLowerCase(java.util.Locale.ROOT));
        }
        if (metadata.isCompilation()) setFieldSafely(tag, FieldKey.IS_COMPILATION, "1");

        if (metadata.isClearAlbumLevelTags()) {
            FieldKey[] albumKeys = {
                FieldKey.MUSICBRAINZ_RELEASEARTISTID, FieldKey.MUSICBRAINZ_RELEASE_STATUS,
                FieldKey.MUSICBRAINZ_RELEASE_COUNTRY, FieldKey.ALBUM_ARTIST_SORT, FieldKey.BARCODE,
                FieldKey.CATALOG_NO, FieldKey.RECORD_LABEL, FieldKey.SCRIPT, FieldKey.DISC_TOTAL
            };
            for (FieldKey key : albumKeys) deleteFieldSafely(tag, key);
            clearOriginalReleaseTags(tag);
        }
        setFieldSafely(tag, FieldKey.MUSICBRAINZ_ARTISTID, metadata.getArtistId());
        setFieldSafely(tag, FieldKey.MUSICBRAINZ_RELEASEARTISTID, metadata.getAlbumArtistId());
        setFieldSafely(tag, FieldKey.MUSICBRAINZ_RELEASE_TRACK_ID, metadata.getReleaseTrackId());
        setFieldSafely(tag, FieldKey.ARTIST_SORT, metadata.getArtistSort());
        setFieldSafely(tag, FieldKey.ALBUM_ARTIST_SORT, metadata.getAlbumArtistSort());
        setFieldSafely(tag, FieldKey.MUSICBRAINZ_RELEASE_STATUS, metadata.getReleaseStatus());
        setFieldSafely(tag, FieldKey.MUSICBRAINZ_RELEASE_COUNTRY, metadata.getReleaseCountry());
        setFieldSafely(tag, FieldKey.MEDIA, metadata.getMediaFormat());
        setFieldSafely(tag, FieldKey.SCRIPT, metadata.getScript());
        setFieldSafely(tag, FieldKey.BARCODE, metadata.getBarcode());
        setFieldSafely(tag, FieldKey.CATALOG_NO, metadata.getCatalogNumber());
        setFieldSafely(tag, FieldKey.RECORD_LABEL, metadata.getRecordLabel());
        writeOriginalReleaseTags(tag, metadata.getOriginalReleaseDate(), metadata.getOriginalYear());

        if (metadata.isExtraTrack()) {
            // 附加曲目：清掉源文件残留的曲目号 / 碟号，否则播放器会把它和版本里的同号曲目排在一起
            deleteFieldSafely(tag, FieldKey.TRACK);
            deleteFieldSafely(tag, FieldKey.TRACK_TOTAL);
            deleteFieldSafely(tag, FieldKey.DISC_NO);
            deleteFieldSafely(tag, FieldKey.DISC_TOTAL);
            deleteFieldSafely(tag, FieldKey.MUSICBRAINZ_RELEASE_TRACK_ID);
            appendExtraTrackComment(tag);
        }

        // MP4 的 TRACK/DISC 字段不能接收 "1/6"；统一拆成位置与总数。
        if (!metadata.isExtraTrack()) {
            String[] disc = splitPosition(metadata.getDiscNo(), metadata.getDiscTotal());
            String[] track = splitPosition(metadata.getTrackNo(), metadata.getTrackTotal());
            setFieldSafely(tag, FieldKey.DISC_NO, disc[0]);
            setFieldSafely(tag, FieldKey.DISC_TOTAL, disc[1]);
            setFieldSafely(tag, FieldKey.TRACK, track[0]);
            setFieldSafely(tag, FieldKey.TRACK_TOTAL, track[1]);
        }

        // 写入作曲家
        if (metadata.getComposer() != null && !metadata.getComposer().isEmpty()) {
            writeCreatorNames(tag, FieldKey.COMPOSER, "COMPOSERS", metadata.getComposers(), metadata.getComposer(),
                COMPOSER_MBID_KEYS, metadata.getComposerIds());
        }

        // 写入作词家 (使用 LYRICIST 字段)
        if (metadata.getLyricist() != null && !metadata.getLyricist().isEmpty()) {
            writeCreatorNames(tag, FieldKey.LYRICIST, "LYRICISTS", metadata.getLyricists(), metadata.getLyricist(),
                LYRICIST_MBID_KEYS, metadata.getLyricistIds());
        }

        // 写入编曲（部分容器可能不支持该字段，失败只记日志）
        if (metadata.getArranger() != null && !metadata.getArranger().isEmpty()) {
            writeCreatorNames(tag, FieldKey.ARRANGER, "ARRANGERS", metadata.getArrangers(), metadata.getArranger(),
                ARRANGER_MBID_KEYS, metadata.getArrangerIds());
        }

        // 写入歌词
        metadata.discardStaleAutomaticLyrics();
        if (lyricsState(tag) == LyricsState.ABSENT && metadata.getLyrics() != null && !metadata.getLyrics().isBlank()) {
            tag.setField(FieldKey.LYRICS, metadata.getLyrics());
        }
    }

    private void clearOriginalReleaseTags(Tag tag) {
        try {
            if (tag instanceof FlacTag flac) {
                flac.deleteField("ORIGINALDATE");
                flac.deleteField("ORIGINALYEAR");
            } else if (tag instanceof VorbisCommentTag vorbis) {
                vorbis.deleteField("ORIGINALDATE");
                vorbis.deleteField("ORIGINALYEAR");
            } else if (tag instanceof AbstractID3v2Tag id3) {
                id3.removeFrame("TDOR");
                id3.removeFrame("TORY");
                removeTxxxByDescription(id3, "originalyear");
            } else if (tag instanceof Mp4Tag mp4) {
                mp4.deleteField("----:com.apple.iTunes:ORIGINALDATE");
                mp4.deleteField("----:com.apple.iTunes:ORIGINALYEAR");
            }
        } catch (Exception e) {
            log.debug("清空原始发行日期标签失败: {}", e.getMessage());
        }
    }

    private void writeOriginalReleaseTags(Tag tag, String date, String year) {
        try {
            if (tag instanceof FlacTag flac) {
                if (date != null) flac.setField("ORIGINALDATE", date);
                if (year != null) flac.setField("ORIGINALYEAR", year);
            } else if (tag instanceof VorbisCommentTag vorbis) {
                if (date != null) vorbis.setField("ORIGINALDATE", date);
                if (year != null) vorbis.setField("ORIGINALYEAR", year);
            } else if (tag instanceof AbstractID3v2Tag id3) {
                if (date != null) {
                    boolean id3v24 = id3.getMajorVersion() >= 4;
                    String frameId = id3v24 ? ID3v24Frames.FRAME_ID_ORIGINAL_RELEASE_TIME : "TORY";
                    String frameValue = id3v24 ? date : extractYear(date);
                    if (frameValue != null) {
                        AbstractID3v2Frame frame = id3.createFrame(frameId);
                        ((AbstractFrameBodyTextInfo) frame.getBody()).setText(frameValue);
                        id3.setField(frame);
                    }
                }
                if (year != null) {
                    removeTxxxByDescription(id3, "originalyear");
                    AbstractID3v2Frame frame = id3.createFrame("TXXX");
                    byte encoding = id3.getMajorVersion() >= 4 ? TextEncoding.UTF_8 : TextEncoding.UTF_16;
                    frame.setBody(new FrameBodyTXXX(encoding, "originalyear", year));
                    // setField merges TXXX frames by description; setFrame would wipe every other TXXX value.
                    id3.setField(frame);
                }
            } else if (tag instanceof Mp4Tag mp4) {
                if (date != null) mp4.setField(new Mp4TagReverseDnsField(
                    "----:com.apple.iTunes:ORIGINALDATE", "com.apple.iTunes", "ORIGINALDATE", date));
                if (year != null) mp4.setField(new Mp4TagReverseDnsField(
                    "----:com.apple.iTunes:ORIGINALYEAR", "com.apple.iTunes", "ORIGINALYEAR", year));
            }
        } catch (Exception e) {
            log.warn("写入原始发行日期标签失败（容器: {}）: {}", tag.getClass().getSimpleName(), e.getMessage());
            log.debug("原始发行日期标签写入异常", e);
        }
    }

    private void removeTxxxByDescription(AbstractID3v2Tag id3, String description) {
        List<TagField> frames = id3.getFrame("TXXX");
        if (frames == null || frames.isEmpty()) {
            return;
        }
        List<TagField> retained = new ArrayList<>();
        for (TagField field : frames) {
            if (field instanceof AbstractID3v2Frame frame
                && frame.getBody() instanceof FrameBodyTXXX body
                && description.equalsIgnoreCase(body.getDescription())) {
                continue;
            }
            retained.add(field);
        }
        if (retained.isEmpty()) {
            id3.removeFrame("TXXX");
        } else {
            id3.setFrame("TXXX", retained);
        }
    }

    boolean sameAlbumArtistRefreshFile(Path root, Path file, BasicFileAttributes expected) throws IOException {
        if (!LyricsBackfillService.safePath(root, file)) return false;
        BasicFileAttributes actual = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return Objects.equals(expected.fileKey(), actual.fileKey())
            && expected.size() == actual.size() && expected.lastModifiedTime().equals(actual.lastModifiedTime())
            && expected.creationTime().equals(actual.creationTime());
    }

    /** Called under LibraryWriteGuard. Publish the verified copy; the caller retires a relocated source. */
    void refreshAlbumArtist(Path root, Path file, Path target, BasicFileAttributes expected,
                            MusicBrainzClient.ReleaseTagBundle credit)
            throws IOException {
        String name = file.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.'));
        if (!sameAlbumArtistRefreshFile(root, file, expected)
                || LyricsOnlyWriter.hasFlacId3(file, extension.toLowerCase(Locale.ROOT))) {
            throw new IOException("File changed or has unsupported FLAC metadata");
        }
        Path staged = Files.createTempFile(target.getParent(), ".album-artist-", extension);
        try {
            Files.copy(file, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES,
                LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe temporary file");
            AudioFile original = AudioFileIO.read(staged.toFile());
            Tag originalTag = original.getTag();
            if (originalTag == null || !AlbumArtistRefreshService.isVa(AlbumArtistRefreshService.albumArtist(originalTag))) {
                throw new IOException("Album artist changed during refresh");
            }

            Map<String, List<String>> before = unrelatedAlbumArtistFields(original);
            String audioHash = albumArtistRefreshAudioHash(original, staged);
            AudioFile edited = AudioFileIO.read(staged.toFile());
            Tag tag = edited.getTag();
            Tag creditTag = tag instanceof WavTag wav ? wav.getActiveTag() : tag;
            tag.setField(FieldKey.ALBUM_ARTIST, credit.getAlbumArtistCredit());
            writeArtistNames(tag, FieldKey.ALBUM_ARTISTS, credit.getAlbumArtists());
            writeCustomTextTag(creditTag, ALBUM_ARTIST_CREDIT_TAG, credit.getOriginalAlbumArtistCredit());
            replaceField(tag, FieldKey.MUSICBRAINZ_RELEASEARTISTID, credit.getAlbumArtistId());
            replaceField(tag, FieldKey.ALBUM_ARTIST_SORT, credit.getAlbumArtistSort());
            edited.commit();

            AudioFile verified = AudioFileIO.read(staged.toFile());
            Tag written = verified.getTag();
            Tag writtenCredit = written instanceof WavTag wav ? wav.getActiveTag() : written;
            if (!credit.getAlbumArtistCredit().equals(written.getFirst(FieldKey.ALBUM_ARTIST))
                    || !credit.getAlbumArtists().equals(readArtistNames(written, FieldKey.ALBUM_ARTISTS))
                    || !Objects.equals(credit.getOriginalAlbumArtistCredit(), readArtistCreditTag(writtenCredit, ALBUM_ARTIST_CREDIT_TAG))
                    || !sameTagValue(written, FieldKey.MUSICBRAINZ_RELEASEARTISTID, credit.getAlbumArtistId())
                    || !sameTagValue(written, FieldKey.ALBUM_ARTIST_SORT, credit.getAlbumArtistSort())
                    || !before.equals(unrelatedAlbumArtistFields(verified))
                    || !Objects.equals(audioHash, albumArtistRefreshAudioHash(verified, staged))) {
                throw new IOException("Album artist update could not be verified");
            }

            var originalPosix = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (originalPosix != null) {
                try {
                    var attributes = originalPosix.readAttributes();
                    var stagedPosix = Files.getFileAttributeView(staged, PosixFileAttributeView.class);
                    if (stagedPosix != null) {
                        var stagedAttributes = stagedPosix.readAttributes();
                        try {
                            if (!attributes.owner().equals(stagedAttributes.owner())) stagedPosix.setOwner(attributes.owner());
                            if (!attributes.group().equals(stagedAttributes.group())) stagedPosix.setGroup(attributes.group());
                        } catch (IOException | UnsupportedOperationException | SecurityException e) {
                            log.debug("无法保留刷新后的文件属主/属组: {}", target);
                        }
                        try {
                            stagedPosix.setPermissions(attributes.permissions());
                        } catch (IOException | UnsupportedOperationException | SecurityException e) {
                            log.debug("无法保留刷新后的文件权限: {}", target);
                        }
                    }
                } catch (IOException | UnsupportedOperationException | SecurityException e) {
                    log.debug("无法读取原文件 POSIX 属性: {}", file);
                }
            }
            if (!sameAlbumArtistRefreshFile(root, file, expected)) throw new IOException("File changed during refresh");
            if (file.equals(target)) {
                try {
                    Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.move(staged, target);
            }
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException("Unable to write album artist tags", e);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void replaceField(Tag tag, FieldKey key, String value) throws Exception {
        tag.deleteField(key);
        if (value != null && !value.isBlank()) tag.setField(key, value);
    }

    private static boolean sameTagValue(Tag tag, FieldKey key, String value) {
        String actual = tag.getFirst(key);
        return Objects.equals(emptyToNull(actual), emptyToNull(value));
    }

    private static String albumArtistRefreshAudioHash(AudioFile audio, Path file) throws Exception {
        if (audio instanceof MP3File mp3) return HexFormat.of().formatHex(mp3.getHash("SHA-256"));
        Long start = audio.getAudioHeader().getAudioDataStartPosition();
        Long end = audio.getAudioHeader().getAudioDataEndPosition();
        if (start == null || end == null || start < 0 || end <= start || end > Files.size(file)) return null;
        return LyricsOnlyWriter.hash(file, start, end - start);
    }

    private Map<String, List<String>> unrelatedAlbumArtistFields(AudioFile audio) throws Exception {
        Tag tag = audio.getTag();
        Tag creditTag = tag instanceof WavTag wav ? wav.getActiveTag() : tag;
        for (FieldKey key : List.of(FieldKey.ALBUM_ARTIST, FieldKey.ALBUM_ARTISTS,
                FieldKey.MUSICBRAINZ_RELEASEARTISTID, FieldKey.ALBUM_ARTIST_SORT)) {
            tag.deleteField(key);
        }
        writeCustomTextTag(creditTag, ALBUM_ARTIST_CREDIT_TAG, null);

        Map<String, List<String>> fields = new HashMap<>();
        collectTagFields(fields, tag, "active");
        if (tag instanceof WavTag wav) {
            Tag other = creditTag == wav.getID3Tag() ? wav.getInfoTag() : wav.getID3Tag();
            if (other != null) collectTagFields(fields, other, "wav-secondary");
        } else if (audio instanceof MP3File mp3 && mp3.hasID3v1Tag() && tag != mp3.getID3v1Tag()) {
            fields.put("mp3-id3v1", List.of(mp3.getID3v1Tag().toString()));
        }
        return fields;
    }

    private static void collectTagFields(Map<String, List<String>> target, Tag tag, String namespace) throws Exception {
        Tag fieldsTag = tag instanceof FlacTag flac ? flac.getVorbisCommentTag() : tag;
        var fields = fieldsTag.getFields();
        while (fields.hasNext()) {
            TagField field = fields.next();
            byte[] raw = field.getRawContent();
            target.computeIfAbsent(namespace + ":field:" + field.getId(), key -> new ArrayList<>())
                .add(raw == null ? "" : Base64.getEncoder().encodeToString(raw));
        }
        for (Artwork artwork : tag.getArtworkList()) {
            byte[] binary = artwork.getBinaryData();
            target.computeIfAbsent(namespace + ":artwork", key -> new ArrayList<>())
                .add(artwork.getMimeType() + ":" + artwork.getDescription() + ":"
                    + artwork.getPictureType() + ":" + artwork.getImageUrl() + ":"
                    + (binary == null ? "" : Base64.getEncoder().encodeToString(binary)));
        }
    }

    /** 在本地入库前预校验单轨受保护的结构化 ARTISTS 信用，避免缺封面拦截绕过保护校验。 */
    void validateLocalArchiveTrackPermissions(Path file, List<String> trackArtists, boolean inheritAlbumArtists)
            throws IOException {
        if (!inheritAlbumArtists && trackArtists == null) {
            return;
        }
        if (FlacId3Stripper.hasUnsafeId3(file.toFile())) {
            throw new IOException("local.archive.write.failed");
        }
        try {
            AudioFile audio = AudioFileIO.read(file.toFile());
            Tag tag = audio.getTag();
            String trackArtist = tag == null ? null : tag.getFirst(FieldKey.ARTIST);
            List<String> existingArtists = tag == null ? null : readArtistNames(tag, FieldKey.ARTISTS);
            if (inheritAlbumArtists) {
                if ((trackArtist != null && !trackArtist.isBlank()) || existingArtists != null) {
                    throw new IOException("local.archive.stale");
                }
            } else if (!LocalArchiveDraft.canSplitTrackCredit(trackArtist, existingArtists)) {
                throw new IOException("local.archive.stale");
            }
        } catch (IOException e) {
            if ("local.archive.stale".equals(e.getMessage())) throw e;
            throw new IOException("local.archive.write.failed", e);
        } catch (Exception e) {
            throw new IOException("local.archive.write.failed", e);
        }
    }

    /** Writes only the approved album fields and optional track artist list atomically on an archive copy. */
    void writeLocalArchiveTags(Path file, String album, String albumArtist, List<String> albumArtists,
                               List<String> trackArtists, boolean inheritAlbumArtists) throws IOException {
        if (FlacId3Stripper.hasUnsafeId3(file.toFile())) throw new IOException("local.archive.write.failed");
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot) : "";
        Path staged = null;
        try {
            staged = Files.createTempFile(file.getParent(), ".local-tags-", extension);
            Files.copy(file, staged, StandardCopyOption.REPLACE_EXISTING);
            FlacId3Stripper.Result stripResult = FlacId3Stripper.stripId3FromFlac(staged.toFile());
            if (isStripAborted(stripResult)) {
                throw new IOException("local.archive.write.failed");
            }
            AudioFile audio = AudioFileIO.read(staged.toFile());
            Tag tag = audio.getTagOrCreateAndSetDefault();
            String trackArtist = tag.getFirst(FieldKey.ARTIST);
            if (inheritAlbumArtists) {
                if ((trackArtist != null && !trackArtist.isBlank()) || readArtistNames(tag, FieldKey.ARTISTS) != null) {
                    throw new IOException("local.archive.stale");
                }
                trackArtist = albumArtist;
                trackArtists = albumArtists;
                tag.setField(FieldKey.ARTIST, trackArtist);
            } else if (trackArtists != null
                    && !LocalArchiveDraft.canSplitTrackCredit(trackArtist, readArtistNames(tag, FieldKey.ARTISTS))) {
                throw new IOException("local.archive.stale");
            }
            if (!albumArtist.equals(tag.getFirst(FieldKey.ALBUM_ARTIST))) {
                deleteFieldSafely(tag, FieldKey.MUSICBRAINZ_RELEASEARTISTID);
                deleteFieldSafely(tag, FieldKey.ALBUM_ARTIST_SORT);
            }
            tag.setField(FieldKey.ALBUM, album);
            tag.setField(FieldKey.ALBUM_ARTIST, albumArtist);
            writeArtistNames(tag, FieldKey.ALBUM_ARTISTS, albumArtists);
            writeCustomTextTag(tag, ALBUM_ARTIST_CREDIT_TAG, ArtistCreditSupport.buildCreditJson(albumArtist, albumArtists));
            if (trackArtists != null) {
                writeArtistNames(tag, FieldKey.ARTISTS, trackArtists);
                writeCustomTextTag(tag, ARTIST_CREDIT_TAG, ArtistCreditSupport.buildCreditJson(trackArtist, trackArtists));
            }
            audio.commit();

            Tag written = AudioFileIO.read(staged.toFile()).getTag();
            if (written == null || !album.equals(written.getFirst(FieldKey.ALBUM))
                    || !albumArtist.equals(written.getFirst(FieldKey.ALBUM_ARTIST))
                    || !albumArtists.equals(readArtistNames(written, FieldKey.ALBUM_ARTISTS))
                    || !Objects.equals(trackArtist, written.getFirst(FieldKey.ARTIST))
                    || (trackArtists != null && !trackArtists.equals(readArtistNames(written, FieldKey.ARTISTS)))) {
                throw new IOException("Approved tags could not be verified");
            }
            ensureWritablePermissions(staged, false);
            try {
                Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING);
            }
            staged = null;
        } catch (Exception e) {
            if ("local.archive.stale".equals(e.getMessage()) && e instanceof IOException stale) throw stale;
            log.warn("本地入库标签写入失败: {}", file, e);
            throw new IOException("local.archive.write.failed", e);
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 更新已存在文件的标签（通过同目录临时文件 + 原子替换，防止中途异常损坏原文件）
     */
    public boolean updateTagsOnExistingFile(File targetFile, MusicMetadata metadata, byte[] coverArtData) {
        try (var libraryWrite = LibraryWriteGuard.acquire()) {
            if (targetFile == null || !targetFile.isFile()) {
                return false;
            }
            Path targetPath = targetFile.toPath();
            String name = targetFile.getName();
            int dot = name.lastIndexOf('.');
            String extension = dot >= 0 ? name.substring(dot) : "";
            Path staged = null;
            try {
                if (FlacId3Stripper.hasUnsafeId3(targetFile)) return false;
                staged = Files.createTempFile(targetPath.getParent(), ".tag-update-", extension);
                Files.copy(targetPath, staged, StandardCopyOption.REPLACE_EXISTING);
                File stagedFile = staged.toFile();
                FlacId3Stripper.Result stripResult = FlacId3Stripper.stripId3FromFlac(stagedFile);
                if (isStripAborted(stripResult)) {
                    log.warn("FLAC ID3 剥离未通过 ({})，中止更新标签: {}", stripResult, targetFile.getName());
                    return false;
                }
                AudioFile audioFileObj = AudioFileIO.read(stagedFile);
                Tag tag = audioFileObj.getTagOrCreateAndSetDefault();

                updateTextTags(tag, metadata);

                if (coverArtData != null && coverArtData.length > 0) {
                    applyArtwork(tag, coverArtData);
                }

                audioFileObj.commit();
                ensureWritablePermissions(staged, false);
                try {
                    Files.move(staged, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(staged, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                staged = null;
                return true;
            } catch (Exception e) {
                log.error("更新标签失败: {}", targetFile.getName(), e);
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
    }

    private static void applyArtwork(Tag tag, byte[] imageData) throws Exception {
        Artwork artwork = new StandardArtwork();
        artwork.setBinaryData(imageData);
        artwork.setMimeType(detectMimeType(imageData));
        artwork.setPictureType(3);
        artwork.setDescription("");
        tag.deleteArtworkField();
        tag.setField(artwork);
    }

    static String detectMimeType(byte[] imageData) {
        if (imageData == null || imageData.length == 0) return "image/jpeg";
        try (javax.imageio.stream.ImageInputStream input = javax.imageio.ImageIO.createImageInputStream(
                new ByteArrayInputStream(imageData))) {
            java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReaders(input);
            if (readers.hasNext()) {
                javax.imageio.ImageReader reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                    return ("jpg".equals(format) || "jpeg".equals(format))
                        ? "image/jpeg" : "image/" + format;
                } finally {
                    reader.dispose();
                }
            }
        } catch (Exception e) {
            log.debug("检测封面 MIME 失败: {}", e.getMessage());
        }
        return "image/jpeg";
    }

    /**
     * 导出歌词到独立文件
     * @param audioFile 音频文件
     * @param lyrics 歌词内容
     */
    private void exportLyricsToFile(File audioFile, String lyrics) {
        if (lyrics == null || lyrics.isEmpty()) {
            return;
        }

        try {
            // 获取音频文件的路径和名称（不含扩展名）
            String audioFilePath = audioFile.getAbsolutePath();
            int lastDotIndex = audioFilePath.lastIndexOf('.');
            String baseFilePath = lastDotIndex > 0 ? audioFilePath.substring(0, lastDotIndex) : audioFilePath;

            // 创建歌词文件路径（.lrc扩展名）
            File lyricsFile = new File(baseFilePath + (LyricsMatcher.validSynced(lyrics, 3600) ? ".lrc" : ".txt"));

            // CREATE_NEW also closes the check/create race; never overwrite a sidecar.
            try {
                Files.writeString(lyricsFile.toPath(), lyrics, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                return;
            }
            ensureWritablePermissions(lyricsFile.toPath(), false);

            log.info("歌词文件已导出: {}", lyricsFile.getName());

        } catch (IOException e) {
            log.error("导出歌词文件失败: {}", audioFile.getName(), e);
        }
    }

    /**
     * 从日期字符串中提取年份
     */
    private String extractYear(String date) {
        if (date == null || date.isEmpty()) {
            return null;
        }

        // 尝试多种日期格式
        String[] patterns = {"yyyy-MM-dd", "yyyy-MM", "yyyy"};

        for (String pattern : patterns) {
            try {
                if (date.length() >= pattern.replace("-", "").length()) {
                    String yearPart = date.substring(0, 4);
                    int year = Integer.parseInt(yearPart);
                    if (year >= 1900 && year <= 2100) {
                        return yearPart;
                    }
                }
            } catch (Exception e) {
                // 继续尝试下一个格式
            }
        }

        return null;
    }

    /**
     * 创建备份文件
     */
    private File createBackup(File originalFile) {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String backupFileName = originalFile.getName() + ".backup_" + timestamp;
            File backupFile = new File(originalFile.getParent(), backupFileName);

            Files.copy(originalFile.toPath(), backupFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
            ensureWritablePermissions(backupFile.toPath(), false);

            log.info("已创建备份: {}", backupFile.getName());
            return backupFile;

        } catch (IOException e) {
            log.error("创建备份失败", e);
            return null;
        }
    }


    /**
     * 清理文件名中的非法字符
     * 具体规则(非法字符、长度上限、Windows 保留设备名等)见 {@link FileNameSanitizer}
     */
    private String sanitizeFileName(String fileName) {
        return FileNameSanitizer.sanitize(fileName);
    }

    private void ensureWritablePermissions(Path path, boolean isDirectory) {
        AtomicAudioPublisher.ensureWritablePermissions(path, isDirectory);
    }

    static String albumArtistFromTag(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    public enum LyricsState { PRESENT, ABSENT, UNKNOWN }

    public LyricsState embeddedLyricsState(File file) {
        if (FlacId3Stripper.hasUnsafeId3(file)) return LyricsState.UNKNOWN;
        try { return lyricsState(AudioFileIO.read(file).getTag()); }
        catch (Exception e) { return LyricsState.UNKNOWN; }
    }

    static LyricsState lyricsState(Tag tag) {
        if (tag == null) return LyricsState.ABSENT;
        try {
            Tag actual = tag instanceof FlacTag flac ? flac.getVorbisCommentTag() : tag;
            java.util.Iterator<TagField> fields = actual.getFields();
            while (fields.hasNext()) {
                TagField field = fields.next();
                String id = field.getId().toUpperCase(java.util.Locale.ROOT);
                // ID3 frame existence is sufficient, including any language/description or SYLT.
                if (Set.of("USLT", "SYLT", "ULT", "SLT").contains(id)) return LyricsState.PRESENT;
                if (Set.of("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "©LYR").contains(id)
                    && !field.isEmpty()) return LyricsState.PRESENT;
            }
            String standard = actual.getFirst(FieldKey.LYRICS);
            return standard == null || standard.isBlank() ? LyricsState.ABSENT : LyricsState.PRESENT;
        } catch (Exception e) { return LyricsState.UNKNOWN; }
    }

    static String readEmbeddedLyrics(Tag tag) {
        Tag actual = tag instanceof FlacTag flac ? flac.getVorbisCommentTag() : tag;
        String standard = actual.getFirst(FieldKey.LYRICS);
        if (standard != null && !standard.isBlank()) return standard;
        if (actual instanceof VorbisCommentTag) {
            for (String id : List.of("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS")) {
                String value = actual.getFirst(id);
                if (value != null && !value.isBlank()) return value;
            }
        }
        return null;
    }

    /**
     * 读取现有标签（完整版本，包含作曲、作词、歌词等）
     */
    public MusicMetadata readTags(File audioFile) {
        try {
            AudioFile audioFileObj = AudioFileIO.read(audioFile);
            Tag tag = audioFileObj.getTag();

            if (tag == null) {
                return null;
            }

            MusicMetadata metadata = new MusicMetadata();
            metadata.setTitle(tag.getFirst(FieldKey.TITLE));
            metadata.setArtist(tag.getFirst(FieldKey.ARTIST));
            try {
                metadata.setArtists(readArtistNames(tag, FieldKey.ARTISTS));
                metadata.setArtistCredit(readArtistCreditTag(tag, ARTIST_CREDIT_TAG));
            }
            catch (Exception e) { log.debug("Read structured artists: {}", e.getMessage()); }
            String albumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST);
            metadata.setAlbumArtist(albumArtistFromTag(albumArtist));
            try {
                metadata.setAlbumArtists(readArtistNames(tag, FieldKey.ALBUM_ARTISTS));
                metadata.setAlbumArtistCredit(readArtistCreditTag(tag, ALBUM_ARTIST_CREDIT_TAG));
            }
            catch (Exception e) { log.debug("Read structured album artists: {}", e.getMessage()); }
            metadata.setAlbum(tag.getFirst(FieldKey.ALBUM));
            metadata.setReleaseDate(tag.getFirst(FieldKey.YEAR));

            // 新增 MusicBrainz 字段必须逐项隔离：部分容器不支持这些键，
            // 读取失败不能让已有的标题/艺术家等基础标签整体丢失。
            try {
                metadata.setReleaseType(tag.getFirst(FieldKey.MUSICBRAINZ_RELEASE_TYPE));
            } catch (Exception e) {
                log.debug("读取 MusicBrainz 专辑类型失败: {}", e.getMessage());
            }
            try {
                metadata.setReleaseGroupId(tag.getFirst(FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID));
            } catch (Exception e) {
                log.debug("读取 MusicBrainz Release Group ID 失败: {}", e.getMessage());
            }
            try {
                metadata.setReleaseId(tag.getFirst(FieldKey.MUSICBRAINZ_RELEASEID));
            } catch (Exception e) {
                log.debug("读取 MusicBrainz Release ID 失败: {}", e.getMessage());
            }
            try {
                String compilation = tag.getFirst(FieldKey.IS_COMPILATION);
                metadata.setCompilation("1".equals(compilation) || "true".equalsIgnoreCase(compilation));
            } catch (Exception e) {
                log.debug("读取合辑标志失败: {}", e.getMessage());
            }

            // 读取风格
            String genre = tag.getFirst(FieldKey.GENRE);
            if (genre != null && !genre.isEmpty()) {
                metadata.setGenres(java.util.Arrays.asList(genre.split(",")));
            }

            // 读取作曲家
            String composer = tag.getFirst(FieldKey.COMPOSER);
            if (composer != null && !composer.isEmpty()) {
                List<String> names = readCreatorNames(tag, FieldKey.COMPOSER, "COMPOSERS");
                metadata.setComposer(names == null ? composer : String.join(", ", names));
                metadata.setComposers(names);
                metadata.setComposerIds(readCreatorIds(tag, FieldKey.COMPOSER, "COMPOSERS", COMPOSER_MBID_KEYS, names, composer));
            }

            // 读取作词家
            String lyricist = tag.getFirst(FieldKey.LYRICIST);
            if (lyricist != null && !lyricist.isEmpty()) {
                List<String> names = readCreatorNames(tag, FieldKey.LYRICIST, "LYRICISTS");
                metadata.setLyricist(names == null ? lyricist : String.join(", ", names));
                metadata.setLyricists(names);
                metadata.setLyricistIds(readCreatorIds(tag, FieldKey.LYRICIST, "LYRICISTS", LYRICIST_MBID_KEYS, names, lyricist));
            }

            // 读取编曲（逐项隔离，不支持该键的容器不影响其它字段）
            try {
                String arranger = tag.getFirst(FieldKey.ARRANGER);
                if (arranger != null && !arranger.isEmpty()) {
                    List<String> names = readCreatorNames(tag, FieldKey.ARRANGER, "ARRANGERS");
                    metadata.setArranger(names == null ? arranger : String.join(", ", names));
                    metadata.setArrangers(names);
                    metadata.setArrangerIds(readCreatorIds(tag, FieldKey.ARRANGER, "ARRANGERS", ARRANGER_MBID_KEYS, names, arranger));
                }
            } catch (Exception e) {
                log.debug("读取编曲失败: {}", e.getMessage());
            }

            // 读取歌词
            double preciseLen = audioFileObj.getAudioHeader().getPreciseTrackLength();
            if (Double.isFinite(preciseLen) && preciseLen > 0) {
                metadata.setLocalPreciseDuration(preciseLen);
            }
            String lyrics = readEmbeddedLyrics(tag);
            if (lyrics != null && !lyrics.isEmpty()) {
                metadata.setLyrics(lyrics);
            }

            // 读取碟号和曲目号
            String discNo = tag.getFirst(FieldKey.DISC_NO);
            if (discNo != null && !discNo.isEmpty()) {
                metadata.setDiscNo(discNo);
            }

            String trackNo = tag.getFirst(FieldKey.TRACK);
            if (trackNo != null && !trackNo.isEmpty()) {
                metadata.setTrackNo(trackNo);
            }

            return metadata;

        } catch (Exception e) {
            log.error("读取标签失败: {}", audioFile.getName(), e);
            return null;
        }
    }

    /**
     * 检查音频文件是否有**可用的**标签信息（阶段八 #23 加固）。
     *
     * 原实现的问题：
     *   - artist / album / albumArtist / composer / lyricist / lyrics / genre
     *     任意一个非空就返回 true，**只有 composer 一个字段也算「有标签」**；
     *   - 不看占位值，{@code Unknown Artist} / {@code Track 01} 也算有。
     *
     * 现在的语义是「至少有一个**核心身份字段**是真实可用的」：
     * artist / albumArtist / album 三者之一非占位值。
     * composer / lyricist / lyrics / genre 单独存在不再算数——
     * 它们既不能让 Plex 归类，也不能证明上传者整理过。
     *
     * @return true 表示标签至少能用于归类
     */
    public boolean hasPartialTags(File audioFile) {
        try {
            AudioFile audioFileObj = AudioFileIO.read(audioFile);
            Tag tag = audioFileObj.getTag();

            if (tag == null) {
                return false;
            }

            String artist = tag.getFirst(FieldKey.ARTIST);
            String albumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST);
            String album = tag.getFirst(FieldKey.ALBUM);

            return TagQualityEvaluator.isMeaningful(artist)
                || TagQualityEvaluator.isMeaningful(albumArtist)
                || TagQualityEvaluator.isMeaningful(album);

        } catch (Exception e) {
            log.error("检查标签信息失败: {}", audioFile.getName(), e);
            return false;
        }
    }

    /**
     * 检查音频文件是否已内嵌封面
     */
    public boolean hasEmbeddedCover(File audioFile) {
        try {
            AudioFile audioFileObj = AudioFileIO.read(audioFile);
            Tag tag = audioFileObj.getTag();

            if (tag != null) {
                Artwork artwork = tag.getFirstArtwork();
                return artwork != null && artwork.getBinaryData() != null && artwork.getBinaryData().length > 0;
            }

            return false;

        } catch (Exception e) {
            log.debug("检查内嵌封面失败: {}", audioFile.getName());
            return false;
        }
    }

    /**
     * 将文件夹中的封面图片内嵌到音频文件（统一走原子写入流程，避免中途失败损坏原文件）
     * @param audioFile 音频文件
     * @param folderCoverData 文件夹封面数据
     * @return true表示成功
     */
    public boolean embedFolderCover(File audioFile, byte[] folderCoverData) {
        if (folderCoverData == null || folderCoverData.length == 0) {
            return false;
        }
        log.info("内嵌文件夹封面到: {}", audioFile != null ? audioFile.getName() : "null");
        return embedCoverAtomically(audioFile, folderCoverData);
    }

    /**
     * 原子写入封面到已归档音频文件（先在同目录写临时副本再原子替换，避免中途失败损坏原文件）。
     */
    public boolean embedCoverAtomically(File audioFile, byte[] coverArtData) {
        try (var libraryWrite = LibraryWriteGuard.acquire()) {
            if (audioFile == null || !audioFile.isFile() || coverArtData == null || coverArtData.length == 0) {
                return false;
            }
            if (FlacId3Stripper.hasUnsafeId3(audioFile)) {
                log.warn("跳过无法安全保护 ID3 歌词的 FLAC: {}", audioFile);
                return false;
            }
            Path sourcePath = audioFile.toPath();
            String name = audioFile.getName();
            int dot = name.lastIndexOf('.');
            String extension = dot >= 0 ? name.substring(dot) : "";
            Path staged = null;
            try {
                staged = Files.createTempFile(sourcePath.getParent(), ".cover-write-", extension);
                Files.copy(sourcePath, staged, StandardCopyOption.REPLACE_EXISTING);
                File stagedFile = staged.toFile();
                FlacId3Stripper.Result stripResult = FlacId3Stripper.stripId3FromFlac(stagedFile);
                if (isStripAborted(stripResult)) {
                    log.warn("FLAC ID3 剥离未通过 ({})，中止封面写入: {}", stripResult, audioFile.getName());
                    return false;
                }
                AudioFile audioFileObj = AudioFileIO.read(stagedFile);
                Tag tag = audioFileObj.getTagOrCreateAndSetDefault();
                applyArtwork(tag, coverArtData);
                audioFileObj.commit();
                ensureWritablePermissions(staged, false);
                try {
                    Files.move(staged, sourcePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(staged, sourcePath, StandardCopyOption.REPLACE_EXISTING);
                }
                staged = null;
                log.info("原子补写封面成功: {}", audioFile.getName());
                return true;
            } catch (Exception e) {
                log.error("原子补写封面失败: {}", audioFile.getName(), e);
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
    }

}

