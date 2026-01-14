package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.config.MusicConfig;
import org.example.model.MusicMetadata;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.StandardArtwork;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;

/**
 * 音乐标签写入服务
 * 使用 JAudioTagger 库写入音频文件的元数据标签
 */
@Slf4j
public class TagWriterService {
    
    private final MusicConfig config;
    
    public TagWriterService(MusicConfig config) {
        this.config = config;
    }
    
    /**
     * 处理音频文件（复制到新目录并更新标签）
     */
    public boolean processFile(File sourceFile, MusicMetadata metadata, byte[] coverArtData) {
        if (!sourceFile.exists()) {
            log.error("源文件不存在: {}", sourceFile.getAbsolutePath());
            return false;
        }

        try {
            // 1. 确定目标文件路径
            File targetFile = determineTargetFile(sourceFile, metadata);
            
            // 2. 创建目标目录
            if (!targetFile.getParentFile().exists()) {
                targetFile.getParentFile().mkdirs();
                ensureWritablePermissions(targetFile.getParentFile().toPath(), true);
            }

            // 3. 复制文件
            log.info("复制文件: {} -> {}", sourceFile.getName(), targetFile.getName());
            Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            ensureWritablePermissions(targetFile.toPath(), false);

            // 4. 更新标签
            log.info("开始更新标签: {}", targetFile.getName());
            AudioFile audioFileObj = AudioFileIO.read(targetFile);
            Tag tag = audioFileObj.getTagOrCreateAndSetDefault();

            // 更新文本标签
            updateTextTags(tag, metadata);

            // 更新封面
            if (coverArtData != null && coverArtData.length > 0) {
                log.info("写入封面图片...");
                Artwork artwork = new StandardArtwork();
                artwork.setBinaryData(coverArtData);
                artwork.setMimeType("image/jpeg"); // 假设是 JPEG，实际可能需要检测
                tag.deleteArtworkField();
                tag.setField(artwork);
            }

            // 保存更改
            audioFileObj.commit();
            log.info("文件处理完成: {}", targetFile.getName());

            // 如果配置开启,导出歌词到独立文件
            if (config.isExportLyricsToFile() && metadata.getLyrics() != null && !metadata.getLyrics().isEmpty()) {
                exportLyricsToFile(targetFile, metadata.getLyrics());
            }

            return true;

        } catch (Exception e) {
            log.error("处理文件失败: {}", sourceFile.getName(), e);
            return false;
        }
    }

    /**
     * 确定目标文件路径
     * 按照"专辑艺术家/专辑"的目录结构组织文件
     * 使用专辑艺术家(Album Artist)而不是单曲艺术家,避免多艺术家专辑分层混乱
     */
    private File determineTargetFile(File sourceFile, MusicMetadata metadata) {
        String fileName = sourceFile.getName();
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileName.substring(dotIndex);
        }

        // 构建文件名(使用单曲艺术家)
        String newFileName = fileName;
        if (config.isAutoRename() && metadata.getArtist() != null && metadata.getTitle() != null) {
            // 优先使用 metadata 中的碟号和曲目号构建前缀（来自 MusicBrainz）
            String trackPrefix = buildTrackPrefixFromMetadata(metadata);
            
            // 如果 metadata 没有碟号曲目号，尝试从原始文件名提取
            if (trackPrefix.isEmpty()) {
                trackPrefix = extractTrackPrefix(fileName);
            }
            
            String artist = sanitizeFileName(metadata.getArtist());
            String title = sanitizeFileName(metadata.getTitle());
            
            // 如果有曲目编号前缀，保留它
            if (trackPrefix != null && !trackPrefix.isEmpty()) {
                newFileName = trackPrefix + artist + " - " + title + extension;
            } else {
                newFileName = artist + " - " + title + extension;
            }
        }

        // 构建目录结构: 输出目录/专辑艺术家/专辑/文件名
        // 关键改动: 使用 albumArtist 而不是 artist 来创建文件夹
        Path targetPath;
        
        // 优先使用专辑艺术家,如果没有则回退到单曲艺术家
        String folderArtist = metadata.getAlbumArtist();
        if (folderArtist == null || folderArtist.isEmpty()) {
            folderArtist = metadata.getArtist();
        }
        
