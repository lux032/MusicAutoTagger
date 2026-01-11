package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.model.MusicMetadata;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 封面艺术服务
 * 负责封面获取的多层降级策略
 */
@Slf4j
public class CoverArtService {
    
    private final CoverArtCache coverArtCache;
    private final MusicBrainzClient musicBrainzClient;
    
    // 文件夹级别的封面缓存: 文件夹路径 -> 封面数据
    private final Map<String, byte[]> folderCoverCache = new ConcurrentHashMap<>();
    
    public CoverArtService(CoverArtCache coverArtCache, MusicBrainzClient musicBrainzClient) {
        this.coverArtCache = coverArtCache;
        this.musicBrainzClient = musicBrainzClient;
    }
    
    /**
     * 获取封面图片(多层降级策略 + 文件夹级别缓存)
     * 优先级:
     * 0. 检查同文件夹是否已有其他文件获取过封面
     * 0.5. 如果有锁定的专辑ID，检查是否已经为这个专辑获取过封面（跨文件夹复用）
     * 1. 尝试从网络下载(使用缓存)
     * 2. 如果下载失败,检查音频文件是否自带封面
     * 3. 如果没有自带封面,在音频文件所在目录查找cover图片
     *
     * @param audioFile 音频文件
     * @param metadata 元数据（包含封面URL）
     * @param lockedReleaseGroupId 锁定的专辑release group ID，如果非null则优先使用此专辑的封面
     * @param isLooseFile 是否为散落文件
     */
    public byte[] getCoverArtWithFallback(File audioFile, MusicMetadata metadata, 
                                           String lockedReleaseGroupId, boolean isLooseFile) {
        byte[] coverArtData = null;
        String folderPath = audioFile.getParentFile().getAbsolutePath();

        // 策略0: 检查文件夹级别缓存（散落文件跳过此策略）
        if (!isLooseFile) {
            coverArtData = folderCoverCache.get(folderPath);
            if (coverArtData != null && coverArtData.length > 0) {
                log.info("策略0: 使用同文件夹已获取的封面");
                return coverArtData;
            }
        } else {
            log.info("散落文件跳过文件夹级别缓存，独立获取封面");
        }

        // 策略0.5: 如果有锁定的专辑ID，检查 CoverArtCache 中是否已经为这个专辑获取过封面
        if (lockedReleaseGroupId != null) {
            coverArtData = coverArtCache.getCachedCoverByReleaseGroupId(lockedReleaseGroupId);

            if (coverArtData != null && coverArtData.length > 0) {
                log.info("策略0.5: 使用已缓存的锁定专辑封面 (Release Group ID: {})", lockedReleaseGroupId);
                // 只有非散落文件才缓存到文件夹级别
                if (!isLooseFile) {
                    folderCoverCache.put(folderPath, coverArtData);
                    log.info("已缓存到文件夹级别");
                }
                return coverArtData;
            }
        }

        // 策略1: 尝试从网络下载
        String coverArtUrl = null;

        // 如果有锁定的专辑ID，只使用锁定专辑的封面
        if (lockedReleaseGroupId != null) {
            log.info("使用锁定专辑的封面 (Release Group ID: {})", lockedReleaseGroupId);
            try {
                coverArtUrl = musicBrainzClient.getCoverArtUrlByReleaseGroupId(lockedReleaseGroupId);
                if (coverArtUrl != null) {
                    log.info("获取到锁定专辑的封面URL: {}", coverArtUrl);
                } else {
                    log.warn("锁定专辑没有可用的封面，将跳过网络下载，直接进入降级策略");
                }
            } catch (Exception e) {
                log.warn("获取锁定专辑封面URL失败，将跳过网络下载，直接进入降级策略: {}", e.getMessage());
            }
        } else if (metadata.getCoverArtUrl() != null) {
            // 没有锁定专辑时，才使用指纹识别返回的封面URL
            log.info("使用指纹识别返回的封面URL");
            coverArtUrl = metadata.getCoverArtUrl();
        }

        if (coverArtUrl != null) {
            log.info("策略1: 尝试从网络下载封面");
            coverArtData = downloadCoverFromNetwork(coverArtUrl);

            if (coverArtData != null && coverArtData.length > 0) {
                log.info("✓ 成功从网络下载封面");
                
                // 只有非散落文件才缓存到文件夹级别
                if (!isLooseFile) {
                    folderCoverCache.put(folderPath, coverArtData);
                    log.info("已缓存到文件夹级别");
                }

                // 如果有锁定的专辑ID，同时缓存到 Release Group ID 级别，供其他文件夹复用
                if (lockedReleaseGroupId != null) {
                    coverArtCache.cacheCoverByReleaseGroupId(lockedReleaseGroupId, coverArtData);
                    log.info("已将封面缓存到专辑级别 (Release Group ID: {})", lockedReleaseGroupId);
                }

                return coverArtData;
            }
            log.warn("✗ 网络下载失败,尝试降级策略");
        } else {
            log.warn("未获取到封面URL,跳过网络下载,尝试降级策略");
        }
        
        // 策略2: 检查音频文件是否自带封面
        log.info("策略2: 检查音频文件是否自带封面");
        coverArtData = extractCoverFromAudioFile(audioFile);
        
        if (coverArtData != null && coverArtData.length > 0) {
            log.info("✓ 成功从音频文件提取封面");
            
            // 只有非散落文件才缓存到文件夹级别
            if (!isLooseFile) {
                folderCoverCache.put(folderPath, coverArtData);
                log.info("已缓存到文件夹级别");
            }
            
            return coverArtData;
        }
        log.info("✗ 音频文件无封面,尝试降级策略");
        
        // 策略3: 在音频文件所在目录查找cover图片
        log.info("策略3: 在音频文件所在目录查找cover图片");
        coverArtData = findCoverInDirectory(audioFile.getParentFile());
        
        if (coverArtData != null && coverArtData.length > 0) {
            log.info("✓ 成功从目录找到封面图片");
            
            // 只有非散落文件才缓存到文件夹级别
            if (!isLooseFile) {
                folderCoverCache.put(folderPath, coverArtData);
                log.info("已缓存到文件夹级别");
            }
            
            return coverArtData;
        }
        
        log.warn("✗ 所有策略均失败,未找到封面图片");
        return null;
    }
    
