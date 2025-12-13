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