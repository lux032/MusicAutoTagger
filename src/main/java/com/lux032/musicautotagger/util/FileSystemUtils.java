package com.lux032.musicautotagger.util;

import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

/**
 * 文件系统工具类
 * 负责文件系统相关的工具方法
 */
@Slf4j
public class FileSystemUtils {
    
    private final MusicConfig config;

    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(\\d+)");
    private static final Pattern DECLARED_TOTAL = Pattern.compile("/\\s*(\\d+)");
    /**
     * 附加音频（bonus / 试听 / DJ mix 等）的命名特征。
     * 只作为 soft 信号：它也会命中合法曲名（如一首叫 Sample 的歌）
     * 和 MB 确实收录的 Bonus Disc。
     */
    private static final Pattern AUXILIARY_AUDIO = Pattern.compile(
        "(?i)(^|[\\s._\\-\\[\\]()])(bonus|sample|preview|snippet|试听|花絮|dj[\\s._-]*mix)([\\s._\\-\\[\\]()]|$)");

    /**
     * 专辑根目录 -> 最近一次的统计结果。
     * {@link #inspectMusicFilesInFolder(File)} 会递归扫目录并读取**每个**文件的标签，
     * 而它是逐文件调用的——不缓存的话一张 n 轨专辑会产生 n² 次标签读取。
     */
    private final Map<String, CachedInspection> inspectionCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class CachedInspection {
        private final MusicFileCountResult result;
        private final long computedAt;

        CachedInspection(MusicFileCountResult result) {
            this.result = result;
            this.computedAt = System.currentTimeMillis();
        }
    }

    /** 目录内容可能边下载边处理，缓存只保持短时间，避免锁死早期的不完整快照。 */
    private static final long INSPECTION_CACHE_TTL_MS = 30_000;

    public FileSystemUtils(MusicConfig config) {
        this.config = config;
    }

    /** 专辑处理完成 / 失败收尾时调用，丢弃该目录的统计缓存。 */
    public void invalidateInspection(String folderPath) {
        if (folderPath != null) {
            inspectionCache.remove(folderPath);
        }
    }
    
    /**
     * 检查文件夹是否包含临时文件(下载未完成)
     * 常见下载工具临时文件扩展名:
     * - qBittorrent: .!qB (旧版本使用 .!qb)
     * - Transmission: .part
     * - uTorrent/BitTorrent: .ut!
     * - Chrome/Firefox: .crdownload, .tmp
     */
    public boolean hasTempFilesInFolder(File audioFile) {
        File parentDir = audioFile.getParentFile();
        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory()) {
            return false;
        }
        
        File[] files = parentDir.listFiles();
        if (files == null) {
            return false;
        }
        
        // 临时文件扩展名列表
        String[] tempExtensions = {".!qb", ".!qB", ".part", ".ut!", ".crdownload", ".tmp", ".download"};
        
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            
            String fileName = file.getName().toLowerCase();
            for (String tempExt : tempExtensions) {
                if (fileName.endsWith(tempExt.toLowerCase())) {
                    log.info("检测到临时文件: {}", file.getName());
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 统计文件所在文件夹内的音乐文件数量（智能递归，支持多CD专辑）
     *
     * 逻辑:
     * - 如果父文件夹是监控目录本身，只统计当前层级（避免混入其他专辑）
     * - 如果父文件夹是多CD专辑的子文件夹（如 Disc 1, CD1），向上获取专辑根目录
     * - 如果父文件夹是专辑根目录，递归统计（支持多CD专辑）
     */
    public int countMusicFilesInFolder(File audioFile) {
        return inspectMusicFilesInFolder(audioFile).getCount();
    }

    /**
     * 统计音乐文件，并同时判断这个数字能否代表一张完整、单一的专辑。
     * 不可靠时调用方仍可将 count 用于队列/样本规模控制，但不得用于候选发行版评分或硬门槛。
     */
    public MusicFileCountResult inspectMusicFilesInFolder(File audioFile) {
        return inspectMusicFilesInFolder(audioFile, null);
    }

    /** 恢复任务可显式固定 Album Root，避免按 monitorDirectory 重新推导。 */
    public MusicFileCountResult inspectMusicFilesInFolder(File audioFile, File explicitAlbumRoot) {
        File parentDir = audioFile.getParentFile();
        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory()) {
            return new MusicFileCountResult(1, false,
                Collections.singletonList("无法确定文件所在目录"), Collections.emptyList());
        }

        boolean looseFile = explicitAlbumRoot == null && isLooseFileInMonitorRoot(audioFile);
        File scanRoot = explicitAlbumRoot != null ? explicitAlbumRoot
            : (looseFile ? parentDir : getAlbumRootDirectory(audioFile));

        String cacheKey = scanRoot.getAbsolutePath();
        CachedInspection cached = inspectionCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.computedAt < INSPECTION_CACHE_TTL_MS) {
            return cached.result;
        }

        MusicFileCountResult result = doInspect(scanRoot, looseFile);
        inspectionCache.put(cacheKey, new CachedInspection(result));
        return result;
    }

