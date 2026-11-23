package com.lux032.musicautotagger.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.service.CoverCandidateCache;
import com.lux032.musicautotagger.service.ImageCompressor;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 封面缩略图接口，供仪表板「最近处理的文件」使用。
 *
 *   GET /api/cover?rgid=<MusicBrainz Release Group ID>   —— 首选，直接命中封面缓存目录
 *   GET /api/cover?path=<音频文件绝对路径>                —— 兜底，读文件内嵌封面
 *
 * 之所以优先走 rgid：processed_files 里记的是「处理时」的路径，文件归档移走之后那个路径
 * 就不存在了，按路径读必然失败；而封面缓存是按 Release Group 存的，只要专辑识别成功就一直在。
 *
 * 前端拿不到图时会自动回退到渐变占位图，所以这里所有异常都按「没有封面」处理。
 */
@Slf4j
public class CoverServlet extends HttpServlet {

    /**
     * 与 CoverArtCache.releaseGroupCacheKey() 保持一致的缓存 key 前缀，改这里必须同步改那边。
     *
     * 注意有两个命名空间：开启「优先动画版封面」时写入 anime 前缀，否则写普通前缀。
     * 这里必须两个都查：开关打开前后处理的专辑会分布在两个命名空间，
     * 只查一个会让另一半专辑在仪表盘上变成占位图。
     */
    private static final String CACHE_KEY_PREFIX = "release-group:";
    private static final String ANIME_CACHE_KEY_PREFIX = "release-group:anime:";

    /**
     * 缩略图边长。卡片实际只有 132px，给两倍多一点应付高分屏就够了。
     * 缓存里的原图动辄 1~5MB，直接发给前端一排 12 张就是几十 MB。
     */
    private static final int THUMB_SIZE = 320;

    /** 缩略图内存缓存，避免每次刷新面板都重新解码一遍大图。 */
    private static final int THUMB_CACHE_MAX = 64;

