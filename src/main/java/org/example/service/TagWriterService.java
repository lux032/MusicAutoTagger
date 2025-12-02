package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.config.MusicConfig;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.StandardArtwork;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

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
            }

            // 3. 复制文件
            log.info("复制文件: {} -> {}", sourceFile.getName(), targetFile.getName());
            Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

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
            String artist = sanitizeFileName(metadata.getArtist());
            String title = sanitizeFileName(metadata.getTitle());
            newFileName = artist + " - " + title + extension;
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
        }

        return targetFile;
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
            
            log.info("已创建备份: {}", backupFile.getName());
            return backupFile;
            
        } catch (IOException e) {
            log.error("创建备份失败", e);
            return null;
        }
    }
    
    
    /**
     * 清理文件名中的非法字符
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        
        // 替换 Windows 文件名中的非法字符
        return fileName
            .replaceAll("[\\\\/:*?\"<>|]", "")
            .replaceAll("\\s+", " ")
            .trim();
    }
    
    /**
     * 读取现有标签
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
            metadata.setAlbum(tag.getFirst(FieldKey.ALBUM));
            metadata.setReleaseDate(tag.getFirst(FieldKey.YEAR));
            
            String genre = tag.getFirst(FieldKey.GENRE);
            if (genre != null && !genre.isEmpty()) {
                metadata.setGenres(java.util.Arrays.asList(genre.split(",")));
            }
            
            return metadata;
            
        } catch (Exception e) {
            log.error("读取标签失败: {}", audioFile.getName(), e);
            return null;
        }
    }
    
    /**
     * 音乐元数据类
     */
    public static class MusicMetadata {
        private String recordingId;
        private String title;
        private String artist;
        private String albumArtist; // 专辑艺术家
        private String album;
        private String releaseDate;
        private java.util.List<String> genres;
        private byte[] coverArtData;
        
        // 新增字段
        private String composer; // 作曲家
        private String lyricist; // 作词家
        private String lyrics; // 歌词
        
        public String getRecordingId() { return recordingId; }
        public void setRecordingId(String recordingId) { this.recordingId = recordingId; }
        
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        
        public String getArtist() { return artist; }
        public void setArtist(String artist) { this.artist = artist; }
        
        public String getAlbumArtist() { return albumArtist; }
        public void setAlbumArtist(String albumArtist) { this.albumArtist = albumArtist; }
        
        public String getAlbum() { return album; }
        public void setAlbum(String album) { this.album = album; }
        
        public String getReleaseDate() { return releaseDate; }
        public void setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }
        
        public java.util.List<String> getGenres() { return genres; }
        public void setGenres(java.util.List<String> genres) { this.genres = genres; }
        
        public String getComposer() { return composer; }
        public void setComposer(String composer) { this.composer = composer; }
        
        public String getLyricist() { return lyricist; }
        public void setLyricist(String lyricist) { this.lyricist = lyricist; }
        
        public String getLyrics() { return lyrics; }
        public void setLyrics(String lyrics) { this.lyrics = lyrics; }
    }
}