    /**
     * 从网络下载封面(使用缓存)
     */
    private byte[] downloadCoverFromNetwork(String coverArtUrl) {
        try {
            // 首先检查缓存
            byte[] coverArtData = coverArtCache.getCachedCover(coverArtUrl);
            
            if (coverArtData != null) {
                log.info("从缓存获取封面");
                return coverArtData;
            }
            
            // 缓存未命中,下载并压缩
            log.info("正在下载封面图片: {}", coverArtUrl);
            byte[] rawCoverArt = musicBrainzClient.downloadCoverArt(coverArtUrl);
            
            if (rawCoverArt != null && rawCoverArt.length > 0) {
                // 压缩图片到2MB以内
                coverArtData = ImageCompressor.compressImage(rawCoverArt);
                
                // 保存到缓存
                coverArtCache.cacheCover(coverArtUrl, coverArtData);
                return coverArtData;
            }
        } catch (Exception e) {
            log.warn("从网络下载封面失败", e);
        }
        return null;
    }
    
    /**
     * 从音频文件提取封面
     */
    private byte[] extractCoverFromAudioFile(File audioFile) {
        try {
            AudioFile audioFileObj = AudioFileIO.read(audioFile);
            Tag tag = audioFileObj.getTag();
            
            if (tag != null) {
                Artwork artwork = tag.getFirstArtwork();
                if (artwork != null) {
                    byte[] imageData = artwork.getBinaryData();
                    if (imageData != null && imageData.length > 0) {
                        // 压缩图片到2MB以内
                        return ImageCompressor.compressImage(imageData);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从音频文件提取封面失败: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 在目录中查找封面图片
     * 支持的文件名: cover.jpg, cover.png, folder.jpg, folder.png, album.jpg, album.png
     */
    public byte[] findCoverInDirectory(File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return null;
        }
        
        // 支持的封面文件名(优先级顺序)
        String[] coverNames = {"cover", "folder", "album", "front"};
        String[] extensions = {".jpg", ".jpeg", ".png", ".webp"};
        
        for (String coverName : coverNames) {
            for (String ext : extensions) {
                File coverFile = new File(directory, coverName + ext);
                if (coverFile.exists() && coverFile.isFile()) {
                    try {
                        byte[] imageData = Files.readAllBytes(coverFile.toPath());
                        if (imageData != null && imageData.length > 0) {
                            log.info("找到封面文件: {}", coverFile.getName());
                            // 压缩图片到2MB以内
                            return ImageCompressor.compressImage(imageData);
                        }
                    } catch (Exception e) {
                        log.debug("读取封面文件失败: {} - {}", coverFile.getName(), e.getMessage());
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * 从音频文件提取内嵌封面（用于部分识别保留封面）
     */
    public byte[] extractEmbeddedCover(File audioFile) {
        return extractCoverFromAudioFile(audioFile);
    }
    
    /**
     * 根据 Release Group ID 获取封面（用于批量处理时确保使用正确专辑的封面）
     * 优先级：
     * 1. 从 Release Group ID 级别的缓存获取
     * 2. 从网络下载并缓存
     * 3. 从文件夹内任一音频文件提取封面
     * 4. 在专辑根目录查找封面图片
     *
     * @param releaseGroupId 专辑 Release Group ID
     * @param folderPath 文件夹路径（用于更新文件夹级别缓存和降级策略）
     * @return 封面数据，如果获取失败返回 null
     */
    public byte[] getCoverArtByReleaseGroupId(String releaseGroupId, String folderPath) {
        if (releaseGroupId == null || releaseGroupId.isEmpty()) {
            return null;
        }
        
        // 策略1: 检查 Release Group ID 级别的缓存
        byte[] coverArtData = coverArtCache.getCachedCoverByReleaseGroupId(releaseGroupId);
        if (coverArtData != null && coverArtData.length > 0) {
            log.info("策略1: 从缓存获取锁定专辑封面 (Release Group ID: {})", releaseGroupId);
            // 更新文件夹级别缓存
            if (folderPath != null) {
                folderCoverCache.put(folderPath, coverArtData);
                log.info("已更新文件夹级别缓存");
            }
            return coverArtData;
        }
        
        // 策略2: 从网络下载
        try {
            log.info("策略2: 从网络获取锁定专辑封面 (Release Group ID: {})", releaseGroupId);
            String coverArtUrl = musicBrainzClient.getCoverArtUrlByReleaseGroupId(releaseGroupId);
            
            if (coverArtUrl != null) {
                coverArtData = downloadCoverFromNetwork(coverArtUrl);
                
                if (coverArtData != null && coverArtData.length > 0) {
                    log.info("✓ 成功从网络获取锁定专辑封面");
                    
                    // 缓存到 Release Group ID 级别
                    coverArtCache.cacheCoverByReleaseGroupId(releaseGroupId, coverArtData);
                    log.info("已缓存到专辑级别 (Release Group ID: {})", releaseGroupId);
                    
                    // 更新文件夹级别缓存
                    if (folderPath != null) {
                        folderCoverCache.put(folderPath, coverArtData);
                        log.info("已更新文件夹级别缓存");
                    }
                    
                    return coverArtData;
                }
            } else {
                log.warn("未获取到锁定专辑的封面URL");
            }
        } catch (Exception e) {
            log.warn("从网络获取锁定专辑封面失败: {}", e.getMessage());
        }
        
        log.warn("✗ 网络下载失败，尝试降级策略");
        
        // 策略3: 从文件夹内任一音频文件提取封面
        if (folderPath != null) {
            log.info("策略3: 从文件夹内音频文件提取封面");
            coverArtData = extractCoverFromFolderAudioFiles(folderPath);
            
            if (coverArtData != null && coverArtData.length > 0) {
                log.info("✓ 成功从文件夹内音频文件提取封面");
                
                // 缓存到 Release Group ID 级别
                coverArtCache.cacheCoverByReleaseGroupId(releaseGroupId, coverArtData);
                log.info("已缓存到专辑级别 (Release Group ID: {})", releaseGroupId);
                
                // 更新文件夹级别缓存
                folderCoverCache.put(folderPath, coverArtData);
                log.info("已更新文件夹级别缓存");
                
                return coverArtData;
            }
            log.info("✗ 文件夹内音频文件无封面，尝试降级策略");
        }
        
        // 策略4: 在专辑根目录查找封面图片（注意：要在专辑根目录找，而不是 CD1/CD2 子目录）
        if (folderPath != null) {
            log.info("策略4: 在专辑根目录查找封面图片");
            File albumRootDir = getAlbumRootDirectory(new File(folderPath));
            coverArtData = findCoverInDirectory(albumRootDir);
            
            if (coverArtData != null && coverArtData.length > 0) {
                log.info("✓ 成功从专辑根目录找到封面图片");
                
                // 缓存到 Release Group ID 级别
                coverArtCache.cacheCoverByReleaseGroupId(releaseGroupId, coverArtData);
                log.info("已缓存到专辑级别 (Release Group ID: {})", releaseGroupId);
                
                // 更新文件夹级别缓存
                folderCoverCache.put(folderPath, coverArtData);
                log.info("已更新文件夹级别缓存");
                
                return coverArtData;
            }
        }
        
        log.warn("✗ 所有策略均失败，未找到锁定专辑的封面");
        return null;
    }
    
    /**
     * 从文件夹内任一音频文件提取封面
     * 递归扫描文件夹及其子文件夹，找到第一个包含封面的音频文件
     */
    private byte[] extractCoverFromFolderAudioFiles(String folderPath) {
        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            return null;
        }
        
        return extractCoverFromFolderRecursively(folder);
    }
    
    /**
     * 递归扫描文件夹，从第一个包含封面的音频文件提取封面
     */
    private byte[] extractCoverFromFolderRecursively(File folder) {
        File[] files = folder.listFiles();
        if (files == null) {
            return null;
        }
        
        // 先处理当前目录的音频文件
        for (File file : files) {
            if (file.isFile() && isAudioFile(file)) {
                byte[] cover = extractCoverFromAudioFile(file);
                if (cover != null && cover.length > 0) {
                    return cover;
                }
            }
        }
        
        // 再递归处理子目录
        for (File file : files) {
            if (file.isDirectory()) {
                byte[] cover = extractCoverFromFolderRecursively(file);
                if (cover != null && cover.length > 0) {
                    return cover;
                }
            }
        }
        
        return null;
    }
    
    /**
     * 判断文件是否为音频文件
     */
    private boolean isAudioFile(File file) {
        String lowerName = file.getName().toLowerCase();
        return lowerName.endsWith(".mp3") ||
               lowerName.endsWith(".flac") ||
               lowerName.endsWith(".m4a") ||
               lowerName.endsWith(".wav");
    }
    
    /**
     * 获取专辑根目录
     * 如果当前目录是 "Disc X"、"CD X" 等子目录，返回其父目录
     * 否则返回当前目录本身
     */
    private File getAlbumRootDirectory(File folder) {
        if (folder == null || !folder.exists()) {
            return folder;
        }
        
        String folderName = folder.getName().toLowerCase();
        // 检查是否为碟片子目录（Disc 1, CD 1, Disc1, CD1 等）
        if (folderName.matches("^(disc|cd)\\s*\\d+$") ||
            folderName.matches("^(disc|cd)\\d+$")) {
            File parent = folder.getParentFile();
            if (parent != null && parent.exists()) {
                log.debug("检测到碟片子目录 {}，使用父目录 {} 作为专辑根目录", folder.getName(), parent.getName());
                return parent;
            }
        }
        
        return folder;
    }
    
    /**
     * 清除文件夹级别的封面缓存
     */
    public void clearFolderCache(String folderPath) {
        folderCoverCache.remove(folderPath);
    }
    
    /**
     * 获取文件夹缓存的封面
     */
    public byte[] getFolderCachedCover(String folderPath) {
        return folderCoverCache.get(folderPath);
    }
}