    /**
     * 超过这个大小的结果不进内存缓存。
     * 有些封面是算术编码 JPEG，Java 的 ImageIO 拒绝解码（“legal restrictions on
     * arithmetic coding”），只能退回原图。若把这种几 MB 的原图也塞进缓存，
     * 64 条 × 几 MB 就是百兆级堆内存；它们本来也不需要缓存（跳过了解码，读盘很便宜）。
     */
    private static final int THUMB_CACHE_MAX_BYTES = 512 * 1024;
    private static final long MAX_DECODE_PIXELS = 50_000_000L;
    private static final int MAX_DECODE_DIMENSION = 32768;
    private final Map<String, Image> thumbCache = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                return size() > THUMB_CACHE_MAX;
            }
        });

    private final MusicConfig config;

    public CoverServlet(MusicConfig config) {
        this.config = config;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String candidateSha = trimToNull(req.getParameter("candidateSha256"));
        if (CoverCandidateCache.validSha256(candidateSha)) {
            Path candidate = CoverCandidateCache.directory(config).resolve(candidateSha.toLowerCase(Locale.ROOT));
            candidate = candidate.resolveSibling(candidate.getFileName() + ".jpg");
            if (Files.isRegularFile(candidate) && Files.size(candidate) <= ImageCompressor.MAX_INPUT_BYTES) {
                byte[] raw = Files.readAllBytes(candidate);
                String fullParam = trimToNull(req.getParameter("full"));
                boolean full = "1".equals(fullParam) || "true".equalsIgnoreCase(fullParam);
                Image image = full ? new Image(raw, "image/jpeg") : toThumbnail(raw, "image/jpeg");
                if (image != null) {
                    writeImage(resp, image.data, image.mime);
                    return;
                }
            }
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String rgid = trimToNull(req.getParameter("rgid"));
        if (rgid != null) {
            // 内存缓存 key 带上封面偏好：切换「优先动画版」后同一 rgid 对应的图会变，
            // 不区分的话仪表盘会继续显示旧版本
            String cacheKey = ((config != null && config.isPreferAnimeCover()) ? "anime:" : "") + rgid;
            Image thumb = thumbCache.get(cacheKey);
            if (thumb == null) {
                byte[] cached = readFromCoverCache(rgid);
                if (cached != null) {
                    thumb = toThumbnail(cached, "image/jpeg");
                    if (thumb != null && thumb.data.length <= THUMB_CACHE_MAX_BYTES) {
                        thumbCache.put(cacheKey, thumb);
                    }
                }
            }
            if (thumb != null) {
                writeImage(resp, thumb.data, thumb.mime);
                return;
            }
        }

        String rawPath = trimToNull(req.getParameter("path"));
        if (rawPath != null) {
            File source = allowedFile(rawPath);
            if (source != null) {
                String cacheKey = "path:" + source.getAbsolutePath() + ":" + source.lastModified();
                Image thumb = thumbCache.get(cacheKey);
                if (thumb == null) {
                    Image embedded = readEmbedded(source);
                    if (embedded != null) {
                        thumb = toThumbnail(embedded.data, embedded.mime);
                        if (thumb != null && thumb.data.length <= THUMB_CACHE_MAX_BYTES) {
                            thumbCache.put(cacheKey, thumb);
                        }
                    }
                }
                if (thumb != null) {
                    writeImage(resp, thumb.data, thumb.mime);
                    return;
                }
            }
        }

        if (rgid == null && rawPath == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    /** Servlet 实例是多线程共享的，图片数据 + MIME 必须用局部对象带出来，不能放实例字段。 */
    private static final class Image {
        final byte[] data;
        final String mime;

        Image(byte[] data, String mime) {
            this.data = data;
            this.mime = mime;
        }
    }

    /**
     * 缓存目录的解析规则必须和 ApplicationLifecycleManager 里建 CoverArtCache 时完全一致，
     * 否则会去错的目录找图。
     */
    private String resolveCacheDirectory() {
        String dir = config.getCoverArtCacheDirectory();
        if (dir != null && !dir.isBlank()) {
            return dir;
        }
        String output = config.getOutputDirectory();
        return (output == null || output.isBlank()) ? null : output + "/.cover_cache";
    }

    /** 从封面缓存目录读 <md5(<命名空间><id>)>.jpg，两个命名空间都试。 */
    private byte[] readFromCoverCache(String releaseGroupId) {
        String dir = resolveCacheDirectory();
        if (dir == null) {
            return null;
        }
        // 按当前偏好排序：开启动画版时优先拿 anime 命名空间的图，否则优先拿普通的，
        // 两边互为兜底，避免切换开关后旧专辑封面全部消失。
        boolean animeFirst = config != null && config.isPreferAnimeCover();
        String[] prefixes = animeFirst
            ? new String[]{ANIME_CACHE_KEY_PREFIX, CACHE_KEY_PREFIX}
            : new String[]{CACHE_KEY_PREFIX, ANIME_CACHE_KEY_PREFIX};

        for (String prefix : prefixes) {
            try {
                Path file = Paths.get(dir, md5(prefix + releaseGroupId) + ".jpg");
                if (Files.isRegularFile(file)) {
                    return Files.readAllBytes(file);
                }
            } catch (Exception e) {
                log.debug("读取封面缓存失败 (key={}{}): {}", prefix, releaseGroupId, e.getMessage());
            }
        }
        return null;
    }

    /** 从音频文件读内嵌封面，路径必须落在配置声明过的目录内。 */
    private File allowedFile(String rawPath) {
        try {
            File file = new File(rawPath).getCanonicalFile();
            return isAllowed(file) && file.isFile() ? file : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private Image readEmbedded(File file) {
        try {
            AudioFile audioFile = AudioFileIO.read(file);
            Tag tag = audioFile.getTag();
            Artwork artwork = tag == null ? null : tag.getFirstArtwork();
            if (artwork == null || artwork.getBinaryData() == null || artwork.getBinaryData().length == 0) {
                return null;
            }
            String mime = artwork.getMimeType();
            return new Image(artwork.getBinaryData(),
                (mime == null || mime.isBlank()) ? "image/jpeg" : mime);
        } catch (Exception e) {
            log.debug("读取内嵌封面失败: {} ({})", file, e.getMessage());
            return null;
        }
    }

    /**
     * 受限解码并缩成最长边 THUMB_SIZE 的 JPEG：先经 ImageReader 校验宽高与像素上限并子采样，
     * 防止恶意内嵌大图造成 512MB 容器 OOM；解码器无法处理的安全图片格式保留原 MIME。
     */
    private Image toThumbnail(byte[] original, String fallbackMime) {
        if (original == null || original.length == 0 || original.length > ImageCompressor.MAX_INPUT_BYTES) {
            return null;
        }
        ImageReader reader = null;
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            reader = readers.next();
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0 || Math.max(width, height) > MAX_DECODE_DIMENSION
                || (long) width * height > MAX_DECODE_PIXELS) {
                log.warn("拒绝超大尺寸封面缩略图解码: {}x{}", width, height);
                return null;
            }
            String format = reader.getFormatName() == null ? "jpeg" : reader.getFormatName().toLowerCase(Locale.ROOT);
            String detectedMime = ("jpg".equals(format) || "jpeg".equals(format)) ? "image/jpeg" : "image/" + format;
            if (width <= THUMB_SIZE && height <= THUMB_SIZE) {
                return new Image(original, detectedMime);
            }

            int subsample = Math.max(1, Math.max(width, height) / (THUMB_SIZE * 2));
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(subsample, subsample, 0, 0);
            BufferedImage source = reader.read(0, param);
            if (source == null) {
                return null;
            }

            double scale = Math.min(1.0, (double) THUMB_SIZE / Math.max(source.getWidth(), source.getHeight()));
            int targetWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));

            BufferedImage thumb = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumb.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, targetWidth, targetHeight);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(thumb, "jpg", out) || out.size() == 0) {
                return new Image(original, detectedMime);
            }
            return new Image(out.toByteArray(), "image/jpeg");
        } catch (Exception e) {
            log.debug("生成封面缩略图失败: {}", e.getMessage());
            // 算术编码 JPEG 等安全已知图片解码失败且体积适中时退回原图，拒绝未知/恶意字节
            if (ImageCompressor.detectSafeFfmpegDemuxer(original) != null && original.length <= 4 * 1024 * 1024) {
                String safeMime = (fallbackMime != null && fallbackMime.startsWith("image/"))
                    ? fallbackMime : "image/jpeg";
                return new Image(original, safeMime);
            }
            return null;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    private void writeImage(HttpServletResponse resp, byte[] data, String mime) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(mime);
        resp.setContentLength(data.length);
        resp.setHeader("X-Content-Type-Options", "nosniff");
        // 同一 key 的封面基本不变，缓存 10 分钟，避免每次刷新面板都重新读盘
        resp.setHeader("Cache-Control", "private, max-age=600");
        try (OutputStream out = resp.getOutputStream()) {
            out.write(data);
        }
    }

    /** 只放行配置中声明过的目录，避免变成任意文件读取接口。 */
    private boolean isAllowed(File file) {
        List<String> roots = new ArrayList<>();
        roots.add(config.getMonitorDirectory());
        roots.add(config.getOutputDirectory());
        roots.add(config.getFailedDirectory());
        roots.add(config.getPartialDirectory());
        roots.add(config.getCueSplitOutputDir());
        roots.add(config.getReviewStagingDirectory());
        roots.add(config.getRecoveryWorkDirectory());

        Path target = file.toPath();
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            try {
                Path rootPath = Paths.get(root).toAbsolutePath().normalize().toRealPath();
                if (target.startsWith(rootPath)) {
                    return true;
                }
            } catch (IOException | RuntimeException ignored) {
                // 目录不存在或路径非法：跳过
            }
        }
        return false;
    }

    private static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