        if (folderArtist != null && !folderArtist.isEmpty()) {
            String artistDir = sanitizeFileName(folderArtist);
            
            if (metadata.getAlbum() != null && !metadata.getAlbum().isEmpty()) {
                // 有专辑艺术家和专辑信息: 输出目录/专辑艺术家/专辑/文件名
                String albumDir = sanitizeFileName(metadata.getAlbum());
                targetPath = Paths.get(config.getOutputDirectory(), artistDir, albumDir, newFileName);
                log.info("目标路径: {}/{}/{} (专辑艺术家: {})", artistDir, albumDir, newFileName, folderArtist);
            } else {
                // 只有艺术家信息: 输出目录/专辑艺术家/文件名
                targetPath = Paths.get(config.getOutputDirectory(), artistDir, newFileName);
                log.info("目标路径: {}/{} (无专辑信息)", artistDir, newFileName);
            }
        } else {
            // 没有艺术家信息: 直接放在输出目录下
            targetPath = Paths.get(config.getOutputDirectory(), newFileName);
            log.warn("目标路径: {} (无艺术家信息)", newFileName);
        }

        // 确保目标目录存在
        File targetFile = targetPath.toFile();
        File parentDir = targetFile.getParentFile();
        if (!parentDir.exists()) {
            log.info("创建目录: {}", parentDir.getAbsolutePath());
            parentDir.mkdirs();
            ensureWritablePermissions(parentDir.toPath(), true);
        }