    private MusicFileCountResult doInspect(File scanRoot, boolean looseFile) {
        List<File> musicFiles = new ArrayList<>();
        if (looseFile) {
            File[] files = scanRoot.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && isMusicFile(file)) musicFiles.add(file);
                }
            }
        } else {
            collectAudioFilesForMarking(scanRoot, musicFiles);
        }

        int count = Math.max(1, musicFiles.size());

        // hard：这个数字肯定不代表一张专辑的曲目数，完全不能参与专辑匹配。
        List<String> hardReasons = new ArrayList<>();
        // soft：可能不准，但误判率也高。只用于禁用「第一文件立即锁定」等激进优化，
        // 不应导致整张专辑拿不到任何专辑信息。
        List<String> softReasons = new ArrayList<>();

        if (musicFiles.isEmpty()) hardReasons.add("目录扫描未找到音乐文件");
        if (looseFile && musicFiles.size() > 1) hardReasons.add("监控目录根部混有多个散落音乐文件");
        if (containsTempFileRecursively(scanRoot)) hardReasons.add("目录中存在下载临时文件（目录尚未完整）");
        if (containsCueFile(scanRoot) && musicFiles.size() > 1) {
            hardReasons.add("CUE 与多个音频文件并存，可能重复统计源大文件");
        }

        for (File file : musicFiles) {
            String relative = scanRoot.toPath().relativize(file.toPath()).toString();
            if (AUXILIARY_AUDIO.matcher(relative).find()) {
                softReasons.add("存在疑似 bonus/试听/DJ mix 的命名（也可能只是歌名包含该词）");
                break;
            }
        }

        Set<String> albumNames = new HashSet<>();
        Map<Integer, Set<Integer>> tracksByDisc = new HashMap<>();
        Map<Integer, Integer> declaredTotalsByDisc = new HashMap<>();
        int filesWithReadableTags = 0;
        boolean anyDiscTagPresent = false;
        for (File file : musicFiles) {
            try {
                Tag tag = AudioFileIO.read(file).getTag();
                if (tag == null) continue;
                filesWithReadableTags++;
                String album = tag.getFirst(FieldKey.ALBUM);
                if (album != null && !album.trim().isEmpty() && !"unknown album".equalsIgnoreCase(album.trim())) {
                    albumNames.add(album.trim().toLowerCase(Locale.ROOT));
                }
                String discValue = tag.getFirst(FieldKey.DISC_NO);
                if (discValue != null && !discValue.isBlank()) anyDiscTagPresent = true;
                int disc = parseLeadingNumber(discValue, 1);
                String trackValue = tag.getFirst(FieldKey.TRACK);
                int track = parseLeadingNumber(trackValue, 0);
                if (track > 0) tracksByDisc.computeIfAbsent(disc, k -> new HashSet<>()).add(track);
                int declaredTotal = parseDeclaredTotal(trackValue);
                if (declaredTotal > 0) declaredTotalsByDisc.merge(disc, declaredTotal, Math::max);
            } catch (Exception e) {
                log.debug("检查曲目数可靠性时无法读取标签: {} - {}", file.getName(), e.getMessage());
            }
        }

        if (albumNames.size() > 1) hardReasons.add("音频标签包含多个不同专辑名");

        // 曲号缺口检测只在「所有文件都能读出标签」时才成立。
        // 否则一个读失败的文件就会制造出一个假缺口。
        // 同理，多碟专辑若普遍缺 DISC_NO，所有曲号会被挤到 disc=1 并互相覆盖，统计无意义。
        boolean trackNumbersTrustworthy = filesWithReadableTags == musicFiles.size()
            && (anyDiscTagPresent || tracksByDisc.size() <= 1);
        if (trackNumbersTrustworthy) {
            for (Map.Entry<Integer, Set<Integer>> entry : tracksByDisc.entrySet()) {
                Set<Integer> tracks = entry.getValue();
                int maxTrack = tracks.stream().mapToInt(Integer::intValue).max().orElse(0);
                if (maxTrack > tracks.size()) {
                    hardReasons.add("碟 " + entry.getKey() + " 的曲号存在缺口，可能只下载了专辑子集");
                    break;
                }
                Integer declaredTotal = declaredTotalsByDisc.get(entry.getKey());
                if (declaredTotal != null && declaredTotal != tracks.size()) {
                    hardReasons.add("碟 " + entry.getKey() + " 的标签总曲目数与实际文件数不一致");
                    break;
                }
            }
        } else if (!tracksByDisc.isEmpty()) {
            softReasons.add("部分文件标签不可读或缺少碟号，无法校验曲号完整性");
        }

        boolean reliable = hardReasons.isEmpty();
        if (reliable && softReasons.isEmpty()) {
            log.info("{} 中共有 {} 个音乐文件，曲目数输入判定为可靠", scanRoot.getName(), count);
        } else if (reliable) {
            log.info("{} 中共有 {} 个音乐文件，曲目数可用但存在存疑信号（仅禁用快速锁定）: {}",
                scanRoot.getName(), count, String.join("；", softReasons));
        } else {
            log.warn("{} 中共有 {} 个音乐文件，但曲目数输入不可靠: {}",
                scanRoot.getName(), count, String.join("；", hardReasons));
        }
        return new MusicFileCountResult(count, reliable, hardReasons, softReasons);
    }

    private boolean containsTempFileRecursively(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isDirectory() && containsTempFileRecursively(file)) return true;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (file.isFile() && (name.endsWith(".!qb") || name.endsWith(".part") || name.endsWith(".ut!") ||
                name.endsWith(".crdownload") || name.endsWith(".tmp") || name.endsWith(".download"))) return true;
        }
        return false;
    }

    private boolean containsCueFile(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isDirectory() && containsCueFile(file)) return true;
            if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".cue")) return true;
        }
        return false;
    }

    private int parseLeadingNumber(String value, int fallback) {
        if (value == null) return fallback;
        Matcher matcher = LEADING_NUMBER.matcher(value);
        if (!matcher.find()) return fallback;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return fallback;   // 超长数字串
        }
    }

    private int parseDeclaredTotal(String value) {
        if (value == null) return 0;
        Matcher matcher = DECLARED_TOTAL.matcher(value);
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 目录音乐文件统计结果及其可信度。
     *
     * <ul>
     *   <li>{@link #isReliable()} == false（存在 hard reason）：这个数字肯定不是一张专辑的曲目数，
     *       不得参与候选发行版评分或硬门槛。</li>
     *   <li>{@link #hasSoftConcerns()} == true：数字可能不准但误判率也高，
     *       仅用于禁用「第一文件立即锁定整个文件夹」这类激进优化。</li>
     * </ul>
     */
    public static final class MusicFileCountResult {
        private final int count;
        private final boolean reliable;
        private final List<String> reasons;
        private final List<String> softReasons;

        public MusicFileCountResult(int count, boolean reliable, List<String> reasons, List<String> softReasons) {
            this.count = count;
            this.reliable = reliable;
            this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
            this.softReasons = Collections.unmodifiableList(new ArrayList<>(softReasons));
        }

        public int getCount() { return count; }
        /** 不存在 hard reason，曲目数可用于专辑匹配 */
        public boolean isReliable() { return reliable; }
        public List<String> getReasons() { return reasons; }
        public List<String> getSoftReasons() { return softReasons; }
        public boolean hasSoftConcerns() { return !softReasons.isEmpty(); }
        /** 是否允许用第一首文件就锁定整个文件夹（需要最强的输入保证） */
        public boolean isSafeForFastLock() { return reliable && softReasons.isEmpty(); }
    }
    
    /**
     * 碟片子目录命名：Disc 1 / CD2 / Disk-3 / DISC.1 / CD 1 - Bonus / Disc 2 [Live] 等。
     */
    private static final Pattern DISC_FOLDER = Pattern.compile(
        "(?i)^\\s*(disc|disk|cd)[\\s._\\-#]*\\d{1,3}(\\s*([\\-:：_.]|[(\\[（【]).*)?\\s*$");

    /** 目录名是否为多碟专辑的碟片子目录 */
    public static boolean isDiscFolderName(String folderName) {
        return folderName != null && DISC_FOLDER.matcher(folderName).matches();
    }

    /**
     * 获取音频文件所属的专辑根目录。
     * 规则见 {@link #resolveAlbumRootForFolder(File, String)}。
     */
    public File getAlbumRootDirectory(File audioFile) {
        return resolveAlbumRootForFolder(audioFile.getParentFile(), config.getMonitorDirectory());
    }

    /**
     * 由音频所在目录推导专辑根目录。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>音频所在目录本身就是专辑根目录；</li>
     *   <li>若它是碟片子目录（Disc 1 / CD2 …），上升一级到专辑目录；</li>
     *   <li>永远不会越过监控目录（碟片目录直接位于监控目录下时，碟片目录即专辑根）。</li>
     * </ul>
     *
     * <p>旧实现把「监控目录的第一级子目录」当作专辑根。对于
     * {@code 监控目录/艺术家/专辑/xx.flac} 这种结构，会把同一艺术家下的所有专辑
     * 视作同一文件夹，导致专辑锁定、封面缓存、时长序列在不同专辑之间串用。</p>
     *
     * @param folder     音频文件所在目录
     * @param monitorDir 监控目录（可为 null，此时不做越界保护）
     */
    public static File resolveAlbumRootForFolder(File folder, String monitorDir) {
        if (folder == null) {
            return null;
        }
        if (!isDiscFolderName(folder.getName())) {
            return folder;
        }
        File parent = folder.getParentFile();
        if (parent == null) {
            return folder;
        }
        if (monitorDir != null && !monitorDir.isBlank()) {
            try {
                String monitorPath = new File(monitorDir).getCanonicalPath();
                String parentPath = parent.getCanonicalPath();
                // 父目录是监控目录本身（或在其之外）时，不能上升
                if (parentPath.equals(monitorPath)
                    || !parentPath.startsWith(monitorPath + File.separator)) {
                    return folder;
                }
            } catch (IOException e) {
                log.warn("获取专辑根目录失败: {}", e.getMessage());
                return folder;
            }
        }
        return parent;
    }
    
    /**
     * 递归统计文件夹及其子文件夹中的音乐文件数量
     */
    public int countMusicFilesRecursively(File directory) {
        if (!directory.isDirectory()) {
            return 0;
        }
        
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }
        
        int count = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                // 递归统计子文件夹
                count += countMusicFilesRecursively(file);
            } else if (file.isFile() && isMusicFile(file)) {
                count++;
            }
        }
        
        return count;
    }
    
    /**
     * 判断是否为音乐文件
     */
    public boolean isMusicFile(File file) {
        String fileName = file.getName().toLowerCase();
        String[] supportedFormats = config.getSupportedFormats();
        for (String format : supportedFormats) {
            if (fileName.endsWith("." + format.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检测是否为散落在监控目录根目录的文件
     * 用于保底处理机制
     */
    public boolean isLooseFileInMonitorRoot(File audioFile) {
        File parentDir = audioFile.getParentFile();
        if (parentDir == null) {
            return false;
        }
        
        // 获取监控目录的规范路径
        String monitorDirPath;
        try {
            monitorDirPath = new File(config.getMonitorDirectory()).getCanonicalPath();
        } catch (IOException e) {
            monitorDirPath = config.getMonitorDirectory();
        }
        
        // 获取父文件夹的规范路径
        String parentDirPath;
        try {
            parentDirPath = parentDir.getCanonicalPath();
        } catch (IOException e) {
            parentDirPath = parentDir.getAbsolutePath();
        }
        
        // 只要父文件夹就是监控目录根目录,就认为是散落文件
        return parentDirPath.equals(monitorDirPath);
    }
    
    /**
     * 收集专辑根目录下属于这张专辑的音频文件：
     * 根目录自身的文件 + 碟片子目录（Disc 1 / CD2 …）内的文件。
     *
     * <p>不会进入其他子目录：若专辑根目录下还有非碟片子目录（例如艺术家目录里
     * 同时放着单曲和若干专辑子目录），这些子目录会被当作独立的专辑根处理，
     * 不能混进本专辑的曲目数 / 时长序列。</p>
     */
    public void collectAudioFilesForMarking(File directory, java.util.List<File> result) {
        collectAlbumAudioFiles(directory, result, false);
    }

    private void collectAlbumAudioFiles(File directory, java.util.List<File> result, boolean insideDiscFolder) {
        if (!directory.isDirectory()) {
            return;
        }
        
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        
        for (File file : files) {
            if (file.isDirectory()) {
                // 只进入碟片子目录；碟片目录内部则完整递归
                if (insideDiscFolder || isDiscFolderName(file.getName())) {
                    collectAlbumAudioFiles(file, result, true);
                }
            } else if (isMusicFile(file)) {
                // 添加音频文件
                result.add(file);
            }
        }
    }
    
    /**
     * 递归复制目录及其所有内容
     * @return int[2] - [复制成功数, 跳过数]
     */
    public int[] copyDirectoryRecursively(Path source, Path target) throws IOException {
        int[] counts = new int[2]; // [copiedCount, skippedCount]
        
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                try {
                    Files.createDirectories(targetDir);
                } catch (IOException e) {
                    log.warn("无法创建目录: {} - {}", targetDir, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file));
                try {
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    counts[0]++; // copiedCount
                    log.debug("已复制: {}", file.getFileName());
                } catch (IOException e) {
                    log.warn("复制文件失败: {} - {}", file.getFileName(), e.getMessage());
                    counts[1]++; // skippedCount
                }
                return FileVisitResult.CONTINUE;
            }
        });
        
        return counts;
    }
    
    /**
     * 获取相对于监控目录的相对路径
     */
    public String getRelativePath(File file) throws IOException {
        String monitorDirPath = new File(config.getMonitorDirectory()).getCanonicalPath();
        String filePath = file.getCanonicalPath();
        
        if (filePath.startsWith(monitorDirPath)) {
            String relativePath = filePath.substring(monitorDirPath.length());
            // 去掉开头的分隔符
            if (relativePath.startsWith(File.separator)) {
                relativePath = relativePath.substring(1);
            }
            return relativePath;
        }
        
        // 如果无法获取相对路径，返回文件名
        return file.getName();
    }
}
