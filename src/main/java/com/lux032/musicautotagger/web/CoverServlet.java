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

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

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

    /** 与 CoverArtCache 保持一致的缓存 key 前缀，改这里必须同步改那边。 */
    private static final String CACHE_KEY_PREFIX = "release-group:";

    private final MusicConfig config;

    public CoverServlet(MusicConfig config) {
        this.config = config;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String rgid = trimToNull(req.getParameter("rgid"));
        if (rgid != null) {
            byte[] cached = readFromCoverCache(rgid);
            if (cached != null) {
                writeImage(resp, cached, "image/jpeg");
                return;
            }
        }

        String rawPath = trimToNull(req.getParameter("path"));
        if (rawPath != null) {
            Image embedded = readEmbedded(rawPath);
            if (embedded != null) {
                writeImage(resp, embedded.data, embedded.mime);
                return;
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

    /** 从封面缓存目录读 <md5(release-group:<id>)>.jpg。 */
    private byte[] readFromCoverCache(String releaseGroupId) {
        String dir = resolveCacheDirectory();
        if (dir == null) {
            return null;
        }
        try {
            Path file = Paths.get(dir, md5(CACHE_KEY_PREFIX + releaseGroupId) + ".jpg");
            if (Files.isRegularFile(file)) {
                return Files.readAllBytes(file);
            }
        } catch (Exception e) {
            log.debug("读取封面缓存失败 (rgid={}): {}", releaseGroupId, e.getMessage());
        }
        return null;
    }

    /** 从音频文件读内嵌封面，路径必须落在配置声明过的目录内。 */
    private Image readEmbedded(String rawPath) {
        try {
            File file = new File(rawPath).getCanonicalFile();
            if (!isAllowed(file) || !file.isFile()) {
                return null;
            }
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
            log.debug("读取内嵌封面失败: {} ({})", rawPath, e.getMessage());
            return null;
        }
    }

    private void writeImage(HttpServletResponse resp, byte[] data, String mime) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(mime);
        resp.setContentLength(data.length);
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
            byte[] digest = md.digest(text.getBytes());
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