        return targetFile;
    }

    /**
     * 从 metadata 的碟号和曲目号构建文件名前缀
     * 格式: "碟号.曲目号 " (如 "2.05 " 或 "1.12 ")
     * 如果只有曲目号没有碟号，格式: "曲目号. " (如 "05. ")
     */
    private String buildTrackPrefixFromMetadata(MusicMetadata metadata) {
        String discNo = metadata.getDiscNo();
        String trackNo = metadata.getTrackNo();
        
        if (trackNo == null || trackNo.isEmpty()) {
            return "";
        }
        
        try {
            int track = Integer.parseInt(trackNo.split("/")[0].trim()); // 处理 "5/49" 格式
            
            if (discNo != null && !discNo.isEmpty()) {
                int disc = Integer.parseInt(discNo.split("/")[0].trim()); // 处理 "2/2" 格式
                // 多碟专辑: "碟号.曲目号 " 格式 (曲目号补零到2位)
                String prefix = String.format("%d.%02d ", disc, track);
                log.info("使用 metadata 构建文件名前缀: {} (碟号: {}, 曲目号: {})", prefix, disc, track);
                return prefix;
            } else {
                // 单碟专辑: "曲目号. " 格式 (曲目号补零到2位)
                String prefix = String.format("%02d. ", track);
                log.info("使用 metadata 构建文件名前缀: {} (曲目号: {})", prefix, track);
                return prefix;
            }
        } catch (NumberFormatException e) {
            log.warn("无法解析碟号/曲目号: discNo={}, trackNo={}", discNo, trackNo);
            return "";
        }
    }
    
    /**
     * 从文件名中提取曲目编号前缀（备选方案）
     * 支持多种格式:
     * - "01. Title.flac" -> "01. "
     * - "2.01 Title.flac" -> "2.01 " (碟号.曲目号)
     * - "39. Title.flac" -> "39. "
     */
    private String extractTrackPrefix(String fileName) {
        // 先尝试匹配 "碟号.曲目号 " 格式 (如 "2.01 Title")
        if (fileName.matches("^\\d+\\.\\d+\\s+.*")) {
            // 找到第二个空格前的部分
            int spaceIndex = fileName.indexOf(' ');
            if (spaceIndex > 0) {
                return fileName.substring(0, spaceIndex + 1);
            }
        }
        
        // 匹配格式: 数字 + 点 + 空格 (可能有多个空格)
        if (fileName.matches("^\\d+\\.\\s+.*")) {
            // 有空格的情况
            int dotIndex = fileName.indexOf('.');
            if (dotIndex > 0) {
                // 找到点后第一个非空格字符的位置
                int spaceEnd = dotIndex + 1;
                while (spaceEnd < fileName.length() && fileName.charAt(spaceEnd) == ' ') {
                    spaceEnd++;
                }
                return fileName.substring(0, spaceEnd);
            }
        } else if (fileName.matches("^\\d+\\..*")) {
            // 纯数字+点的情况，但后面紧跟着非数字字符
            // 避免把 "2.01" 错误解析
            int dotIndex = fileName.indexOf('.');
            if (dotIndex > 0) {
                // 检查点后是否是数字（表示这是碟号.曲目号格式）
                if (dotIndex + 1 < fileName.length() && Character.isDigit(fileName.charAt(dotIndex + 1))) {
                    // 这是碟号.曲目号格式，需要找到曲目号结束的位置
                    int numEnd = dotIndex + 1;
                    while (numEnd < fileName.length() && Character.isDigit(fileName.charAt(numEnd))) {
                        numEnd++;
                    }
                    // 跳过后续空格
                    while (numEnd < fileName.length() && fileName.charAt(numEnd) == ' ') {
                        numEnd++;
                    }
                    return fileName.substring(0, numEnd);
                } else {
                    // 单纯的曲目号格式
                    return fileName.substring(0, dotIndex + 2); // "XX. "
                }
            }
        }
        
        log.debug("无法从文件名提取曲目编号: {}", fileName);
        return "";
    }

    /**
     * 更新文本标签
     */
    private void updateTextTags(Tag tag, MusicMetadata metadata) throws Exception {
        if (metadata.getTitle() != null && !metadata.getTitle().isEmpty()) {
            tag.setField(FieldKey.TITLE, metadata.getTitle());
        }
        
        if (metadata.getArtist() != null && !metadata.getArtist().isEmpty()) {
            tag.setField(FieldKey.ARTIST, metadata.getArtist());
        }
        
        // 写入专辑艺术家标签
        if (metadata.getAlbumArtist() != null && !metadata.getAlbumArtist().isEmpty()) {
            tag.setField(FieldKey.ALBUM_ARTIST, metadata.getAlbumArtist());
        }
        
        if (metadata.getAlbum() != null && !metadata.getAlbum().isEmpty()) {
            tag.setField(FieldKey.ALBUM, metadata.getAlbum());
        }
        
        if (metadata.getReleaseDate() != null && !metadata.getReleaseDate().isEmpty()) {
            // 直接写入完整日期,不再只提取年份
            tag.setField(FieldKey.YEAR, metadata.getReleaseDate());
        }
        
        if (metadata.getGenres() != null && !metadata.getGenres().isEmpty()) {
            tag.setField(FieldKey.GENRE, String.join(", ", metadata.getGenres()));
        }
        
        if (metadata.getRecordingId() != null && !metadata.getRecordingId().isEmpty()) {
            tag.setField(FieldKey.MUSICBRAINZ_TRACK_ID, metadata.getRecordingId());
        }
        
        // 写入碟号和曲目号
        if (metadata.getDiscNo() != null && !metadata.getDiscNo().isEmpty()) {
            tag.setField(FieldKey.DISC_NO, metadata.getDiscNo());
        }
        if (metadata.getTrackNo() != null && !metadata.getTrackNo().isEmpty()) {
            tag.setField(FieldKey.TRACK, metadata.getTrackNo());
        }
        
        // 写入作曲家
        if (metadata.getComposer() != null && !metadata.getComposer().isEmpty()) {
            tag.setField(FieldKey.COMPOSER, metadata.getComposer());
        }
        
        // 写入作词家 (使用 LYRICIST 字段)
        if (metadata.getLyricist() != null && !metadata.getLyricist().isEmpty()) {
            tag.setField(FieldKey.LYRICIST, metadata.getLyricist());
        }
        
        // 写入歌词
        if (metadata.getLyrics() != null && !metadata.getLyrics().isEmpty()) {
            tag.setField(FieldKey.LYRICS, metadata.getLyrics());
        }
    }

    /**
     * 更新已存在文件的标签（不复制文件）
     */
    public boolean updateTagsOnExistingFile(File targetFile, MusicMetadata metadata, byte[] coverArtData) {
        try {
            AudioFile audioFileObj = AudioFileIO.read(targetFile);
            Tag tag = audioFileObj.getTagOrCreateAndSetDefault();

            updateTextTags(tag, metadata);

            if (coverArtData != null && coverArtData.length > 0) {
                Artwork artwork = new StandardArtwork();
                artwork.setBinaryData(coverArtData);
                artwork.setMimeType("image/jpeg");
                tag.deleteArtworkField();
                tag.setField(artwork);
            }

            audioFileObj.commit();
            return true;
        } catch (Exception e) {
            log.error("更新标签失败: {}", targetFile.getName(), e);
            return false;
        }
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
            File lyricsFile = new File(baseFilePath + ".lrc");
            
            // 写入歌词内容
            try (java.io.BufferedWriter writer = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(lyricsFile),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                writer.write(lyrics);
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
     * 保留<INST>等有意义的标记，只替换文件系统真正不允许的字符
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        
        // Windows文件系统不允许的字符: \ / : * ? " < > |
        // 但我们需要保留<INST>这样的标记，所以特殊处理
        String result = fileName;
        
        // 先保护<INST>等特殊标记，临时替换为全角括号
        result = result.replace("<INST>", "〔INST〕");
        result = result.replace("<inst>", "〔inst〕");
        result = result.replace("<Inst>", "〔Inst〕");
        
        // 替换文件系统不允许的字符
        result = result.replaceAll("[\\\\/:*?\"<>|]", "");
        
        // 恢复<INST>标记，使用方括号代替尖括号（文件系统安全）
        result = result.replace("〔INST〕", "[INST]");
        result = result.replace("〔inst〕", "[inst]");
        result = result.replace("〔Inst〕", "[Inst]");
        
        // 清理多余空格
        result = result.replaceAll("\\s+", " ").trim();
        
        return result;
    }

    private void ensureWritablePermissions(Path path, boolean isDirectory) {
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
                configuredMode = isDirectory ? "rwxrwxrwx" : "rw-rw-rw-";
            }
            Set<PosixFilePermission> permissions = parsePermissions(configuredMode.trim());
            Files.setPosixFilePermissions(path, permissions);
        } catch (Exception e) {
            log.debug("无法设置文件权限: {}", path);
        }
    }

    private Set<PosixFilePermission> parsePermissions(String mode) {
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

    private void applyPermissions(Set<PosixFilePermission> permissions, char digit,
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
            metadata.setAlbumArtist(tag.getFirst(FieldKey.ALBUM_ARTIST));
            metadata.setAlbum(tag.getFirst(FieldKey.ALBUM));
            metadata.setReleaseDate(tag.getFirst(FieldKey.YEAR));

            // 读取风格
            String genre = tag.getFirst(FieldKey.GENRE);
            if (genre != null && !genre.isEmpty()) {
                metadata.setGenres(java.util.Arrays.asList(genre.split(",")));
            }

            // 读取作曲家
            String composer = tag.getFirst(FieldKey.COMPOSER);
            if (composer != null && !composer.isEmpty()) {
                metadata.setComposer(composer);
            }

            // 读取作词家
            String lyricist = tag.getFirst(FieldKey.LYRICIST);
            if (lyricist != null && !lyricist.isEmpty()) {
                metadata.setLyricist(lyricist);
            }

            // 读取歌词
            String lyrics = tag.getFirst(FieldKey.LYRICS);
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
     * 检查音频文件是否有部分有用的标签信息
     * 检查项目：艺术家、专辑、作曲家、作词家、歌词、风格等
     * @return true表示至少有一项有用信息
     */
    public boolean hasPartialTags(File audioFile) {
        try {
            AudioFile audioFileObj = AudioFileIO.read(audioFile);
            Tag tag = audioFileObj.getTag();
            
            if (tag == null) {
                return false;
            }
            
            // 检查是否有艺术家
            String artist = tag.getFirst(FieldKey.ARTIST);
            if (artist != null && !artist.trim().isEmpty()) {
                return true;
            }
            
            // 检查是否有专辑
            String album = tag.getFirst(FieldKey.ALBUM);
            if (album != null && !album.trim().isEmpty()) {
                return true;
            }
            
            // 检查是否有专辑艺术家
            String albumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST);
            if (albumArtist != null && !albumArtist.trim().isEmpty()) {
                return true;
            }
            
            // 检查是否有作曲家
            String composer = tag.getFirst(FieldKey.COMPOSER);
            if (composer != null && !composer.trim().isEmpty()) {
                return true;
            }
            
            // 检查是否有作词家
            String lyricist = tag.getFirst(FieldKey.LYRICIST);
            if (lyricist != null && !lyricist.trim().isEmpty()) {
                return true;
            }
            
            // 检查是否有歌词
            String lyrics = tag.getFirst(FieldKey.LYRICS);
            if (lyrics != null && !lyrics.trim().isEmpty()) {
                return true;
            }
            
            // 检查是否有风格
            String genre = tag.getFirst(FieldKey.GENRE);
            if (genre != null && !genre.trim().isEmpty()) {
                return true;
            }
            
            return false;
            
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
     * 将文件夹中的封面图片内嵌到音频文件
     * @param audioFile 音频文件
     * @param folderCoverData 文件夹封面数据
     * @return true表示成功
     */
    public boolean embedFolderCover(File audioFile, byte[] folderCoverData) {
        if (folderCoverData == null || folderCoverData.length == 0) {
            return false;
        }
        
        try {
            log.info("内嵌文件夹封面到: {}", audioFile.getName());
            AudioFile audioFileObj = AudioFileIO.read(audioFile);
            Tag tag = audioFileObj.getTagOrCreateAndSetDefault();
            
            // 创建封面对象
            Artwork artwork = new StandardArtwork();
            artwork.setBinaryData(folderCoverData);
            artwork.setMimeType("image/jpeg");
            
            // 删除现有封面并设置新封面
            tag.deleteArtworkField();
            tag.setField(artwork);
            
            // 保存更改
            audioFileObj.commit();
            log.info("封面内嵌成功: {}", audioFile.getName());
            return true;
            
        } catch (Exception e) {
            log.error("内嵌封面失败: {}", audioFile.getName(), e);
            return false;
        }
    }
    
}
